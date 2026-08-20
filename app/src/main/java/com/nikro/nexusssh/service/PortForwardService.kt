package com.nikro.nexusssh.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nikro.nexusssh.MainActivity
import com.nikro.nexusssh.R
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.data.repository.PortForwardRepository
import com.nikro.nexusssh.ssh.SshSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds tunnels open without the UI.
 *
 * A tunnel is only useful while something can reach it, so forwarding gets its own foreground
 * service separate from terminal sessions: closing the last terminal must not take the SOCKS proxy
 * down with it. On boot, rules marked "start automatically" are reconnected here.
 */
@AndroidEntryPoint
class PortForwardService : Service() {

    @Inject
    lateinit var sessions: SshSessionManager

    @Inject
    lateinit var rules: PortForwardRepository

    @Inject
    lateinit var hosts: HostRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NotificationChannels.FORWARDING_NOTIFICATION_ID,
            buildNotification("Starting tunnels"),
        )

        when (intent?.action) {
            ACTION_STOP_ALL -> {
                sessions.closeAll()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> scope.launch { startAutoRules() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Opens one session per host that has auto-start rules.
     *
     * The session manager applies the host's rules itself once the connection is up, so this only
     * has to make sure the right hosts are connected - and it reuses a session that already exists
     * rather than opening a second one.
     */
    private suspend fun startAutoRules() {
        val autoStart = rules.autoStartRules().filter { it.enabled }
        if (autoStart.isEmpty()) {
            stopSelf()
            return
        }

        var started = 0
        autoStart.map { it.hostId }.distinct().forEach { hostId ->
            val alive = sessions.sessionsFor(hostId).any { it.isAlive }
            if (alive) {
                started++
                return@forEach
            }
            val host = hosts.host(hostId) ?: return@forEach
            sessions.connect(host).onSuccess { started++ }
        }

        if (started == 0) {
            stopSelf()
            return
        }
        startForeground(
            NotificationChannels.FORWARDING_NOTIFICATION_ID,
            buildNotification(
                if (autoStart.size == 1) {
                    autoStart.first().label.ifBlank { autoStart.first().asSshCommand }
                } else {
                    "${autoStart.size} tunnels active"
                },
            ),
        )
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PortForwardService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.FORWARDING)
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentTitle("Port forwarding")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START_AUTO = "com.nikro.nexusssh.action.START_AUTO_FORWARDS"
        const val ACTION_STOP_ALL = "com.nikro.nexusssh.action.STOP_FORWARDS"

        fun startAuto(context: Context) {
            context.startForegroundService(
                Intent(context, PortForwardService::class.java).setAction(ACTION_START_AUTO),
            )
        }
    }
}
