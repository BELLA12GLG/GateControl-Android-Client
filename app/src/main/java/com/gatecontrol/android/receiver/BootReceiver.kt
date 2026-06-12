package com.gatecontrol.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gatecontrol.android.service.VpnForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.d("BootReceiver: BOOT_COMPLETED received, unconditionally starting VPN service")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 完全不依赖任何 Repository，直接启动服务
                val serviceIntent = Intent(context, VpnForegroundService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: failed to start service")
            } finally {
                pendingResult.finish()
            }
        }
    }
}