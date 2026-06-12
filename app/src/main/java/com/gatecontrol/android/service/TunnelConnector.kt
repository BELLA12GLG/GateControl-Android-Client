package com.gatecontrol.android.service

import com.gatecontrol.android.common.HostnameSanitizer
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HostnameReportRequest
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 所有入口点（VPN 界面、快捷磁贴、开机自启）共用的连接路径。
 *
 * 支持两种模式：
 *  - 服务器模式：拉取 split-tunnel preset、预解析 DNS、上报主机名
 *  - 纯 WireGuard 模式（[SetupRepository.isWireGuardOnlyMode]）：跳过所有服务器调用，
 *    直接用本地存储的 WireGuard 配置连接，不崩溃
 */
@Singleton
class TunnelConnector @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
) {

    suspend fun connectWithUserSettings(): Boolean {
        val config = setupRepository.getWireGuardConfig()
        if (config.isEmpty()) {
            Timber.w("TunnelConnector: no WireGuard config available")
            return false
        }

        // 纯 WireGuard 模式：跳过所有服务器交互
        if (setupRepository.isWireGuardOnlyMode()) {
            Timber.d("TunnelConnector: WireGuard-only mode — skipping server calls")
            return connectTunnel(config, SplitTunnelConfig(), serverUrl = "")
        }

        val serverUrl = setupRepository.getServerUrl()

        // 服务器模式：预解析 DNS（VPN 建立后系统 DNS 会改变）
        if (serverUrl.isNotEmpty()) {
            try {
                val host = java.net.URI(serverUrl).host
                if (host != null) apiClientProvider.preResolveDns(host)
            } catch (_: Exception) {}
        }

        val splitTunnelConfig = resolveSplitTunnelConfig(serverUrl)

        return connectTunnel(config, splitTunnelConfig, serverUrl)
    }

    private suspend fun connectTunnel(
        config: String,
        splitTunnelConfig: SplitTunnelConfig,
        serverUrl: String,
    ): Boolean {
        return try {
            tunnelManager.connect(config, splitTunnelConfig)
            Timber.d(
                "TunnelConnector: tunnel connect requested (mode=%s, %d networks, %d apps)",
                splitTunnelConfig.mode,
                splitTunnelConfig.networks.size,
                splitTunnelConfig.apps.size,
            )
            if (serverUrl.isNotEmpty()) reportDeviceHostname(serverUrl)
            true
        } catch (e: Exception) {
            Timber.e(e, "TunnelConnector: connect failed")
            false
        }
    }

    private suspend fun resolveSplitTunnelConfig(serverUrl: String): SplitTunnelConfig {
        var splitTunnelConfig = SplitTunnelConfig()
        try {
            var adminPresetActive = false
            if (serverUrl.isNotEmpty()) {
                try {
                    val client = apiClientProvider.getClient(serverUrl)
                    val preset = client.getSplitTunnelPreset()
                    if (preset.ok && preset.mode != "off" && preset.source != "none") {
                        settingsRepository.setSplitTunnelMode(preset.mode)
                        val arr = JSONArray()
                        preset.networks.forEach {
                            arr.put(JSONObject().put("cidr", it.cidr).put("label", it.label))
                        }
                        settingsRepository.setSplitTunnelNetworks(arr.toString())
                        settingsRepository.setSplitTunnelAdminLocked(preset.locked)
                        adminPresetActive = true

                        val userApps = settingsRepository.getSplitTunnelAppsV2().first()
                        splitTunnelConfig = SplitTunnelConfig(
                            mode = preset.mode,
                            networks = preset.networks.map { it.cidr },
                            apps = parseSplitAppsJson(userApps),
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Split-tunnel preset fetch failed")
                }
            }

            if (!adminPresetActive) {
                val mode = settingsRepository.getSplitTunnelMode().first()
                if (mode != "off") {
                    val networksJson = settingsRepository.getSplitTunnelNetworks().first()
                    val appsJson = settingsRepository.getSplitTunnelAppsV2().first()
                    splitTunnelConfig = SplitTunnelConfig(
                        mode = mode,
                        networks = parseSplitNetworksJsonToCidrs(networksJson),
                        apps = parseSplitAppsJson(appsJson),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Split-tunnel config load failed")
        }
        return splitTunnelConfig
    }

    private suspend fun reportDeviceHostname(serverUrl: String) {
        if (serverUrl.isEmpty()) return
        try {
            val sanitized = HostnameSanitizer.sanitize(android.os.Build.MODEL)
            if (sanitized.isNullOrBlank()) return
            val client = apiClientProvider.getClient(serverUrl)
            val response = client.reportHostname(HostnameReportRequest(sanitized))
            Timber.d("Hostname report: assigned=${response.assigned} changed=${response.changed}")
        } catch (e: Exception) {
            Timber.d(e, "Hostname report skipped: ${e.message}")
        }
    }

    private fun parseSplitNetworksJsonToCidrs(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("cidr") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse split-tunnel networks JSON, falling back to empty")
            emptyList()
        }
    }

    private fun parseSplitAppsJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("package") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse split-tunnel apps JSON, falling back to empty")
            emptyList()
        }
    }
}
