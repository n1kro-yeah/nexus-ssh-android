package com.nikro.nexusssh.terminal

/**
 * Per-cell rendering attributes packed into a single [Long] so a 10 000-line scrollback costs
 * 8 bytes per cell instead of an object header plus fields.
 *
 * Layout (bit 0 = LSB):
 * ```
 *  0..24  foreground colour  (25 bits: 1 flag bit + 24-bit RGB, or palette index)
 * 25..49  background colour  (25 bits, same encoding)
 * 50..63  attribute flags
 * ```
 * A colour is either a palette index (0..255) or a true colour value with [TRUE_COLOR_FLAG] set.
 */
object CellStyle {

    const val FG_SHIFT = 0
    const val BG_SHIFT = 25
    const val COLOR_MASK = 0x1FFFFFFL // 25 bits

    /** Set on a colour field when the remaining 24 bits are literal RGB. */
    const val TRUE_COLOR_FLAG = 1L shl 24

    /** Palette index meaning "whatever the theme says the default is". */
    const val DEFAULT_FG_INDEX = 256L
    const val DEFAULT_BG_INDEX = 257L

    private const val ATTR_SHIFT = 50

    const val BOLD = 1L shl 50
    const val DIM = 1L shl 51
    const val ITALIC = 1L shl 52
    const val UNDERLINE = 1L shl 53
    const val BLINK = 1L shl 54
    const val INVERSE = 1L shl 55
    const val INVISIBLE = 1L shl 56
    const val STRIKETHROUGH = 1L shl 57
    const val DOUBLE_UNDERLINE = 1L shl 58
    const val CURLY_UNDERLINE = 1L shl 59
    const val OVERLINE = 1L shl 60
    /** Marks a cell that continues a wide (CJK/emoji) glyph started in the previous column. */
    const val WIDE_CONTINUATION = 1L shl 61
    /** The cell participates in a detected hyperlink (OSC 8 or heuristic URL matching). */
    const val HYPERLINK = 1L shl 62

    val DEFAULT: Long = pack(DEFAULT_FG_INDEX, DEFAULT_BG_INDEX, 0L)

    fun pack(foreground: Long, background: Long, attributes: Long): Long =
        ((foreground and COLOR_MASK) shl FG_SHIFT) or
            ((background and COLOR_MASK) shl BG_SHIFT) or
            (attributes and ATTRIBUTE_MASK)

    fun foreground(style: Long): Long = (style ushr FG_SHIFT) and COLOR_MASK

    fun background(style: Long): Long = (style ushr BG_SHIFT) and COLOR_MASK

    fun attributes(style: Long): Long = style and ATTRIBUTE_MASK

    fun withForeground(style: Long, color: Long): Long =
        (style and (COLOR_MASK shl FG_SHIFT).inv()) or ((color and COLOR_MASK) shl FG_SHIFT)

    fun withBackground(style: Long, color: Long): Long =
        (style and (COLOR_MASK shl BG_SHIFT).inv()) or ((color and COLOR_MASK) shl BG_SHIFT)

    fun withAttributes(style: Long, attributes: Long): Long =
        (style and ATTRIBUTE_MASK.inv()) or (attributes and ATTRIBUTE_MASK)

    fun addAttribute(style: Long, flag: Long): Long = style or flag

    fun removeAttribute(style: Long, flag: Long): Long = style and flag.inv()

    fun has(style: Long, flag: Long): Boolean = (style and flag) != 0L

    fun trueColor(red: Int, green: Int, blue: Int): Long =
        TRUE_COLOR_FLAG or
            ((red.coerceIn(0, 255).toLong() and 0xFF) shl 16) or
            ((green.coerceIn(0, 255).toLong() and 0xFF) shl 8) or
            (blue.coerceIn(0, 255).toLong() and 0xFF)

    fun isTrueColor(color: Long): Boolean = (color and TRUE_COLOR_FLAG) != 0L

    fun paletteIndex(color: Long): Int = (color and 0xFFFFFF).toInt()

    fun rgb(color: Long): Int = (color and 0xFFFFFF).toInt()

    /** Swaps foreground and background, used to render the INVERSE attribute and selections. */
    fun inverted(style: Long): Long =
        pack(background(style), foreground(style), attributes(style) and INVERSE.inv())

    private const val ATTRIBUTE_MASK: Long = -1L shl ATTR_SHIFT
}
