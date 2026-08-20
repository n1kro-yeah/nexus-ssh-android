package com.nikro.nexusssh.ssh.wire

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Writer for the SSH binary format defined in RFC 4251 section 5.
 *
 * Used to build public key blobs, OpenSSH private key files and SSH agent messages, all of which
 * share the same length-prefixed encoding.
 */
class SshWriter(initialCapacity: Int = 256) {

    private val out = ByteArrayOutputStream(initialCapacity)

    val size: Int get() = out.size()

    fun writeByte(value: Int): SshWriter {
        out.write(value and 0xFF)
        return this
    }

    fun writeBoolean(value: Boolean): SshWriter = writeByte(if (value) 1 else 0)

    /** `uint32`, big endian. */
    fun writeUInt32(value: Long): SshWriter {
        out.write(((value shr 24) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
        return this
    }

    fun writeUInt32(value: Int): SshWriter = writeUInt32(value.toLong() and 0xFFFFFFFFL)

    fun writeUInt64(value: Long): SshWriter {
        for (shift in 56 downTo 0 step 8) {
            out.write(((value shr shift) and 0xFF).toInt())
        }
        return this
    }

    /** `string`: 4-byte length followed by the raw bytes. */
    fun writeString(value: ByteArray): SshWriter {
        writeUInt32(value.size.toLong())
        out.write(value)
        return this
    }

    fun writeString(value: String): SshWriter = writeString(value.toByteArray(Charsets.UTF_8))

    /**
     * `mpint`: two's complement, minimal length, with a leading zero byte inserted when the high
     * bit is set so the value is never read back as negative.
     */
    fun writeMpInt(value: BigInteger): SshWriter = writeString(value.toByteArray())

    /** Appends bytes with no length prefix. */
    fun writeRaw(value: ByteArray, offset: Int = 0, length: Int = value.size - offset): SshWriter {
        out.write(value, offset, length)
        return this
    }

    /** A name-list, e.g. `ssh-rsa,rsa-sha2-256`. */
    fun writeNameList(names: List<String>): SshWriter = writeString(names.joinToString(","))

    fun toByteArray(): ByteArray = out.toByteArray()

    /** Wraps the current contents in a length prefix, as required by nested blobs. */
    fun toLengthPrefixed(): ByteArray = SshWriter(size + 4).writeString(toByteArray()).toByteArray()
}

/** Reader counterpart to [SshWriter]. Throws [SshWireException] on truncated input. */
class SshReader(private val data: ByteArray, private var position: Int = 0) {

    val remaining: Int get() = data.size - position

    val hasRemaining: Boolean get() = remaining > 0

    val offset: Int get() = position

    fun readByte(): Int {
        require(1)
        return data[position++].toInt() and 0xFF
    }

    fun readBoolean(): Boolean = readByte() != 0

    fun readUInt32(): Long {
        require(4)
        var value = 0L
        repeat(4) { value = (value shl 8) or (data[position++].toLong() and 0xFF) }
        return value
    }

    fun readUInt32AsInt(): Int {
        val value = readUInt32()
        if (value > Int.MAX_VALUE) throw SshWireException("Length $value exceeds supported size")
        return value.toInt()
    }

    fun readUInt64(): Long {
        require(8)
        var value = 0L
        repeat(8) { value = (value shl 8) or (data[position++].toLong() and 0xFF) }
        return value
    }

    fun readString(): ByteArray {
        val length = readUInt32AsInt()
        require(length)
        val bytes = data.copyOfRange(position, position + length)
        position += length
        return bytes
    }

    fun readStringUtf8(): String = String(readString(), Charsets.UTF_8)

    fun readMpInt(): BigInteger {
        val bytes = readString()
        return if (bytes.isEmpty()) BigInteger.ZERO else BigInteger(bytes)
    }

    /** Positive interpretation, for values that are moduli rather than signed numbers. */
    fun readPositiveMpInt(): BigInteger {
        val bytes = readString()
        return if (bytes.isEmpty()) BigInteger.ZERO else BigInteger(1, bytes)
    }

    fun readNameList(): List<String> =
        readStringUtf8().split(',').filter { it.isNotEmpty() }

    fun readRaw(length: Int): ByteArray {
        require(length)
        val bytes = data.copyOfRange(position, position + length)
        position += length
        return bytes
    }

    fun readRemaining(): ByteArray {
        val bytes = data.copyOfRange(position, data.size)
        position = data.size
        return bytes
    }

    fun skip(length: Int) {
        require(length)
        position += length
    }

    /** Reads a nested blob and returns a reader over it. */
    fun readSubReader(): SshReader = SshReader(readString())

    private fun require(length: Int) {
        if (length < 0) throw SshWireException("Negative length $length")
        if (remaining < length) {
            throw SshWireException("Truncated SSH data: need $length bytes, have $remaining")
        }
    }
}

class SshWireException(message: String) : RuntimeException(message)

/** Helpers shared by the key codec and the agent. */
object SshWire {

    /** The 4-byte-length + payload form of a single string. */
    fun string(value: String): ByteArray = SshWriter().writeString(value).toByteArray()

    /** Concatenates already-encoded chunks. */
    fun concat(vararg parts: ByteArray): ByteArray {
        val writer = SshWriter(parts.sumOf { it.size })
        parts.forEach { writer.writeRaw(it) }
        return writer.toByteArray()
    }

    /** Constant-time comparison, used when checking key blobs and MACs. */
    fun equalsConstantTime(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }
}
