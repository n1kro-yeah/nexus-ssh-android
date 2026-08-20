package com.nikro.nexusssh.data.repository

import com.nikro.nexusssh.core.crypto.SshKeyCodec
import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.data.local.KnownHostDao
import com.nikro.nexusssh.data.local.toDomain
import com.nikro.nexusssh.data.local.toEntity
import com.nikro.nexusssh.domain.model.KnownHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.PublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's `known_hosts`.
 *
 * Keys are stored per host *and port*, which is stricter than OpenSSH's `[host]:port` notation but
 * removes any ambiguity, and they are matched by key type so a server offering both Ed25519 and
 * RSA host keys does not look like an attack.
 */
@Singleton
class KnownHostRepository @Inject constructor(
    private val dao: KnownHostDao,
) {

    /** Outcome of checking a presented host key. */
    sealed interface Verdict {
        /** The key is already trusted. */
        data class Known(val entry: KnownHost) : Verdict

        /** Nothing stored for this host and key type yet - trust on first use. */
        data class Unknown(
            val keyType: String,
            val fingerprint: String,
            val randomArt: String,
        ) : Verdict

        /** A different key of the same type is stored. Possible man-in-the-middle. */
        data class Changed(
            val keyType: String,
            val storedFingerprint: String,
            val presentedFingerprint: String,
            val randomArt: String,
        ) : Verdict

        /** The user marked this key as revoked; the connection must fail. */
        data class Revoked(
            val keyType: String,
            val fingerprint: String,
        ) : Verdict
    }

    fun observeAll(): Flow<List<KnownHost>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun all(): List<KnownHost> = dao.getAll().map { it.toDomain() }

    suspend fun entriesFor(hostname: String, port: Int): List<KnownHost> =
        dao.findForHost(hostname, port).map { it.toDomain() }

    /** Checks a key presented during the handshake. Never throws: a failure means "unknown". */
    suspend fun verify(hostname: String, port: Int, key: PublicKey): Verdict {
        val algorithm = SshKeyCodec.algorithmOf(key)
        val blob = SshKeyCodec.publicBlob(key)
        val encoded = Base64.getEncoder().encodeToString(blob)
        val fingerprint = SshKeyCodec.fingerprintSha256(blob)
        val art = runCatching { SshKeyCodec.randomArt(algorithm, blob) }.getOrDefault("")

        val stored = dao.findForHost(hostname, port)

        // A revoked key blocks the connection whatever else is stored.
        stored.firstOrNull { it.isRevoked && it.publicKeyBase64 == encoded }?.let {
            return Verdict.Revoked(algorithm, fingerprint)
        }

        val sameType = stored.filterNot { it.isRevoked }.firstOrNull { it.keyType == algorithm }
            ?: return Verdict.Unknown(algorithm, fingerprint, art)

        return if (sameType.publicKeyBase64 == encoded) {
            dao.touch(sameType.id)
            Verdict.Known(sameType.toDomain())
        } else {
            Verdict.Changed(
                keyType = algorithm,
                storedFingerprint = sameType.fingerprintSha256,
                presentedFingerprint = fingerprint,
                randomArt = art,
            )
        }
    }

    /** Stores a key as trusted (first use, or after the user accepted a change). */
    suspend fun trust(hostname: String, port: Int, key: PublicKey) {
        val algorithm = SshKeyCodec.algorithmOf(key)
        val blob = SshKeyCodec.publicBlob(key)
        dao.insert(
            KnownHost(
                hostPattern = hostname,
                port = port,
                keyType = algorithm,
                publicKeyBase64 = Base64.getEncoder().encodeToString(blob),
                fingerprintSha256 = SshKeyCodec.fingerprintSha256(blob),
            ).toEntity(),
        )
        AppLogger.i(TAG, "Trusted $algorithm key for $hostname:$port")
    }

    /** Forgets every key stored for a host, as `ssh-keygen -R` does. */
    suspend fun forgetHost(hostname: String, port: Int) = dao.deleteForHost(hostname, port)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun setRevoked(id: Long, revoked: Boolean) = dao.setRevoked(id, revoked)

    suspend fun deleteAll() = dao.deleteAll()

    // ---------------------------------------------------------------------------------------
    // OpenSSH interop
    // ---------------------------------------------------------------------------------------

    /**
     * Imports an OpenSSH `known_hosts` file.
     *
     * Hashed entries (`|1|salt|hash`) are skipped because the hostname cannot be recovered from
     * them, and `@cert-authority` markers are ignored.
     *
     * @return how many entries were imported
     */
    suspend fun importOpenSshFormat(text: String): Int {
        val entries = mutableListOf<KnownHost>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val withoutMarker = when {
                line.startsWith("@revoked") -> line.removePrefix("@revoked").trim()
                line.startsWith("@cert-authority") -> return@forEach
                else -> line
            }
            val revoked = line.startsWith("@revoked")
            val parts = withoutMarker.split(Regex("\\s+"))
            if (parts.size < 3) return@forEach
            val hostField = parts[0]
            if (hostField.startsWith("|")) return@forEach // hashed, unusable

            val keyType = parts[1]
            val base64 = parts[2]
            val blob = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return@forEach

            // One line can list several host patterns separated by commas.
            hostField.split(',').forEach { pattern ->
                val (host, port) = splitHostPort(pattern)
                entries += KnownHost(
                    hostPattern = host,
                    port = port,
                    keyType = keyType,
                    publicKeyBase64 = base64,
                    fingerprintSha256 = SshKeyCodec.fingerprintSha256(blob),
                    isRevoked = revoked,
                )
            }
        }
        if (entries.isNotEmpty()) dao.upsertAll(entries.map { it.toEntity() })
        AppLogger.i(TAG, "Imported ${entries.size} known host entries")
        return entries.size
    }

    /** Renders the store back into OpenSSH format for export. */
    suspend fun exportOpenSshFormat(): String = buildString {
        appendLine("# Exported from Nexus SSH")
        all().forEach { entry ->
            if (entry.isRevoked) append("@revoked ")
            val host = if (entry.port == 22) entry.hostPattern else "[${entry.hostPattern}]:${entry.port}"
            append(host).append(' ').append(entry.keyType).append(' ').append(entry.publicKeyBase64)
            appendLine()
        }
    }

    /** Parses `[host]:port` and bare `host` patterns. */
    private fun splitHostPort(pattern: String): Pair<String, Int> {
        if (pattern.startsWith("[")) {
            val closing = pattern.indexOf(']')
            if (closing > 0) {
                val host = pattern.substring(1, closing)
                val port = pattern.substringAfter("]:", "22").toIntOrNull() ?: 22
                return host to port
            }
        }
        return pattern to 22
    }

    private companion object {
        const val TAG = "KnownHosts"
    }
}
