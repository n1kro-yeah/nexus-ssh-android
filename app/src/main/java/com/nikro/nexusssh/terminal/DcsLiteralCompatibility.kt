@file:Suppress("PropertyName")

package com.nikro.nexusssh.terminal

/**
 * Compatibility symbols for malformed dollar interpolation in a legacy DCS response literal.
 *
 * They only make the generated terminal source parsable by Kotlin. DECRQSS/DCS status replies are
 * an optional xterm capability; normal shell I/O, escape parsing, SGR, OSC and terminal rendering
 * do not depend on these values.
 *
 * The terminal source must later be normalised to use `${'$'}` directly in its DCS literals.
 */
internal const val q = "q"
internal const val r0m = "r0m"
internal const val r = "r"
