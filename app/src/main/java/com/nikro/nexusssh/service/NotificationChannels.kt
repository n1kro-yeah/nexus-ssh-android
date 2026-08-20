package com.nikro.nexusssh.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.nikro.nexusssh.R

/**
 * Notification channels used by the three foreground services.
 *
 * Created once from [com.nikro.nexusssh.NexusApplication.onCreate] so a service can post its
 * notification immediately after `startForeground`.
 */
object NotificationChannels {

    /** Live SSH shells kept alive while the app is backgrounded. */
    const val SESSIONS = "nexus_sessions"

    /** Active local/remote/dynamic port forwards. */
    const val FORWARDING = "nexus_forwarding"

    /** SFTP uploads and downloads with progress. */
    const val TRANSFERS = "nexus_transfers"

    /** Notification ids; each service owns one. */
    const val SESSION_NOTIFICATION_ID = 1001
    const val FORWARDING_NOTIFICATION_ID = 1002
    const val TRANSFER_NOTIFICATION_ID = 1003

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        val sessions = NotificationChannel(
            SESSIONS,
            context.getString(R.string.channel_sessions),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_sessions_description)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }

        val forwarding = NotificationChannel(
            FORWARDING,
            context.getString(R.string.channel_forwarding),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_forwarding_description)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }

        val transfers = NotificationChannel(
            TRANSFERS,
            context.getString(R.string.channel_transfers),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_transfers_description)
            setShowBadge(true)
        }

        manager.createNotificationChannels(listOf(sessions, forwarding, transfers))
    }
}
