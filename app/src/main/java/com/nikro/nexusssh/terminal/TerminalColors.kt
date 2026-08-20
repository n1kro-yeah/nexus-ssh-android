package com.nikro.nexusssh.terminal

/**
 * Colour resolution for the terminal renderer.
 *
 * A cell's colour field is either a palette index (0..255, or the two "default" sentinels) or a
 * literal 24-bit value. [TerminalPalette.resolve] turns that into an ARGB int, taking the active
 * theme, any `OSC 4` overrides and the bold-brightens-colours preference into account.
 */
object TerminalPalette {

    fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or
            ((red.coerceIn(0, 255)) shl 16) or
            ((green.coerceIn(0, 255)) shl 8) or
            (blue.coerceIn(0, 255))

    /** The 240 non-ANSI entries of the xterm-256 palette (16..231 cube, 232..255 greys). */
    val xterm256: IntArray = IntArray(256) { index ->
        when {
            index < 16 -> 0 // filled from the theme at resolve time
            index < 232 -> {
                val value = index - 16
                val red = value / 36
                val green = (value % 36) / 6
                val blue = value % 6
                rgb(cubeLevel(red), cubeLevel(green), cubeLevel(blue))
            }

            else -> {
                val level = 8 + (index - 232) * 10
                rgb(level, level, level)
            }
        }
    }

    private fun cubeLevel(step: Int): Int = if (step == 0) 0 else 55 + step * 40

    /**
     * @param color the packed colour field from [CellStyle]
     * @param defaultColor value to use for the "default" sentinels
     * @param bright brighten indices 0..7 (used for bold text when the preference is on)
     */
    fun resolve(
        color: Long,
        theme: TerminalTheme,
        overrides: Map<Int, Int> = emptyMap(),
        defaultColor: Int,
        bright: Boolean = false,
    ): Int {
        if (CellStyle.isTrueColor(color)) {
            return (0xFF shl 24) or CellStyle.rgb(color)
        }
        return when (val index = CellStyle.paletteIndex(color)) {
            CellStyle.DEFAULT_FG_INDEX.toInt(), CellStyle.DEFAULT_BG_INDEX.toInt() -> defaultColor
            in 0..7 -> overrides[index] ?: theme.ansi[if (bright) index + 8 else index]
            in 8..15 -> overrides[index] ?: theme.ansi[index]
            in 16..255 -> overrides[index] ?: xterm256[index]
            else -> defaultColor
        }
    }

    /** Blends [overlay] over [base] with [alpha] (0..1); used for selection and search highlights. */
    fun blend(base: Int, overlay: Int, alpha: Float): Int {
        val inverse = 1f - alpha
        val red = ((base shr 16 and 0xFF) * inverse + (overlay shr 16 and 0xFF) * alpha).toInt()
        val green = ((base shr 8 and 0xFF) * inverse + (overlay shr 8 and 0xFF) * alpha).toInt()
        val blue = ((base and 0xFF) * inverse + (overlay and 0xFF) * alpha).toInt()
        return rgb(red, green, blue)
    }

    /** Perceived luminance, used to decide whether a theme is light or dark. */
    fun luminance(color: Int): Float {
        val red = (color shr 16 and 0xFF) / 255f
        val green = (color shr 8 and 0xFF) / 255f
        val blue = (color and 0xFF) / 255f
        return 0.2126f * red + 0.7152f * green + 0.0722f * blue
    }
}

/**
 * A terminal colour scheme: 16 ANSI colours plus the special-purpose ones.
 * [ansi] is ordered black, red, green, yellow, blue, magenta, cyan, white, then the bright eight.
 */
data class TerminalTheme(
    val name: String,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val cursorText: Int,
    val selection: Int,
    val ansi: IntArray,
) {
    val isDark: Boolean get() = TerminalPalette.luminance(background) < 0.5f

    override fun equals(other: Any?): Boolean =
        this === other || (other is TerminalTheme && name == other.name)

    override fun hashCode(): Int = name.hashCode()
}

/** The built-in schemes offered in Settings and per host. */
object TerminalThemes {

    private fun theme(
        name: String,
        foreground: Long,
        background: Long,
        cursor: Long,
        selection: Long,
        colors: LongArray,
    ): TerminalTheme {
        require(colors.size == 16) { "$name must define 16 ANSI colours" }
        return TerminalTheme(
            name = name,
            foreground = opaque(foreground),
            background = opaque(background),
            cursor = opaque(cursor),
            cursorText = opaque(background),
            selection = opaque(selection),
            ansi = IntArray(16) { opaque(colors[it]) },
        )
    }

    private fun opaque(value: Long): Int = ((0xFFL shl 24) or (value and 0xFFFFFF)).toInt()

    val nexusDark = theme(
        name = "Nexus Dark",
        foreground = 0xE6E9EF,
        background = 0x101319,
        cursor = 0x7AA2F7,
        selection = 0x2C3347,
        colors = longArrayOf(
            0x1B1E26, 0xE05561, 0x8CC265, 0xD5A44E, 0x4AA5F0, 0xC162DE, 0x42B3C2, 0xC7C9CE,
            0x4D5163, 0xFF6B7F, 0xA5E075, 0xF2C55C, 0x5CB6FF, 0xDA70FF, 0x56C7D6, 0xFFFFFF,
        ),
    )

    val dracula = theme(
        name = "Dracula",
        foreground = 0xF8F8F2,
        background = 0x282A36,
        cursor = 0xF8F8F2,
        selection = 0x44475A,
        colors = longArrayOf(
            0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
            0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF,
        ),
    )

    val solarizedDark = theme(
        name = "Solarized Dark",
        foreground = 0x839496,
        background = 0x002B36,
        cursor = 0x93A1A1,
        selection = 0x073642,
        colors = longArrayOf(
            0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
            0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
        ),
    )

    val solarizedLight = theme(
        name = "Solarized Light",
        foreground = 0x657B83,
        background = 0xFDF6E3,
        cursor = 0x586E75,
        selection = 0xEEE8D5,
        colors = longArrayOf(
            0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
            0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
        ),
    )

    val nord = theme(
        name = "Nord",
        foreground = 0xD8DEE9,
        background = 0x2E3440,
        cursor = 0xD8DEE9,
        selection = 0x434C5E,
        colors = longArrayOf(
            0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
            0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
        ),
    )

    val gruvboxDark = theme(
        name = "Gruvbox Dark",
        foreground = 0xEBDBB2,
        background = 0x282828,
        cursor = 0xEBDBB2,
        selection = 0x504945,
        colors = longArrayOf(
            0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
            0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
        ),
    )

    val oneDark = theme(
        name = "One Dark",
        foreground = 0xABB2BF,
        background = 0x282C34,
        cursor = 0x528BFF,
        selection = 0x3E4451,
        colors = longArrayOf(
            0x282C34, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF,
            0x5C6370, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xFFFFFF,
        ),
    )

    val tomorrowNight = theme(
        name = "Tomorrow Night",
        foreground = 0xC5C8C6,
        background = 0x1D1F21,
        cursor = 0xAEAFAD,
        selection = 0x373B41,
        colors = longArrayOf(
            0x1D1F21, 0xCC6666, 0xB5BD68, 0xF0C674, 0x81A2BE, 0xB294BB, 0x8ABEB7, 0xC5C8C6,
            0x969896, 0xCC6666, 0xB5BD68, 0xF0C674, 0x81A2BE, 0xB294BB, 0x8ABEB7, 0xFFFFFF,
        ),
    )

    val monokai = theme(
        name = "Monokai",
        foreground = 0xF8F8F2,
        background = 0x272822,
        cursor = 0xF8F8F0,
        selection = 0x49483E,
        colors = longArrayOf(
            0x272822, 0xF92672, 0xA6E22E, 0xE6DB74, 0x66D9EF, 0xAE81FF, 0xA1EFE4, 0xF8F8F2,
            0x75715E, 0xFD5FF0, 0xCFCFC2, 0xFD971F, 0x66D9EF, 0xAE81FF, 0xA1EFE4, 0xF9F8F5,
        ),
    )

    val catppuccinMocha = theme(
        name = "Catppuccin Mocha",
        foreground = 0xCDD6F4,
        background = 0x1E1E2E,
        cursor = 0xF5E0DC,
        selection = 0x45475A,
        colors = longArrayOf(
            0x45475A, 0xF38BA8, 0xA6E3A1, 0xF9E2AF, 0x89B4FA, 0xF5C2E7, 0x94E2D5, 0xBAC2DE,
            0x585B70, 0xF38BA8, 0xA6E3A1, 0xF9E2AF, 0x89B4FA, 0xF5C2E7, 0x94E2D5, 0xA6ADC8,
        ),
    )

    val github = theme(
        name = "GitHub Light",
        foreground = 0x24292F,
        background = 0xFFFFFF,
        cursor = 0x044289,
        selection = 0xC8E1FF,
        colors = longArrayOf(
            0x24292E, 0xD73A49, 0x22863A, 0xB08800, 0x0366D6, 0x5A32A3, 0x1B7C83, 0x6A737D,
            0x959DA5, 0xCB2431, 0x28A745, 0xDBAB09, 0x2188FF, 0x8A63D2, 0x3192AA, 0xD1D5DA,
        ),
    )

    val all: List<TerminalTheme> = listOf(
        nexusDark,
        dracula,
        oneDark,
        catppuccinMocha,
        nord,
        gruvboxDark,
        tomorrowNight,
        monokai,
        solarizedDark,
        solarizedLight,
        github,
    )

    val default: TerminalTheme = nexusDark

    fun byName(name: String?): TerminalTheme =
        all.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default
}
