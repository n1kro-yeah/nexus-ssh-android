package com.nikro.nexusssh.domain.model

import kotlinx.serialization.Serializable

/** Wire protocol used for a host entry. */
enum class Protocol(val displayName: String, val defaultPort: Int) {
    SSH("SSH", 22),
    TELNET("Telnet", 23),
    LOCAL("Local shell", 0),
}

enum class SshKeyType(val displayName: String, val opensshName: String) {
    ED25519("Ed25519", "ssh-ed25519"),
    ECDSA_P256("ECDSA nistp256", "ecdsa-sha2-nistp256"),
    ECDSA_P384("ECDSA nistp384", "ecdsa-sha2-nistp384"),
    ECDSA_P521("ECDSA nistp521", "ecdsa-sha2-nistp521"),
    RSA_2048("RSA 2048", "ssh-rsa"),
    RSA_3072("RSA 3072", "ssh-rsa"),
    RSA_4096("RSA 4096", "ssh-rsa"),
    UNKNOWN("Unknown", ""),
    ;

    val isRsa: Boolean get() = this == RSA_2048 || this == RSA_3072 || this == RSA_4096

    val rsaBits: Int
        get() = when (this) {
            RSA_2048 -> 2048
            RSA_3072 -> 3072
            RSA_4096 -> 4096
            else -> 0
        }

    val ecCurve: String?
        get() = when (this) {
            ECDSA_P256 -> "secp256r1"
            ECDSA_P384 -> "secp384r1"
            ECDSA_P521 -> "secp521r1"
            else -> null
        }

    companion object {
        fun fromOpenSshName(name: String): SshKeyType = when (name) {
            "ssh-ed25519" -> ED25519
            "ecdsa-sha2-nistp256" -> ECDSA_P256
            "ecdsa-sha2-nistp384" -> ECDSA_P384
            "ecdsa-sha2-nistp521" -> ECDSA_P521
            "ssh-rsa", "rsa-sha2-256", "rsa-sha2-512" -> RSA_4096
            else -> UNKNOWN
        }
    }
}

enum class ForwardType(val displayName: String, val sshFlag: String) {
    LOCAL("Local", "-L"),
    REMOTE("Remote", "-R"),
    DYNAMIC("Dynamic (SOCKS5)", "-D"),
}

enum class BackspaceMode(val displayName: String, val code: Byte) {
    DELETE("Delete (0x7F)", 0x7F),
    BACKSPACE("Backspace (0x08)", 0x08),
}

enum class CursorStyle(val displayName: String) {
    BLOCK("Block"),
    UNDERLINE("Underline"),
    BAR("Bar"),
}

enum class ConnectionStatus {
    IDLE,
    RESOLVING,
    CONNECTING,
    VERIFYING_HOST_KEY,
    AUTHENTICATING,
    OPENING_CHANNEL,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    FAILED,
}

@Serializable
data class HostGroup(
    val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val color: Int? = null,
    val defaultIdentityId: Long? = null,
    val defaultPort: Int? = null,
    val defaultJumpHostId: Long? = null,
    val agentForwarding: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
data class Identity(
    val id: Long = 0,
    val label: String,
    val username: String,
    /** Sealed by [com.nikro.nexusssh.core.crypto.CryptoVault]; never stored in plaintext. */
    val sealedPassword: String? = null,
    val keyId: Long? = null,
    val askPasswordEveryTime: Boolean = false,
)

@Serializable
data class SshKey(
    val id: Long = 0,
    val label: String,
    val type: SshKeyType,
    val sealedPrivateKey: String,
    val publicKeyLine: String,
    val fingerprintSha256: String,
    val fingerprintMd5: String,
    val comment: String = "",
    val isPassphraseProtected: Boolean = false,
    val sealedPassphrase: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "generated",
    val bits: Int = 0,
)

@Serializable
data class Host(
    val id: Long = 0,
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val protocol: Protocol = Protocol.SSH,
    val groupId: Long? = null,
    val identityId: Long? = null,
    val username: String? = null,
    val sealedPassword: String? = null,
    val keyId: Long? = null,
    val jumpHostId: Long? = null,
    val agentForwarding: Boolean = false,
    val x11Forwarding: Boolean = false,
    val compression: Boolean = false,
    val keepAliveSeconds: Int = 30,
    val connectTimeoutMs: Int = 15_000,
    val charset: String = "UTF-8",
    val terminalType: String = "xterm-256color",
    val themeName: String? = null,
    val fontSizeSp: Int? = null,
    val backspaceMode: BackspaceMode = BackspaceMode.DELETE,
    val startupSnippetId: Long? = null,
    val environment: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val color: Int? = null,
    val notes: String = "",
    val isFavorite: Boolean = false,
    val strictHostKeyChecking: Boolean = true,
    val lastConnectedAt: Long? = null,
    val connectCount: Int = 0,
    val moshEnabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val displayAddress: String
        get() = buildString {
            username?.let {
                append(it)
                append('@')
            }
            append(hostname)
            if (port != protocol.defaultPort) {
                append(':')
                append(port)
            }
        }
}

@Serializable
data class KnownHost(
    val id: Long = 0,
    val hostPattern: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
    val fingerprintSha256: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val isRevoked: Boolean = false,
)

@Serializable
data class Snippet(
    val id: Long = 0,
    val name: String,
    val script: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val packageName: String? = null,
    val runInBackground: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** `${var:default}` placeholders discovered inside the script. */
    val variables: List<SnippetVariable>
        get() = VARIABLE_REGEX.findAll(script).map { match ->
            val body = match.groupValues[1]
            val parts = body.split(':', limit = 2)
            SnippetVariable(parts[0], parts.getOrNull(1).orEmpty())
        }.distinctBy { it.name }.toList()

    fun render(values: Map<String, String>): String =
        VARIABLE_REGEX.replace(script) { match ->
            val body = match.groupValues[1]
            val parts = body.split(':', limit = 2)
            values[parts[0]] ?: parts.getOrNull(1).orEmpty()
        }

    companion object {
        private val VARIABLE_REGEX = Regex("\\$\\{([^}]+)}")
    }
}

@Serializable
data class SnippetVariable(val name: String, val defaultValue: String)

@Serializable
data class PortForwardRule(
    val id: Long = 0,
    val label: String,
    val type: ForwardType,
    val hostId: Long,
    val bindAddress: String = "127.0.0.1",
    val localPort: Int = 8080,
    val remoteHost: String = "localhost",
    val remotePort: Int = 80,
    val autoStart: Boolean = false,
    val enabled: Boolean = true,
) {
    fun asSshCommand(hostDisplay: String): String = when (type) {
        ForwardType.LOCAL -> "ssh -L $bindAddress:$localPort:$remoteHost:$remotePort $hostDisplay"
        ForwardType.REMOTE -> "ssh -R $remotePort:$remoteHost:$localPort $hostDisplay"
        ForwardType.DYNAMIC -> "ssh -D $bindAddress:$localPort $hostDisplay"
    }
}

@Serializable
data class ConnectionHistoryEntry(
    val id: Long = 0,
    val hostId: Long?,
    val label: String,
    val address: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val succeeded: Boolean = true,
    val errorMessage: String? = null,
) {
    val durationMs: Long get() = (endedAt ?: System.currentTimeMillis()) - startedAt
}
