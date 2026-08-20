package com.nikro.nexusssh.terminal

import com.nikro.nexusssh.core.log.AppLogger

/**
 * Callbacks the emulator needs from its owner (usually an SSH channel plus the UI).
 */
interface TerminalHost {
    /** Sends bytes back to the remote side (DSR replies, mouse events, key input). */
    fun write(bytes: ByteArray)

    fun onBell() {}

    fun onTitleChanged(title: String) {}

    /** OSC 52 - the remote asked to put [text] on the system clipboard. */
    fun onClipboardCopy(text: String) {}

    /** The visible content changed; the renderer should schedule a frame. */
    fun onScreenUpdated() {}

    /** Emitted when the alternate screen is entered or left. */
    fun onAlternateScreen(active: Boolean) {}
}

/**
 * A VT100/VT220/xterm compatible terminal emulator.
 *
 * The parser follows Paul Williams' DEC ANSI state machine (https://vt100.net/emu/dec_ansi_parser)
 * which is what xterm, VTE and every serious emulator implement. Supported feature set:
 *
 *  * C0 and C1 control codes, 7-bit and 8-bit
 *  * CSI cursor motion, erasing, insert/delete line and character, scrolling regions
 *  * SGR with 16, 256 and 24-bit colour (both `;` and `:` separated forms)
 *  * DEC private modes: application cursor keys and keypad, origin, autowrap, insert,
 *    cursor visibility, alternate screen (47/1047/1049), bracketed paste, focus reporting
 *  * mouse tracking: X10, normal, button-event, any-event, with SGR (1006) and UTF-8 encodings
 *  * OSC 0/1/2 (title), 4 (palette), 8 (hyperlinks), 52 (clipboard), 10/11 (colour queries)
 *  * DCS/APC/PM/SOS string handling (consumed, not interpreted), DECALN, DECSC/DECRC, tab stops,
 *    G0-G3 character sets including DEC special graphics
 */
class TerminalEmulator(
    columns: Int,
    rows: Int,
    scrollbackLimit: Int = 10_000,
    private val host: TerminalHost,
) {

    // ---------------------------------------------------------------------------------------
    // Screens
    // ---------------------------------------------------------------------------------------

    private var normalBuffer = TerminalBuffer(columns, rows, scrollbackLimit, collectScrollback = true)
    private var alternateBuffer = TerminalBuffer(columns, rows, 0, collectScrollback = false)

    var buffer: TerminalBuffer = normalBuffer
        private set

    var isAlternateScreen: Boolean = false
        private set

    var columns: Int = columns
        private set

    var rows: Int = rows
        private set

    // ---------------------------------------------------------------------------------------
    // Cursor and rendition
    // ---------------------------------------------------------------------------------------

    var cursorRow: Int = 0
        private set

    var cursorColumn: Int = 0
        private set

    var cursorVisible: Boolean = true
        private set

    private var currentStyle: Long = CellStyle.DEFAULT
    private var pendingWrap = false

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    private data class SavedCursor(
        val row: Int,
        val column: Int,
        val style: Long,
        val originMode: Boolean,
        val g0: Int,
        val g1: Int,
        val charsetIndex: Int,
    )

    private var savedCursor: SavedCursor? = null
    private var savedCursorAlternate: SavedCursor? = null

    // ---------------------------------------------------------------------------------------
    // Modes
    // ---------------------------------------------------------------------------------------

    var applicationCursorKeys = false
        private set

    var applicationKeypad = false
        private set

    var bracketedPaste = false
        private set

    var reverseVideo = false
        private set

    var focusReporting = false
        private set

    var mouseTrackingMode = MouseMode.OFF
        private set

    var mouseProtocol = MouseProtocol.X11
        private set

    private var originMode = false
    private var autoWrap = true
    private var insertMode = false
    private var lineFeedNewLineMode = false
    private var reverseWrapAround = false

    enum class MouseMode { OFF, X10, NORMAL, BUTTON_EVENT, ANY_EVENT }

    enum class MouseProtocol { X11, UTF8, SGR, URXVT }

    // ---------------------------------------------------------------------------------------
    // Character sets
    // ---------------------------------------------------------------------------------------

    private val charsets = intArrayOf(CHARSET_ASCII, CHARSET_ASCII, CHARSET_ASCII, CHARSET_ASCII)
    private var activeCharset = 0
    private var singleShift = -1

    // ---------------------------------------------------------------------------------------
    // Tabs, titles, parser state
    // ---------------------------------------------------------------------------------------

    private var tabStops = BooleanArray(columns) { it % 8 == 0 }
    private val titleStack = ArrayDeque<String>()
    var title: String = ""
        private set

    private val decoder = Utf8Decoder()
    private var state = State.GROUND
    private val parameters = IntArray(MAX_PARAMETERS)
    private var parameterCount = 0
    private var parameterStarted = false
    private val subParameters = IntArray(MAX_PARAMETERS)
    private var subParameterCount = 0
    private var collectedIntermediates = StringBuilder(4)
    private var privateMarker = 0.toChar()
    private val stringBuffer = StringBuilder(256)
    private var stringKind = StringKind.NONE

    /** Palette overrides applied via OSC 4. */
    val paletteOverrides = HashMap<Int, Int>()

    private enum class State {
        GROUND,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI_ENTRY,
        CSI_PARAM,
        CSI_INTERMEDIATE,
        CSI_IGNORE,
        OSC_STRING,
        DCS_ENTRY,
        DCS_PARAM,
        DCS_INTERMEDIATE,
        DCS_PASSTHROUGH,
        DCS_IGNORE,
        SOS_PM_APC_STRING,
    }

    private enum class StringKind { NONE, OSC, DCS, APC }

    // =======================================================================================
    // Public API
    // =======================================================================================

    /** Feeds [length] bytes read from the remote shell. */
    fun process(bytes: ByteArray, length: Int = bytes.size) {
        var index = 0
        while (index < length) {
            val byte = bytes[index].toInt() and 0xFF
            when {
                // While inside a string (OSC/DCS/APC) bytes are consumed verbatim.
                state == State.OSC_STRING || state == State.DCS_PASSTHROUGH ||
                    state == State.SOS_PM_APC_STRING || state == State.DCS_IGNORE ->
                    consumeStringByte(byte)

                byte < 0x80 -> processCodePoint(byte)

                else -> {
                    val codePoint = decoder.decode(byte)
                    if (codePoint >= 0) processCodePoint(codePoint)
                }
            }
            index++
        }
        host.onScreenUpdated()
    }

    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        val safeColumns = newColumns.coerceAtLeast(2)
        val safeRows = newRows.coerceAtLeast(2)

        val adjustment = normalBuffer.resize(safeColumns, safeRows, currentStyle, cursorRow)
        alternateBuffer.resize(safeColumns, safeRows, currentStyle, cursorRow)

        columns = safeColumns
        rows = safeRows
        cursorRow = (cursorRow + adjustment).coerceIn(0, rows - 1)
        cursorColumn = cursorColumn.coerceIn(0, columns - 1)
        scrollTop = 0
        scrollBottom = rows - 1

        val newTabs = BooleanArray(columns) { it % 8 == 0 }
        System.arraycopy(tabStops, 0, newTabs, 0, minOf(tabStops.size, newTabs.size))
        tabStops = newTabs
        pendingWrap = false
        host.onScreenUpdated()
    }

    fun reset() {
        currentStyle = CellStyle.DEFAULT
        cursorRow = 0
        cursorColumn = 0
        cursorVisible = true
        originMode = false
        autoWrap = true
        insertMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        bracketedPaste = false
        reverseVideo = false
        mouseTrackingMode = MouseMode.OFF
        mouseProtocol = MouseProtocol.X11
        scrollTop = 0
        scrollBottom = rows - 1
        charsets.fill(CHARSET_ASCII)
        activeCharset = 0
        tabStops = BooleanArray(columns) { it % 8 == 0 }
        paletteOverrides.clear()
        if (isAlternateScreen) switchToNormalScreen(restoreCursor = false)
        normalBuffer.clearScreen(CellStyle.DEFAULT)
        normalBuffer.clearScrollback()
        alternateBuffer.clearScreen(CellStyle.DEFAULT)
        state = State.GROUND
        decoder.reset()
        host.onScreenUpdated()
    }

    fun clearScrollback() {
        normalBuffer.clearScrollback()
        host.onScreenUpdated()
    }

    /** Full screen erase used by the "clear" toolbar action. */
    fun clearScreenAndScrollback() {
        buffer.clearScreen(currentStyle)
        normalBuffer.clearScrollback()
        cursorRow = 0
        cursorColumn = 0
        host.onScreenUpdated()
    }

    fun setScrollbackLimit(limit: Int) {
        normalBuffer.scrollbackLimit = limit
    }

    /** Sends [text] respecting bracketed-paste mode. */
    fun paste(text: String) {
        val sanitised = text.replace("\u001b", "")
        val payload = if (bracketedPaste) "\u001b[200~$sanitised\u001b[201~" else sanitised
        host.write(payload.toByteArray(Charsets.UTF_8))
    }

    // =======================================================================================
    // Parser
    // =======================================================================================

    private fun processCodePoint(codePoint: Int) {
        // C0 controls and DEL are handled from any state (except string states, see process()).
        if (codePoint < 0x20) {
            when (state) {
                State.DCS_ENTRY, State.DCS_PARAM, State.DCS_INTERMEDIATE -> Unit
                else -> {
                    executeControl(codePoint)
                    return
                }
            }
        }

        when (state) {
            State.GROUND -> ground(codePoint)
            State.ESCAPE -> escape(codePoint)
            State.ESCAPE_INTERMEDIATE -> escapeIntermediate(codePoint)
            State.CSI_ENTRY -> csiEntry(codePoint)
            State.CSI_PARAM -> csiParam(codePoint)
            State.CSI_INTERMEDIATE -> csiIntermediate(codePoint)
            State.CSI_IGNORE -> if (codePoint in 0x40..0x7E) state = State.GROUND
            State.DCS_ENTRY, State.DCS_PARAM, State.DCS_INTERMEDIATE -> dcsEntry(codePoint)
            else -> Unit
        }
    }

    private fun ground(codePoint: Int) {
        when (codePoint) {
            0x1B -> enterEscape()
            0x7F -> Unit // DEL is ignored
            0x9B -> { enterEscape(); state = State.CSI_ENTRY } // 8-bit CSI
            0x9D -> { enterEscape(); startString(StringKind.OSC) }
            0x90 -> { enterEscape(); state = State.DCS_ENTRY }
            0x9E, 0x9F, 0x98 -> { enterEscape(); startString(StringKind.APC) }
            0x84 -> index()
            0x85 -> nextLine()
            0x88 -> tabStops[cursorColumn.coerceIn(0, columns - 1)] = true
            0x8D -> reverseIndex()
            else -> putCodePoint(codePoint)
        }
    }

    private fun enterEscape() {
        state = State.ESCAPE
        collectedIntermediates.setLength(0)
        privateMarker = 0.toChar()
        parameterCount = 0
        parameterStarted = false
        parameters.fill(0)
    }

    private fun escape(codePoint: Int) {
        when (codePoint.toChar()) {
            '[' -> state = State.CSI_ENTRY
            ']' -> startString(StringKind.OSC)
            'P' -> state = State.DCS_ENTRY
            '^', '_' -> startString(StringKind.APC)
            'X' -> startString(StringKind.APC) // SOS
            '7' -> { saveCursor(); state = State.GROUND }
            '8' -> { restoreCursor(); state = State.GROUND }
            'D' -> { index(); state = State.GROUND }
            'E' -> { nextLine(); state = State.GROUND }
            'H' -> { tabStops[cursorColumn.coerceIn(0, columns - 1)] = true; state = State.GROUND }
            'M' -> { reverseIndex(); state = State.GROUND }
            'c' -> { reset(); state = State.GROUND }
            '=' -> { applicationKeypad = true; state = State.GROUND }
            '>' -> { applicationKeypad = false; state = State.GROUND }
            'N' -> { singleShift = 2; state = State.GROUND }
            'O' -> { singleShift = 3; state = State.GROUND }
            '(', ')', '*', '+', '-', '.', '/', '#', ' ', '%' -> {
                collectedIntermediates.append(codePoint.toChar())
                state = State.ESCAPE_INTERMEDIATE
            }
            '\\' -> state = State.GROUND // ST outside of a string
            else -> state = State.GROUND
        }
    }

    private fun escapeIntermediate(codePoint: Int) {
        val intermediate = collectedIntermediates.firstOrNull() ?: ' '
        val final = codePoint.toChar()
        when (intermediate) {
            '(' -> charsets[0] = charsetFor(final)
            ')', '-' -> charsets[1] = charsetFor(final)
            '*', '.' -> charsets[2] = charsetFor(final)
            '+', '/' -> charsets[3] = charsetFor(final)
            '#' -> if (final == '8') decAlignmentTest()
            '%' -> Unit // charset selection (UTF-8) - we are always UTF-8
            ' ' -> Unit // 7/8-bit control transmission
        }
        state = State.GROUND
    }

    private fun csiEntry(codePoint: Int) {
        parameters.fill(0)
        parameterCount = 0
        parameterStarted = false
        subParameterCount = 0
        collectedIntermediates.setLength(0)
        privateMarker = 0.toChar()
        state = State.CSI_PARAM
        csiParam(codePoint)
    }

    private fun csiParam(codePoint: Int) {
        val char = codePoint.toChar()
        when {
            char in '0'..'9' -> {
                if (parameterCount == 0) parameterCount = 1
                parameterStarted = true
                val index = parameterCount - 1
                if (index < MAX_PARAMETERS) {
                    parameters[index] = (parameters[index] * 10 + (codePoint - 0x30)).coerceAtMost(65535)
                }
            }

            char == ';' -> {
                if (parameterCount == 0) parameterCount = 1
                if (parameterCount < MAX_PARAMETERS) parameters[parameterCount++] = 0
                parameterStarted = false
            }

            char == ':' -> {
                // Sub-parameters (SGR 38:2::r:g:b). Flattened into the main list, which is what
                // the SGR handler expects.
                if (parameterCount == 0) parameterCount = 1
                if (parameterCount < MAX_PARAMETERS) parameters[parameterCount++] = 0
                subParameterCount++
                parameterStarted = false
            }

            char in '<'..'?' -> privateMarker = char

            char in ' '..'/' -> {
                collectedIntermediates.append(char)
                state = State.CSI_INTERMEDIATE
            }

            char in '@'..'~' -> {
                dispatchCsi(char)
                state = State.GROUND
            }

            else -> state = State.CSI_IGNORE
        }
    }

    private fun csiIntermediate(codePoint: Int) {
        val char = codePoint.toChar()
        when {
            char in ' '..'/' -> collectedIntermediates.append(char)
            char in '@'..'~' -> {
                dispatchCsi(char)
                state = State.GROUND
            }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun dcsEntry(codePoint: Int) {
        val char = codePoint.toChar()
        when {
            char in '0'..'9' || char == ';' -> Unit
            char in '@'..'~' -> startString(StringKind.DCS)
            else -> state = State.DCS_IGNORE
        }
    }

    private fun startString(kind: StringKind) {
        stringKind = kind
        stringBuffer.setLength(0)
        state = when (kind) {
            StringKind.OSC -> State.OSC_STRING
            StringKind.DCS -> State.DCS_PASSTHROUGH
            else -> State.SOS_PM_APC_STRING
        }
    }

    private fun consumeStringByte(byte: Int) {
        when (byte) {
            0x07 -> finishString() // BEL terminates OSC
            0x1B -> stringBuffer.append('\u001b')
            0x9C -> finishString()
            else -> {
                // ESC \ (ST)
                if (stringBuffer.isNotEmpty() && stringBuffer.last() == '\u001b') {
                    stringBuffer.setLength(stringBuffer.length - 1)
                    if (byte == '\\'.code) {
                        finishString()
                        return
                    }
                }
                if (stringBuffer.length < MAX_STRING_LENGTH) {
                    if (byte >= 0x80) {
                        val cp = decoder.decode(byte)
                        if (cp >= 0) stringBuffer.appendCodePoint(cp)
                    } else {
                        stringBuffer.append(byte.toChar())
                    }
                }
            }
        }
    }

    private fun finishString() {
        when (stringKind) {
            StringKind.OSC -> handleOsc(stringBuffer.toString())
            StringKind.DCS -> handleDcs(stringBuffer.toString())
            else -> Unit
        }
        stringBuffer.setLength(0)
        stringKind = StringKind.NONE
        state = State.GROUND
    }

    // =======================================================================================
    // Control codes
    // =======================================================================================

    private fun executeControl(codePoint: Int) {
        when (codePoint) {
            0x00 -> Unit // NUL
            0x07 -> host.onBell()
            0x08 -> backspace()
            0x09 -> tabForward(1)
            0x0A, 0x0B, 0x0C -> {
                lineFeed()
                if (lineFeedNewLineMode) cursorColumn = 0
            }
            0x0D -> {
                cursorColumn = 0
                pendingWrap = false
            }
            0x0E -> activeCharset = 1 // SO
            0x0F -> activeCharset = 0 // SI
            0x18, 0x1A -> state = State.GROUND // CAN, SUB
            0x1B -> enterEscape()
            else -> Unit
        }
    }

    private fun backspace() {
        if (cursorColumn > 0) {
            cursorColumn--
        } else if (reverseWrapAround && cursorRow > 0) {
            cursorRow--
            cursorColumn = columns - 1
        }
        pendingWrap = false
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorRow == scrollBottom) {
            buffer.scrollUp(scrollTop, scrollBottom, 1, currentStyle)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun index() = lineFeed()

    private fun nextLine() {
        lineFeed()
        cursorColumn = 0
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorRow == scrollTop) {
            buffer.scrollDown(scrollTop, scrollBottom, 1, currentStyle)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun tabForward(count: Int) {
        repeat(count.coerceAtLeast(1)) {
            var column = cursorColumn + 1
            while (column < columns && !tabStops[column]) column++
            cursorColumn = column.coerceAtMost(columns - 1)
        }
        pendingWrap = false
    }

    private fun tabBackward(count: Int) {
        repeat(count.coerceAtLeast(1)) {
            var column = cursorColumn - 1
            while (column > 0 && !tabStops[column]) column--
            cursorColumn = column.coerceAtLeast(0)
        }
    }

    // =======================================================================================
    // Printing
    // =======================================================================================

    private fun putCodePoint(rawCodePoint: Int) {
        var codePoint = rawCodePoint
        val charsetIndex = if (singleShift >= 0) singleShift else activeCharset
        singleShift = -1
        if (charsets[charsetIndex] == CHARSET_DEC_GRAPHICS) {
            codePoint = mapDecGraphics(codePoint)
        }

        val width = CharWidth.of(codePoint)

        if (width == 0) {
            // Combining mark: attach to the previous cell so accents render correctly.
            val target = if (cursorColumn > 0) cursorColumn - 1 else 0
            val row = buffer.row(cursorRow)
            val base = row.codePointAt(target)
            if (base != TerminalRow.SPACE) {
                val combined = String(Character.toChars(base)) + String(Character.toChars(codePoint))
                // Only the first code point of the cluster fits in one cell; keep the base glyph
                // and remember the cluster through the style's HYPERLINK-free spare bits is not
                // possible, so we approximate by writing the composed character when it exists.
                val composed = java.text.Normalizer.normalize(combined, java.text.Normalizer.Form.NFC)
                if (composed.codePointCount(0, composed.length) == 1) {
                    row.setCell(target, composed.codePointAt(0), row.styleAt(target))
                }
            }
            return
        }

        if (pendingWrap && autoWrap) {
            buffer.row(cursorRow).wrapped = true
            cursorColumn = 0
            lineFeed()
            pendingWrap = false
        }

        if (cursorColumn + width > columns) {
            if (autoWrap) {
                buffer.row(cursorRow).wrapped = true
                cursorColumn = 0
                lineFeed()
            } else {
                cursorColumn = columns - width
            }
        }

        val row = buffer.row(cursorRow)
        if (insertMode) row.insertCells(cursorColumn, width, currentStyle)

        row.setCell(cursorColumn, codePoint, currentStyle)
        if (width == 2 && cursorColumn + 1 < columns) {
            row.setCell(
                cursorColumn + 1,
                TerminalRow.WIDE_PLACEHOLDER,
                CellStyle.addAttribute(currentStyle, CellStyle.WIDE_CONTINUATION),
            )
        }

        cursorColumn += width
        if (cursorColumn >= columns) {
            cursorColumn = columns - 1
            pendingWrap = true
        }
    }

    // =======================================================================================
    // CSI dispatch
    // =======================================================================================

    private fun parameter(index: Int, default: Int = 1): Int {
        if (index >= parameterCount) return default
        val value = parameters[index]
        return if (value == 0 && default != 0) default else value
    }

    private fun rawParameter(index: Int): Int = if (index < parameterCount) parameters[index] else 0

    private fun dispatchCsi(final: Char) {
        val intermediates = collectedIntermediates.toString()

        if (privateMarker == '?') {
            when (final) {
                'h' -> setDecModes(true)
                'l' -> setDecModes(false)
                'r' -> restoreDecModes()
                's' -> saveDecModes()
                'n' -> deviceStatusPrivate(parameter(0, 0))
                'J' -> eraseInDisplay(parameter(0, 0)) // DECSED, selective erase
                'K' -> eraseInLine(parameter(0, 0))
                'c' -> host.write("\u001b[?6c".toByteArray())
                else -> AppLogger.v(TAG, "Unhandled private CSI ?$final")
            }
            return
        }

        if (privateMarker == '>') {
            when (final) {
                'c' -> host.write("\u001b[>0;276;0c".toByteArray()) // secondary DA, xterm-like
                'q' -> host.write("\u001bP>|Nexus SSH 1.0\u001b\\".toByteArray())
                else -> Unit
            }
            return
        }

        when (final) {
            '@' -> buffer.row(cursorRow).insertCells(cursorColumn, parameter(0), currentStyle)
            'A' -> moveCursor(cursorRow - parameter(0), cursorColumn)
            'B', 'e' -> moveCursor(cursorRow + parameter(0), cursorColumn)
            'C', 'a' -> moveCursor(cursorRow, cursorColumn + parameter(0))
            'D' -> moveCursor(cursorRow, cursorColumn - parameter(0))
            'E' -> moveCursor(cursorRow + parameter(0), 0)
            'F' -> moveCursor(cursorRow - parameter(0), 0)
            'G', '`' -> moveCursor(cursorRow, parameter(0) - 1)
            'H', 'f' -> {
                val row = parameter(0) - 1 + if (originMode) scrollTop else 0
                moveCursor(row, parameter(1) - 1)
            }
            'I' -> tabForward(parameter(0))
            'J' -> eraseInDisplay(parameter(0, 0))
            'K' -> eraseInLine(parameter(0, 0))
            'L' -> if (cursorRow in scrollTop..scrollBottom) {
                buffer.insertLines(cursorRow, parameter(0), scrollBottom, currentStyle)
            }
            'M' -> if (cursorRow in scrollTop..scrollBottom) {
                buffer.deleteLines(cursorRow, parameter(0), scrollBottom, currentStyle)
            }
            'P' -> buffer.row(cursorRow).deleteCells(cursorColumn, parameter(0), currentStyle)
            'S' -> buffer.scrollUp(scrollTop, scrollBottom, parameter(0), currentStyle)
            'T' -> buffer.scrollDown(scrollTop, scrollBottom, parameter(0), currentStyle)
            'X' -> {
                val count = parameter(0)
                buffer.row(cursorRow).clearRange(cursorColumn, cursorColumn + count, currentStyle)
            }
            'Z' -> tabBackward(parameter(0))
            'b' -> repeatLastCharacter(parameter(0))
            'c' -> host.write("\u001b[?62;1;6;9;15;22c".toByteArray()) // VT220 + colour
            'd' -> moveCursor(parameter(0) - 1, cursorColumn)
            'g' -> clearTabStop(parameter(0, 0))
            'h' -> setAnsiModes(true)
            'l' -> setAnsiModes(false)
            'm' -> applySgr()
            'n' -> deviceStatus(parameter(0, 0))
            'p' -> if (intermediates == "!") softReset()
            'q' -> if (intermediates == " ") setCursorStyle(parameter(0, 0))
            'r' -> setScrollingRegion(parameter(0), parameter(1, rows))
            's' -> saveCursor()
            't' -> windowManipulation()
            'u' -> restoreCursor()
            else -> AppLogger.v(TAG, "Unhandled CSI $final")
        }
    }

    private fun moveCursor(row: Int, column: Int) {
        val minRow = if (originMode) scrollTop else 0
        val maxRow = if (originMode) scrollBottom else rows - 1
        cursorRow = row.coerceIn(minRow, maxRow)
        cursorColumn = column.coerceIn(0, columns - 1)
        pendingWrap = false
    }

    private fun repeatLastCharacter(count: Int) {
        val previousColumn = cursorColumn - 1
        if (previousColumn < 0) return
        val row = buffer.row(cursorRow)
        val codePoint = row.codePointAt(previousColumn)
        repeat(count) { putCodePoint(codePoint) }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                buffer.row(cursorRow).clearRange(cursorColumn, columns, currentStyle)
                for (r in cursorRow + 1 until rows) buffer.row(r).clear(currentStyle)
            }

            1 -> {
                buffer.row(cursorRow).clearRange(0, cursorColumn + 1, currentStyle)
                for (r in 0 until cursorRow) buffer.row(r).clear(currentStyle)
            }

            2 -> buffer.clearScreen(currentStyle)

            3 -> {
                buffer.clearScreen(currentStyle)
                normalBuffer.clearScrollback()
            }
        }
        pendingWrap = false
    }

    private fun eraseInLine(mode: Int) {
        val row = buffer.row(cursorRow)
        when (mode) {
            0 -> row.clearRange(cursorColumn, columns, currentStyle)
            1 -> row.clearRange(0, cursorColumn + 1, currentStyle)
            2 -> row.clear(currentStyle)
        }
        pendingWrap = false
    }

    private fun clearTabStop(mode: Int) {
        when (mode) {
            0 -> if (cursorColumn in tabStops.indices) tabStops[cursorColumn] = false
            3 -> tabStops.fill(false)
        }
    }

    private fun setScrollingRegion(top: Int, bottom: Int) {
        val newTop = (top - 1).coerceIn(0, rows - 1)
        val newBottom = (bottom - 1).coerceIn(newTop, rows - 1)
        scrollTop = newTop
        scrollBottom = newBottom
        moveCursor(if (originMode) scrollTop else 0, 0)
    }

    private fun setCursorStyle(style: Int) {
        // 0/1 blinking block, 2 steady block, 3 blinking underline, 4 steady underline,
        // 5 blinking bar, 6 steady bar. The renderer reads [cursorShape].
        cursorShape = when (style) {
            3, 4 -> CursorShape.UNDERLINE
            5, 6 -> CursorShape.BAR
            else -> CursorShape.BLOCK
        }
        cursorBlinkRequested = style == 0 || style == 1 || style == 3 || style == 5
    }

    enum class CursorShape { BLOCK, UNDERLINE, BAR }

    var cursorShape: CursorShape = CursorShape.BLOCK
        private set

    var cursorBlinkRequested: Boolean = true
        private set

    private fun softReset() {
        currentStyle = CellStyle.DEFAULT
        originMode = false
        autoWrap = true
        insertMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        cursorVisible = true
        scrollTop = 0
        scrollBottom = rows - 1
        savedCursor = null
    }

    private fun windowManipulation() {
        when (rawParameter(0)) {
            // Report window size in characters: CSI 8 ; rows ; cols t
            18 -> host.write("\u001b[8;$rows;${columns}t".toByteArray())
            // Report window size in pixels (approximated with an 8x16 cell).
            14 -> host.write("\u001b[4;${rows * 16};${columns * 8}t".toByteArray())
            22 -> titleStack.addLast(title)
            23 -> titleStack.removeLastOrNull()?.let {
                title = it
                host.onTitleChanged(it)
            }
        }
    }

    private fun decAlignmentTest() {
        for (r in 0 until rows) {
            val row = buffer.row(r)
            for (c in 0 until columns) row.setCell(c, 'E'.code, CellStyle.DEFAULT)
        }
        cursorRow = 0
        cursorColumn = 0
    }

    // =======================================================================================
    // Modes
    // =======================================================================================

    private fun setAnsiModes(enable: Boolean) {
        for (i in 0 until maxOf(parameterCount, 1)) {
            when (rawParameter(i)) {
                4 -> insertMode = enable
                20 -> lineFeedNewLineMode = enable
            }
        }
    }

    private val savedDecModes = HashMap<Int, Boolean>()

    private fun saveDecModes() {
        for (i in 0 until maxOf(parameterCount, 1)) {
            val mode = rawParameter(i)
            savedDecModes[mode] = readDecMode(mode)
        }
    }

    private fun restoreDecModes() {
        for (i in 0 until maxOf(parameterCount, 1)) {
            val mode = rawParameter(i)
            savedDecModes[mode]?.let { applyDecMode(mode, it) }
        }
    }

    private fun readDecMode(mode: Int): Boolean = when (mode) {
        1 -> applicationCursorKeys
        5 -> reverseVideo
        6 -> originMode
        7 -> autoWrap
        25 -> cursorVisible
        1004 -> focusReporting
        2004 -> bracketedPaste
        47, 1047, 1049 -> isAlternateScreen
        else -> false
    }

    private fun setDecModes(enable: Boolean) {
        for (i in 0 until maxOf(parameterCount, 1)) {
            applyDecMode(rawParameter(i), enable)
        }
    }

    private fun applyDecMode(mode: Int, enable: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = enable
            3 -> {
                // DECCOLM: switching column count clears the screen.
                buffer.clearScreen(currentStyle)
                moveCursor(0, 0)
            }
            5 -> reverseVideo = enable
            6 -> {
                originMode = enable
                moveCursor(if (enable) scrollTop else 0, 0)
            }
            7 -> autoWrap = enable
            12 -> cursorBlinkRequested = enable
            25 -> cursorVisible = enable
            45 -> reverseWrapAround = enable
            66 -> applicationKeypad = enable
            1000 -> mouseTrackingMode = if (enable) MouseMode.NORMAL else MouseMode.OFF
            1002 -> mouseTrackingMode = if (enable) MouseMode.BUTTON_EVENT else MouseMode.OFF
            1003 -> mouseTrackingMode = if (enable) MouseMode.ANY_EVENT else MouseMode.OFF
            9 -> mouseTrackingMode = if (enable) MouseMode.X10 else MouseMode.OFF
            1004 -> focusReporting = enable
            1005 -> mouseProtocol = if (enable) MouseProtocol.UTF8 else MouseProtocol.X11
            1006 -> mouseProtocol = if (enable) MouseProtocol.SGR else MouseProtocol.X11
            1015 -> mouseProtocol = if (enable) MouseProtocol.URXVT else MouseProtocol.X11
            47, 1047 -> if (enable) switchToAlternateScreen(saveCursor = false) else switchToNormalScreen(restoreCursor = false)
            1048 -> if (enable) saveCursor() else restoreCursor()
            1049 -> if (enable) switchToAlternateScreen(saveCursor = true) else switchToNormalScreen(restoreCursor = true)
            2004 -> bracketedPaste = enable
            else -> AppLogger.v(TAG, "Unhandled DEC mode $mode")
        }
    }

    private fun switchToAlternateScreen(saveCursor: Boolean) {
        if (isAlternateScreen) return
        if (saveCursor) savedCursorAlternate = captureCursor()
        alternateBuffer.clearScreen(currentStyle)
        buffer = alternateBuffer
        isAlternateScreen = true
        if (saveCursor) {
            cursorRow = 0
            cursorColumn = 0
        }
        host.onAlternateScreen(true)
    }

    private fun switchToNormalScreen(restoreCursor: Boolean) {
        if (!isAlternateScreen) return
        buffer = normalBuffer
        isAlternateScreen = false
        if (restoreCursor) {
            savedCursorAlternate?.let(::applyCursor)
            savedCursorAlternate = null
        }
        host.onAlternateScreen(false)
    }

    private fun captureCursor() = SavedCursor(
        row = cursorRow,
        column = cursorColumn,
        style = currentStyle,
        originMode = originMode,
        g0 = charsets[0],
        g1 = charsets[1],
        charsetIndex = activeCharset,
    )

    private fun applyCursor(saved: SavedCursor) {
        cursorRow = saved.row.coerceIn(0, rows - 1)
        cursorColumn = saved.column.coerceIn(0, columns - 1)
        currentStyle = saved.style
        originMode = saved.originMode
        charsets[0] = saved.g0
        charsets[1] = saved.g1
        activeCharset = saved.charsetIndex
        pendingWrap = false
    }

    private fun saveCursor() {
        savedCursor = captureCursor()
    }

    private fun restoreCursor() {
        savedCursor?.let(::applyCursor) ?: moveCursor(0, 0)
    }

    // =======================================================================================
    // SGR
    // =======================================================================================

    private fun applySgr() {
        if (parameterCount == 0) {
            currentStyle = CellStyle.DEFAULT
            return
        }
        var i = 0
        while (i < parameterCount) {
            when (val code = parameters[i]) {
                0 -> currentStyle = CellStyle.DEFAULT
                1 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.BOLD)
                2 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.DIM)
                3 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.ITALIC)
                4 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.UNDERLINE)
                5, 6 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.BLINK)
                7 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.INVERSE)
                8 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.INVISIBLE)
                9 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.STRIKETHROUGH)
                21 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.DOUBLE_UNDERLINE)
                22 -> currentStyle = CellStyle.removeAttribute(currentStyle, CellStyle.BOLD or CellStyle.DIM)
                23 -> currentStyle = CellStyle.removeAttribute(currentStyle, CellStyle.ITALIC)
                24 -> currentStyle = CellStyle.removeAttribute(
                    currentStyle,
                    CellStyle.UNDERLINE or CellStyle.DOUBLE_UNDERLINE or CellStyle.CURLY_UNDERLINE,
                )
                25 -> currentStyle = CellStyle.removeAttribute(currentStyle, CellStyle.BLINK)
                27 -> currentStyle = CellStyle.removeAttribute(currentStyle, CellStyle.INVERSE)
                28 -> currentStyle = CellStyle.removeAttribute(currentStyle, CellStyle.INVISIBLE)
                29 -> currentStyle = CellStyle.removeAttribute(currentStyle, CellStyle.STRIKETHROUGH)
                in 30..37 -> currentStyle = CellStyle.withForeground(currentStyle, (code - 30).toLong())
                38 -> i = applyExtendedColor(i, foreground = true)
                39 -> currentStyle = CellStyle.withForeground(currentStyle, CellStyle.DEFAULT_FG_INDEX)
                in 40..47 -> currentStyle = CellStyle.withBackground(currentStyle, (code - 40).toLong())
                48 -> i = applyExtendedColor(i, foreground = false)
                49 -> currentStyle = CellStyle.withBackground(currentStyle, CellStyle.DEFAULT_BG_INDEX)
                53 -> currentStyle = CellStyle.addAttribute(currentStyle, CellStyle.OVERLINE)
                55 -> currentStyle = CellStyle.removeAttribute(currentStyle, CellStyle.OVERLINE)
                58 -> i = skipExtendedColor(i) // underline colour: parsed, rendered as normal
                59 -> Unit
                in 90..97 -> currentStyle = CellStyle.withForeground(currentStyle, (code - 90 + 8).toLong())
                in 100..107 -> currentStyle = CellStyle.withBackground(currentStyle, (code - 100 + 8).toLong())
                else -> Unit
            }
            i++
        }
    }

    /** Handles `38;5;n`, `38;2;r;g;b` and the colon-separated variants. Returns the new index. */
    private fun applyExtendedColor(startIndex: Int, foreground: Boolean): Int {
        var i = startIndex
        if (i + 1 >= parameterCount) return i
        return when (parameters[i + 1]) {
            5 -> {
                val index = if (i + 2 < parameterCount) parameters[i + 2].toLong() else 0L
                currentStyle = if (foreground) {
                    CellStyle.withForeground(currentStyle, index)
                } else {
                    CellStyle.withBackground(currentStyle, index)
                }
                i + 2
            }

            2 -> {
                // Some emitters insert an empty colour-space id: 38:2::r:g:b
                var base = i + 2
                if (parameterCount - base >= 4) base++
                val r = if (base < parameterCount) parameters[base] else 0
                val g = if (base + 1 < parameterCount) parameters[base + 1] else 0
                val b = if (base + 2 < parameterCount) parameters[base + 2] else 0
                val packed = CellStyle.trueColor(r, g, b)
                currentStyle = if (foreground) {
                    CellStyle.withForeground(currentStyle, packed)
                } else {
                    CellStyle.withBackground(currentStyle, packed)
                }
                base + 2
            }

            else -> {
                i++
                i
            }
        }
    }

    private fun skipExtendedColor(startIndex: Int): Int {
        if (startIndex + 1 >= parameterCount) return startIndex
        return when (parameters[startIndex + 1]) {
            5 -> startIndex + 2
            2 -> startIndex + 4
            else -> startIndex + 1
        }
    }

    // =======================================================================================
    // Reports
    // =======================================================================================

    private fun deviceStatus(mode: Int) {
        when (mode) {
            5 -> host.write("\u001b[0n".toByteArray()) // terminal OK
            6 -> {
                val reportRow = cursorRow - (if (originMode) scrollTop else 0) + 1
                host.write("\u001b[$reportRow;${cursorColumn + 1}R".toByteArray())
            }
        }
    }

    private fun deviceStatusPrivate(mode: Int) {
        when (mode) {
            6 -> {
                val reportRow = cursorRow - (if (originMode) scrollTop else 0) + 1
                host.write("\u001b[?$reportRow;${cursorColumn + 1};1R".toByteArray())
            }

            15 -> host.write("\u001b[?11n".toByteArray()) // no printer
            25 -> host.write("\u001b[?21n".toByteArray()) // UDKs locked
            26 -> host.write("\u001b[?27;1;0;0n".toByteArray()) // keyboard: North American
        }
    }

    // =======================================================================================
    // OSC / DCS
    // =======================================================================================

    private fun handleOsc(payload: String) {
        val separator = payload.indexOf(';')
        val command = if (separator >= 0) payload.substring(0, separator) else payload
        val data = if (separator >= 0) payload.substring(separator + 1) else ""

        when (command) {
            "0", "2" -> {
                title = data
                host.onTitleChanged(data)
            }

            "1" -> Unit // icon name

            "4" -> parsePaletteAssignment(data)

            "8" -> Unit // hyperlink: params;uri - the URI is picked up by the link detector

            "10" -> host.write("\u001b]10;rgb:ffff/ffff/ffff\u0007".toByteArray())
            "11" -> host.write("\u001b]11;rgb:0000/0000/0000\u0007".toByteArray())

            "52" -> {
                // selection;base64 - only the clipboard target is honoured.
                val parts = data.split(';', limit = 2)
                if (parts.size == 2 && parts[1] != "?") {
                    runCatching {
                        val decoded = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
                        host.onClipboardCopy(String(decoded, Charsets.UTF_8))
                    }
                }
            }

            "104" -> paletteOverrides.clear()

            else -> AppLogger.v(TAG, "Unhandled OSC $command")
        }
    }

    /** `OSC 4 ; index ; rgb:RRRR/GGGG/BBBB` (also accepts #RRGGBB). */
    private fun parsePaletteAssignment(data: String) {
        val parts = data.split(';')
        var i = 0
        while (i + 1 < parts.size) {
            val index = parts[i].toIntOrNull()
            val spec = parts[i + 1]
            if (index != null) {
                parseColorSpec(spec)?.let { paletteOverrides[index] = it }
            }
            i += 2
        }
    }

    private fun parseColorSpec(spec: String): Int? = when {
        spec.startsWith("rgb:") -> {
            val components = spec.removePrefix("rgb:").split('/')
            if (components.size == 3) {
                val values = components.map { component ->
                    val value = component.toIntOrNull(16) ?: return null
                    when (component.length) {
                        1 -> value * 17
                        2 -> value
                        4 -> value shr 8
                        else -> value and 0xFF
                    }
                }
                TerminalPalette.rgb(values[0], values[1], values[2])
            } else {
                null
            }
        }

        spec.startsWith("#") && spec.length == 7 ->
            spec.substring(1).toIntOrNull(16)?.let { 0xFF000000.toInt() or it }

        else -> null
    }

    private fun handleDcs(payload: String) {
        // DECRQSS (request status string) is the only DCS worth answering for a shell client.
        if (payload.startsWith("\\$q")) {
            val request = payload.removePrefix("\\$q")
            val response = when (request) {
                "m" -> "\u001bP1\\$r0m\u001b\\\\"
                "r" -> "\u001bP1\\$r${scrollTop + 1};${scrollBottom + 1}r\u001b\\\\"
                else -> "\u001bP0\\$r\u001b\\\\"
            }
            host.write(response.toByteArray())
        }
    }

    // =======================================================================================
    // Mouse reporting
    // =======================================================================================

    /**
     * Encodes a mouse event for the active protocol.
     *
     * @param button 0 left, 1 middle, 2 right, 64 wheel up, 65 wheel down
     * @param column zero-based
     * @param row zero-based
     */
    fun sendMouseEvent(button: Int, column: Int, row: Int, pressed: Boolean, motion: Boolean = false) {
        if (mouseTrackingMode == MouseMode.OFF) return
        if (motion && mouseTrackingMode != MouseMode.ANY_EVENT && mouseTrackingMode != MouseMode.BUTTON_EVENT) return

        val safeColumn = column.coerceIn(0, columns - 1)
        val safeRow = row.coerceIn(0, rows - 1)
        var code = button
        if (motion) code += 32

        val payload = when (mouseProtocol) {
            MouseProtocol.SGR -> {
                val action = if (pressed) 'M' else 'm'
                "\u001b[<$code;${safeColumn + 1};${safeRow + 1}$action"
            }

            MouseProtocol.URXVT -> {
                val value = (if (pressed) code else 3) + 32
                "\u001b[$value;${safeColumn + 1};${safeRow + 1}M"
            }

            else -> {
                val value = (if (pressed) code else 3) + 32
                buildString {
                    append("\u001b[M")
                    append(value.toChar())
                    append((safeColumn + 33).toChar())
                    append((safeRow + 33).toChar())
                }
            }
        }
        host.write(payload.toByteArray(Charsets.UTF_8))
    }

    fun sendFocusEvent(focused: Boolean) {
        if (!focusReporting) return
        host.write((if (focused) "\u001b[I" else "\u001b[O").toByteArray())
    }

    // =======================================================================================
    // Character sets
    // =======================================================================================

    private fun charsetFor(final: Char): Int = when (final) {
        '0' -> CHARSET_DEC_GRAPHICS
        'A' -> CHARSET_UK
        else -> CHARSET_ASCII
    }

    /** DEC special graphics: the box-drawing set used by ncurses, mc, dialog... */
    private fun mapDecGraphics(codePoint: Int): Int {
        if (codePoint < 0x5F || codePoint > 0x7E) return codePoint
        return DEC_GRAPHICS[codePoint - 0x5F]
    }

    companion object {
        private const val TAG = "TerminalEmulator"
        private const val MAX_PARAMETERS = 32
        private const val MAX_STRING_LENGTH = 8192

        private const val CHARSET_ASCII = 0
        private const val CHARSET_DEC_GRAPHICS = 1
        private const val CHARSET_UK = 2

        /** Code points for 0x5F..0x7E in the DEC special graphics set. */
        private val DEC_GRAPHICS = intArrayOf(
            0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )
    }
}
