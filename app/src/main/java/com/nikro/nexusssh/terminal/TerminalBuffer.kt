package com.nikro.nexusssh.terminal

/**
 * Screen plus scrollback for one terminal.
 *
 * Coordinates come in two flavours:
 *  * **screen** rows, `0 until rows`, used by the emulator and the cursor
 *  * **absolute** lines, `0 until totalLines`, which include the scrollback and are used by the
 *    renderer, selection and search so they stay stable while output scrolls
 */
class TerminalBuffer(
    columns: Int,
    rows: Int,
    scrollbackLimit: Int,
    private val collectScrollback: Boolean,
) {

    var columns: Int = columns
        private set

    var rows: Int = rows
        private set

    var scrollbackLimit: Int = scrollbackLimit
        set(value) {
            field = value.coerceAtLeast(0)
            trimScrollback()
        }

    private val screen = ArrayList<TerminalRow>(rows).apply {
        repeat(rows) { add(TerminalRow(columns)) }
    }

    private val scrollback = ArrayDeque<TerminalRow>()

    /** Rows currently kept above the screen. */
    val scrollbackLines: Int get() = scrollback.size

    /** Scrollback + screen. */
    val totalLines: Int get() = scrollback.size + screen.size

    /** Absolute index of the first visible (screen) line. */
    val viewportTop: Int get() = scrollback.size

    fun row(index: Int): TerminalRow = screen[index.coerceIn(0, screen.size - 1)]

    fun line(absolute: Int): TerminalRow {
        val index = absolute.coerceIn(0, totalLines - 1)
        return if (index < scrollback.size) scrollback[index] else screen[index - scrollback.size]
    }

    fun clearScreen(style: Long) {
        screen.forEach { it.clear(style) }
    }

    fun clearScrollback() {
        scrollback.clear()
    }

    /** SU / LF at the bottom margin: the region moves up and blank rows appear at [bottom]. */
    fun scrollUp(top: Int, bottom: Int, count: Int, style: Long) {
        val first = top.coerceIn(0, rows - 1)
        val last = bottom.coerceIn(first, rows - 1)
        val amount = count.coerceIn(0, last - first + 1)
        repeat(amount) {
            val removed = screen.removeAt(first)
            if (first == 0 && collectScrollback && scrollbackLimit > 0) {
                scrollback.addLast(removed)
                trimScrollback()
            }
            val recycled = if (first == 0 && collectScrollback && scrollbackLimit > 0) {
                TerminalRow(columns)
            } else {
                removed.apply { clear(style) }
            }
            if (recycled.columns != columns) recycled.resize(columns, style)
            recycled.clear(style)
            screen.add(last, recycled)
        }
    }

    /** SD / RI at the top margin: the region moves down and blank rows appear at [top]. */
    fun scrollDown(top: Int, bottom: Int, count: Int, style: Long) {
        val first = top.coerceIn(0, rows - 1)
        val last = bottom.coerceIn(first, rows - 1)
        val amount = count.coerceIn(0, last - first + 1)
        repeat(amount) {
            val removed = screen.removeAt(last)
            removed.clear(style)
            screen.add(first, removed)
        }
    }

    /** IL - insert blank lines at [at], pushing the rest down to [bottom]. */
    fun insertLines(at: Int, count: Int, bottom: Int, style: Long) {
        val start = at.coerceIn(0, rows - 1)
        val last = bottom.coerceIn(start, rows - 1)
        val amount = count.coerceIn(0, last - start + 1)
        repeat(amount) {
            val removed = screen.removeAt(last)
            removed.clear(style)
            screen.add(start, removed)
        }
    }

    /** DL - delete lines at [at], pulling the rest up and blanking [bottom]. */
    fun deleteLines(at: Int, count: Int, bottom: Int, style: Long) {
        val start = at.coerceIn(0, rows - 1)
        val last = bottom.coerceIn(start, rows - 1)
        val amount = count.coerceIn(0, last - start + 1)
        repeat(amount) {
            val removed = screen.removeAt(start)
            removed.clear(style)
            screen.add(last, removed)
        }
    }

    /**
     * Resizes the buffer. Growing pulls lines back out of the scrollback (so `clear`ing a window
     * then rotating the device does not lose the prompt), shrinking pushes them into it.
     *
     * @return how much the cursor row moved, to be applied by the caller.
     */
    fun resize(newColumns: Int, newRows: Int, style: Long, cursorRow: Int): Int {
        var adjustment = 0

        if (newColumns != columns) {
            screen.forEach { it.resize(newColumns, style) }
            scrollback.forEach { it.resize(newColumns, style) }
            columns = newColumns
        }

        if (newRows > rows) {
            var missing = newRows - rows
            while (missing > 0 && collectScrollback && scrollback.isNotEmpty()) {
                screen.add(0, scrollback.removeLast())
                adjustment++
                missing--
            }
            repeat(missing) { screen.add(TerminalRow(columns)) }
        } else if (newRows < rows) {
            var extra = rows - newRows
            // Prefer dropping empty rows below the cursor before archiving real output.
            while (extra > 0 && screen.size > 1 && screen.last().isBlank && screen.size - 1 > cursorRow) {
                screen.removeAt(screen.size - 1)
                extra--
            }
            while (extra > 0 && screen.size > 1) {
                val removed = screen.removeAt(0)
                if (collectScrollback && scrollbackLimit > 0) {
                    scrollback.addLast(removed)
                    trimScrollback()
                }
                adjustment--
                extra--
            }
        }

        rows = screen.size
        return adjustment
    }

    /** Extracts text between two absolute positions, honouring soft wraps. */
    fun textBetween(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): String {
        val firstLine = startLine.coerceIn(0, totalLines - 1)
        val lastLine = endLine.coerceIn(firstLine, totalLines - 1)
        val builder = StringBuilder()
        for (absolute in firstLine..lastLine) {
            val row = line(absolute)
            val from = if (absolute == firstLine) startColumn.coerceIn(0, row.columns) else 0
            val to = if (absolute == lastLine) (endColumn + 1).coerceIn(from, row.columns) else row.columns
            builder.append(row.text(from, to, trimTrailing = absolute != lastLine && !row.wrapped))
            if (absolute != lastLine && !row.wrapped) builder.append('\n')
        }
        return builder.toString()
    }

    /** Whole buffer as text - used by "share output" and log export. */
    fun snapshotText(includeScrollback: Boolean = true): String {
        val from = if (includeScrollback) 0 else scrollback.size
        return textBetween(from, 0, totalLines - 1, columns - 1)
    }

    private fun trimScrollback() {
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
    }
}
