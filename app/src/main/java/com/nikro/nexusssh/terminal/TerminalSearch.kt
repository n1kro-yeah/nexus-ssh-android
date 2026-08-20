package com.nikro.nexusssh.terminal

/**
 * Incremental search over the screen and scrollback.
 *
 * Matches are collected once per query into absolute buffer coordinates; navigation then just
 * moves an index, which keeps "next match" instant even with a 100k-line scrollback.
 */
class TerminalSearch {

    data class Options(
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val useRegex: Boolean = false,
    )

    data class Match(
        val line: Int,
        val startColumn: Int,
        val endColumn: Int,
    ) {
        val length: Int get() = endColumn - startColumn + 1
    }

    var query: String = ""
        private set

    var options: Options = Options()
        private set

    var matches: List<Match> = emptyList()
        private set

    /** Index into [matches]; -1 when there is no active match. */
    var currentIndex: Int = -1
        private set

    var error: String? = null
        private set

    val isActive: Boolean get() = query.isNotEmpty()

    val matchCount: Int get() = matches.size

    /**
     * Runs a search and selects the match closest to [nearLine] (normally the bottom of the
     * viewport), so the first jump goes to the most recent hit rather than to the top of history.
     *
     * @return the number of matches found
     */
    fun search(
        buffer: TerminalBuffer,
        query: String,
        options: Options = Options(),
        nearLine: Int = buffer.totalLines,
    ): Int {
        this.query = query
        this.options = options
        this.error = null

        if (query.isEmpty()) {
            matches = emptyList()
            currentIndex = -1
            return 0
        }

        val regex = buildRegex(query, options)
        if (regex == null) {
            matches = emptyList()
            currentIndex = -1
            return 0
        }

        val found = mutableListOf<Match>()
        for (line in 0 until buffer.totalLines) {
            val row = buffer.line(line)
            if (row.isBlank) continue
            val text = row.text(0, row.columns, trimTrailing = true)
            if (text.isEmpty()) continue
            regex.findAll(text).forEach { match ->
                if (match.value.isNotEmpty()) {
                    // String indices and columns line up because a row stores one code point per
                    // cell; wide characters keep a placeholder cell, so widths still match.
                    found += Match(line, match.range.first, match.range.last)
                }
            }
        }

        matches = found
        currentIndex = if (found.isEmpty()) {
            -1
        } else {
            // Closest match at or above nearLine, else the last one.
            found.indexOfLast { it.line <= nearLine }.takeIf { it >= 0 } ?: found.lastIndex
        }
        return found.size
    }

    private fun buildRegex(query: String, options: Options): Regex? {
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val pattern = when {
            options.useRegex -> query
            options.wholeWord -> "\\b" + Regex.escape(query) + "\\b"
            else -> Regex.escape(query)
        }
        return try {
            Regex(pattern, flags)
        } catch (failure: Exception) {
            error = failure.message ?: "Invalid pattern"
            null
        }
    }

    fun current(): Match? = matches.getOrNull(currentIndex)

    /** Moves to the next match, wrapping around. */
    fun next(): Match? {
        if (matches.isEmpty()) return null
        currentIndex = (currentIndex + 1).let { if (it >= matches.size) 0 else it }
        return current()
    }

    /** Moves to the previous match, wrapping around. */
    fun previous(): Match? {
        if (matches.isEmpty()) return null
        currentIndex = (currentIndex - 1).let { if (it < 0) matches.lastIndex else it }
        return current()
    }

    /** Matches on one line, used by the renderer to highlight hits. */
    fun matchesOn(line: Int): List<Match> =
        if (matches.isEmpty()) emptyList() else matches.filter { it.line == line }

    fun isCurrent(match: Match): Boolean = current() == match

    fun clear() {
        query = ""
        matches = emptyList()
        currentIndex = -1
        error = null
    }
}
