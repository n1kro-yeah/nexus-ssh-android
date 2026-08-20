package com.nikro.nexusssh

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.service.NotificationChannels
import com.nikro.nexusssh.ssh.SecurityProviderInstaller
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Responsibilities:
 *  * install the BouncyCastle security provider before any SSH code runs;
 *  * create the notification channels used by the foreground services;
 *  * configure WorkManager with a Hilt-aware worker factory.
 */
@HiltAndroidApp
class NexusApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }

        // Android bundles a stripped-down BouncyCastle under the `BC` name. SSHJ needs the full
        // provider for Ed25519 / modern KEX, so we replace it before the first SSH handshake.
        SecurityProviderInstaller.install()

        createNotificationChannels()
        AppLogger.i(TAG, "NexusSSH ${BuildConfig.VERSION_NAME} started")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val sessions = NotificationChannel(
            NotificationChannels.SESSIONS,
            getString(R.string.notification_channel_sessions),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows which SSH sessions are being kept alive in the background"
            setShowBadge(false)
        }

        val forwarding = NotificationChannel(
            NotificationChannels.FORWARDING,
            getString(R.string.notification_channel_forwarding),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Active local, remote and dynamic (SOCKS) tunnels"
            setShowBadge(false)
        }

        val transfers = NotificationChannel(
            NotificationChannels.TRANSFERS,
            getString(R.string.notification_channel_transfers),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "SFTP upload and download progress"
            setShowBadge(true)
        }

        manager.createNotificationChannels(listOf(sessions, forwarding, transfers))
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }

    private companion object {
        const val TAG = "NexusApplication"
    }
}
