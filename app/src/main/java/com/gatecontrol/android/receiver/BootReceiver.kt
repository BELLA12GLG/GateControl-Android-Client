package com.gatecontrol.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.core.CorruptionException
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.service.VpnForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.d("BootReceiver: BOOT_COMPLETED received")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 读取用户的自动连接开关，捕获 DataStore 损坏异常
                val autoConnect = try {
                    settingsRepository.getAutoConnect().first()
                } catch (e: CorruptionException) {
                    Timber.e(e, "DataStore corrupted, autoConnect defaults to false")
                    false
                }

                if (autoConnect) {
                    Timber.d("BootReceiver: auto-connect enabled, starting VpnForegroundService")
                    val serviceIntent = Intent(context, VpnForegroundService::class.java)
                    context.startForegroundService(serviceIntent)
                } else {
                    Timber.d("BootReceiver: auto-connect disabled, skipping")
                }
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: error during auto-connect")
            } finally {
                pendingResult.finish()
            }
        }
    }
}