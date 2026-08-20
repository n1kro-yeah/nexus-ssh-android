package com.nikro.nexusssh.ssh

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.data.repository.HistoryRepository
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.data.repository.KnownHostRepository
import com.nikro.nexusssh.data.repository.PortForwardRepository
import com.nikro.nexusssh.domain.model.ConnectionStatus
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.ssh.forwarding.PortForwardManager
import com.nikro.nexusssh.ssh.sftp.SftpManager
import com.nikro.nexusssh.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every live connection in the app.
 *
 * Sessions outlive the screens that started them, which is what lets a terminal keep running while
 * the user browses SFTP or leaves the app (the foreground service holds the process). Prompts
 * raised on SSHJ's transport thread are turned into a queue the UI can answer, and each session
 * writes one row into the connection history.
 */
@Singleton
class SshSessionManager @Inject constructor(
    private val configFactory: ConnectionConfigFactory,
    private val knownHosts: KnownHostRepository,
    private val hosts: HostRepository,
    private val history: HistoryRepository,
    private val portForwards: PortForwardRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One connected (or connecting) session. */
    class Entry(
        val id: String,
        val config: ConnectionConfig,
        val connection: SshConnection,
        val terminal: TerminalSession,
        internal val historyId: Long,
    ) {
        var forwards: PortForwardManager? = null
            internal set
        var sftp: SftpManager? = null
            internal set

        val label: String get() = config.label
        val isAlive: Boolean get() = connection.isConnected && terminal.isAlive
    }

    /** A question the SSH layer is blocked on. */
    class PendingPrompt internal constructor(
        val id: String,
        val sessionLabel: String,
        val prompt: SshPrompt,
        private val textAnswer: CompletableDeferred<String?>? = null,
        private val confirmAnswer: CompletableDeferred<Boolean>? = null,
    ) {
        val needsText: Boolean get() = textAnswer != null

        fun submit(text: String?) {
            textAnswer?.complete(text)
            confirmAnswer?.complete(text != null)
        }

        fun accept(accepted: Boolean) {
            confirmAnswer?.complete(accepted)
            if (!accepted) textAnswer?.complete(null)
        }

        fun cancel() {
            textAnswer?.complete(null)
            confirmAnswer?.complete(false)
        }
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    private val _sessions = MutableStateFlow<List<Entry>>(emptyList())
    val sessions: StateFlow<List<Entry>> = _sessions.asStateFlow()

    private val _pendingPrompts = MutableStateFlow<List<PendingPrompt>>(emptyList())
    val pendingPrompts: StateFlow<List<PendingPrompt>> = _pendingPrompts.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    // ---------------------------------------------------------------------------------------
    // Connecting
    // ---------------------------------------------------------------------------------------

    /**
     * Connects to [host] and opens a shell.
     *
     * @return the new session id, or the failure that stopped it
     */
    suspend fun connect(
        host: Host,
        columns: Int = 80,
        rows: Int = 24,
        overrideUsername: String? = null,
        overridePassword: String? = null,
    ): Result<String> {
        val config = try {
            configFactory.build(host, columns, rows, overrideUsername, overridePassword)
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        return open(config)
    }

    /** Connects using a config built elsewhere (quick connect, deep links, snippets). */
    suspend fun open(config: ConnectionConfig): Result<String> {
        val sessionId = UUID.randomUUID().toString()
        val historyId = history.start(
            hostId = config.hostId.takeIf { it != 0L },
            label = config.label,
            address = config.address,
        )

        val connection = SshConnection(
            config = config,
            knownHosts = knownHosts,
            onPrompt = { prompt -> askText(config.label, prompt) },
            onConfirm = { prompt -> askConfirm(config.label, prompt) },
            onEvent = { event -> AppLogger.d(TAG, "[${config.label}] $event") },
        )

        return try {
            connection.connect()
            val terminal = TerminalSession(
                id = sessionId,
                config = config,
                connection = connection,
                scope = scope,
                initialColumns = config.initialColumns,
                initialRows = config.initialRows,
                onClosed = { session -> onSessionClosed(session.id) },
            )
            terminal.open()

            val entry = Entry(sessionId, config, connection, terminal, historyId)
            entries[sessionId] = entry
            publish()
            _activeSessionId.value = sessionId

            if (config.hostId != 0L) {
                scope.launch { hosts.markConnected(config.hostId) }
                scope.launch { startAutoForwards(entry) }
            }
            Result.success(sessionId)
        } catch (error: Throwable) {
            connection.disconnect(error.message)
            scope.launch {
                history.finish(historyId, 0, 0, succeeded = false, error = error.friendlyMessage())
            }
            clearPromptsFor(config.label)
            Result.failure(error)
        }
    }

    /** Reconnects a dead session, reusing its config. */
    suspend fun reconnect(sessionId: String): Result<String> {
        val entry = entries[sessionId] ?: return Result.failure(IllegalStateException("No such session"))
        close(sessionId)
        return open(entry.config)
    }

    private suspend fun startAutoForwards(entry: Entry) {
        val rules = portForwards.autoStartFor(entry.config.hostId)
        if (rules.isEmpty()) return
        val manager = forwardManager(entry.id) ?: return
        rules.forEach { manager.start(it) }
    }

    // ---------------------------------------------------------------------------------------
    // Access
    // ---------------------------------------------------------------------------------------

    fun entry(sessionId: String): Entry? = entries[sessionId]

    fun terminal(sessionId: String): TerminalSession? = entries[sessionId]?.terminal

    fun setActive(sessionId: String?) {
        _activeSessionId.value = sessionId
    }

    /** Sessions for one host, so the host list can show "2 open". */
    fun sessionsFor(hostId: Long): List<Entry> =
        entries.values.filter { it.config.hostId == hostId }

    val activeCount: Int get() = entries.values.count { it.isAlive }

    fun forwardManager(sessionId: String): PortForwardManager? {
        val entry = entries[sessionId] ?: return null
        entry.forwards?.let { return it }
        return PortForwardManager(entry.connection, scope).also { entry.forwards = it }
    }

    /** SFTP on the existing transport - no second login. */
    fun sftpManager(sessionId: String): SftpManager? {
        val entry = entries[sessionId] ?: return null
        entry.sftp?.let { return it }
        return SftpManager(entry.connection).also { entry.sftp = it }
    }

    /** Opens a dedicated connection for the file browser when no session is open yet. */
    suspend fun openSftpOnly(host: Host): Result<Pair<String, SftpManager>> {
        val sessionResult = connect(host)
        return sessionResult.mapCatching { id ->
            id to (sftpManager(id) ?: error("Could not start SFTP"))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Closing
    // ---------------------------------------------------------------------------------------

    fun close(sessionId: String) {
        val entry = entries.remove(sessionId) ?: return
        entry.forwards?.stopAll()
        entry.sftp?.close()
        entry.terminal.close()
        entry.connection.disconnect("Closed by the user")
        scope.launch {
            history.finish(
                id = entry.historyId,
                bytesIn = entry.terminal.bytesIn.get(),
                bytesOut = entry.terminal.bytesOut.get(),
                succeeded = true,
            )
        }
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = entries.keys.firstOrNull()
        }
        publish()
    }

    fun closeAll() {
        entries.keys.toList().forEach(::close)
    }

    /** Called by a terminal that lost its channel; the entry stays so the tab can reconnect. */
    private fun onSessionClosed(sessionId: String) {
        val entry = entries[sessionId] ?: return
        scope.launch {
            history.finish(
                id = entry.historyId,
                bytesIn = entry.terminal.bytesIn.get(),
                bytesOut = entry.terminal.bytesOut.get(),
                succeeded = entry.terminal.status.value != ConnectionStatus.FAILED,
            )
        }
        publish()
    }

    private fun publish() {
        _sessions.value = entries.values.sortedBy { it.terminal.startedAt }
    }

    // ---------------------------------------------------------------------------------------
    // Prompts
    // ---------------------------------------------------------------------------------------

    private suspend fun askText(label: String, prompt: SshPrompt): String? {
        val answer = CompletableDeferred<String?>()
        val pending = PendingPrompt(UUID.randomUUID().toString(), label, prompt, textAnswer = answer)
        _pendingPrompts.value = _pendingPrompts.value + pending
        val result = withTimeoutOrNull(PROMPT_TIMEOUT_MS) { answer.await() }
        _pendingPrompts.value = _pendingPrompts.value - pending
        return result
    }

    private suspend fun askConfirm(label: String, prompt: SshPrompt): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val pending = PendingPrompt(UUID.randomUUID().toString(), label, prompt, confirmAnswer = answer)
        _pendingPrompts.value = _pendingPrompts.value + pending
        val result = withTimeoutOrNull(PROMPT_TIMEOUT_MS) { answer.await() } ?: false
        _pendingPrompts.value = _pendingPrompts.value - pending
        return result
    }

    private fun clearPromptsFor(label: String) {
        _pendingPrompts.value.filter { it.sessionLabel == label }.forEach { it.cancel() }
        _pendingPrompts.value = _pendingPrompts.value.filterNot { it.sessionLabel == label }
    }

    private companion object {
        const val TAG = "SessionManager"

        /** Long enough to fetch a 2FA code, short enough not to hang a thread forever. */
        const val PROMPT_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
