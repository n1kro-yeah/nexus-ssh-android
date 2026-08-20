package com.nikro.nexusssh.terminal

/**
 * A text selection expressed in absolute buffer coordinates, so it survives scrolling and new
 * output arriving underneath it.
 *
 * [Mode.BLOCK] selects a rectangle (handy for copying a column out of `top`), the other modes
 * behave like a desktop terminal.
 */
data class TerminalSelection(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val mode: Mode = Mode.CHARACTER,
) {

    enum class Mode { CHARACTER, WORD, LINE, BLOCK }

    val isEmpty: Boolean
        get() = startLine == endLine && startColumn == endColumn && mode != Mode.LINE

    /** Start before end, so rendering and extraction never have to care about drag direction. */
    fun normalized(): TerminalSelection {
        val reversed = endLine < startLine || (endLine == startLine && endColumn < startColumn)
        return if (!reversed) {
            this
        } else {
            TerminalSelection(endLine, endColumn, startLine, startColumn, mode)
        }
    }

    /** True when the cell at [line]/[column] is painted with the selection colour. */
    fun contains(line: Int, column: Int): Boolean {
        val selection = normalized()
        if (line < selection.startLine || line > selection.endLine) return false
        return when (selection.mode) {
            Mode.LINE -> true
            Mode.BLOCK -> {
                val left = minOf(selection.startColumn, selection.endColumn)
                val right = maxOf(selection.startColumn, selection.endColumn)
                column in left..right
            }

            else -> when {
                selection.startLine == selection.endLine ->
                    column >= selection.startColumn && column <= selection.endColumn

                line == selection.startLine -> column >= selection.startColumn
                line == selection.endLine -> column <= selection.endColumn
                else -> true
            }
        }
    }

    /** Column range of the selection on [line], or null when the line is untouched. */
    fun rangeOn(line: Int, columns: Int): IntRange? {
        val selection = normalized()
        if (line < selection.startLine || line > selection.endLine) return null
        return when (selection.mode) {
            Mode.LINE -> 0 until columns
            Mode.BLOCK -> {
                val left = minOf(selection.startColumn, selection.endColumn)
                val right = maxOf(selection.startColumn, selection.endColumn)
                left..right.coerceAtMost(columns - 1)
            }

            else -> {
                val from = if (line == selection.startLine) selection.startColumn else 0
                val to = if (line == selection.endLine) selection.endColumn else columns - 1
                if (from > to) null else from..to
            }
        }
    }

    /**
     * Copies the selected region out of [buffer].
     *
     * Trailing blanks are trimmed per line (matching xterm) and lines that the emulator marked as
     * soft-wrapped are joined without a newline, so copying a long command produces one line.
     */
    fun extractText(buffer: TerminalBuffer): String {
        val selection = normalized()
        val builder = StringBuilder()
        for (line in selection.startLine..selection.endLine) {
            if (line < 0 || line >= buffer.totalLines) continue
            val row = buffer.line(line)
            val range = selection.rangeOn(line, row.columns) ?: continue
            val from = range.first.coerceIn(0, row.columns)
            val to = (range.last + 1).coerceIn(from, row.columns)
            val trimTrailing = selection.mode == Mode.LINE ||
                (line != selection.endLine && selection.mode != Mode.BLOCK)
            builder.append(row.text(from, to, trimTrailing))
            val isLast = line == selection.endLine
            if (!isLast) {
                val softWrapped = row.wrapped && selection.mode != Mode.BLOCK
                if (!softWrapped) builder.append('\n')
            }
        }
        return builder.toString()
    }

    companion object {
        /** Selection covering one whole logical line, including its soft-wrapped continuation. */
        fun forLine(buffer: TerminalBuffer, line: Int): TerminalSelection {
            var last = line
            while (last + 1 < buffer.totalLines && buffer.line(last).wrapped) last++
            var first = line
            while (first > 0 && buffer.line(first - 1).wrapped) first--
            return TerminalSelection(first, 0, last, buffer.line(last).columns - 1, Mode.LINE)
        }

        /** Double-tap selection: the word under the cell. */
        fun forWord(buffer: TerminalBuffer, line: Int, column: Int): TerminalSelection {
            if (line < 0 || line >= buffer.totalLines) {
                return TerminalSelection(line, column, line, column, Mode.WORD)
            }
            val row = buffer.line(line)
            if (column < 0 || column >= row.columns) {
                return TerminalSelection(line, column, line, column, Mode.WORD)
            }
            if (!isWordCharacter(row.codePointAt(column))) {
                return TerminalSelection(line, column, line, column, Mode.WORD)
            }
            var start = column
            while (start > 0 && isWordCharacter(row.codePointAt(start - 1))) start--
            var end = column
            while (end + 1 < row.columns && isWordCharacter(row.codePointAt(end + 1))) end++
            return TerminalSelection(line, start, line, end, Mode.WORD)
        }

        /**
         * Characters that count as part of a "word". Path and URL punctuation is included so a
         * double tap grabs `/etc/nginx/nginx.conf` or `user@host` in one go.
         */
        private fun isWordCharacter(codePoint: Int): Boolean {
            if (codePoint <= 0) return false
            val char = codePoint.toChar()
            return char.isLetterOrDigit() || char in WORD_PUNCTUATION
        }

        private const val WORD_PUNCTUATION = "_-./:@~+=%#?&$[]{}!*'\"\\"
    }
}

/**
 * Finds URLs, IP addresses and `user@host` pairs in terminal rows so they can be tapped.
 *
 * Matching runs against the reconstructed logical line (soft wraps joined) so a URL split across
 * two rows is still recognised, then the hit is mapped back to per-row column ranges.
 */
object TerminalLinkDetector {

    data class Link(
        val text: String,
        val kind: Kind,
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
    ) {
        enum class Kind { URL, EMAIL, HOST, PATH }

        /** What the tap handler should open. */
        val uri: String
            get() = when (kind) {
                Kind.URL -> text
                Kind.EMAIL -> "mailto:$text"
                Kind.HOST -> "ssh://$text"
                Kind.PATH -> text
            }
    }

    private val urlRegex = Regex(
        """(?:https?|ftp|ssh|sftp|file)://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE,
    )
    private val emailRegex = Regex("""[\w.+-]+@[\w-]+\.[\w.-]{2,}""")
    private val hostRegex = Regex("""[\w.-]+@(?:\d{1,3}(?:\.\d{1,3}){3}|[\w-]+(?:\.[\w-]+)+)""")
    private val pathRegex = Regex("""(?:/[\w.\-+@]+){2,}/?""")

    /** Scans [lineCount] rows starting at absolute line [from]. */
    fun detect(buffer: TerminalBuffer, from: Int, lineCount: Int): List<Link> {
        val links = mutableListOf<Link>()
        var line = from.coerceAtLeast(0)
        val end = (from + lineCount).coerceAtMost(buffer.totalLines)
        while (line < end) {
            // Join the soft-wrapped continuation rows into one logical line.
            val rows = mutableListOf<Pair<Int, TerminalRow>>()
            var cursor = line
            while (cursor < buffer.totalLines) {
                val row = buffer.line(cursor)
                rows += cursor to row
                if (!row.wrapped) break
                cursor++
            }
            val text = rows.joinToString("") { it.second.text(0, it.second.columns, false) }
            links += matchesIn(text).map { (matchText, kind, range) ->
                val start = locate(rows, range.first)
                val stop = locate(rows, range.last)
                Link(matchText, kind, start.first, start.second, stop.first, stop.second)
            }
            line = cursor + 1
        }
        return links
    }

    /** The link at a tapped cell, if any. */
    fun linkAt(buffer: TerminalBuffer, line: Int, column: Int): Link? =
        detect(buffer, line, 1).firstOrNull { link ->
            TerminalSelection(link.startLine, link.startColumn, link.endLine, link.endColumn)
                .contains(line, column)
        }

    private fun matchesIn(text: String): List<Triple<String, Link.Kind, IntRange>> {
        if (text.isBlank()) return emptyList()
        val found = mutableListOf<Triple<String, Link.Kind, IntRange>>()
        val taken = mutableListOf<IntRange>()

        fun collect(regex: Regex, kind: Link.Kind) {
            regex.findAll(text).forEach { match ->
                val range = match.range
                if (taken.none { it.first <= range.last && range.first <= it.last }) {
                    // Trailing punctuation is almost never part of the target.
                    val trimmed = match.value.trimEnd('.', ',', ')', ']', '\'', '"', ';', ':')
                    val adjusted = range.first..(range.first + trimmed.length - 1)
                    taken += adjusted
                    found += Triple(trimmed, kind, adjusted)
                }
            }
        }

        collect(urlRegex, Link.Kind.URL)
        collect(emailRegex, Link.Kind.EMAIL)
        collect(hostRegex, Link.Kind.HOST)
        collect(pathRegex, Link.Kind.PATH)
        return found.sortedBy { it.third.first }
    }

    /** Maps an index in the joined text back to (absolute line, column). */
    private fun locate(rows: List<Pair<Int, TerminalRow>>, index: Int): Pair<Int, Int> {
        var remaining = index
        rows.forEach { (line, row) ->
            if (remaining < row.columns) return line to remaining
            remaining -= row.columns
        }
        val last = rows.last()
        return last.first to (last.second.columns - 1)
    }
}
