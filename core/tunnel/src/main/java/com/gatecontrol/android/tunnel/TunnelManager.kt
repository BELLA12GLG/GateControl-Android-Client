package com.gatecontrol.android.tunnel

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.InetAddresses
import com.wireguard.config.InetNetwork
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunnelManager @Inject constructor(private val context: Context) {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var backend: Backend? = null
    private var tunnel: Tunnel? = null

    private var prevRxBytes: Long = 0L
    private var prevTxBytes: Long = 0L
    private var prevStatsTime: Long = 0L

    fun initialize() {
        try {
            backend = GoBackend(context)
            tunnel = object : Tunnel {
                override fun getName(): String = TUNNEL_NAME
                override fun onStateChange(newState: Tunnel.State) {
                    Timber.d("Tunnel state changed: $newState")
                }
            }
            Timber.d("TunnelManager initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize TunnelManager")
        }
    }

    /**
     * Legacy connect signature — kept for backward compatibility (BootReceiver, Tile, etc.).
     * Converts the old (routes, apps) parameters to a [SplitTunnelConfig] and delegates.
     */
    suspend fun connect(
        configString: String,
        splitTunnelRoutes: List<String> = emptyList(),
        excludedApps: List<String> = emptyList()
    ) {
        val splitConfig = if (splitTunnelRoutes.isNotEmpty() || excludedApps.isNotEmpty()) {
            SplitTunnelConfig(
                mode = "include",
                networks = splitTunnelRoutes,
                apps = excludedApps,
            )
        } else {
            SplitTunnelConfig() // mode = "off"
        }
        connectInternal(configString, splitConfig)
    }

    /**
     * New connect signature accepting a full [SplitTunnelConfig] with mode-aware routing.
     */
    suspend fun connect(configString: String, splitConfig: SplitTunnelConfig) {
        connectInternal(configString, splitConfig)
    }

    private suspend fun connectInternal(configString: String, splitConfig: SplitTunnelConfig) {
        withContext(Dispatchers.IO) {
            try {
                val parsedConfig = TunnelConfig.parse(configString)
                val wgConfig = buildWgConfig(parsedConfig, splitConfig)

                _state.value = TunnelState.Connecting
                Timber.d("Connecting tunnel with split-tunnel mode: ${splitConfig.mode}")

                // ── 修复 BackendException ────────────────────────────────────────
                // 问题：快速断开后立即重连，GoBackend 内部的 WireGuard Go 运行时
                //       尚未完全释放 VPN 文件描述符，再次调用 setState(UP) 就会
                //       在 setStateInternal(line 198) 抛出 BackendException。
                //
                // 修复策略：
                //  1. 连接前先显式发一次 setState(DOWN)，确保 Go 层状态归零。
                //     即使隧道已经是 DOWN，这个调用也是幂等且无害的。
                //  2. 给系统 300ms 释放底层 VPN 套接字，再执行 UP。
                //  3. 若 backend 为 null（首次或被重置），重新初始化。
                // ────────────────────────────────────────────────────────────────
                val currentBackend = backend ?: run {
                    initialize()
                    backend
                } ?: throw IllegalStateException("Backend not available")

                val currentTunnel = tunnel
                    ?: throw IllegalStateException("Tunnel not initialized")

                // 先强制下线，确保 Go 层干净
                try {
                    currentBackend.setState(currentTunnel, Tunnel.State.DOWN, null)
                } catch (ignored: Exception) {
                    // 已经是 DOWN 时会抛异常，忽略即可
                }
                // 等待系统释放 VPN 文件描述符
                delay(300)

                currentBackend.setState(currentTunnel, Tunnel.State.UP, wgConfig)

                prevRxBytes = 0L
                prevTxBytes = 0L
                prevStatsTime = System.currentTimeMillis()

                _state.value = TunnelState.Connected()
                Timber.i("Tunnel connected successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect tunnel")
                // 连接失败时重置 backend，下次重连强制重新初始化 GoBackend
                // 避免残留的错误状态导致后续连接持续失败
                backend = null
                _state.value = TunnelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                _state.value = TunnelState.Disconnecting
                Timber.d("Disconnecting tunnel...")

                val currentBackend = backend
                val currentTunnel = tunnel

                if (currentBackend != null && currentTunnel != null) {
                    currentBackend.setState(currentTunnel, Tunnel.State.DOWN, null)
                }

                _stats.value = TunnelStats()
                prevRxBytes = 0L
                prevTxBytes = 0L
                prevStatsTime = 0L

                // 重置 backend，下次 connect() 会重新初始化 GoBackend。
                // 这样可避免快速断开/重连时 Go 运行时持有旧 VPN fd 导致的 BackendException。
                backend = null

                _state.value = TunnelState.Disconnected
                Timber.i("Tunnel disconnected")
            } catch (e: Exception) {
                Timber.e(e, "Failed to disconnect tunnel")
                backend = null  // 即使断开失败也重置，防止后续连接用到损坏的实例
                _state.value = TunnelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun getStatistics(): TunnelStats? {
        return try {
            val currentBackend = backend ?: return null
            val currentTunnel = tunnel ?: return null

            if (_state.value !is TunnelState.Connected) return null

            val statistics = currentBackend.getStatistics(currentTunnel)
            val now = System.currentTimeMillis()
            val elapsedSec = if (prevStatsTime > 0) (now - prevStatsTime) / 1000.0 else 1.0

            val totalRx = statistics.totalRx()
            val totalTx = statistics.totalTx()

            // Get latest handshake from peer statistics via reflection
            // (API varies across WireGuard library versions)
            var latestHandshake = 0L
            try {
                val peersMethod = statistics.javaClass.getMethod("peers")
                val peerKeys = peersMethod.invoke(statistics) as? Set<*>
                peerKeys?.forEach { key ->
                    try {
                        val peerMethod = statistics.javaClass.getMethod("peer", key!!.javaClass)
                        val peerStats = peerMethod.invoke(statistics, key)
                        if (peerStats != null) {
                            val hsField = peerStats.javaClass.getField("latestHandshakeEpochMillis")
                            val hs = hsField.getLong(peerStats)
                            if (hs > latestHandshake) latestHandshake = hs
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) {
                Timber.d("Handshake timestamp not available from Statistics API")
                // Reflection failed — we have no handshake data.
                // Return null so TunnelMonitor skips Tier 2 entirely this cycle.
                // Health detection falls back to Tier 1 (rx traffic check only).
                return null
            }

            val rxSpeed = if (elapsedSec > 0) ((totalRx - prevRxBytes) / elapsedSec).toLong() else 0L
            val txSpeed = if (elapsedSec > 0) ((totalTx - prevTxBytes) / elapsedSec).toLong() else 0L

            prevRxBytes = totalRx
            prevTxBytes = totalTx
            prevStatsTime = now

            val tunnelStats = TunnelStats(
                rxBytes = totalRx,
                txBytes = totalTx,
                rxSpeed = maxOf(0L, rxSpeed),
                txSpeed = maxOf(0L, txSpeed),
                lastHandshakeEpoch = latestHandshake / 1000
            )

            _stats.value = tunnelStats
            tunnelStats
        } catch (e: Exception) {
            Timber.e(e, "Failed to get tunnel statistics")
            null
        }
    }

    fun isConnected(): Boolean = _state.value is TunnelState.Connected

    private fun buildWgConfig(
        parsed: TunnelConfig,
        splitConfig: SplitTunnelConfig,
    ): Config {
        // Effective DNS list:
        //   1. If user supplied DNS overrides, use those.
        //   2. Otherwise use whatever the WG config specified.
        // Then filter by IP-family if user picked "ipv4_only" / "ipv6_only".
        val rawDns = if (splitConfig.dnsServers.isNotEmpty()) {
            splitConfig.dnsServers
        } else {
            parsed.dns
        }
        val effectiveDns = NetworkPrefsHelpers.filterDnsByProtocol(rawDns, splitConfig.ipProtocol)

        val ifaceBuilder = Interface.Builder()
            .parsePrivateKey(parsed.privateKey)
            .parseAddresses(parsed.address)

        effectiveDns.forEach { dns ->
            ifaceBuilder.parseDnsServers(dns)
        }
        parsed.mtu?.let { ifaceBuilder.setMtu(it) }

        // App filtering — excludeApplications and includeApplications are mutually exclusive
        when (splitConfig.mode) {
            "exclude" -> {
                if (splitConfig.apps.isNotEmpty()) {
                    ifaceBuilder.excludeApplications(splitConfig.apps.toSet())
                }
            }
            "include" -> {
                if (splitConfig.apps.isNotEmpty()) {
                    ifaceBuilder.includeApplications(splitConfig.apps.toSet())
                }
            }
            // "off" — no app filtering
        }

        // Endpoint resolution honoring IP-family preference.
        //
        // The WireGuard library (parseEndpoint) accepts "host:port" and does
        // its own DNS resolution internally — but it has no concept of
        // "IPv6 only". To enforce an IP family we pre-resolve the host
        // ourselves, pick the right address, and pass the literal IP back.
        // For "auto" we leave the original string so behavior is unchanged.
        val effectiveEndpoint = resolveEndpoint(parsed.endpoint, splitConfig.ipProtocol)

        val peerBuilder = Peer.Builder()
            .parsePublicKey(parsed.publicKey)
            .parseEndpoint(effectiveEndpoint)

        parsed.presharedKey?.let { peerBuilder.parsePreSharedKey(it) }
        parsed.persistentKeepalive?.let { peerBuilder.setPersistentKeepalive(it) }

        // DNS IPs as /32 (or /128 for IPv6) — always included to prevent DNS leaks
        val dnsIps = effectiveDns.map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.contains(":")) "$it/128" else "$it/32" }

        val allowedIpsRaw = when (splitConfig.mode) {
            "exclude" -> {
                if (splitConfig.networks.isEmpty()) {
                    // No networks excluded — full tunnel (use original AllowedIPs)
                    NetworkPrefsHelpers.filterAllowedIpsByProtocol(parsed.allowedIps, splitConfig.ipProtocol)
                } else {
                    // Compute complement: 0.0.0.0/0 minus excluded networks (IPv4)
                    val complement = CidrComplement.computeAllowedIps(splitConfig.networks)
                    // Always include ::/0 to prevent IPv6 leaks — exclude mode means
                    // "everything through VPN except these networks", so IPv6 must also
                    // be tunneled. Also add DNS + VPN subnet to prevent DNS leaks.
                    val combined = (complement + listOf("::/0") + dnsIps + VPN_SUBNET).distinct()
                    NetworkPrefsHelpers.filterCidrsByProtocol(combined, splitConfig.ipProtocol).joinToString(",")
                }
            }
            "include" -> {
                // Only route specified networks + DNS + VPN subnet
                val combined = (splitConfig.networks + dnsIps + VPN_SUBNET).distinct()
                NetworkPrefsHelpers.filterCidrsByProtocol(combined, splitConfig.ipProtocol).joinToString(",")
            }
            else -> {
                // Off — use original AllowedIPs from WG config
                NetworkPrefsHelpers.filterAllowedIpsByProtocol(parsed.allowedIps, splitConfig.ipProtocol)
            }
        }
        peerBuilder.parseAllowedIPs(allowedIpsRaw)

        return Config.Builder()
            .setInterface(ifaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    /**
     * Resolve the WireGuard endpoint "host:port" honoring [ipProtocol].
     *
     * - "auto": return [original] unchanged — let WireGuard / Android pick.
     * - "ipv6_preferred": resolve [original] and pick the first IPv6 address;
     *   fall back to IPv4 if no IPv6 reachable.
     * - "ipv4_only": resolve and reject if no IPv4 found.
     * - "ipv6_only": resolve and reject if no IPv6 found.
     *
     * On any resolution failure we fall back to the original string so the
     * WireGuard library still has a chance — better to attempt connect than
     * fail before we ever sent a packet.
     */
    private fun resolveEndpoint(original: String, ipProtocol: String): String {
        if (ipProtocol == "auto") return original
        // Parse host and port — endpoint may be host:port, [v6]:port, or v6:port (rare).
        val (host, port) = NetworkPrefsHelpers.splitHostPort(original) ?: return original

        return try {
            val addresses = InetAddress.getAllByName(host).toList()
            val v6 = addresses.filterIsInstance<java.net.Inet6Address>()
            val v4 = addresses.filterIsInstance<java.net.Inet4Address>()

            val picked: InetAddress? = when (ipProtocol) {
                "ipv4_only" -> v4.firstOrNull()
                "ipv6_only" -> v6.firstOrNull()
                "ipv6_preferred" -> v6.firstOrNull() ?: v4.firstOrNull()
                else -> null
            }
            if (picked == null) {
                Timber.w(
                    "Endpoint %s has no address matching protocol=%s — using original",
                    host, ipProtocol,
                )
                return original
            }
            val ipString = picked.hostAddress ?: return original
            // Bracket IPv6 in the endpoint string so parseEndpoint splits correctly.
            if (picked is java.net.Inet6Address) "[$ipString]:$port" else "$ipString:$port"
        } catch (e: Exception) {
            Timber.w(e, "Endpoint resolve failed for %s, falling back to original", host)
            original
        }
    }

    companion object {
        private const val TUNNEL_NAME = "gatecontrol"
        private const val VPN_SUBNET = "10.8.0.0/24"
    }
}
