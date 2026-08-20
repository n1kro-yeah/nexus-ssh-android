package com.nikro.nexusssh.ssh.agent

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.ssh.wire.SshReader
import com.nikro.nexusssh.ssh.wire.SshWriter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.PrivateKey
import java.security.Signature

/**
 * An in-app SSH agent.
 *
 * It answers the two requests that matter - "list your identities" and "sign this" - using keys
 * unlocked from the vault, and never hands the private key itself to the server. That is what
 * makes agent forwarding safe: a compromised remote host can ask for signatures while the session
 * is open, but cannot steal the key.
 *
 * Message numbers follow draft-miller-ssh-agent (the OpenSSH agent protocol).
 */
class SshAgent(
    private val identityProvider: () -> List<AgentKey>,
    /** Asked before every signature so forwarding cannot be used silently. */
    private val confirmSignature: (AgentKey) -> Boolean = { true },
) {

    /** One unlocked key the agent is willing to use. */
    data class AgentKey(
        val comment: String,
        val algorithm: String,
        val publicBlob: ByteArray,
        val privateKey: PrivateKey,
    ) {
        override fun equals(other: Any?): Boolean =
            other is AgentKey && publicBlob.contentEquals(other.publicBlob)

        override fun hashCode(): Int = publicBlob.contentHashCode()
    }

    /** Number of signatures served, shown on the agent screen. */
    var signatureCount: Int = 0
        private set

    /**
     * Handles one agent message (without the 4-byte length prefix) and returns the response
     * payload, also without a length prefix.
     */
    fun handleMessage(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return failure()
        val reader = SshReader(payload)
        return when (val type = reader.readByte()) {
            REQUEST_IDENTITIES -> identitiesAnswer()
            SIGN_REQUEST -> signAnswer(reader)
            ADD_IDENTITY, REMOVE_IDENTITY, REMOVE_ALL_IDENTITIES, LOCK, UNLOCK -> {
                // The key set is managed by the app, not by remote requests.
                AppLogger.d(TAG, "Refusing agent request $type")
                failure()
            }

            EXTENSION -> failure()
            else -> failure()
        }
    }

    /** Same as [handleMessage] but with the wire framing, for use on a forwarded channel. */
    fun handleFramed(framed: ByteArray): ByteArray {
        val reader = SshReader(framed)
        val body = reader.readString()
        val response = handleMessage(body)
        return SshWriter(response.size + 4).writeString(response).toByteArray()
    }

    private fun identitiesAnswer(): ByteArray {
        val keys = identityProvider()
        val writer = SshWriter()
        writer.writeByte(IDENTITIES_ANSWER)
        writer.writeUInt32(keys.size.toLong())
        keys.forEach { key ->
            writer.writeString(key.publicBlob)
            writer.writeString(key.comment)
        }
        return writer.toByteArray()
    }

    private fun signAnswer(reader: SshReader): ByteArray {
        val requestedBlob = reader.readString()
        val data = reader.readString()
        val flags = reader.readUInt32()

        val key = identityProvider().firstOrNull { it.publicBlob.contentEquals(requestedBlob) }
            ?: return failure()

        if (!confirmSignature(key)) return failure()

        val signature = try {
            sign(key, data, flags)
        } catch (error: Throwable) {
            AppLogger.w(TAG, "Signing failed: ${error.message}")
            return failure()
        }

        signatureCount++
        return SshWriter()
            .writeByte(SIGN_RESPONSE)
            .writeString(signature)
            .toByteArray()
    }

    /** Produces the `signature blob` of RFC 4253 section 6.6 for the key's algorithm. */
    private fun sign(key: AgentKey, data: ByteArray, flags: Long): ByteArray {
        return when {
            key.algorithm == "ssh-rsa" -> {
                // The client can ask for a modern hash through the flag bits.
                val (name, jcaAlgorithm) = when {
                    flags and FLAG_RSA_SHA2_512 != 0L -> "rsa-sha2-512" to "SHA512withRSA"
                    flags and FLAG_RSA_SHA2_256 != 0L -> "rsa-sha2-256" to "SHA256withRSA"
                    else -> "ssh-rsa" to "SHA1withRSA"
                }
                val raw = rawSignature(jcaAlgorithm, key.privateKey, data)
                SshWriter().writeString(name).writeString(raw).toByteArray()
            }

            key.algorithm == "ssh-ed25519" -> {
                val raw = rawSignature("Ed25519", key.privateKey, data)
                SshWriter().writeString("ssh-ed25519").writeString(raw).toByteArray()
            }

            key.algorithm.startsWith("ecdsa-sha2-") -> {
                val curve = key.algorithm.removePrefix("ecdsa-sha2-")
                val jcaAlgorithm = when (curve) {
                    "nistp256" -> "SHA256withECDSA"
                    "nistp384" -> "SHA384withECDSA"
                    "nistp521" -> "SHA512withECDSA"
                    else -> throw IllegalArgumentException("Unknown curve $curve")
                }
                val der = rawSignature(jcaAlgorithm, key.privateKey, data)
                val (r, s) = decodeDerSignature(der)
                // SSH wants the two integers as mpints inside a nested string.
                val inner = SshWriter().writeMpInt(r).writeMpInt(s).toByteArray()
                SshWriter().writeString(key.algorithm).writeString(inner).toByteArray()
            }

            else -> throw IllegalArgumentException("Cannot sign with ${key.algorithm}")
        }
    }

    private fun rawSignature(algorithm: String, key: PrivateKey, data: ByteArray): ByteArray {
        val signature = runCatching {
            Signature.getInstance(algorithm, BouncyCastleProvider.PROVIDER_NAME)
        }.getOrElse { Signature.getInstance(algorithm) }
        signature.initSign(key)
        signature.update(data)
        return signature.sign()
    }

    /** Minimal DER reader for `SEQUENCE { INTEGER r, INTEGER s }`. */
    private fun decodeDerSignature(der: ByteArray): Pair<BigInteger, BigInteger> {
        var index = 0
        require(der[index++] == 0x30.toByte()) { "Not a DER sequence" }
        index += lengthFieldSize(der, index)
        require(der[index++] == 0x02.toByte()) { "Expected INTEGER" }
        val rLengthSize = lengthFieldSize(der, index)
        val rLength = readLength(der, index)
        index += rLengthSize
        val r = BigInteger(der.copyOfRange(index, index + rLength))
        index += rLength
        require(der[index++] == 0x02.toByte()) { "Expected INTEGER" }
        val sLengthSize = lengthFieldSize(der, index)
        val sLength = readLength(der, index)
        index += sLengthSize
        val s = BigInteger(der.copyOfRange(index, index + sLength))
        return r to s
    }

    private fun lengthFieldSize(der: ByteArray, offset: Int): Int {
        val first = der[offset].toInt() and 0xFF
        return if (first and 0x80 == 0) 1 else 1 + (first and 0x7F)
    }

    private fun readLength(der: ByteArray, offset: Int): Int {
        val first = der[offset].toInt() and 0xFF
        if (first and 0x80 == 0) return first
        var length = 0
        for (index in 1..(first and 0x7F)) {
            length = (length shl 8) or (der[offset + index].toInt() and 0xFF)
        }
        return length
    }

    private fun failure(): ByteArray = byteArrayOf(FAILURE.toByte())

    companion object {
        private const val TAG = "SshAgent"

        const val FAILURE = 5
        const val SUCCESS = 6
        const val REQUEST_IDENTITIES = 11
        const val IDENTITIES_ANSWER = 12
        const val SIGN_REQUEST = 13
        const val SIGN_RESPONSE = 14
        const val ADD_IDENTITY = 17
        const val REMOVE_IDENTITY = 18
        const val REMOVE_ALL_IDENTITIES = 19
        const val LOCK = 22
        const val UNLOCK = 23
        const val EXTENSION = 27

        const val FLAG_RSA_SHA2_256 = 2L
        const val FLAG_RSA_SHA2_512 = 4L

        /** Channel type OpenSSH opens back to the client when forwarding is active. */
        const val CHANNEL_TYPE = "auth-agent@openssh.com"

        /** Channel request that enables forwarding for a session. */
        const val REQUEST_TYPE = "auth-agent-req@openssh.com"
    }
}
