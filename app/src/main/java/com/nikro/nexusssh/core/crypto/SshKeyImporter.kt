package com.nikro.nexusssh.core.crypto

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.domain.model.SshKeyType
import com.nikro.nexusssh.ssh.SecurityProviderInstaller
import com.nikro.nexusssh.ssh.auth.StaticPasswordFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS5KeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import java.io.StringReader
import java.security.KeyPair

/**
 * Imports private keys pasted or picked from storage.
 *
 * Parsing is delegated to SSHJ, which understands every format users actually have:
 * `openssh-key-v1` (including bcrypt-encrypted), classic PEM (PKCS#1/SEC1), PKCS#8 and PuTTY
 * `.ppk`. The parsed pair is then re-serialised into one canonical unencrypted `openssh-key-v1`
 * blob before it goes into the vault, so the rest of the app only ever deals with one format.
 */
class SshKeyImporter {

    sealed interface ParseResult {
        /** The key was read and normalised. */
        data class Success(
            val type: SshKeyType,
            val normalizedPem: String,
            val publicKeyLine: String,
            val fingerprintSha256: String,
            val fingerprintMd5: String,
            val randomArt: String,
            val comment: String,
            val bits: Int,
            val sourceFormat: String,
            val wasEncrypted: Boolean,
        ) : ParseResult

        /** The file is encrypted and the passphrase was missing or wrong. */
        data class NeedsPassphrase(val sourceFormat: String, val wrongPassphrase: Boolean) : ParseResult

        data class Failure(val message: String) : ParseResult
    }

    suspend fun parseAsync(pem: String, passphrase: CharArray?): ParseResult =
        withContext(Dispatchers.Default) { parse(pem, passphrase) }

    fun parse(pem: String, passphrase: CharArray?): ParseResult {
        SecurityProviderInstaller.install()
        val text = pem.trim()
        if (text.isEmpty()) return ParseResult.Failure("The file is empty")

        val sourceFormat = SshKeyCodec.describePrivateKeyPem(text)
        val encrypted = SshKeyCodec.isEncryptedPem(text)
        if (encrypted && (passphrase == null || passphrase.isEmpty())) {
            return ParseResult.NeedsPassphrase(sourceFormat, wrongPassphrase = false)
        }

        val provider = try {
            providerFor(text)
        } catch (error: Throwable) {
            return ParseResult.Failure("Unrecognised key format: ${error.message ?: sourceFormat}")
        }

        return try {
            provider.init(StringReader(text), StaticPasswordFinder(passphrase))
            val publicKey = provider.public
                ?: return ParseResult.Failure("The file contains no public key material")
            val privateKey = provider.private
                ?: return ParseResult.Failure("The file contains no private key")

            val comment = extractComment(text)
            val algorithm = SshKeyCodec.algorithmOf(publicKey)
            val blob = SshKeyCodec.publicBlob(publicKey)
            val normalized = runCatching {
                SshKeyCodec.openSshPrivateKey(KeyPair(publicKey, privateKey), comment)
            }.getOrElse {
                AppLogger.w(TAG, "Keeping the original PEM: ${it.message}")
                text + "\n"
            }

            ParseResult.Success(
                type = SshKeyType.fromOpenSshName(algorithm),
                normalizedPem = normalized,
                publicKeyLine = SshKeyCodec.publicKeyLine(algorithm, blob, comment),
                fingerprintSha256 = SshKeyCodec.fingerprintSha256(blob),
                fingerprintMd5 = SshKeyCodec.fingerprintMd5(blob),
                randomArt = SshKeyCodec.randomArt(algorithm, blob),
                comment = comment,
                bits = SshKeyCodec.bitLength(algorithm, blob),
                sourceFormat = sourceFormat,
                wasEncrypted = encrypted,
            )
        } catch (error: Throwable) {
            val message = error.message.orEmpty()
            val looksLikeBadPassphrase = message.contains("decrypt", ignoreCase = true) ||
                message.contains("passphrase", ignoreCase = true) ||
                message.contains("MAC", ignoreCase = true) ||
                message.contains("padding", ignoreCase = true)
            if (encrypted || looksLikeBadPassphrase) {
                ParseResult.NeedsPassphrase(sourceFormat, wrongPassphrase = true)
            } else {
                AppLogger.w(TAG, "Import failed: $message")
                ParseResult.Failure(message.ifBlank { "Could not read the key" })
            }
        }
    }

    /** Picks the SSHJ reader that matches the file, falling back to format detection. */
    private fun providerFor(pem: String): FileKeyProvider = when {
        pem.contains("BEGIN OPENSSH PRIVATE KEY") -> OpenSSHKeyV1KeyFile()
        pem.contains("PuTTY-User-Key-File") -> PuTTYKeyFile()
        else -> when (KeyProviderUtil.detectKeyFileFormat(pem, false)) {
            KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            KeyFormat.PKCS5 -> PKCS5KeyFile()
            KeyFormat.PuTTY -> PuTTYKeyFile()
            else -> throw IllegalArgumentException("Unsupported key file")
        }
    }

    /** Pulls the comment out of an OpenSSH or PuTTY file so the key gets a meaningful label. */
    private fun extractComment(pem: String): String {
        pem.lineSequence().forEach { line ->
            if (line.startsWith("Comment:")) return line.removePrefix("Comment:").trim().trim('"')
        }
        return ""
    }

    /** Reads a public key file (`id_ed25519.pub`) for the "paste public key" flow. */
    fun parsePublicKey(line: String): ParseResult = runCatching {
        val info = SshKeyCodec.parsePublicKeyLine(line)
        ParseResult.Success(
            type = info.type,
            normalizedPem = "",
            publicKeyLine = SshKeyCodec.publicKeyLine(info.algorithm, info.blob, info.comment),
            fingerprintSha256 = info.fingerprintSha256,
            fingerprintMd5 = info.fingerprintMd5,
            randomArt = SshKeyCodec.randomArt(info.algorithm, info.blob),
            comment = info.comment,
            bits = info.bits,
            sourceFormat = "OpenSSH public key",
            wasEncrypted = false,
        )
    }.getOrElse { ParseResult.Failure(it.message ?: "Not a public key") }

    private companion object {
        const val TAG = "SshKeyImporter"
    }
}
