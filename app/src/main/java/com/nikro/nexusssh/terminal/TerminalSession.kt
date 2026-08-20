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
 *
 * The read loop lives on [Dispatchers.IO] and feeds the emulator directly - no intermediate
 * buffering - then bumps [frame] so the Compose renderer redraws once per batch instead of once
 * per byte.
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

    /** Incremented after every processed chunk; the renderer keys its redraws on this. */
    private val _frame = MutableStateFlow(0L)
    val frame: StateFlow<Long> = _frame.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _bell = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val bell: SharedFlow<Unit> = _bell.asSharedFlow()

    /** Scroll offset in lines above the live screen; 0 means "following output". */
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

    /** Opens the shell channel and starts pumping output into the emulator. */
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
                emulator.process(buffer, read)
                _frame.value = _frame.value + 1
            }
            finish("Session ended" + (shell.exitStatus?.let { " (exit $it)" } ?: ""))
        } catch (error: IOException) {
            if (_status.value != ConnectionStatus.DISCONNECTED) {
                finish(error.friendlyMessage())
            }
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

    /** Closes the shell channel. The owning [SshConnection] is closed by the session manager. */
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

    /** Positive [lines] scrolls back into history. */
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

    /** Absolute index of the first line the renderer should draw. */
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
        val target = (emulator.buffer.viewportTop - absoluteLine + emulator.rows / 2)
        _scrollOffset.value = target.coerceIn(0, emulator.buffer.scrollbackLines)
    }

    /** Whole scrollback as text, for "share" and "save log". */
    fun snapshot(includeScrollback: Boolean = true): String =
        emulator.buffer.snapshotText(includeScrollback)

    // ---------------------------------------------------------------------------------------
    // TerminalHost
    // ---------------------------------------------------------------------------------------

    override fun write(bytes: ByteArray) = send(bytes)

    override fun onBell() {
        onBellRequested()
        scope.launch { _bell.emit(Unit) }
    }

    override fun onTitleChanged(title: String) {
        _title.value = title.ifBlank { config.label }
    }

    override fun onClipboardCopy(text: String) = onClipboard(text)

    override fun onScreenUpdated() {
        // The frame counter is bumped by the read loop after each chunk, which coalesces the
        // many updates a single escape-sequence burst produces into one recomposition.
    }

    override fun onAlternateScreen(active: Boolean) {
        // Full-screen apps (vim, htop, less) manage their own scrolling.
        if (active) _scrollOffset.value = 0
    }

    private companion object {
        const val TAG = "TerminalSession"
        const val READ_BUFFER = 16 * 1024
    }
}
