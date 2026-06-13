package com.gatecontrol.android.ui.vpn

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.ui.components.ios.BigToggleState
import com.gatecontrol.android.ui.components.ios.IosBigToggle
import com.gatecontrol.android.ui.components.ios.IosLazyScreenScaffold
import com.gatecontrol.android.ui.components.ios.IosNavigationRow
import com.gatecontrol.android.ui.components.ios.IosListSection
import com.gatecontrol.android.ui.components.ios.IosStatCard
import com.gatecontrol.android.ui.components.ios.IosStatGrid
import com.gatecontrol.android.ui.components.ios.IosToggleRow
import com.gatecontrol.android.ui.components.ios.IosValueRow
import com.gatecontrol.android.ui.theme.IosTileBlue
import com.gatecontrol.android.ui.theme.IosTileGreen
import com.gatecontrol.android.ui.theme.IosTileIndigo
import com.gatecontrol.android.ui.theme.IosTileOrange
import com.gatecontrol.android.ui.theme.IosTilePurple
import com.gatecontrol.android.ui.theme.IosTileRed
import com.gatecontrol.android.ui.theme.IosTileTeal
import kotlinx.coroutines.delay

/**
 * VPN home screen — iOS-style grouped-list layout.
 *
 * Top of the screen is the large connection [IosBigToggle] (status + Switch).
 * Below it sit short grouped-list sections:
 *
 *   1. Transfer stats (4-cell grid: download, upload, down speed, up speed)
 *   2. Connection details (server, port, uptime)
 *   3. Network section (split-tunnel link + kill switch toggle + DNS test row)
 *
 * Bandwidth graph and the detailed traffic-usage breakdown are kept as
 * optional rows but only render when both data and permission are present.
 */
@Composable
fun VpnScreen(
    viewModel: VpnViewModel = hiltViewModel(),
    onTokenInvalid: () -> Unit = {},
    onNavigateToSplitTunnel: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val vpnPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.connect()
        }
    }

    val tunnelState by viewModel.tunnelState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val trafficUsage by viewModel.trafficUsage.collectAsState()
    val permissions by viewModel.permissions.collectAsState()
    val killSwitchEnabled by viewModel.killSwitchEnabled.collectAsState()
    val activePort by viewModel.activePort.collectAsState()

    var showRotatePortDialog by remember { mutableStateOf(false) }

    // Bandwidth history ring buffers
    val rxHistory = remember { mutableStateListOf<Long>() }
    val txHistory = remember { mutableStateListOf<Long>() }

    LaunchedEffect(tunnelState) {
        if (tunnelState is TunnelState.Connected) {
            while (true) {
                delay(1_000)
                if (rxHistory.size >= 60) rxHistory.removeAt(0)
                if (txHistory.size >= 60) txHistory.removeAt(0)
                rxHistory.add(stats.rxSpeed)
                txHistory.add(stats.txSpeed)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startMonitoring()
        viewModel.validateToken()
    }

    val tokenInvalid by viewModel.tokenInvalid.collectAsState()
    LaunchedEffect(tokenInvalid) { if (tokenInvalid) onTokenInvalid() }

    val peerDisabled by viewModel.peerDisabled.collectAsState()
    LaunchedEffect(peerDisabled) {
        if (peerDisabled) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.peer_disabled_by_server),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(tunnelState) {
        if (tunnelState is TunnelState.Connected) {
            delay(2_000)
            viewModel.invalidateApiClients()
            viewModel.loadPermissions()
            viewModel.loadTrafficStats()
        } else if (tunnelState is TunnelState.Disconnected) {
            viewModel.invalidateApiClients()
            viewModel.loadPermissions()
            viewModel.loadTrafficStats()
        }
    }

    // Tick every second to update connection duration
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(tunnelState) {
        if (tunnelState is TunnelState.Connected) {
            while (true) {
                delay(1_000)
                tick = System.currentTimeMillis()
            }
        }
    }

    val isConnected = tunnelState is TunnelState.Connected
    val isBusy = tunnelState is TunnelState.Connecting
        || tunnelState is TunnelState.Disconnecting
        || tunnelState is TunnelState.Reconnecting

    val bigToggleState = when {
        isConnected -> BigToggleState.Connected
        isBusy -> BigToggleState.Connecting
        else -> BigToggleState.Disconnected
    }

    val connectedSince = (tunnelState as? TunnelState.Connected)?.connectedSince ?: 0L
    val uptimeSeconds = if (connectedSince > 0) {
        @Suppress("UNUSED_EXPRESSION") tick // force recompose
        (System.currentTimeMillis() - connectedSince) / 1000
    } else 0L
    val uptimeText = if (uptimeSeconds > 0) Formatters.formatDuration(uptimeSeconds) else "—"

    val toggleTitle = when (bigToggleState) {
        BigToggleState.Connected -> stringResource(R.string.vpn_state_connected)
        BigToggleState.Connecting -> stringResource(R.string.vpn_state_connecting)
        BigToggleState.Disconnected -> stringResource(R.string.vpn_state_disconnected)
    }
    val toggleSubtitle = when (bigToggleState) {
        BigToggleState.Connected -> {
            val server = viewModel.serverHost ?: stringResource(R.string.vpn_server)
            if (uptimeSeconds > 0) "$server · $uptimeText" else server
        }
        BigToggleState.Connecting -> stringResource(R.string.vpn_state_connecting_subtitle)
        BigToggleState.Disconnected -> stringResource(R.string.vpn_state_disconnected_subtitle)
    }

    IosLazyScreenScaffold(title = stringResource(R.string.nav_vpn)) {
        item { Spacer(Modifier.height(8.dp)) }

        // ── Big toggle (status hero) ──
        item {
            IosBigToggle(
                state = bigToggleState,
                title = toggleTitle,
                subtitle = toggleSubtitle,
                checked = isConnected,
                onCheckedChange = { newChecked ->
                    if (newChecked) {
                        val prepareIntent = android.net.VpnService.prepare(context)
                        if (prepareIntent != null) {
                            vpnPermissionLauncher.launch(prepareIntent)
                        } else {
                            viewModel.connect()
                        }
                    } else {
                        viewModel.disconnect()
                    }
                },
            )
        }

        // ── Transfer stats (only show if connected or have non-zero data) ──
        val hasStats = isConnected || stats.rxBytes > 0 || stats.txBytes > 0
        if (hasStats) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                IosStatGrid {
                    cell { mod ->
                        IosStatCard(
                            label = stringResource(R.string.vpn_received),
                            value = Formatters.formatBytes(stats.rxBytes),
                            icon = Icons.Filled.ArrowDownward,
                            iconColor = IosTileGreen,
                            modifier = mod,
                        )
                    }
                    cell { mod ->
                        IosStatCard(
                            label = stringResource(R.string.vpn_sent),
                            value = Formatters.formatBytes(stats.txBytes),
                            icon = Icons.Filled.ArrowUpward,
                            iconColor = IosTileBlue,
                            modifier = mod,
                        )
                    }
                    cell { mod ->
                        IosStatCard(
                            label = stringResource(R.string.vpn_down_speed),
                            value = Formatters.formatSpeed(stats.rxSpeed),
                            icon = Icons.Filled.Bolt,
                            iconColor = IosTileOrange,
                            modifier = mod,
                        )
                    }
                    cell { mod ->
                        IosStatCard(
                            label = stringResource(R.string.vpn_up_speed),
                            value = Formatters.formatSpeed(stats.txSpeed),
                            icon = Icons.Filled.Bolt,
                            iconColor = IosTilePurple,
                            modifier = mod,
                        )
                    }
                }
            }
        }

        // ── Bandwidth graph (only while connected) ──
        if (isConnected) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                BandwidthGraph(
                    rxHistory = rxHistory.toList(),
                    txHistory = txHistory.toList(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ── Connection details ──
        if (isConnected) {
            item {
                IosListSection(header = stringResource(R.string.vpn_section_connection)) {
                    IosValueRow(
                        title = stringResource(R.string.vpn_server),
                        value = viewModel.serverHost ?: "—",
                        icon = Icons.Filled.Cloud,
                        iconBg = IosTileBlue,
                        showDivider = true,
                    )
                    IosNavigationRow(
                        title = stringResource(R.string.vpn_port),
                        icon = Icons.Filled.Shield,
                        iconBg = IosTileIndigo,
                        trailingText = activePort?.toString() ?: "—",
                        showDivider = true,
                        onClick = { showRotatePortDialog = true },
                    )
                    IosValueRow(
                        title = stringResource(R.string.vpn_handshake),
                        value = uptimeText,
                        icon = Icons.Filled.Schedule,
                        iconBg = IosTileTeal,
                    )
                }
            }
        }

        // ── Traffic usage card (server-provided quota) ──
        if (permissions.traffic && trafficUsage != null) {
            item {
                TrafficUsage(
                    traffic = trafficUsage,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // ── Network section ──
        item {
            IosListSection(header = stringResource(R.string.vpn_section_network)) {
                IosNavigationRow(
                    title = stringResource(R.string.split_tunnel_title),
                    icon = Icons.Filled.Route,
                    iconBg = IosTileGreen,
                    showDivider = true,
                    onClick = onNavigateToSplitTunnel,
                )
                IosToggleRow(
                    title = stringResource(R.string.vpn_kill_switch),
                    icon = Icons.Filled.Lock,
                    iconBg = IosTileRed,
                    checked = killSwitchEnabled,
                    onCheckedChange = { viewModel.toggleKillSwitch(it) },
                    showDivider = permissions.dns,
                )
                if (permissions.dns) {
                    var dnsTestResult by remember { mutableStateOf<String?>(null) }
                    var dnsTestLoading by remember { mutableStateOf(false) }
                    IosNavigationRow(
                        title = if (dnsTestLoading) stringResource(R.string.dns_testing)
                                else stringResource(R.string.dns_leak_test),
                        icon = Icons.Filled.Dns,
                        iconBg = IosTilePurple,
                        trailingText = dnsTestResult,
                        onClick = {
                            if (!dnsTestLoading) {
                                dnsTestLoading = true
                                viewModel.runDnsLeakTest { result ->
                                    dnsTestResult = result
                                    dnsTestLoading = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showRotatePortDialog) {
        AlertDialog(
            onDismissRequest = { showRotatePortDialog = false },
            title = { Text(stringResource(R.string.port_rotate_title)) },
            text  = { Text(stringResource(R.string.port_rotate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showRotatePortDialog = false
                    viewModel.manualRotatePort()
                }) {
                    Text(
                        text = stringResource(R.string.port_rotate_confirm),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRotatePortDialog = false }) {
                    Text(stringResource(R.string.port_rotate_cancel))
                }
            },
        )
    }
}
