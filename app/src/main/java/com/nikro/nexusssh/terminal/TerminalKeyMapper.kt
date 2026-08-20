package com.nikro.nexusssh.terminal

import android.view.KeyEvent
import com.nikro.nexusssh.domain.model.BackspaceMode

/**
 * Translates Android key events into the byte sequences a Unix shell expects.
 *
 * Covers the xterm key model: application vs normal cursor keys, application keypad, the
 * modifier-parameter form (`CSI 1;5C` for Ctrl+Right), function keys F1-F20, and the
 * Ctrl+letter control-code range.
 */
object TerminalKeyMapper {

    /** Modifier bitmask matching xterm's `1 + mods` encoding. */
    const val MOD_SHIFT = 1
    const val MOD_ALT = 2
    const val MOD_CTRL = 4

    fun modifierParameter(shift: Boolean, alt: Boolean, ctrl: Boolean): Int {
        var value = 1
        if (shift) value += MOD_SHIFT
        if (alt) value += MOD_ALT
        if (ctrl) value += MOD_CTRL
        return value
    }

    /**
     * Maps a key press. Returns null when the key produces no direct sequence and the caller
     * should fall back to the unicode character from the IME.
     */
    fun map(
        keyCode: Int,
        shift: Boolean,
        alt: Boolean,
        ctrl: Boolean,
        applicationCursorKeys: Boolean,
        applicationKeypad: Boolean,
        backspaceMode: BackspaceMode = BackspaceMode.DELETE,
    ): ByteArray? {
        val modifiers = modifierParameter(shift, alt, ctrl)
        val hasModifiers = modifiers != 1

        fun cursor(letter: Char): ByteArray = when {
            hasModifiers -> "\u001b[1;$modifiers$letter"
            applicationCursorKeys -> "\u001bO$letter"
            else -> "\u001b[$letter"
        }.toByteArray()

        fun tilde(number: Int): ByteArray =
            (if (hasModifiers) "\u001b[$number;$modifiers~" else "\u001b[$number~").toByteArray()

        fun ss3(letter: Char): ByteArray =
            (if (hasModifiers) "\u001b[1;$modifiers$letter" else "\u001bO$letter").toByteArray()

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> cursor('A')
            KeyEvent.KEYCODE_DPAD_DOWN -> cursor('B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursor('C')
            KeyEvent.KEYCODE_DPAD_LEFT -> cursor('D')
            KeyEvent.KEYCODE_MOVE_HOME -> if (hasModifiers) "\u001b[1;${modifiers}H".toByteArray() else cursor('H')
            KeyEvent.KEYCODE_MOVE_END -> if (hasModifiers) "\u001b[1;${modifiers}F".toByteArray() else cursor('F')
            KeyEvent.KEYCODE_INSERT -> tilde(2)
            KeyEvent.KEYCODE_FORWARD_DEL -> tilde(3)
            KeyEvent.KEYCODE_PAGE_UP -> tilde(5)
            KeyEvent.KEYCODE_PAGE_DOWN -> tilde(6)

            KeyEvent.KEYCODE_DEL -> when {
                alt -> byteArrayOf(0x1B, backspaceMode.code)
                ctrl -> byteArrayOf(0x08)
                else -> byteArrayOf(backspaceMode.code)
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (alt) byteArrayOf(0x1B, 0x0D) else byteArrayOf(0x0D)

            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)

            KeyEvent.KEYCODE_TAB -> when {
                shift -> "\u001b[Z".toByteArray()
                alt -> byteArrayOf(0x1B, 0x09)
                else -> byteArrayOf(0x09)
            }

            KeyEvent.KEYCODE_F1 -> ss3('P')
            KeyEvent.KEYCODE_F2 -> ss3('Q')
            KeyEvent.KEYCODE_F3 -> ss3('R')
            KeyEvent.KEYCODE_F4 -> ss3('S')
            KeyEvent.KEYCODE_F5 -> tilde(15)
            KeyEvent.KEYCODE_F6 -> tilde(17)
            KeyEvent.KEYCODE_F7 -> tilde(18)
            KeyEvent.KEYCODE_F8 -> tilde(19)
            KeyEvent.KEYCODE_F9 -> tilde(20)
            KeyEvent.KEYCODE_F10 -> tilde(21)
            KeyEvent.KEYCODE_F11 -> tilde(23)
            KeyEvent.KEYCODE_F12 -> tilde(24)

            KeyEvent.KEYCODE_NUMPAD_0 -> keypad('p', '0', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_1 -> keypad('q', '1', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_2 -> keypad('r', '2', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_3 -> keypad('s', '3', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_4 -> keypad('t', '4', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_5 -> keypad('u', '5', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_6 -> keypad('v', '6', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_7 -> keypad('w', '7', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_8 -> keypad('x', '8', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_9 -> keypad('y', '9', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_DOT -> keypad('n', '.', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_ADD -> keypad('k', '+', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> keypad('m', '-', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> keypad('j', '*', applicationKeypad)
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> keypad('o', '/', applicationKeypad)

            else -> null
        }
    }

    private fun keypad(applicationChar: Char, normalChar: Char, application: Boolean): ByteArray =
        if (application) "\u001bO$applicationChar".toByteArray() else byteArrayOf(normalChar.code.toByte())

    /**
     * Encodes a printable character with the Ctrl/Alt modifiers applied.
     * Ctrl+A..Ctrl+Z become 0x01..0x1A; Alt prefixes ESC (the "meta sends escape" convention).
     */
    fun encodeCharacter(codePoint: Int, ctrl: Boolean, alt: Boolean): ByteArray {
        var value = codePoint
        if (ctrl) {
            value = when (value) {
                in 'a'.code..'z'.code -> value - 'a'.code + 1
                in 'A'.code..'Z'.code -> value - 'A'.code + 1
                ' '.code, '@'.code -> 0
                '['.code -> 27
                '\\'.code -> 28
                ']'.code -> 29
                '^'.code -> 30
                '_'.code, '?'.code -> 31
                else -> value
            }
        }
        val text = String(Character.toChars(value))
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (alt) byteArrayOf(0x1B) + bytes else bytes
    }

    /** The keys shown on the terminal's extra-key row. */
    val extraKeys: List<ExtraKey> = listOf(
        ExtraKey("ESC", byteArrayOf(0x1B)),
        ExtraKey("TAB", byteArrayOf(0x09)),
        ExtraKey("CTRL", ByteArray(0), sticky = true),
        ExtraKey("ALT", ByteArray(0), sticky = true),
        ExtraKey("\u2190", "\u001b[D".toByteArray()),
        ExtraKey("\u2193", "\u001b[B".toByteArray()),
        ExtraKey("\u2191", "\u001b[A".toByteArray()),
        ExtraKey("\u2192", "\u001b[C".toByteArray()),
        ExtraKey("|", "|".toByteArray()),
        ExtraKey("/", "/".toByteArray()),
        ExtraKey("-", "-".toByteArray()),
        ExtraKey("~", "~".toByteArray()),
        ExtraKey("HOME", "\u001b[H".toByteArray()),
        ExtraKey("END", "\u001b[F".toByteArray()),
        ExtraKey("PGUP", "\u001b[5~".toByteArray()),
        ExtraKey("PGDN", "\u001b[6~".toByteArray()),
        ExtraKey("CTRL+C", byteArrayOf(0x03)),
        ExtraKey("CTRL+D", byteArrayOf(0x04)),
        ExtraKey("CTRL+Z", byteArrayOf(0x1A)),
        ExtraKey("CTRL+L", byteArrayOf(0x0C)),
        ExtraKey("CTRL+R", byteArrayOf(0x12)),
    )

    data class ExtraKey(val label: String, val bytes: ByteArray, val sticky: Boolean = false) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ExtraKey && label == other.label && sticky == other.sticky)

        override fun hashCode(): Int = 31 * label.hashCode() + sticky.hashCode()
    }
}
