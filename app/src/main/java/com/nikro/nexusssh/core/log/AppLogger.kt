package com.nikro.nexusssh.core.log

import android.util.Log
import com.nikro.nexusssh.BuildConfig
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Tiny logging facade with an in-memory ring buffer so the user can inspect (and share)
 * a diagnostic log from Settings without needing adb.
 *
 * Every message is scrubbed for credentials before it is stored.
 */
object AppLogger {

    enum class Level(val label: String, val priority: Int) {
        VERBOSE("V", Log.VERBOSE),
        DEBUG("D", Log.DEBUG),
        INFO("I", Log.INFO),
        WARN("W", Log.WARN),
        ERROR("E", Log.ERROR),
    }

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: String?,
    )

    private const val MAX_ENTRIES = 2_000
    private val lock = ReentrantReadWriteLock()
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)

    /** Patterns that must never end up in a shareable log. */
    private val redactionPatterns = listOf(
        Regex("(?i)(password\\s*[=:]\\s*)(\\S+)"),
        Regex("(?i)(passphrase\\s*[=:]\\s*)(\\S+)"),
        Regex("(?i)(token\\s*[=:]\\s*)(\\S+)"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
    )

    @Volatile
    var minimumLevel: Level = if (BuildConfig.DEBUG) Level.DEBUG else Level.INFO

    fun v(tag: String, message: String, t: Throwable? = null) = log(Level.VERBOSE, tag, message, t)
    fun d(tag: String, message: String, t: Throwable? = null) = log(Level.DEBUG, tag, message, t)
    fun i(tag: String, message: String, t: Throwable? = null) = log(Level.INFO, tag, message, t)
    fun w(tag: String, message: String, t: Throwable? = null) = log(Level.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(Level.ERROR, tag, message, t)

    fun log(level: Level, tag: String, message: String, t: Throwable? = null) {
        if (level.priority < minimumLevel.priority) return
        val safe = redact(message)
        Log.println(level.priority, tag, if (t == null) safe else "$safe\n${Log.getStackTraceString(t)}")
        lock.write {
            if (buffer.size >= MAX_ENTRIES) buffer.pollFirst()
            buffer.addLast(
                Entry(
                    timestamp = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = safe,
                    throwable = t?.let { Log.getStackTraceString(it) },
                ),
            )
        }
    }

    fun snapshot(): List<Entry> = lock.read { buffer.toList() }

    fun clear() = lock.write { buffer.clear() }

    fun dump(): String = buildString {
        snapshot().forEach { entry ->
            append(entry.timestamp)
            append(' ')
            append(entry.level.label)
            append('/')
            append(entry.tag)
            append(": ")
            append(entry.message)
            append('\n')
            entry.throwable?.let {
                append(it)
                append('\n')
            }
        }
    }

    private fun redact(message: String): String {
        var result = message
        redactionPatterns.forEachIndexed { index, regex ->
            result = if (index == redactionPatterns.lastIndex) {
                regex.replace(result, "<private key redacted>")
            } else {
                regex.replace(result) { match -> match.groupValues[1] + "\u2022\u2022\u2022\u2022\u2022\u2022" }
            }
        }
        return result
    }
}
