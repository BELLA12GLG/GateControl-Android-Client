package com.gatecontrol.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
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
    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var apiClientProvider: ApiClientProvider

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.d("BootReceiver: BOOT_COMPLETED received")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val autoConnect = settingsRepository.getAutoConnect().first()

                // 两种工作模式都需要有 WireGuard 配置
                val hasWgConfig = setupRepository.hasWireGuardConfig()
                if (!autoConnect || !hasWgConfig) {
                    Timber.d("BootReceiver: auto-connect disabled or no WireGuard config, skipping")
                    pendingResult.finish()
                    return@launch
                }

                // 判断工作模式：
                //   服务器模式 — isConfigured() == true (有 serverUrl + apiToken)
                //   纯 WireGuard 模式 — 只有 wg_config，没有 serverUrl/token
                val serverMode = setupRepository.isConfigured()
                val serverUrl = if (serverMode) setupRepository.getServerUrl() else ""

                if (serverMode && serverUrl.isNotEmpty()) {
                    // ── 服务器模式：先验证 token，再决定是否连接 ────────────────
                    try {
                        val client = apiClientProvider.getClient(serverUrl)
                        client.ping()
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 401 || e.code() == 403) {
                            Timber.w("BootReceiver: token invalid (${e.code()}), skipping auto-connect")
                            pendingResult.finish()
                            return@launch
                        }
                        // 其他 HTTP 错误（5xx 等）允许离线自动连接
                    } catch (_: Exception) {
                        // 网络不通 — 允许离线自动连接（WireGuard 本身不需要服务器在线）
                    }
                }
                // 纯 WireGuard 模式：跳过所有服务器检查，直接连接

                Timber.d("BootReceiver: starting VPN (serverMode=$serverMode)")
                val serviceIntent = Intent(context, VpnForegroundService::class.java).apply {
                    putExtra(VpnForegroundService.EXTRA_SERVER, serverUrl)
                    // 使用字符串字面量，避免 Hilt kapt 生成顺序问题导致的编译错误
                    putExtra("auto_connect", true)
                }
                context.startForegroundService(serviceIntent)

            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: error during auto-connect")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
