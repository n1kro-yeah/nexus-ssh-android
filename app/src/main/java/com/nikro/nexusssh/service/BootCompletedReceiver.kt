package com.nikro.nexusssh.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nikro.nexusssh.data.repository.PortForwardRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts tunnels marked "start automatically" after a reboot.
 *
 * Nothing is connected here directly: the receiver only wakes the forwarding service, which owns
 * the notification and the retry policy. If no rule wants auto-start, the service is never touched.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var portForwards: PortForwardRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val autoStart = portForwards.autoStartRules()
                if (autoStart.isNotEmpty()) {
                    context.startForegroundService(
                        Intent(context, PortForwardService::class.java).apply {
                            action = PortForwardService.ACTION_START_AUTO
                        },
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
