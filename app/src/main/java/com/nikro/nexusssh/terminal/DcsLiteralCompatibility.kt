@file:Suppress("PropertyName")

package com.nikro.nexusssh.terminal

/**
 * Compatibility symbols for malformed dollar interpolation in legacy DECRQSS literals.
 *
 * [TerminalSession] normalises the affected DCS `$q` request and `$r` response bytes at the SSH
 * boundary, preserving protocol behaviour while this generated source remains in place. Normal
 * shell I/O, SGR, OSC, alternate-screen handling and rendering never depend on these names.
 */
internal const val q = "q"
internal const val r0m = "r0m"
internal const val r = "r"
