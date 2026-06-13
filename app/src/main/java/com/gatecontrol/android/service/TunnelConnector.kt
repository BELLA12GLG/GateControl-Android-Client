package com.gatecontrol.android.service

import com.gatecontrol.android.common.HostnameSanitizer
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HostnameReportRequest
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * 此类是 split-tunnel 配置解析的**唯一**权威实现。其他入口点（[VpnForegroundService] 的
 * 自启路径、[com.gatecontrol.android.ui.vpn.VpnViewModel] 的手动 connect 路径）都必须
 * 通过 [resolveSplitTunnelConfig] 获取配置，而不是各自复制一份解析逻辑。
 */
@Singleton
class TunnelConnector @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
) {

    /**
     * 防止 connect 流程被并发触发（开机自启 + Tile 同时点击等场景）。
     * resolveSplitTunnelConfig 内部写 DataStore，并发会造成 last-write-wins 覆盖。
     */
    private val connectMutex = Mutex()

    /** V1→V2 迁移只运行一次，由 [ensureSplitTunnelMigrated] 守护。 */
    @Volatile
    private var migrationDone = false

    suspend fun connectWithUserSettings(): Boolean = connectMutex.withLock {
        val config = setupRepository.getWireGuardConfig()
        if (config.isEmpty()) {
            Timber.w("TunnelConnector: no WireGuard config available")
            return@withLock false
        }

        // 纯 WireGuard 模式：跳过所有服务器交互
        if (setupRepository.isWireGuardOnlyMode()) {
            Timber.d("TunnelConnector: WireGuard-only mode — skipping server calls")
            val splitTunnelConfig = resolveSplitTunnelConfig(serverUrl = "")
            return@withLock connectTunnel(config, splitTunnelConfig, serverUrl = "")
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

        return@withLock connectTunnel(config, splitTunnelConfig, serverUrl)
    }

    /**
     * 解析当前用户的 split-tunnel 配置。**所有**连接路径都应通过此方法获取配置，
     * 不要再复制实现。
     *
     * 在解析前会先触发一次性的 V1→V2 迁移（[SettingsRepository.migrateSplitTunnelIfNeeded]），
     * 确保从旧版本升级的用户的 split-tunnel 设置不会因为没打开过 Settings 页面而丢失。
     *
     * @param serverUrl 服务器 URL；空字符串表示纯 WireGuard 模式，跳过服务器 preset 拉取。
     */
    suspend fun resolveSplitTunnelConfig(serverUrl: String): SplitTunnelConfig {
        ensureSplitTunnelMigrated()

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

    /**
     * 在 connect 路径上确保 V1→V2 split-tunnel 迁移已执行。
     *
     * 旧版本只在 [com.gatecontrol.android.ui.settings.SettingsViewModel] 初始化时调用
     * [SettingsRepository.migrateSplitTunnelIfNeeded]，导致从未打开 Settings 页面的
     * 用户在升级后丢失分流配置（mode 默认为 "off"）。这里在所有 connect 入口都先
     * 触发迁移，且通过 [migrationDone] 与 mutex 双重幂等：
     *  - mutex 保证同一进程内只有一个 connect 在跑；
     *  - migrationDone 标志避免每次 connect 都触发 DataStore 写事务；
     *  - migrateSplitTunnelIfNeeded 本身的条件 (`oldEnabled != null && newMode == null`)
     *    保证即使被多次调用也不会重复迁移或破坏已有 v2 数据。
     */
    private suspend fun ensureSplitTunnelMigrated() {
        if (migrationDone) return
        try {
            settingsRepository.migrateSplitTunnelIfNeeded()
        } catch (e: Exception) {
            Timber.w(e, "Split-tunnel V1→V2 migration failed (will retry on next connect)")
            return // 不要设 migrationDone，下次再试
        }
        migrationDone = true
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
