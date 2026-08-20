package com.nikro.nexusssh.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nikro.nexusssh.R
import com.nikro.nexusssh.data.local.TransferDao
import com.nikro.nexusssh.data.local.TransferEntity
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.ssh.SshSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject

/** Runs SFTP transfers outside the UI with a foreground notification. */
@AndroidEntryPoint
class SftpTransferService : Service() {

    @Inject lateinit var sessions: SshSessionManager
    @Inject lateinit var hosts: HostRepository
    @Inject lateinit var transfers: TransferDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(permits = 2)
    private var active = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                scope.launch { transfers.cancelAll(System.currentTimeMillis()) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ENQUEUE -> enqueue(intent)
            else -> Unit
        }
        return START_NOT_STICKY
    }

    private fun enqueue(intent: Intent) {
        val hostId = intent.getLongExtra(EXTRA_HOST_ID, 0L)
        val remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH) ?: return
        val upload = intent.getBooleanExtra(EXTRA_UPLOAD, false)
        val localUri = intent.getStringExtra(EXTRA_LOCAL_URI)
        val localPath = intent.getStringExtra(EXTRA_LOCAL_PATH)
        val fileName = remotePath.substringAfterLast('/').ifBlank { "file" }

        startForeground(
            NotificationChannels.TRANSFER_NOTIFICATION_ID,
            notification(fileName, 0, indeterminate = true),
        )
        active++

        scope.launch {
            slots.withPermit {
                val host = hosts.host(hostId)
                if (host == null) {
                    finished()
                    return@withPermit
                }
                val rowId = transfers.insert(
                    TransferEntity(
                        hostId = hostId,
                        hostLabel = host.label,
                        upload = upload,
                        localPath = localPath ?: localUri.orEmpty(),
                        remotePath = remotePath,
                        fileName = fileName,
                        state = "running",
                    ),
                )
                val manager = sessions.sessionsFor(hostId)
                    .firstOrNull { it.isAlive }
                    ?.let { sessions.sftpManager(it.id) }
                    ?: sessions.openSftpOnly(host).getOrNull()?.second

                if (manager == null) {
                    transfers.updateState(
                        rowId,
                        "failed",
                        "Could not open an SFTP session",
                        System.currentTimeMillis(),
                    )
                    finished()
                    return@withPermit
                }

                val outcome = runCatching {
                    if (upload) {
                        val uri = localUri?.let(Uri::parse)
                        val size = uri?.let { sizeOf(it) } ?: 0L
                        val stream = when {
                            uri != null -> contentResolver.openInputStream(uri)
                            localPath != null -> File(localPath).inputStream()
                            else -> null
                        } ?: error("No local file to upload")
                        stream.use { input ->
                            manager.upload(input, remotePath, size) { progress ->
                                // SFTP callbacks are synchronous; schedule the suspend DAO update.
                                scope.launch {
                                    publish(rowId, fileName, progress.transferred, progress.fraction)
                                }
                            }
                        }
                    } else {
                        val target = File(
                            getExternalFilesDir(null) ?: filesDir,
                            "downloads",
                        ).apply { mkdirs() }
                        val file = File(target, fileName)
                        val size = manager.stat(remotePath)?.size ?: 0L
                        file.outputStream().use { output ->
                            manager.download(remotePath, output, size) { progress ->
                                scope.launch {
                                    publish(rowId, fileName, progress.transferred, progress.fraction)
                                }
                            }
                        }
                    }
                }

                outcome.fold(
                    onSuccess = {
                        transfers.updateState(rowId, "done", null, System.currentTimeMillis())
                    },
                    onFailure = { failure ->
                        transfers.updateState(
                            rowId,
                            "failed",
                            failure.message ?: failure::class.java.simpleName,
                            System.currentTimeMillis(),
                        )
                    },
                )
                finished()
            }
        }
    }

    private suspend fun publish(rowId: Long, fileName: String, bytes: Long, fraction: Float) {
        transfers.updateProgress(rowId, bytes)
        val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NotificationChannels.TRANSFER_NOTIFICATION_ID,
            notification(fileName, percent, indeterminate = false),
        )
    }

    private fun finished() {
        active--
        if (active <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun sizeOf(uri: Uri): Long =
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0L) } ?: 0L

    private fun notification(fileName: String, percent: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, NotificationChannels.TRANSFERS)
            .setContentTitle("Transferring $fileName")
            .setContentText(if (indeterminate) "Starting" else "$percent%")
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(
                0,
                "Cancel",
                android.app.PendingIntent.getService(
                    this,
                    2,
                    Intent(this, SftpTransferService::class.java).setAction(ACTION_CANCEL_ALL),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENQUEUE = "com.nikro.nexusssh.action.ENQUEUE_TRANSFER"
        const val ACTION_CANCEL_ALL = "com.nikro.nexusssh.action.CANCEL_TRANSFERS"
        private const val EXTRA_HOST_ID = "hostId"
        private const val EXTRA_REMOTE_PATH = "remotePath"
        private const val EXTRA_LOCAL_URI = "localUri"
        private const val EXTRA_LOCAL_PATH = "localPath"
        private const val EXTRA_UPLOAD = "upload"

        /** Queues a download of [remotePath] from [hostId]. */
        fun download(context: Context, hostId: Long, remotePath: String) {
            context.startService(
                Intent(context, SftpTransferService::class.java)
                    .setAction(ACTION_ENQUEUE)
                    .putExtra(EXTRA_HOST_ID, hostId)
                    .putExtra(EXTRA_REMOTE_PATH, remotePath)
                    .putExtra(EXTRA_UPLOAD, false),
            )
        }

        /** Queues an upload of a content [uri] to [remotePath]. */
        fun upload(context: Context, hostId: Long, uri: String, remotePath: String) {
            context.startService(
                Intent(context, SftpTransferService::class.java)
                    .setAction(ACTION_ENQUEUE)
                    .putExtra(EXTRA_HOST_ID, hostId)
                    .putExtra(EXTRA_REMOTE_PATH, remotePath)
                    .putExtra(EXTRA_LOCAL_URI, uri)
                    .putExtra(EXTRA_UPLOAD, true),
            )
        }
    }
}
