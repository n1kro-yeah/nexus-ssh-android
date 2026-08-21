package com.nikro.nexusssh.ssh

import com.nikro.nexusssh.domain.model.BackspaceMode
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.domain.model.Protocol

/** Fully resolved connection parameters, independent of database access. */
data class ConnectionConfig(
    val hostId: Long,
    val label: String,
    val hostname: String,
    val port: Int,
    val protocol: Protocol = Protocol.SSH,
    val username: String,
    /** Plaintext password, already unsealed. Null when only key auth is available. */
    val password: String? = null,
    val keys: List<PrivateKeyMaterial> = emptyList(),
    val jumpChain: List<ConnectionConfig> = emptyList(),
    val agentForwarding: Boolean = false,
    val x11Forwarding: Boolean = false,
    val compression: Boolean = false,
    val keepAliveSeconds: Int = 30,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 0,
    val charset: String = "UTF-8",
    val terminalType: String = "xterm-256color",
    val environment: Map<String, String> = emptyMap(),
    val startupCommand: String? = null,
    val strictHostKeyChecking: Boolean = true,
    val backspaceMode: BackspaceMode = BackspaceMode.DELETE,
    val initialColumns: Int = 80,
    val initialRows: Int = 24,
    val allowPasswordAuth: Boolean = true,
    val allowKeyboardInteractive: Boolean = true,
    val allowAgentAuth: Boolean = true,
) {
    val address: String get() = "$username@$hostname:$port"
    val hasSecrets: Boolean get() = password != null || keys.isNotEmpty()

    companion object {
        fun from(
            host: Host,
            username: String,
            password: String?,
            keys: List<PrivateKeyMaterial>,
            jumpChain: List<ConnectionConfig> = emptyList(),
            columns: Int = 80,
            rows: Int = 24,
        ): ConnectionConfig = ConnectionConfig(
            hostId = host.id,
            label = host.label,
            hostname = host.hostname,
            port = host.port,
            protocol = host.protocol,
            username = username,
            password = password,
            keys = keys,
            jumpChain = jumpChain,
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
}

/** An unsealed private key ready to hand to SSHJ. */
data class PrivateKeyMaterial(
    val keyId: Long,
    val label: String,
    val pem: String,
    val passphrase: CharArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrivateKeyMaterial) return false
        return keyId == other.keyId &&
            label == other.label &&
            pem == other.pem &&
            passphrase.contentEqualsNullable(other.passphrase)
    }

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + pem.hashCode()
        result = 31 * result + (passphrase?.contentHashCode() ?: 0)
        return result
    }

    fun wipe() {
        passphrase?.fill(Char.MIN_VALUE)
    }

    private fun CharArray?.contentEqualsNullable(other: CharArray?): Boolean = when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }
}

/** Events emitted by a live connection so the UI can narrate what is happening. */
sealed interface SshEvent {
    data class Status(val message: String) : SshEvent
    data class Output(val bytes: ByteArray, val length: Int) : SshEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Output && length == other.length && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + length
    }

    data class Error(val message: String, val cause: Throwable? = null) : SshEvent
    data class Banner(val text: String) : SshEvent
    data object Connected : SshEvent
    data class Closed(val reason: String?) : SshEvent
}

/** Prompts the SSH layer needs to bounce back to the UI mid-handshake. */
sealed interface SshPrompt {
    data class Password(val username: String, val attempt: Int) : SshPrompt
    data class Passphrase(val keyLabel: String, val attempt: Int) : SshPrompt
    data class KeyboardInteractive(
        val name: String,
        val instruction: String,
        val prompt: String,
        val echo: Boolean,
    ) : SshPrompt

    data class UnknownHostKey(
        val hostname: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
        val randomArt: String,
    ) : SshPrompt

    data class ChangedHostKey(
        val hostname: String,
        val port: Int,
        val keyType: String,
        val storedFingerprint: String,
        val presentedFingerprint: String,
        val randomArt: String,
    ) : SshPrompt

    data class AgentUse(val hostname: String, val keyFingerprint: String) : SshPrompt
}
