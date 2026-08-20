package com.nikro.nexusssh.core.util

/**
 * Parses the connection strings accepted by the quick-connect bar and by `ssh://` deep links.
 *
 * Supported shapes:
 * ```
 *   host
 *   host:2222
 *   user@host
 *   user@host:2222
 *   ssh://user@host:2222
 *   sftp://user@host/remote/path
 *   telnet://host:23
 *   ssh://user@host:22/#jump=bastion
 * ```
 */
object ConnectionString {

    enum class Scheme(val defaultPort: Int) {
        SSH(22),
        SFTP(22),
        TELNET(23),
    }

    data class Parsed(
        val scheme: Scheme,
        val username: String?,
        val host: String,
        val port: Int,
        val path: String? = null,
        val jump: String? = null,
    ) {
        fun display(): String = buildString {
            if (username != null) {
                append(username)
                append('@')
            }
            append(host)
            if (port != scheme.defaultPort) {
                append(':')
                append(port)
            }
        }
    }

    fun parse(raw: String): Parsed? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        var rest = trimmed
        var scheme = Scheme.SSH
        val schemeSeparator = rest.indexOf("://")
        if (schemeSeparator > 0) {
            scheme = when (rest.take(schemeSeparator).lowercase()) {
                "ssh" -> Scheme.SSH
                "sftp" -> Scheme.SFTP
                "telnet" -> Scheme.TELNET
                else -> return null
            }
            rest = rest.substring(schemeSeparator + 3)
        }

        var jump: String? = null
        val fragmentIndex = rest.indexOf('#')
        if (fragmentIndex >= 0) {
            val fragment = rest.substring(fragmentIndex + 1)
            rest = rest.substring(0, fragmentIndex)
            jump = fragment.split('&')
                .mapNotNull { part ->
                    val kv = part.split('=', limit = 2)
                    if (kv.size == 2 && kv[0].equals("jump", ignoreCase = true)) kv[1] else null
                }
                .firstOrNull()
        }

        var path: String? = null
        val slashIndex = rest.indexOf('/')
        if (slashIndex >= 0) {
            path = rest.substring(slashIndex)
            rest = rest.substring(0, slashIndex)
        }

        var username: String? = null
        val atIndex = rest.lastIndexOf('@')
        if (atIndex >= 0) {
            username = rest.substring(0, atIndex).takeIf { it.isNotBlank() }
            rest = rest.substring(atIndex + 1)
        }

        // IPv6 literal: [::1]:22
        var host: String
        var port = scheme.defaultPort
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) return null
            host = rest.substring(1, close)
            val remainder = rest.substring(close + 1)
            if (remainder.startsWith(":")) {
                port = remainder.drop(1).toIntOrNull() ?: return null
            }
        } else {
            val colon = rest.lastIndexOf(':')
            if (colon > 0) {
                val maybePort = rest.substring(colon + 1).toIntOrNull()
                if (maybePort != null) {
                    host = rest.substring(0, colon)
                    port = maybePort
                } else {
                    host = rest
                }
            } else {
                host = rest
            }
        }

        if (host.isBlank()) return null
        if (port !in 1..65535) return null

        return Parsed(scheme, username, host, port, path?.takeIf { it != "/" }, jump)
    }

    /** Validates a hostname / IPv4 / IPv6 literal without doing DNS resolution. */
    fun isValidHost(value: String): Boolean {
        if (value.isBlank() || value.length > 253) return false
        if (value.contains(':')) {
            // Rough IPv6 sanity check.
            return value.count { it == ':' } in 2..8 && value.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        }
        return value.split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first() != '-' &&
                label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }
}
