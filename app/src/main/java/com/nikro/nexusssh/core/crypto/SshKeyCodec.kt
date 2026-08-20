package com.nikro.nexusssh.core.crypto

import com.nikro.nexusssh.domain.model.SshKeyType
import com.nikro.nexusssh.ssh.wire.SshReader
import com.nikro.nexusssh.ssh.wire.SshWireException
import com.nikro.nexusssh.ssh.wire.SshWriter
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Encoding and decoding of SSH key material: `authorized_keys` lines, fingerprints, and the
 * `openssh-key-v1` private key container.
 *
 * Reading *encrypted* private keys is left to SSHJ (it implements bcrypt-pbkdf); this codec covers
 * everything the app produces itself plus the parsing needed to show key details.
 */
object SshKeyCodec {

    private const val AUTH_MAGIC = "openssh-key-v1"
    private val base64 = Base64.getEncoder()
    private val base64NoPadding = Base64.getEncoder().withoutPadding()
    private val base64Decoder = Base64.getDecoder()

    // ---------------------------------------------------------------------------------------
    // Public keys
    // ---------------------------------------------------------------------------------------

    data class PublicKeyInfo(
        val algorithm: String,
        val blob: ByteArray,
        val comment: String,
    ) {
        val type: SshKeyType get() = SshKeyType.fromOpenSshName(algorithm)
        val fingerprintSha256: String get() = fingerprintSha256(blob)
        val fingerprintMd5: String get() = fingerprintMd5(blob)
        val bits: Int get() = bitLength(algorithm, blob)

        override fun equals(other: Any?): Boolean =
            other is PublicKeyInfo && algorithm == other.algorithm && blob.contentEquals(other.blob)

        override fun hashCode(): Int = 31 * algorithm.hashCode() + blob.contentHashCode()
    }

    /** Parses one `authorized_keys` / `known_hosts` style key: `<algo> <base64> [comment]`. */
    fun parsePublicKeyLine(line: String): PublicKeyInfo {
        val trimmed = line.trim()
        require(trimmed.isNotEmpty()) { "Empty key line" }
        val parts = trimmed.split(Regex("\\s+"), limit = 3)
        require(parts.size >= 2) { "Not an OpenSSH public key" }
        val algorithm = parts[0]
        val blob = try {
            base64Decoder.decode(parts[1])
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed base64 in public key", error)
        }
        // The blob repeats the algorithm name; a mismatch means the line is corrupt.
        val embedded = runCatching { SshReader(blob).readStringUtf8() }.getOrNull()
        require(embedded == algorithm) { "Key type does not match the key body" }
        return PublicKeyInfo(algorithm, blob, parts.getOrElse(2) { "" }.trim())
    }

    fun publicKeyLine(algorithm: String, blob: ByteArray, comment: String = ""): String {
        val body = "$algorithm ${base64.encodeToString(blob)}"
        return if (comment.isBlank()) body else "$body $comment"
    }

    fun publicKeyLine(key: PublicKey, comment: String = ""): String {
        val algorithm = algorithmOf(key)
        return publicKeyLine(algorithm, publicBlob(key), comment)
    }

    /** The SSH wire encoding of a public key, i.e. what gets hashed for a fingerprint. */
    fun publicBlob(key: PublicKey): ByteArray = when (key) {
        is RSAPublicKey -> SshWriter()
            .writeString("ssh-rsa")
            .writeMpInt(key.publicExponent)
            .writeMpInt(key.modulus)
            .toByteArray()

        is ECPublicKey -> {
            val curve = curveNameOf(key.params.curve.field.fieldSize)
            SshWriter()
                .writeString("ecdsa-sha2-$curve")
                .writeString(curve)
                .writeString(encodeEcPoint(key))
                .toByteArray()
        }

        else -> {
            val raw = ed25519PublicBytes(key)
                ?: throw IllegalArgumentException("Unsupported key algorithm ${key.algorithm}")
            SshWriter().writeString("ssh-ed25519").writeString(raw).toByteArray()
        }
    }

    /** Rebuilds a JCA key from an SSH blob. Used to show details of stored host keys. */
    fun decodePublicBlob(blob: ByteArray): PublicKey {
        val reader = SshReader(blob)
        return when (val algorithm = reader.readStringUtf8()) {
            "ssh-rsa", "rsa-sha2-256", "rsa-sha2-512" -> {
                val exponent = reader.readPositiveMpInt()
                val modulus = reader.readPositiveMpInt()
                KeyFactory.getInstance("RSA").generatePublic(
                    java.security.spec.RSAPublicKeySpec(modulus, exponent),
                )
            }

            "ssh-ed25519" -> {
                val raw = reader.readString()
                require(raw.size == 32) { "Ed25519 keys are 32 bytes" }
                // X.509 wrapper for id-Ed25519 (1.3.101.112) followed by the raw point.
                val spki = ED25519_SPKI_PREFIX + raw
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
            }

            "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" -> {
                reader.readStringUtf8() // curve name, already implied by the algorithm
                val point = reader.readString()
                decodeEcPublicKey(algorithm.removePrefix("ecdsa-sha2-"), point)
            }

            else -> throw SshWireException("Unsupported key type $algorithm")
        }
    }

    /** `SHA256:` fingerprint exactly as OpenSSH prints it (base64, no padding). */
    fun fingerprintSha256(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + base64NoPadding.encodeToString(digest)
    }

    /** Legacy `MD5:aa:bb:...` fingerprint, still shown by some servers and panels. */
    fun fingerprintMd5(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(blob)
        return "MD5:" + digest.joinToString(":") { "%02x".format(it) }
    }

    fun fingerprintSha256(key: PublicKey): String = fingerprintSha256(publicBlob(key))

    fun fingerprintMd5(key: PublicKey): String = fingerprintMd5(publicBlob(key))

    /**
     * OpenSSH "randomart" - the drunken bishop walk. Users recognise a changed host key from the
     * picture far faster than from a base64 digest.
     */
    fun randomArt(algorithm: String, blob: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(blob)
        val width = 17
        val height = 9
        val field = Array(height) { IntArray(width) }
        var x = width / 2
        var y = height / 2
        field[y][x] = -1 // start marker

        digest.forEach { byte ->
            var bits = byte.toInt() and 0xFF
            repeat(4) {
                val goRight = bits and 0x1 != 0
                val goDown = bits and 0x2 != 0
                x = (if (goRight) x + 1 else x - 1).coerceIn(0, width - 1)
                y = (if (goDown) y + 1 else y - 1).coerceIn(0, height - 1)
                if (field[y][x] >= 0) field[y][x] = field[y][x] + 1
                bits = bits shr 2
            }
        }
        field[height / 2][width / 2] = -1
        field[y][x] = -2 // end marker

        val characters = " .o+=*BOX@%&#/^SE"
        val bits = bitLength(algorithm, blob)
        val header = "[${algorithm.removePrefix("ssh-").uppercase()} $bits]"
        return buildString {
            append('+').append(header.padCenter(width, '-')).append("+\n")
            field.forEach { row ->
                append('|')
                row.forEach { value ->
                    val symbol = when (value) {
                        -1 -> 'S'
                        -2 -> 'E'
                        else -> characters[value.coerceAtMost(characters.length - 3)]
                    }
                    append(symbol)
                }
                append("|\n")
            }
            append('+').append("".padCenter(width, '-')).append('+')
        }
    }

    private fun String.padCenter(length: Int, pad: Char): String {
        if (this.length >= length) return substring(0, length)
        val total = length - this.length
        val left = total / 2
        return pad.toString().repeat(left) + this + pad.toString().repeat(total - left)
    }

    /** Key strength in bits, derived from the blob so it works for imported keys too. */
    fun bitLength(algorithm: String, blob: ByteArray): Int = runCatching {
        val reader = SshReader(blob)
        reader.readStringUtf8()
        when {
            algorithm.startsWith("ssh-rsa") || algorithm.startsWith("rsa-sha2") -> {
                reader.readPositiveMpInt() // exponent
                reader.readPositiveMpInt().bitLength()
            }

            algorithm == "ssh-ed25519" -> 256
            algorithm.startsWith("ecdsa-sha2-nistp") ->
                algorithm.removePrefix("ecdsa-sha2-nistp").toIntOrNull() ?: 256

            else -> 0
        }
    }.getOrDefault(0)

    // ---------------------------------------------------------------------------------------
    // Private keys
    // ---------------------------------------------------------------------------------------

    /**
     * Serialises a key pair as an unencrypted `openssh-key-v1` PEM file - the format `ssh-keygen`
     * writes and every server tool understands.
     *
     * Keys created in the app are protected by the device keystore instead of a passphrase, and
     * are re-encrypted with a passphrase only on export.
     */
    fun openSshPrivateKey(pair: KeyPair, comment: String = ""): String {
        val publicBlob = publicBlob(pair.public)
        val checkInt = java.security.SecureRandom().nextInt()

        val privateSection = SshWriter().apply {
            writeUInt32(checkInt.toLong() and 0xFFFFFFFFL)
            writeUInt32(checkInt.toLong() and 0xFFFFFFFFL)
            writeRaw(privateKeyBody(pair))
            writeString(comment)
        }

        // Pad to the cipher block size (8 for "none") with 1, 2, 3...
        val padded = privateSection.toByteArray().let { body ->
            val blockSize = 8
            val padding = (blockSize - body.size % blockSize) % blockSize
            if (padding == 0) {
                body
            } else {
                body + ByteArray(padding) { (it + 1).toByte() }
            }
        }

        val container = SshWriter().apply {
            writeRaw(AUTH_MAGIC.toByteArray(Charsets.US_ASCII))
            writeByte(0)
            writeString("none") // ciphername
            writeString("none") // kdfname
            writeString(ByteArray(0)) // kdfoptions
            writeUInt32(1L) // number of keys
            writeString(publicBlob)
            writeString(padded)
        }

        return wrapPem("OPENSSH PRIVATE KEY", container.toByteArray())
    }

    private fun privateKeyBody(pair: KeyPair): ByteArray = when (val privateKey = pair.private) {
        is RSAPrivateCrtKey -> {
            val publicKey = pair.public as RSAPublicKey
            SshWriter()
                .writeString("ssh-rsa")
                .writeMpInt(publicKey.modulus)
                .writeMpInt(publicKey.publicExponent)
                .writeMpInt(privateKey.privateExponent)
                .writeMpInt(privateKey.crtCoefficient)
                .writeMpInt(privateKey.primeP)
                .writeMpInt(privateKey.primeQ)
                .toByteArray()
        }

        is ECPrivateKey -> {
            val publicKey = pair.public as ECPublicKey
            val curve = curveNameOf(publicKey.params.curve.field.fieldSize)
            SshWriter()
                .writeString("ecdsa-sha2-$curve")
                .writeString(curve)
                .writeString(encodeEcPoint(publicKey))
                .writeMpInt(privateKey.s)
                .toByteArray()
        }

        else -> {
            val publicRaw = ed25519PublicBytes(pair.public)
                ?: throw IllegalArgumentException("Unsupported key ${privateKey.algorithm}")
            val seed = ed25519Seed(privateKey)
                ?: throw IllegalArgumentException("Cannot extract Ed25519 seed")
            SshWriter()
                .writeString("ssh-ed25519")
                .writeString(publicRaw)
                // OpenSSH stores seed || public key as the "private" field.
                .writeString(seed + publicRaw)
                .toByteArray()
        }
    }

    /** Detects the PEM type of an imported key so the UI can explain what it got. */
    fun describePrivateKeyPem(pem: String): String = when {
        pem.contains("BEGIN OPENSSH PRIVATE KEY") -> "OpenSSH"
        pem.contains("BEGIN RSA PRIVATE KEY") -> "PEM (PKCS#1, RSA)"
        pem.contains("BEGIN EC PRIVATE KEY") -> "PEM (SEC1, ECDSA)"
        pem.contains("BEGIN DSA PRIVATE KEY") -> "PEM (DSA)"
        pem.contains("BEGIN ENCRYPTED PRIVATE KEY") -> "PKCS#8 (encrypted)"
        pem.contains("BEGIN PRIVATE KEY") -> "PKCS#8"
        pem.contains("PuTTY-User-Key-File") -> "PuTTY (.ppk)"
        else -> "Unknown"
    }

    fun isEncryptedPem(pem: String): Boolean = when {
        pem.contains("BEGIN ENCRYPTED PRIVATE KEY") -> true
        pem.contains("Proc-Type: 4,ENCRYPTED") -> true
        pem.contains("BEGIN OPENSSH PRIVATE KEY") -> isEncryptedOpenSshKey(pem)
        pem.contains("Encryption: aes") -> true // PuTTY
        else -> false
    }

    /** Reads the `ciphername` field of an openssh-key-v1 blob; "none" means unencrypted. */
    private fun isEncryptedOpenSshKey(pem: String): Boolean = runCatching {
        val body = pem.lines()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()
        val decoded = base64Decoder.decode(body)
        val reader = SshReader(decoded, AUTH_MAGIC.length + 1)
        reader.readStringUtf8() != "none"
    }.getOrDefault(false)

    /** PKCS#8 DER wrapped in a PEM envelope, used by "export as PEM". */
    fun pkcs8Pem(key: PrivateKey): String = wrapPem("PRIVATE KEY", key.encoded)

    fun wrapPem(label: String, der: ByteArray): String = buildString {
        append("-----BEGIN ").append(label).append("-----\n")
        base64.encodeToString(der).chunked(70).forEach { append(it).append('\n') }
        append("-----END ").append(label).append("-----\n")
    }

    fun unwrapPem(pem: String): ByteArray = base64Decoder.decode(
        pem.lines().filterNot { it.startsWith("-----") || it.contains(":") }.joinToString("").trim(),
    )

    // ---------------------------------------------------------------------------------------
    // Algorithm helpers
    // ---------------------------------------------------------------------------------------

    fun algorithmOf(key: PublicKey): String = when (key) {
        is RSAPublicKey -> "ssh-rsa"
        is ECPublicKey -> "ecdsa-sha2-" + curveNameOf(key.params.curve.field.fieldSize)
        else -> "ssh-ed25519"
    }

    private fun curveNameOf(fieldSize: Int): String = when (fieldSize) {
        256 -> "nistp256"
        384 -> "nistp384"
        521 -> "nistp521"
        else -> throw IllegalArgumentException("Unsupported EC field size $fieldSize")
    }

    /** Uncompressed point encoding: `04 || X || Y`, both padded to the field size. */
    private fun encodeEcPoint(key: ECPublicKey): ByteArray {
        val fieldBytes = (key.params.curve.field.fieldSize + 7) / 8
        val x = key.w.affineX.toFixedLength(fieldBytes)
        val y = key.w.affineY.toFixedLength(fieldBytes)
        return byteArrayOf(0x04) + x + y
    }

    private fun decodeEcPublicKey(curveName: String, point: ByteArray): PublicKey {
        require(point.isNotEmpty() && point[0] == 0x04.toByte()) { "Compressed points are not supported" }
        val fieldBytes = (point.size - 1) / 2
        val x = BigInteger(1, point.copyOfRange(1, 1 + fieldBytes))
        val y = BigInteger(1, point.copyOfRange(1 + fieldBytes, point.size))
        val parameters = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec(jceCurveName(curveName)))
        }
        val spec = parameters.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(x, y), spec),
        )
    }

    fun jceCurveName(sshCurve: String): String = when (sshCurve) {
        "nistp256" -> "secp256r1"
        "nistp384" -> "secp384r1"
        "nistp521" -> "secp521r1"
        else -> throw IllegalArgumentException("Unknown curve $sshCurve")
    }

    private fun BigInteger.toFixedLength(length: Int): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size == length + 1 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, bytes.size)
            bytes.size < length -> ByteArray(length - bytes.size) + bytes
            else -> bytes.copyOfRange(bytes.size - length, bytes.size)
        }
    }

    /** The 32 raw bytes of an Ed25519 public key, taken from its X.509 encoding. */
    fun ed25519PublicBytes(key: PublicKey): ByteArray? {
        if (!key.algorithm.contains("Ed", ignoreCase = true)) return null
        val encoded = key.encoded ?: return null
        return if (encoded.size >= 32) encoded.copyOfRange(encoded.size - 32, encoded.size) else null
    }

    /** The 32-byte Ed25519 seed from a PKCS#8 private key. */
    fun ed25519Seed(key: PrivateKey): ByteArray? = runCatching {
        val info = PrivateKeyInfo.getInstance(key.encoded)
        val inner = ASN1OctetString.getInstance(info.parsePrivateKey())
        inner.octets.takeIf { it.size == 32 }
    }.getOrNull()

    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
}
