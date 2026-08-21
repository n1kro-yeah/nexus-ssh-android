package com.nikro.nexusssh.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nikro.nexusssh.core.log.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envelope encryption for every secret the app persists (passwords, private keys, passphrases).
 * A hardware-backed AES key wraps data encrypted with AES-256-GCM; an optional PBKDF2-derived
 * passcode key adds a second layer for archives and protected local records.
 */
@Singleton
class CryptoVault @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val secureRandom = SecureRandom()

    @Volatile
    private var passcodeKey: SecretKey? = null

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun masterKey(requireUserAuth: Boolean = false): SecretKey {
        val alias = if (requireUserAuth) ALIAS_AUTH_BOUND else ALIAS_MASTER
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (requireUserAuth) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setInvalidatedByBiometricEnrollment(true)
            }
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
        ) {
            runCatching { builder.setIsStrongBoxBacked(true) }
        }

        return try {
            generator.init(builder.build())
            generator.generateKey()
        } catch (error: Throwable) {
            // StrongBox can reject the spec on some OEM devices; retry without it.
            AppLogger.w(TAG, "Falling back to non-StrongBox key generation: ${error.message}")
            val fallbackBuilder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (requireUserAuth) fallbackBuilder.setUserAuthenticationRequired(true)
            generator.init(fallbackBuilder.build())
            generator.generateKey()
        }
    }

    /** Derives and caches the passcode-derived key for this process lifetime. */
    fun unlockWithPasscode(passcode: CharArray, saltBase64: String) {
        passcodeKey = deriveKey(passcode, Base64.decode(saltBase64, Base64.NO_WRAP))
        passcode.fill(Char.MIN_VALUE)
    }

    fun lock() {
        passcodeKey = null
    }

    val isPasscodeUnlocked: Boolean get() = passcodeKey != null

    fun newSalt(): String {
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    /** Produces a stable verifier without persisting the passcode itself. */
    fun passcodeVerifier(passcode: CharArray, saltBase64: String): String {
        val derived = deriveKey(passcode.copyOf(), Base64.decode(saltBase64, Base64.NO_WRAP))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("nexus-ssh-verifier".toByteArray())
        val out = digest.digest(derived.encoded)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun deriveKey(passcode: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passcode, salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    /** Encrypts [plaintext]; returns null when [plaintext] is null. */
    fun seal(plaintext: String?): String? = plaintext?.let { seal(it.toByteArray(Charsets.UTF_8)) }

    fun seal(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val payload = passcodeKey?.let { xorLayer(plaintext, it, iv) } ?: plaintext
        val ciphertext = cipher.doFinal(payload)
        return buildString {
            append(FORMAT_VERSION)
            append(':')
            append(Base64.encodeToString(iv, Base64.NO_WRAP))
            append(':')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    fun openToString(sealed: String?): String? = open(sealed)?.toString(Charsets.UTF_8)

    fun open(sealed: String?): ByteArray? {
        if (sealed.isNullOrBlank()) return null
        val parts = sealed.split(':')
        if (parts.size != 3 || parts[0] != FORMAT_VERSION) {
            AppLogger.w(TAG, "Unrecognised sealed blob format")
            return null
        }
        return try {
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val decrypted = cipher.doFinal(ciphertext)
            passcodeKey?.let { xorLayer(decrypted, it, iv) } ?: decrypted
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Unable to open sealed blob", error)
            null
        }
    }

    /** Keystore-independent portable archive encryption. */
    fun sealPortable(plaintext: ByteArray, password: CharArray): String {
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val key = deriveKey(password.copyOf(), salt)
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        password.fill(Char.MIN_VALUE)
        return listOf(
            PORTABLE_VERSION,
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString(":")
    }

    fun openPortable(sealed: String, password: CharArray): ByteArray {
        val parts = sealed.split(':')
        require(parts.size == 4 && parts[0] == PORTABLE_VERSION) { "Unsupported archive format" }
        val salt = Base64.decode(parts[1], Base64.NO_WRAP)
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[3], Base64.NO_WRAP)
        val key = deriveKey(password.copyOf(), salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        password.fill(Char.MIN_VALUE)
        return cipher.doFinal(ciphertext)
    }

    /**
     * A second keystream layer derived from the passcode key and IV. This is not a replacement
     * for AEAD; it prevents recovery by an attacker who can invoke the Keystore but lacks the
     * passcode.
     */
    private fun xorLayer(data: ByteArray, key: SecretKey, iv: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(key)
        val out = ByteArray(data.size)
        var produced = 0
        var counter = 0
        while (produced < data.size) {
            mac.reset()
            mac.update(iv)
            mac.update(counter.toByte())
            mac.update((counter ushr 8).toByte())
            val block = mac.doFinal()
            val take = minOf(block.size, data.size - produced)
            for (index in 0 until take) {
                out[produced + index] = (data[produced + index].toInt() xor block[index].toInt()).toByte()
            }
            produced += take
            counter++
        }
        return out
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    private companion object {
        const val TAG = "CryptoVault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_MASTER = "nexus_vault_master"
        const val ALIAS_AUTH_BOUND = "nexus_vault_auth_bound"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PBKDF2_ITERATIONS = 210_000
        const val FORMAT_VERSION = "v1"
        const val PORTABLE_VERSION = "nxp1"
    }
}
