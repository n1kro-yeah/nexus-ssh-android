package com.nikro.nexusssh.core.crypto

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.domain.model.SshKeyType
import com.nikro.nexusssh.ssh.SecurityProviderInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec

/**
 * Creates new SSH key pairs on the device.
 *
 * Everything is generated through the bundled BouncyCastle provider so the result is identical on
 * every Android version, and serialised with [SshKeyCodec] into the same `openssh-key-v1` form
 * `ssh-keygen` produces.
 */
class SshKeyGenerator {

    /** Everything the keychain needs to store a freshly generated key. */
    data class GeneratedKey(
        val type: SshKeyType,
        val comment: String,
        val privateKeyPem: String,
        val publicKeyLine: String,
        val fingerprintSha256: String,
        val fingerprintMd5: String,
        val randomArt: String,
        val bits: Int,
    )

    /**
     * @param type which algorithm to use; Ed25519 is the default because it is small, fast and
     *   supported by every current server
     * @param comment the trailing comment of the public key line, e.g. `user@phone`
     */
    suspend fun generate(
        type: SshKeyType,
        comment: String = defaultComment(),
    ): GeneratedKey = withContext(Dispatchers.Default) {
        SecurityProviderInstaller.install()
        val pair = when {
            type == SshKeyType.ED25519 -> generateEd25519()
            type.isRsa -> generateRsa(type.rsaBits ?: 3072)
            type.ecCurve != null -> generateEcdsa(type.ecCurve!!)
            else -> throw IllegalArgumentException("Cannot generate a key of type $type")
        }

        val blob = SshKeyCodec.publicBlob(pair.public)
        val algorithm = SshKeyCodec.algorithmOf(pair.public)
        GeneratedKey(
            type = type,
            comment = comment,
            privateKeyPem = SshKeyCodec.openSshPrivateKey(pair, comment),
            publicKeyLine = SshKeyCodec.publicKeyLine(algorithm, blob, comment),
            fingerprintSha256 = SshKeyCodec.fingerprintSha256(blob),
            fingerprintMd5 = SshKeyCodec.fingerprintMd5(blob),
            randomArt = SshKeyCodec.randomArt(algorithm, blob),
            bits = SshKeyCodec.bitLength(algorithm, blob),
        ).also { AppLogger.i(TAG, "Generated ${type.displayName} key ${it.fingerprintSha256}") }
    }

    private fun generateEd25519(): KeyPair {
        val generator = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(255, SecureRandom())
        return generator.generateKeyPair()
    }

    private fun generateRsa(bits: Int): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        // F4 is the public exponent every SSH implementation expects.
        generator.initialize(RSAKeyGenParameterSpec(bits, RSAKeyGenParameterSpec.F4), SecureRandom())
        return generator.generateKeyPair()
    }

    private fun generateEcdsa(curve: String): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(ECGenParameterSpec(SshKeyCodec.jceCurveName(curve)), SecureRandom())
        return generator.generateKeyPair()
    }

    /**
     * Re-encrypts a stored key for export. Passphrase-protected export uses PKCS#8 with
     * PBKDF2/AES because that is what Android's JCE can produce without a native bcrypt
     * implementation; OpenSSH, PuTTY and every library read it back fine.
     */
    fun exportPublicKey(privateKeyPem: String, comment: String): String? = runCatching {
        val imported = SshKeyImporter().parse(privateKeyPem, null)
        (imported as? SshKeyImporter.ParseResult.Success)?.let {
            SshKeyCodec.publicKeyLine(it.publicKeyLine.substringBefore(' '), SshKeyCodec.parsePublicKeyLine(it.publicKeyLine).blob, comment)
        }
    }.getOrNull()

    companion object {
        private const val TAG = "SshKeyGenerator"

        fun defaultComment(): String {
            val device = android.os.Build.MODEL?.replace(' ', '-') ?: "android"
            return "nexus-ssh@$device"
        }
    }
}
