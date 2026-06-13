package com.gatecontrol.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.tunnel.PortRotator
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelMonitor
import com.gatecontrol.android.tunnel.TunnelStats
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps the VPN notification visible and runs
 * [TunnelMonitor] while the tunnel is active.
 *
 * 支持两种工作模式（通过 [EXTRA_AUTO_CONNECT] 区分）：
 *  - 服务器模式：由 VpnViewModel 负责连接，本服务只维护通知 + 监控
 *  - 纯 WireGuard 模式：开机自启时本服务直接调用 [TunnelConnector.connectWithUserSettings]，
 *    不依赖任何服务器接口（但会应用 split-tunnel 配置 — 见下方 fix #1）
 */
@AndroidEntryPoint
class VpnForegroundService : VpnService() {

    // ── Hilt 直接注入，不再依赖 TunnelStateHolder 静态持有 ─────────────────
    @Inject lateinit var tunnelManager: TunnelManager
    @Inject lateinit var setupRepository: SetupRepository
    // fix #1：开机自启路径必须通过 TunnelConnector 才能应用用户的 split-tunnel 配置。
    // 旧实现直接 `tunnelManager.connect(rawConfig, SplitTunnelConfig())` 传空配置，
    // 导致用户设的 exclude/include 规则全部丢失。
    @Inject lateinit var tunnelConnector: TunnelConnector

    companion object {
        const val CHANNEL_ID = "vpn_status"
        const val NOTIF_ID = 1001
        const val ACTION_DISCONNECT = "com.gatecontrol.android.ACTION_DISCONNECT"
        const val EXTRA_SERVER = "server"
        const val EXTRA_AUTO_CONNECT = "auto_connect"   // Boolean：是否在服务启动时直接连接隧道

        const val EXTRA_RX_BYTES = "rx_bytes"
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_CONNECTED_SINCE = "connected_since"
    }

    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var statsJob: Job? = null
    private var monitorJob: Job? = null
    private var autoConnectJob: Job? = null

    private var serverLabel: String = ""
    private var rxBytes: Long = 0L
    private var txBytes: Long = 0L
    private var connectedSinceMs: Long = 0L

    @Volatile private var latestStats: TunnelStats = TunnelStats()

    private var tunnelMonitor: TunnelMonitor? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Timber.d("VpnForegroundService: disconnect action received")
            onRevoke()
            return START_NOT_STICKY
        }

        serverLabel = intent?.getStringExtra(EXTRA_SERVER) ?: ""
        rxBytes = intent?.getLongExtra(EXTRA_RX_BYTES, 0L) ?: 0L
        txBytes = intent?.getLongExtra(EXTRA_TX_BYTES, 0L) ?: 0L
        connectedSinceMs = intent?.getLongExtra(EXTRA_CONNECTED_SINCE, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val shouldAutoConnect = intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) ?: false

        startForeground(NOTIF_ID, buildNotification())
        startStatsUpdater()

        if (shouldAutoConnect) {
            // 开机自启路径：直接连接隧道，无需 Activity / ViewModel
            startAutoConnect()
        } else {
            // 正常路径：VpnViewModel 负责连接，本服务只维护通知 + 监控
            startTunnelMonitor()
        }

        Timber.d("VpnForegroundService: started, server=$serverLabel autoConnect=$shouldAutoConnect")
        return START_STICKY
    }

    override fun onRevoke() {
        Timber.d("VpnForegroundService: revoked by system")
        stopAllJobs()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllJobs()
    }

    // ── 开机自启：直接连接隧道 ────────────────────────────────────────────

    private fun startAutoConnect() {
        autoConnectJob?.cancel()
        autoConnectJob = serviceScope.launch(Dispatchers.IO) {
            try {
                // ① 先检查 VPN 权限。
                //    prepare() 返回 null  → 权限已授予，可以直接连。
                //    prepare() 返回非 null → 权限未授予（或崩溃后被系统撤销），
                //    无法在没有 Activity 的情况下弹授权对话框，发通知提示用户手动打开一次 App。
                val prepareIntent = android.net.VpnService.prepare(this@VpnForegroundService)
                if (prepareIntent != null) {
                    Timber.w("VpnForegroundService: VPN permission not granted, cannot auto-connect")
                    showVpnPermissionNotification()
                    stopSelf()
                    return@launch
                }

                if (!setupRepository.hasWireGuardConfig()) {
                    Timber.w("VpnForegroundService: no WireGuard config available, cannot auto-connect")
                    stopSelf()
                    return@launch
                }

                Timber.d("VpnForegroundService: auto-connecting tunnel via TunnelConnector")
                // fix #1：通过 TunnelConnector 应用用户保存的 split-tunnel 配置
                // （旧代码这里是 `tunnelManager.connect(rawConfig, SplitTunnelConfig())`，
                // 永远传空配置，导致开机自启时分流失效）。
                val connected = tunnelConnector.connectWithUserSettings()
                if (!connected) {
                    Timber.w("VpnForegroundService: auto-connect aborted by TunnelConnector")
                    stopSelf()
                    return@launch
                }
                connectedSinceMs = System.currentTimeMillis()
                Timber.i("VpnForegroundService: tunnel auto-connected successfully")

                // 连接成功后启动监控
                startTunnelMonitor()
            } catch (e: Exception) {
                Timber.e(e, "VpnForegroundService: auto-connect failed")
            }
        }
    }

    /** 当开机自启时 VPN 权限已被撤销，发一条持久通知引导用户打开 App 重新授权。 */
    private fun showVpnPermissionNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 99, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_permission_required_title))
            .setContentText(getString(R.string.vpn_permission_required_body))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, notif)
    }

    // ── TunnelMonitor 集成 ────────────────────────────────────────────────────

    private fun startTunnelMonitor() {
        stopMonitor()

        // 使用 Hilt 注入的 tunnelManager（@AndroidEntryPoint 保证非 null）
        val tm = tunnelManager

        val portRotator: PortRotator? = try {
            val rawConfig = setupRepository.getWireGuardConfig()
            if (rawConfig.isNotEmpty()) {
                val originalPort = TunnelConfig.parse(rawConfig).getServerPort()
                PortRotator(originalPort)
            } else null
        } catch (e: Exception) {
            Timber.w(e, "VpnForegroundService: could not build PortRotator")
            null
        }

        val monitor = TunnelMonitor(
            trafficCheckIntervalMs = 5_000L,
            stallCyclesBeforeHandshakeCheck = 6,
            maxHandshakeAgeSec = 180L,
            maxReconnectAttempts = 10,
            failuresBeforeDisconnect = 3,
        )
        tunnelMonitor = monitor

        monitor.start(
            scope = serviceScope,
            statsProvider = { tm.getStatistics() },
            localStats = { latestStats },
            portRotator = portRotator,
        )

        monitorJob = serviceScope.launch {
            monitor.reconnectEvent.collect { req ->
                Timber.i(
                    "VpnForegroundService: forwarding reconnect request " +
                        "attempt=${req.attempt}/${req.maxAttempts} port=${req.suggestedPort}"
                )
                TunnelStateHolder.reconnectEvents.emit(req)
            }
        }

        Timber.d("VpnForegroundService: TunnelMonitor started (portRotator=${portRotator != null})")
    }

    private fun stopMonitor() {
        tunnelMonitor?.stop()
        tunnelMonitor = null
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun stopAllJobs() {
        stopMonitor()
        statsJob?.cancel()
        autoConnectJob?.cancel()
    }

    // ── 通知 ──────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_vpn),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_vpn)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = Intent(this, VpnForegroundService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val uptimeSec = (System.currentTimeMillis() - connectedSinceMs) / 1000
        val statsText = getString(
            R.string.notif_vpn_stats,
            Formatters.formatBytes(rxBytes),
            Formatters.formatBytes(txBytes),
            Formatters.formatDuration(uptimeSec),
        )
        val title = if (serverLabel.isNotEmpty()) {
            getString(R.string.notif_vpn_connected, serverLabel)
        } else {
            getString(R.string.vpn_connected)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statsText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.vpn_disconnect),
                disconnectPending,
            )
            .build()
    }

    private fun startStatsUpdater() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (isActive) {
                delay(1_000)
                tunnelManager.getStatistics()?.let { stats ->
                    latestStats = stats
                    rxBytes = stats.rxBytes
                    txBytes = stats.txBytes
                }
                nm.notify(NOTIF_ID, buildNotification())
            }
        }
    }
}
