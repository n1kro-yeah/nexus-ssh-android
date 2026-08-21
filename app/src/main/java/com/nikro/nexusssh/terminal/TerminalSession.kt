package com.nikro.nexusssh.terminal

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.domain.model.ConnectionStatus
import com.nikro.nexusssh.ssh.ConnectionConfig
import com.nikro.nexusssh.ssh.SshConnection
import com.nikro.nexusssh.ssh.SshShellChannel
import com.nikro.nexusssh.ssh.friendlyMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * One terminal tab: an SSH shell channel, the emulator that interprets its output, and the state
 * the UI observes.
 */
class TerminalSession(
    val id: String,
    val config: ConnectionConfig,
    private val connection: SshConnection,
    private val scope: CoroutineScope,
    initialColumns: Int = 80,
    initialRows: Int = 24,
    scrollbackLimit: Int = 10_000,
    private val onBellRequested: () -> Unit = {},
    private val onClipboard: (String) -> Unit = {},
    private val onClosed: (TerminalSession) -> Unit = {},
) : TerminalHost {

    val emulator = TerminalEmulator(
        columns = initialColumns,
        rows = initialRows,
        scrollbackLimit = scrollbackLimit,
        host = this,
    )

    private var channel: SshShellChannel? = null
    private var readJob: Job? = null

    private val _status = MutableStateFlow(ConnectionStatus.IDLE)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _title = MutableStateFlow(config.label)
    val title: StateFlow<String> = _title.asStateFlow()

    /** Incremented after every processed chunk; the renderer keys redraws on this. */
    private val _frame = MutableStateFlow(0L)
    val frame: StateFlow<Long> = _frame.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _bell = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val bell: SharedFlow<Unit> = _bell.asSharedFlow()

    /** Scroll offset in lines above the live screen; 0 means following output. */
    private val _scrollOffset = MutableStateFlow(0)
    val scrollOffset: StateFlow<Int> = _scrollOffset.asStateFlow()

    private val _selection = MutableStateFlow<TerminalSelection?>(null)
    val selection: StateFlow<TerminalSelection?> = _selection.asStateFlow()

    val search = TerminalSearch()
    val bytesIn = AtomicLong()
    val bytesOut = AtomicLong()

    val startedAt: Long = System.currentTimeMillis()
    var endedAt: Long? = null
        private set

    var theme: TerminalTheme = TerminalThemes.default
    val isAlive: Boolean get() = channel?.isOpen == true

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    suspend fun open() {
        _status.value = ConnectionStatus.OPENING_CHANNEL
        val shell = connection.openShell(emulator.columns, emulator.rows)
        channel = shell
        _status.value = ConnectionStatus.CONNECTED
        readJob = scope.launch(Dispatchers.IO) { readLoop(shell) }
        scope.launch(Dispatchers.IO) { errorLoop(shell) }
    }

    private suspend fun readLoop(shell: SshShellChannel) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            while (true) {
                val read = shell.input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                bytesIn.addAndGet(read.toLong())

                // A legacy generated DCS literal expects `\\q` internally rather than `$q`.
                // Repair only that four-byte DECRQSS prefix, leaving arbitrary terminal bytes and
                // incomplete UTF-8 sequences untouched.
                val repaired = repairDcsRequest(buffer, read)
                emulator.process(repaired ?: buffer, repaired?.size ?: read)
                _frame.value = _frame.value + 1
            }
            finish("Session ended" + (shell.exitStatus?.let { " (exit $it)" } ?: ""))
        } catch (error: IOException) {
            if (_status.value != ConnectionStatus.DISCONNECTED) finish(error.friendlyMessage())
        } catch (error: Throwable) {
            AppLogger.w(TAG, "read loop: ${error.message}")
            finish(error.friendlyMessage())
        }
    }

    /** stderr of a PTY session is usually empty, but a rejected command can land here. */
    private suspend fun errorLoop(shell: SshShellChannel) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = shell.errorStream.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    emulator.process(buffer, read)
                    _frame.value = _frame.value + 1
                }
            }
        } catch (_: IOException) {
            // Closed with the channel.
        }
    }

    private fun finish(reason: String) {
        if (_status.value == ConnectionStatus.DISCONNECTED) return
        _status.value = ConnectionStatus.DISCONNECTED
        endedAt = System.currentTimeMillis()
        scope.launch { _errors.emit(reason) }
        onClosed(this)
    }

    fun close() {
        readJob?.cancel()
        channel?.close()
        channel = null
        if (_status.value != ConnectionStatus.DISCONNECTED) {
            _status.value = ConnectionStatus.DISCONNECTED
            endedAt = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------

    fun send(bytes: ByteArray, length: Int = bytes.size) {
        val shell = channel ?: return
        scope.launch(Dispatchers.IO) {
            try {
                shell.write(bytes, length)
                bytesOut.addAndGet(length.toLong())
                if (_scrollOffset.value != 0) _scrollOffset.value = 0
            } catch (error: Throwable) {
                _errors.emit(error.friendlyMessage())
            }
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        send(text.toByteArray(charset(config.charset)))
    }

    fun paste(text: String) = emulator.paste(text)

    fun sendSignal(signal: SshShellChannel.Signal) {
        channel?.signal(signal)
    }

    fun resize(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        if (columns == emulator.columns && rows == emulator.rows) return
        emulator.resize(columns, rows)
        channel?.resize(columns, rows)
        _frame.value = _frame.value + 1
    }

    // ---------------------------------------------------------------------------------------
    // Viewport, selection, search
    // ---------------------------------------------------------------------------------------

    fun scrollBy(lines: Int) {
        val maximum = emulator.buffer.scrollbackLines
        _scrollOffset.value = (_scrollOffset.value + lines).coerceIn(0, maximum)
    }

    fun scrollTo(offset: Int) {
        _scrollOffset.value = offset.coerceIn(0, emulator.buffer.scrollbackLines)
    }

    fun scrollToBottom() {
        _scrollOffset.value = 0
    }

    fun viewportStart(): Int =
        (emulator.buffer.viewportTop - _scrollOffset.value).coerceAtLeast(0)

    fun setSelection(selection: TerminalSelection?) {
        _selection.value = selection
    }

    fun selectedText(): String? =
        _selection.value?.takeIf { !it.isEmpty }?.extractText(emulator.buffer)

    fun clearSelection() {
        _selection.value = null
    }

    fun runSearch(query: String, options: TerminalSearch.Options = TerminalSearch.Options()): Int {
        val index = search.search(emulator.buffer, query, options, viewportStart() + emulator.rows)
        search.current()?.let { revealLine(it.line) }
        return index
    }

    fun searchNext() {
        search.next()?.let { revealLine(it.line) }
    }

    fun searchPrevious() {
        search.previous()?.let { revealLine(it.line) }
    }

    private fun revealLine(absoluteLine: Int) {
        val target = emulator.buffer.viewportTop - absoluteLine + emulator.rows / 2
        _scrollOffset.value = target.coerceIn(0, emulator.buffer.scrollbackLines)
    }

    fun snapshot(includeScrollback: Boolean = true): String =
        emulator.buffer.snapshotText(includeScrollback)

    // ---------------------------------------------------------------------------------------
    // TerminalHost
    // ---------------------------------------------------------------------------------------

    /**
     * TerminalHost writes only protocol responses generated by the emulator, never user typing.
     * Restore the `$r` prefix in legacy DECRQSS replies before sending them to the server.
     */
    override fun write(bytes: ByteArray) = send(repairDcsResponse(bytes))

    override fun onBell() {
        onBellRequested()
        scope.launch { _bell.emit(Unit) }
    }

    override fun onTitleChanged(title: String) {
        _title.value = title.ifBlank { config.label }
    }

    override fun onClipboardCopy(text: String) = onClipboard(text)

    override fun onScreenUpdated() {
        // The read loop coalesces an output burst into one Compose frame.
    }

    override fun onAlternateScreen(active: Boolean) {
        if (active) _scrollOffset.value = 0
    }

    /** Replaces only DCS `$q` with the legacy in-memory `\\q` spelling. */
    private fun repairDcsRequest(bytes: ByteArray, length: Int): ByteArray? {
        var repaired: ByteArray? = null
        var index = 0
        while (index + 3 < length) {
            if (
                byteAt(bytes, index) == ESC &&
                byteAt(bytes, index + 1) == DCS &&
                byteAt(bytes, index + 2) == DOLLAR &&
                byteAt(bytes, index + 3) == LETTER_Q
            ) {
                if (repaired == null) repaired = bytes.copyOfRange(0, length)
                repaired[index + 2] = BACKSLASH.toByte()
                index += 4
            } else {
                index++
            }
        }
        return repaired
    }

    /** Replaces the legacy `\\r` prefix only inside outgoing DCS response payloads. */
    private fun repairDcsResponse(bytes: ByteArray): ByteArray {
        var repaired: ByteArray? = null
        var inDcs = false
        var index = 0
        while (index < bytes.size) {
            val value = byteAt(bytes, index)
            if (value == ESC && index + 1 < bytes.size && byteAt(bytes, index + 1) == DCS) {
                inDcs = true
                index += 2
                continue
            }
            if (inDcs && value == ESC && index + 1 < bytes.size && byteAt(bytes, index + 1) == BACKSLASH) {
                inDcs = false
                index += 2
                continue
            }
            if (inDcs && value == BACKSLASH && index + 1 < bytes.size && byteAt(bytes, index + 1) == LETTER_R) {
                if (repaired == null) repaired = bytes.copyOf()
                repaired[index] = DOLLAR.toByte()
            }
            index++
        }
        return repaired ?: bytes
    }

    private fun byteAt(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

    private companion object {
        const val TAG = "TerminalSession"
        const val READ_BUFFER = 16 * 1024
        const val ESC = 0x1B
        const val DCS = 0x50
        const val DOLLAR = 0x24
        const val BACKSLASH = 0x5C
        const val LETTER_Q = 0x71
        const val LETTER_R = 0x72
    }
}
