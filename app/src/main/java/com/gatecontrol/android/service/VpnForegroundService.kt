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
import com.gatecontrol.android.tunnel.PortRotator
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelMonitor
import com.gatecontrol.android.tunnel.TunnelStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service that keeps the VPN notification visible and runs
 * [TunnelMonitor] while the tunnel is active.
 *
 * Responsibilities:
 *  - Show the persistent VPN notification with live stats
 *  - Run [TunnelMonitor] with the two-tier detection strategy (local traffic
 *    fast-check every 5 s → handshake stale-check every ~30 s)
 *  - Forward [TunnelMonitor.reconnectEvent] to [TunnelStateHolder.reconnectEvents]
 *    so [VpnViewModel] can handle port-swap + reconnect when it is active
 *
 * The service survives screen-off because it is a foreground service, so
 * the monitor loop keeps running even when the ViewModel is destroyed.
 */
class VpnForegroundService : VpnService() {

    companion object {
        const val CHANNEL_ID = "vpn_status"
        const val NOTIF_ID = 1001
        const val ACTION_DISCONNECT = "com.gatecontrol.android.ACTION_DISCONNECT"
        const val EXTRA_SERVER = "server"

        const val EXTRA_RX_BYTES = "rx_bytes"
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_CONNECTED_SINCE = "connected_since"
    }

    // ── Coroutine scope — survives screen-off as a foreground service ──────────
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var statsJob: Job? = null
    private var monitorJob: Job? = null

    private var serverLabel: String = ""
    private var rxBytes: Long = 0L
    private var txBytes: Long = 0L
    private var connectedSinceMs: Long = 0L

    /** Shared most-recent stats snapshot, written by the 1-s stats job. */
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

        startForeground(NOTIF_ID, buildNotification())
        startStatsUpdater()
        startTunnelMonitor()

        Timber.d("VpnForegroundService: started, server=$serverLabel")
        return START_STICKY
    }

    override fun onRevoke() {
        Timber.d("VpnForegroundService: revoked by system")
        stopMonitor()
        statsJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitor()
        statsJob?.cancel()
    }

    // ── TunnelMonitor integration ─────────────────────────────────────────────

    private fun startTunnelMonitor() {
        stopMonitor()

        val tm = TunnelStateHolder.tunnelManager ?: run {
            Timber.w("VpnForegroundService: TunnelManager not available — monitor not started")
            return
        }

        // Build a PortRotator from the stored config's original port
        val portRotator: PortRotator? = try {
            val rawConfig = TunnelStateHolder.setupRepository?.getWireGuardConfig() ?: ""
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
            stallCyclesBeforeHandshakeCheck = 6,   // 6 × 5 s = 30 s before escalating
            maxHandshakeAgeSec = 180L,
            maxReconnectAttempts = 10,
            failuresBeforeDisconnect = 3,
        )
        tunnelMonitor = monitor

        monitor.start(
            scope = serviceScope,
            // Tier 2: suspending backend query (used only when traffic is idle)
            statsProvider = { tm.getStatistics() },
            // Tier 1: in-memory snapshot — zero network I/O
            localStats = { latestStats },
            portRotator = portRotator,
        )

        // Forward reconnect events to TunnelStateHolder so the ViewModel can
        // handle them (port-swap + connect). When the ViewModel is not active
        // the tryEmit call is non-blocking — the event may be dropped, which
        // is acceptable because the monitor will emit another after the next
        // back-off delay.
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

    // ── Notification ──────────────────────────────────────────────────────────

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
            val tm = TunnelStateHolder.tunnelManager
            val nm = getSystemService(NotificationManager::class.java)
            while (isActive) {
                delay(1_000)
                // Update in-memory snapshot used by TunnelMonitor Tier 1 check
                if (tm != null) {
                    tm.getStatistics()?.let { stats ->
                        latestStats = stats
                        rxBytes = stats.rxBytes
                        txBytes = stats.txBytes
                    }
                }
                nm.notify(NOTIF_ID, buildNotification())
            }
        }
    }
}
