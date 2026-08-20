package com.nikro.nexusssh.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Human readable byte counts: 1 536 -> "1.5 KiB". */
fun Long.formatBytes(): String {
    if (this < 1024) return "$this B"
    val exp = (ln(toDouble()) / ln(1024.0)).toInt().coerceAtMost(6)
    val unit = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %siB", this / 1024.0.pow(exp.toDouble()), unit)
}

/** Transfer speed formatting used by the SFTP queue. */
fun Long.formatSpeed(): String = "${formatBytes()}/s"

/** "3m 12s" style duration used in the session list and history. */
fun Long.formatDuration(): String {
    if (this <= 0) return "0s"
    val hours = TimeUnit.MILLISECONDS.toHours(this)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this) % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        if (hours > 0 || minutes > 0) append("${minutes}m ")
        append("${seconds}s")
    }.trim()
}

private val absoluteFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
private val timeOnlyFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

fun Long.formatAbsolute(): String = absoluteFormat.format(Date(this))

fun Long.formatTimeOnly(): String = timeOnlyFormat.format(Date(this))

/** Relative timestamps for "last connected" labels. */
fun Long.formatRelative(now: Long = System.currentTimeMillis()): String {
    val delta = now - this
    if (abs(delta) < TimeUnit.MINUTES.toMillis(1)) return "just now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    if (minutes < 60) return "${minutes}m ago"
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    if (hours < 24) return "${hours}h ago"
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    if (days < 7) return "${days}d ago"
    if (days < 30) return "${days / 7}w ago"
    return formatAbsolute()
}

/** Unix permission bits -> `drwxr-xr-x`. */
fun Int.formatPosixPermissions(isDirectory: Boolean, isSymlink: Boolean = false): String {
    val type = when {
        isSymlink -> 'l'
        isDirectory -> 'd'
        else -> '-'
    }
    val sb = StringBuilder(10)
    sb.append(type)
    val bits = intArrayOf(0b100_000_000, 0b010_000_000, 0b001_000_000)
    val letters = charArrayOf('r', 'w', 'x')
    for (group in 0..2) {
        for (i in 0..2) {
            val mask = bits[i] ushr (group * 3)
            sb.append(if (this and mask != 0) letters[i] else '-')
        }
    }
    return sb.toString()
}

/** `0755` style representation used in the chmod dialog. */
fun Int.formatOctal(): String = String.format(Locale.US, "%04o", this and 0xFFF)

fun String.parseOctalOrNull(): Int? = runCatching { toInt(8) }.getOrNull()

/** Truncates long paths in the middle so both ends stay visible. */
fun String.ellipsizeMiddle(max: Int): String {
    if (length <= max) return this
    val keep = (max - 1) / 2
    return take(keep) + "\u2026" + takeLast(max - keep - 1)
}

fun ByteArray.toHex(separator: Char? = null): String = buildString(size * 3) {
    this@toHex.forEachIndexed { index, byte ->
        if (separator != null && index > 0) append(separator)
        append(HEX[(byte.toInt() shr 4) and 0xF])
        append(HEX[byte.toInt() and 0xF])
    }
}

private const val HEX = "0123456789abcdef"
