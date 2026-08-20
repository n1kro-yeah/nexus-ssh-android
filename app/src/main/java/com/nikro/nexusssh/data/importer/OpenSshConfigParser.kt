package com.nikro.nexusssh.data.importer

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.domain.model.ForwardType
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.domain.model.PortForwardRule
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses an OpenSSH `~/.ssh/config` file into importable hosts.
 *
 * Supported keywords: Host, HostName, Port, User, IdentityFile, IdentitiesOnly, ProxyJump,
 * ProxyCommand (recognised, reported as unsupported), ForwardAgent, Compression,
 * ServerAliveInterval, ConnectTimeout, StrictHostKeyChecking, LocalForward, RemoteForward,
 * DynamicForward, SetEnv/SendEnv, RequestTTY and Include (reported, not followed).
 *
 * Matching semantics mirror OpenSSH: `Host *` blocks act as defaults for every entry that
 * follows, and the first value wins for keywords that appear more than once.
 */
@Singleton
class OpenSshConfigParser @Inject constructor() {

    data class ParsedHost(
        val host: Host,
        val identityFiles: List<String> = emptyList(),
        val proxyJump: String? = null,
        val forwards: List<PortForwardRule> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    data class ParseResult(
        val hosts: List<ParsedHost>,
        val warnings: List<String>,
        val includes: List<String>,
    )

    private data class Block(
        val patterns: List<String>,
        val values: MutableMap<String, MutableList<String>> = linkedMapOf(),
    ) {
        fun first(keyword: String): String? = values[keyword.lowercase()]?.firstOrNull()
        fun all(keyword: String): List<String> = values[keyword.lowercase()].orEmpty()
        fun put(keyword: String, value: String) {
            values.getOrPut(keyword.lowercase()) { mutableListOf() }.add(value)
        }
    }

    fun parse(text: String): ParseResult {
        val blocks = mutableListOf<Block>()
        val includes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var current: Block? = null

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            // Keyword and argument may be separated by whitespace or '='.
            val separator = line.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
            if (separator <= 0) {
                warnings += "Line ${index + 1}: cannot parse \"$line\""
                return@forEachIndexed
            }
            val keyword = line.substring(0, separator).trim()
            val argument = line.substring(separator + 1).trim().trim('=').trim()

            when (keyword.lowercase()) {
                "host" -> {
                    current = Block(splitArguments(argument)).also { blocks += it }
                }

                "match" -> {
                    warnings += "Line ${index + 1}: Match blocks are not imported"
                    current = null
                }

                "include" -> includes += argument

                else -> {
                    val block = current
                    if (block == null) {
                        warnings += "Line ${index + 1}: \"$keyword\" appears before any Host block"
                    } else {
                        block.put(keyword, unquote(argument))
                    }
                }
            }
        }

        val defaults = blocks.filter { it.patterns.any { pattern -> pattern == "*" } }
        val parsed = blocks
            .filterNot { block -> block.patterns.all { it == "*" } }
            .flatMap { block -> block.patterns.map { pattern -> pattern to block } }
            .filterNot { (pattern, _) -> pattern.startsWith("!") || pattern.contains('*') || pattern.contains('?') }
            .map { (pattern, block) -> toHost(pattern, block, defaults) }

        AppLogger.i(TAG, "Parsed ${parsed.size} hosts from ssh_config (${warnings.size} warnings)")
        return ParseResult(parsed, warnings, includes)
    }

    private fun toHost(pattern: String, block: Block, defaults: List<Block>): ParsedHost {
        fun value(keyword: String): String? =
            block.first(keyword) ?: defaults.firstNotNullOfOrNull { it.first(keyword) }

        fun values(keyword: String): List<String> =
            block.all(keyword).ifEmpty { defaults.flatMap { it.all(keyword) } }

        val warnings = mutableListOf<String>()
        val hostname = value("hostname") ?: pattern
        val port = value("port")?.toIntOrNull() ?: 22
        val environment = values("setenv").mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()

        value("proxycommand")?.let {
            warnings += "ProxyCommand is not supported; use ProxyJump instead ($it)"
        }
        value("controlpath")?.let { warnings += "Connection multiplexing (ControlMaster) is not supported" }

        val host = Host(
            label = pattern,
            hostname = hostname,
            port = port,
            username = value("user"),
            agentForwarding = value("forwardagent").isYes(),
            x11Forwarding = value("forwardx11").isYes(),
            compression = value("compression").isYes(),
            keepAliveSeconds = value("serveraliveinterval")?.toIntOrNull() ?: 30,
            connectTimeoutMs = (value("connecttimeout")?.toIntOrNull() ?: 15) * 1000,
            strictHostKeyChecking = value("stricthostkeychecking")?.lowercase() != "no",
            environment = environment,
            tags = listOf("imported"),
            notes = buildString {
                value("identityfile")?.let { append("IdentityFile: ").append(it).append('\n') }
                value("proxyjump")?.let { append("ProxyJump: ").append(it).append('\n') }
            }.trim(),
        )

        val forwards = buildList {
            values("localforward").forEach { spec ->
                parseForward(spec, ForwardType.LOCAL, pattern)?.let { add(it) }
                    ?: run { warnings += "Cannot parse LocalForward \"$spec\"" }
            }
            values("remoteforward").forEach { spec ->
                parseForward(spec, ForwardType.REMOTE, pattern)?.let { add(it) }
                    ?: run { warnings += "Cannot parse RemoteForward \"$spec\"" }
            }
            values("dynamicforward").forEach { spec ->
                parseForward(spec, ForwardType.DYNAMIC, pattern)?.let { add(it) }
                    ?: run { warnings += "Cannot parse DynamicForward \"$spec\"" }
            }
        }

        return ParsedHost(
            host = host,
            identityFiles = values("identityfile"),
            proxyJump = value("proxyjump"),
            forwards = forwards,
            warnings = warnings,
        )
    }

    /**
     * `LocalForward [bind:]port host:hostport`, `RemoteForward [bind:]port host:hostport`,
     * `DynamicForward [bind:]port`.
     */
    private fun parseForward(spec: String, type: ForwardType, label: String): PortForwardRule? {
        val parts = spec.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when (type) {
            ForwardType.DYNAMIC -> {
                val (bind, port) = splitBindPort(parts.getOrNull(0) ?: return null) ?: return null
                PortForwardRule(
                    label = "$label SOCKS",
                    type = type,
                    hostId = 0,
                    bindAddress = bind,
                    localPort = port,
                )
            }

            else -> {
                if (parts.size < 2) return null
                val (bind, listenPort) = splitBindPort(parts[0]) ?: return null
                val targetParts = parts[1].split(':')
                if (targetParts.size < 2) return null
                val targetPort = targetParts.last().toIntOrNull() ?: return null
                val targetHost = targetParts.dropLast(1).joinToString(":").trim('[', ']')
                PortForwardRule(
                    label = "$label ${type.displayName}",
                    type = type,
                    hostId = 0,
                    bindAddress = bind,
                    localPort = listenPort,
                    remoteHost = targetHost,
                    remotePort = targetPort,
                )
            }
        }
    }

    private fun splitBindPort(token: String): Pair<String, Int>? {
        val parts = token.split(':')
        return when (parts.size) {
            1 -> "127.0.0.1" to (parts[0].toIntOrNull() ?: return null)
            2 -> parts[0].ifBlank { "127.0.0.1" } to (parts[1].toIntOrNull() ?: return null)
            else -> {
                val port = parts.last().toIntOrNull() ?: return null
                parts.dropLast(1).joinToString(":").trim('[', ']') to port
            }
        }
    }

    private fun splitArguments(argument: String): List<String> =
        argument.split(Regex("\\s+")).map { unquote(it) }.filter { it.isNotBlank() }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') value.substring(1, value.length - 1) else value

    private fun String?.isYes(): Boolean = this?.lowercase() == "yes"

    private companion object {
        const val TAG = "SshConfigParser"
    }
}
