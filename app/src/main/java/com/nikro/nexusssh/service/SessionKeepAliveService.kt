package com.nikro.nexusssh.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nikro.nexusssh.MainActivity
import com.nikro.nexusssh.R
import com.nikro.nexusssh.ssh.SshSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps open SSH sessions alive when the app is not in the foreground.
 *
 * Android will freeze the process and drop the sockets otherwise, which is the single most annoying
 * thing a mobile terminal can do. The service exists only while at least one session is open, and
 * its notification is the honest statement of that: how many sessions, and a way to end them.
 */
@AndroidEntryPoint
class SessionKeepAliveService : Service() {

    @Inject
    lateinit var sessions: SshSessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            sessions.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NotificationChannels.SESSION_NOTIFICATION_ID,
            buildNotification(sessions.sessions.value.count { it.isAlive }, null),
        )

        if (observer == null) {
            observer = scope.launch {
                sessions.sessions.collectLatest { entries ->
                    val alive = entries.filter { it.isAlive }
                    if (alive.isEmpty()) {
                        // Nothing left to protect: let the process be frozen again.
                        stopSelf()
                        return@collectLatest
                    }
                    val label = alive.firstOrNull()?.label
                    startForeground(
                        NotificationChannels.SESSION_NOTIFICATION_ID,
                        buildNotification(alive.size, label),
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(count: Int, firstLabel: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionKeepAliveService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            count <= 0 -> getString(R.string.app_name)
            count == 1 && firstLabel != null -> firstLabel
            else -> "$count active sessions"
        }

        return NotificationCompat.Builder(this, NotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentTitle(title)
            .setContentText(
                if (count == 1) "SSH session running" else "$count SSH sessions running",
            )
            .setContentIntent(contentIntent)
            .addAction(0, "Disconnect all", disconnectIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_DISCONNECT_ALL = "com.nikro.nexusssh.action.DISCONNECT_ALL"

        /** Starts the service; safe to call repeatedly. */
        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, SessionKeepAliveService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, SessionKeepAliveService::class.java))
        }
    }
}
