package com.nikro.nexusssh.ssh

import com.nikro.nexusssh.core.crypto.CryptoVault
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.data.repository.KeychainRepository
import com.nikro.nexusssh.domain.model.Host
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a stored [Host] into the flat [ConnectionConfig] the SSH engine consumes.
 *
 * This is where all the inheritance lives: group defaults, the identity's username and password,
 * the key attached to the host or to its identity, and the ProxyJump chain - each hop resolved
 * into its own config with its own credentials.
 */
@Singleton
class ConnectionConfigFactory @Inject constructor(
    private val hosts: HostRepository,
    private val keychain: KeychainRepository,
    private val vault: CryptoVault,
) {

    /**
     * @param columns initial terminal width, sent in the PTY request
     * @param overrideUsername used by "connect as..." and by quick connect
     * @param overridePassword a password typed for this connection only, never stored
     */
    suspend fun build(
        host: Host,
        columns: Int = 80,
        rows: Int = 24,
        overrideUsername: String? = null,
        overridePassword: String? = null,
        tryAllKeys: Boolean = true,
    ): ConnectionConfig {
        val resolved = hosts.applyGroupDefaults(host)
        val chain = hosts.resolveJumpChain(resolved).map { hop ->
            // Hops never carry their own chain: the list is already flattened in order.
            single(hosts.applyGroupDefaults(hop), columns, rows, tryAllKeys = false)
        }
        return single(
            host = resolved,
            columns = columns,
            rows = rows,
            overrideUsername = overrideUsername,
            overridePassword = overridePassword,
            tryAllKeys = tryAllKeys,
        ).copy(jumpChain = chain)
    }

    /** Config for a single host, with no jump chain attached. */
    private suspend fun single(
        host: Host,
        columns: Int,
        rows: Int,
        overrideUsername: String? = null,
        overridePassword: String? = null,
        tryAllKeys: Boolean = false,
    ): ConnectionConfig {
        val identity = host.identityId?.let { hosts.identity(it) }

        val username = overrideUsername?.takeIf { it.isNotBlank() }
            ?: host.username?.takeIf { it.isNotBlank() }
            ?: identity?.username?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No username set for ${host.label}")

        // A password typed for this connection wins; then the host's, then the identity's.
        val password = overridePassword
            ?: host.sealedPassword?.let { vault.openToString(it) }
            ?: identity?.sealedPassword?.let { vault.openToString(it) }

        val keyIds = buildList {
            host.keyId?.let(::add)
            identity?.keyId?.let { if (it !in this) add(it) }
        }
        val keys = when {
            keyIds.isNotEmpty() -> keychain.materialFor(keyIds)
            // With no key selected, offer the keychain the way ssh-agent would - bounded, so a
            // large keychain cannot trip the server's authentication attempt limit.
            tryAllKeys -> keychain.allKeys().take(MAX_OFFERED_KEYS).mapNotNull {
                keychain.materialFor(it.id)
            }

            else -> emptyList()
        }

        val askEveryTime = identity?.askPasswordEveryTime == true

        return ConnectionConfig(
            hostId = host.id,
            label = host.label,
            hostname = host.hostname,
            port = if (host.port > 0) host.port else host.protocol.defaultPort,
            protocol = host.protocol,
            username = username,
            password = password?.takeUnless { askEveryTime },
            keys = keys,
            agentForwarding = host.agentForwarding,
            x11Forwarding = host.x11Forwarding,
            compression = host.compression,
            keepAliveSeconds = host.keepAliveSeconds,
            connectTimeoutMs = host.connectTimeoutMs,
            charset = host.charset,
            terminalType = host.terminalType,
            environment = host.environment,
            strictHostKeyChecking = host.strictHostKeyChecking,
            backspaceMode = host.backspaceMode,
            initialColumns = columns,
            initialRows = rows,
        )
    }

    /** Quick connect: a host that exists only for this session. */
    suspend fun buildAdHoc(
        hostname: String,
        port: Int,
        username: String,
        password: String? = null,
        keyId: Long? = null,
        columns: Int = 80,
        rows: Int = 24,
    ): ConnectionConfig = ConnectionConfig(
        hostId = 0L,
        label = "$username@$hostname",
        hostname = hostname,
        port = port,
        username = username,
        password = password,
        keys = keyId?.let { keychain.materialFor(it) }?.let(::listOf) ?: emptyList(),
        initialColumns = columns,
        initialRows = rows,
    )

    private companion object {
        const val MAX_OFFERED_KEYS = 5
    }
}
