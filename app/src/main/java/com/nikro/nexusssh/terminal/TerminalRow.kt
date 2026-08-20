package com.nikro.nexusssh.terminal

/**
 * One line of terminal text.
 *
 * Cells are stored in two parallel primitive arrays - code points and packed [CellStyle] values -
 * so a 10 000 line scrollback of 120 columns costs about 14 MB instead of the ~100 MB an object
 * per cell would need.
 */
class TerminalRow(columns: Int) {

    var columns: Int = columns
        private set

    private var codePoints = IntArray(columns) { SPACE }
    private var styles = LongArray(columns) { CellStyle.DEFAULT }

    /** True when the line continues on the next row because of autowrap (soft wrap). */
    var wrapped: Boolean = false

    /** Bumped on every mutation; the renderer uses it to skip untouched rows. */
    var revision: Int = 0
        private set

    fun codePointAt(column: Int): Int =
        if (column in 0 until columns) codePoints[column] else SPACE

    fun styleAt(column: Int): Long =
        if (column in 0 until columns) styles[column] else CellStyle.DEFAULT

    fun setCell(column: Int, codePoint: Int, style: Long) {
        if (column !in 0 until columns) return
        codePoints[column] = codePoint
        styles[column] = style
        revision++
    }

    fun setStyle(column: Int, style: Long) {
        if (column !in 0 until columns) return
        styles[column] = style
        revision++
    }

    fun clear(style: Long) {
        codePoints.fill(SPACE)
        styles.fill(blankStyle(style))
        wrapped = false
        revision++
    }

    /** Clears `[from, to)`, clamped to the row. */
    fun clearRange(from: Int, to: Int, style: Long) {
        val start = from.coerceIn(0, columns)
        val end = to.coerceIn(start, columns)
        if (start == end) return
        val blank = blankStyle(style)
        for (i in start until end) {
            codePoints[i] = SPACE
            styles[i] = blank
        }
        if (end >= columns) wrapped = false
        revision++
    }

    /** ICH - shifts the tail right, dropping what falls off the end. */
    fun insertCells(column: Int, count: Int, style: Long) {
        val start = column.coerceIn(0, columns)
        val amount = count.coerceIn(0, columns - start)
        if (amount == 0) return
        val moved = columns - start - amount
        if (moved > 0) {
            System.arraycopy(codePoints, start, codePoints, start + amount, moved)
            System.arraycopy(styles, start, styles, start + amount, moved)
        }
        val blank = blankStyle(style)
        for (i in start until start + amount) {
            codePoints[i] = SPACE
            styles[i] = blank
        }
        revision++
    }

    /** DCH - shifts the tail left, filling the end with blanks. */
    fun deleteCells(column: Int, count: Int, style: Long) {
        val start = column.coerceIn(0, columns)
        val amount = count.coerceIn(0, columns - start)
        if (amount == 0) return
        val moved = columns - start - amount
        if (moved > 0) {
            System.arraycopy(codePoints, start + amount, codePoints, start, moved)
            System.arraycopy(styles, start + amount, styles, start, moved)
        }
        val blank = blankStyle(style)
        for (i in columns - amount until columns) {
            codePoints[i] = SPACE
            styles[i] = blank
        }
        revision++
    }

    /** Grows or shrinks the row, preserving content. */
    fun resize(newColumns: Int, style: Long) {
        if (newColumns == columns) return
        val newCodePoints = IntArray(newColumns) { SPACE }
        val blank = blankStyle(style)
        val newStyles = LongArray(newColumns) { blank }
        val copy = minOf(columns, newColumns)
        System.arraycopy(codePoints, 0, newCodePoints, 0, copy)
        System.arraycopy(styles, 0, newStyles, 0, copy)
        codePoints = newCodePoints
        styles = newStyles
        columns = newColumns
        if (newColumns < copy) wrapped = false
        revision++
    }

    /** Index of the last non-blank cell, or -1 for an empty row. */
    fun lastNonBlankColumn(): Int {
        for (i in columns - 1 downTo 0) {
            if (codePoints[i] != SPACE || CellStyle.background(styles[i]) != CellStyle.DEFAULT_BG_INDEX) {
                return i
            }
        }
        return -1
    }

    val isBlank: Boolean get() = lastNonBlankColumn() < 0

    /** Renders `[from, to)` as text; wide-glyph placeholders are skipped. */
    fun text(from: Int = 0, to: Int = columns, trimTrailing: Boolean = false): String {
        val start = from.coerceIn(0, columns)
        var end = to.coerceIn(start, columns)
        if (trimTrailing) {
            val last = lastNonBlankColumn()
            end = minOf(end, last + 1).coerceAtLeast(start)
        }
        if (start >= end) return ""
        val builder = StringBuilder(end - start)
        for (i in start until end) {
            val codePoint = codePoints[i]
            if (codePoint == WIDE_PLACEHOLDER) continue
            builder.appendCodePoint(codePoint)
        }
        return builder.toString()
    }

    /** Copies this row (used when the alternate screen is snapshotted). */
    fun copy(): TerminalRow {
        val row = TerminalRow(columns)
        System.arraycopy(codePoints, 0, row.codePoints, 0, columns)
        System.arraycopy(styles, 0, row.styles, 0, columns)
        row.wrapped = wrapped
        return row
    }

    /** Blank cells keep the background colour (so `clear` paints the current bg like xterm). */
    private fun blankStyle(style: Long): Long = CellStyle.pack(
        CellStyle.DEFAULT_FG_INDEX,
        CellStyle.background(style),
        0L,
    )

    companion object {
        const val SPACE = ' '.code

        /** Filler stored in the second cell of a double-width glyph. */
        const val WIDE_PLACEHOLDER = -1
    }
}
