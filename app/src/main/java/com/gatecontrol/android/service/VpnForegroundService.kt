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
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelMonitor
import com.gatecontrol.android.tunnel.TunnelStats
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 *
 * ## v6: monitor → coordinator wiring
 *
 * The Monitor used to emit a chain of disconnect/reconnect events that
 * VpnViewModel consumed via a global SharedFlow on [TunnelStateHolder].
 * That made two-process coordination implicit and a real bug (the
 * disconnectEvent had no collector).
 *
 * Now the Monitor only emits ONE [TunnelMonitor.StallEvent] when it
 * detects a stall. The service collects it and calls
 * [PortRotationCoordinator.connectWithRetry] directly — the ViewModel is
 * not in the loop because it may not be alive when a Doze-period stall
 * happens. The coordinator is a process-singleton, so any state it owns
 * (activePort, persisted last-good port) is shared with the ViewModel
 * when the UI returns.
 */
@AndroidEntryPoint
class VpnForegroundService : VpnService() {

    @Inject lateinit var tunnelManager: TunnelManager
    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    // fix #1：开机自启路径必须通过 TunnelConnector 才能应用用户的 split-tunnel 配置。
    @Inject lateinit var tunnelConnector: TunnelConnector
    // v6: monitor → coordinator wiring lives here.
    @Inject lateinit var portRotationCoordinator: PortRotationCoordinator

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
        // v6.2: keep monitor alive across reconnects. If the auto-reconnect
        // chain fails and the user later re-establishes the tunnel by hand,
        // we still need health checks.
        startStateObserver()
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

        val tm = tunnelManager
        val monitor = TunnelMonitor(
            trafficCheckIntervalMs = 5_000L,
            stallCyclesBeforeHandshakeCheck = 6,
            maxHandshakeAgeSec = 180L,
        )
        tunnelMonitor = monitor

        monitor.start(
            scope = serviceScope,
            statsProvider = { tm.getStatistics() },
            localStats = { latestStats },
        )

        // When the monitor detects a stall, run a connect-with-retry through
        // the coordinator. The coordinator is the single source of truth for
        // port state, so the same flow works whether the ViewModel is alive
        // (UI in foreground) or not (Doze, screen-off, etc.).
        //
        // v6.2: we use `first()` rather than `collect { }` because the monitor
        // emits at most ONE stall event before stopping itself. Using collect
        // and then calling startTunnelMonitor() inside the lambda would
        // self-cancel the current collect job (stopMonitor cancels monitorJob,
        // and we ARE monitorJob), throwing a stray CancellationException.
        // Taking the single event and exiting cleanly lets the next
        // startTunnelMonitor() reach steady state without self-cancel.
        monitorJob = serviceScope.launch {
            val event = monitor.stallEvents.first()
            Timber.i("VpnForegroundService: monitor stall reason=${event.reason}")
            handleMonitorStall()
        }

        Timber.d("VpnForegroundService: TunnelMonitor started")
    }

    /**
     * Disconnect, then run connect-with-retry. Restart the monitor after the
     * tunnel is re-established so it keeps watching the new connection.
     *
     * The 300 ms post-disconnect delay matches what was in v1 — GoBackend's
     * VPN file descriptor needs a moment to fully release before we can
     * setState(UP) again, otherwise it throws BackendException.
     *
     * ## v6.2 fix: exclude the stalling port
     *
     * Without excludePorts, when the persisted port equals the stalling
     * port (the common case — last-successful is whatever we were just
     * using), Coordinator's preferred-first logic would retry the same
     * port that just failed. We capture the active port BEFORE disconnect
     * (disconnect nulls it) and pass it as excludePorts so the strategy
     * picks something genuinely different.
     */
    private suspend fun handleMonitorStall() {
        // Capture BEFORE disconnect — coordinator.activePort goes null
        // once we tear down.
        val stallingPort = portRotationCoordinator.activePort.value
        val excludeSet = if (stallingPort != null) setOf(stallingPort) else emptySet()
        Timber.i(
            "VpnForegroundService: monitor stall on port=$stallingPort; will exclude from retry"
        )

        try { tunnelManager.disconnect() } catch (_: Exception) {}
        delay(300L)

        val rawConfig = setupRepository.getWireGuardConfig()
        if (rawConfig.isEmpty()) return

        val serverUrl = setupRepository.getServerUrl()
        val splitConfig = tunnelConnector.resolveSplitTunnelConfig(serverUrl)

        // Pass the persisted port as preferredFirstPort even if it equals
        // the stalling port — the excludePorts machinery will skip it,
        // and if a future Coordinator change un-skips it, that's a logged
        // behavior we'd notice rather than silent wrong-port retry.
        val persisted = settingsRepository.getLastSuccessfulPort().first().takeIf { it != 0 }
        val outcome = portRotationCoordinator.connectWithRetry(
            rawConfig = rawConfig,
            splitConfig = splitConfig,
            preferredFirstPort = persisted,
            excludePorts = excludeSet,
        )
        when (outcome) {
            is PortRotationCoordinator.Outcome.Connected -> {
                // Re-arm the monitor on the new tunnel. We re-arm even if
                // the rotation didn't actually change the port (could happen
                // if persisted != active and persisted succeeded) so the
                // tunnel stays observed.
                startTunnelMonitor()
            }
            else -> {
                Timber.w("VpnForegroundService: monitor-triggered reconnect failed — $outcome")
                // The tunnel is now disconnected. We don't restart the
                // monitor because there's nothing to monitor — but we do
                // need to make sure that when the user (or auto-connect)
                // brings the tunnel back up, the monitor resumes. That's
                // handled by the tunnelManager.state observer in
                // [startStateObserver] (see below).
            }
        }
    }

    /**
     * v6.2: Observe the global tunnel state. When the tunnel transitions
     * to [TunnelState.Connected] but no monitor is currently running,
     * start one. This covers the case where [handleMonitorStall] failed
     * and the user later brings the tunnel back up manually — without
     * this observer, the new tunnel would have no health checks.
     */
    private fun startStateObserver() {
        serviceScope.launch {
            tunnelManager.state.collect { state ->
                if (state is com.gatecontrol.android.tunnel.TunnelState.Connected
                    && tunnelMonitor == null
                ) {
                    Timber.d("VpnForegroundService: tunnel reconnected; re-arming monitor")
                    startTunnelMonitor()
                }
            }
        }
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
