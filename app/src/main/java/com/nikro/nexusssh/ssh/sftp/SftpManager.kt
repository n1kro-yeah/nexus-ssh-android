package com.nikro.nexusssh.ssh.sftp

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.ssh.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * The SFTP half of the app: a browsable remote filesystem plus resumable transfers.
 *
 * Transfers use explicit offsets on [net.schmizz.sshj.sftp.RemoteFile] instead of the convenience
 * `get`/`put` helpers, which is what makes progress reporting, cancellation and resume possible.
 */
class SftpManager(private val connection: SshConnection) {

    private var client: SFTPClient? = null

    /** A remote directory entry in a UI-friendly shape. */
    data class RemoteEntry(
        val name: String,
        val path: String,
        val size: Long,
        val modifiedAt: Long,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
        val permissions: Int,
        val ownerId: Int,
        val groupId: Int,
    ) {
        val isHidden: Boolean get() = name.startsWith(".")

        /** `drwxr-xr-x` style rendering. */
        val permissionString: String
            get() = buildString {
                append(
                    when {
                        isSymlink -> 'l'
                        isDirectory -> 'd'
                        else -> '-'
                    },
                )
                val bits = listOf(0b100_000_000, 0b010_000_000, 0b001_000_000)
                val letters = listOf('r', 'w', 'x')
                for (group in 0..2) {
                    for (bit in 0..2) {
                        val mask = bits[bit] shr (group * 3)
                        append(if (permissions and mask != 0) letters[bit] else '-')
                    }
                }
            }

        val octalPermissions: String get() = Integer.toOctalString(permissions and 0xFFF).padStart(3, '0')
    }

    data class Progress(
        val transferred: Long,
        val total: Long,
        val bytesPerSecond: Long,
    ) {
        val fraction: Float get() = if (total <= 0) 0f else (transferred.toFloat() / total).coerceIn(0f, 1f)
    }

    suspend fun connect(): SFTPClient = withContext(Dispatchers.IO) {
        client?.takeIf { connection.isConnected } ?: connection.openSftp().also { client = it }
    }

    fun close() {
        runCatching { client?.close() }
        client = null
    }

    // ---------------------------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------------------------

    suspend fun home(): String = withContext(Dispatchers.IO) {
        runCatching { connect().canonicalize(".") }.getOrDefault("/")
    }

    suspend fun list(path: String, showHidden: Boolean = true): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            val sftp = connect()
            sftp.ls(path)
                .map { it.toEntry(sftp) }
                .filter { showHidden || !it.isHidden }
                .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

    suspend fun stat(path: String): RemoteEntry? = withContext(Dispatchers.IO) {
        runCatching {
            val sftp = connect()
            val attributes = sftp.stat(path)
            attributes.toEntry(path.substringAfterLast('/').ifEmpty { path }, path, isSymlink = false)
        }.getOrNull()
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) { connect().mkdirs(path) }

    suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        connect().rename(from, to)
    }

    suspend fun delete(path: String, recursive: Boolean = false) = withContext(Dispatchers.IO) {
        val sftp = connect()
        val attributes = sftp.stat(path)
        if (attributes.mode.type == FileMode.Type.DIRECTORY) {
            if (recursive) deleteRecursively(sftp, path) else sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    private fun deleteRecursively(sftp: SFTPClient, path: String) {
        sftp.ls(path).forEach { entry ->
            if (entry.isDirectory) deleteRecursively(sftp, entry.path) else sftp.rm(entry.path)
        }
        sftp.rmdir(path)
    }

    suspend fun chmod(path: String, octal: String) = withContext(Dispatchers.IO) {
        val permissions = octal.toInt(8)
        connect().chmod(path, permissions)
    }

    suspend fun symlinkTarget(path: String): String? = withContext(Dispatchers.IO) {
        runCatching { connect().readlink(path) }.getOrNull()
    }

    /** Free/used space, shown in the SFTP browser footer when the server supports statvfs. */
    suspend fun diskUsage(path: String): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val statistics = connect().statVFS(path)
            val total = statistics.blocks * statistics.blockSize
            val free = statistics.blocksAvailable * statistics.blockSize
            total to free
        }.getOrNull()
    }

    /** Creates an empty file, used by the "new file" action. */
    suspend fun touch(path: String) = withContext(Dispatchers.IO) {
        connect().open(path, EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC)).close()
    }

    // ---------------------------------------------------------------------------------------
    // Transfers
    // ---------------------------------------------------------------------------------------

    /**
     * Downloads [remotePath] into [destination]. Passing an existing partial file resumes it.
     *
     * @return the number of bytes transferred by this call
     */
    suspend fun download(
        remotePath: String,
        destination: OutputStream,
        totalSize: Long,
        startOffset: Long = 0,
        onProgress: (Progress) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val sftp = connect()
        val file = sftp.open(remotePath, EnumSet.of(OpenMode.READ))
        val buffer = ByteArray(CHUNK)
        var offset = startOffset
        var transferred = 0L
        val tracker = RateTracker()
        try {
            while (currentCoroutineContext().isActive) {
                val read = file.read(offset, buffer, 0, buffer.size)
                if (read <= 0) break
                destination.write(buffer, 0, read)
                offset += read
                transferred += read
                tracker.add(read.toLong())
                onProgress(Progress(offset, totalSize, tracker.bytesPerSecond))
            }
            destination.flush()
        } finally {
            runCatching { file.close() }
        }
        transferred
    }

    /** Uploads [source] to [remotePath], optionally appending from [startOffset]. */
    suspend fun upload(
        source: InputStream,
        remotePath: String,
        totalSize: Long,
        startOffset: Long = 0,
        onProgress: (Progress) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val sftp = connect()
        val modes = if (startOffset > 0) {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
        } else {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }
        val file = sftp.open(remotePath, modes)
        val buffer = ByteArray(CHUNK)
        var offset = startOffset
        var transferred = 0L
        val tracker = RateTracker()
        try {
            while (currentCoroutineContext().isActive) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                file.write(offset, buffer, 0, read)
                offset += read
                transferred += read
                tracker.add(read.toLong())
                onProgress(Progress(offset, totalSize, tracker.bytesPerSecond))
            }
        } finally {
            runCatching { file.close() }
        }
        transferred
    }

    /** Recursively uploads a local directory tree. */
    suspend fun uploadDirectory(
        localDirectory: File,
        remotePath: String,
        onProgress: (String, Progress) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val sftp = connect()
        sftp.mkdirs(remotePath)
        localDirectory.walkTopDown().forEach { file ->
            val relative = file.relativeTo(localDirectory).path.replace(File.separatorChar, '/')
            if (relative.isEmpty()) return@forEach
            val target = "$remotePath/$relative"
            if (file.isDirectory) {
                runCatching { sftp.mkdirs(target) }
            } else {
                file.inputStream().use { stream ->
                    upload(stream, target, file.length()) { progress -> onProgress(relative, progress) }
                }
            }
        }
    }

    /** Recursively downloads a remote directory tree. */
    suspend fun downloadDirectory(
        remotePath: String,
        localDirectory: File,
        onProgress: (String, Progress) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val sftp = connect()
        localDirectory.mkdirs()
        fun walk(remote: String, local: File) {
            sftp.ls(remote).forEach { entry ->
                val child = File(local, entry.name)
                if (entry.isDirectory) {
                    child.mkdirs()
                    walk(entry.path, child)
                } else {
                    child.outputStream().use { output ->
                        kotlinx.coroutines.runBlocking {
                            download(entry.path, output, entry.attributes.size) { progress ->
                                onProgress(entry.name, progress)
                            }
                        }
                    }
                }
            }
        }
        walk(remotePath, localDirectory)
    }

    /** Reads a small remote file completely - used by the built-in text editor. */
    suspend fun readText(path: String, maxBytes: Int = 2 * 1024 * 1024): String =
        withContext(Dispatchers.IO) {
            val sftp = connect()
            val file = sftp.open(path, EnumSet.of(OpenMode.READ))
            try {
                val size = minOf(file.length(), maxBytes.toLong()).toInt()
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val read = file.read(offset.toLong(), bytes, offset, size - offset)
                    if (read <= 0) break
                    offset += read
                }
                String(bytes, 0, offset, Charsets.UTF_8)
            } finally {
                runCatching { file.close() }
            }
        }

    suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        upload(bytes.inputStream(), path, bytes.size.toLong())
        Unit
    }

    // ---------------------------------------------------------------------------------------
    // Mapping helpers
    // ---------------------------------------------------------------------------------------

    private fun RemoteResourceInfo.toEntry(sftp: SFTPClient): RemoteEntry {
        val symlink = attributes.mode.type == FileMode.Type.SYMLINK
        // A symlink's own attributes hide what it points at; resolve so directories sort right.
        val effective = if (symlink) {
            runCatching { sftp.stat(path) }.getOrDefault(attributes)
        } else {
            attributes
        }
        return effective.toEntry(name, path, symlink)
    }

    private fun FileAttributes.toEntry(name: String, path: String, isSymlink: Boolean) = RemoteEntry(
        name = name,
        path = path,
        size = size,
        modifiedAt = mtime * 1000L,
        isDirectory = mode.type == FileMode.Type.DIRECTORY,
        isSymlink = isSymlink,
        permissions = mode.permissionsMask,
        ownerId = uid,
        groupId = gid,
    )

    /** Smooths the instantaneous rate so the UI does not flicker. */
    private class RateTracker {
        private var windowStart = System.nanoTime()
        private var windowBytes = 0L
        var bytesPerSecond: Long = 0
            private set

        fun add(bytes: Long) {
            windowBytes += bytes
            val now = System.nanoTime()
            val elapsed = now - windowStart
            if (elapsed >= 500_000_000L) {
                bytesPerSecond = windowBytes * 1_000_000_000L / elapsed
                windowStart = now
                windowBytes = 0
            }
        }
    }

    companion object {
        private const val TAG = "SftpManager"
        private const val CHUNK = 32 * 1024

        /** Maps SFTP status codes onto something a person can act on. */
        fun describe(error: Throwable): String = when {
            error is SFTPException -> when (error.statusCode) {
                net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE -> "No such file or directory"
                net.schmizz.sshj.sftp.Response.StatusCode.PERMISSION_DENIED -> "Permission denied"
                net.schmizz.sshj.sftp.Response.StatusCode.FAILURE -> "The server refused the operation"
                else -> error.message ?: "SFTP error"
            }

            else -> error.message ?: error::class.java.simpleName
        }.also { AppLogger.d(TAG, it) }
    }
}
