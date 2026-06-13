package com.gatecontrol.android.ui.settings

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.ios.IosColoredIconTile
import com.gatecontrol.android.ui.components.ios.IosDetailScaffold
import com.gatecontrol.android.ui.components.ios.IosListCard
import com.gatecontrol.android.ui.components.ios.IosListRow
import com.gatecontrol.android.ui.components.ios.IosListSection
import com.gatecontrol.android.ui.components.ios.IosSectionFooter
import com.gatecontrol.android.ui.components.ios.IosSectionHeader
import com.gatecontrol.android.ui.components.ios.IosSegmentedControl
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.IosTileBlue
import com.gatecontrol.android.ui.theme.IosTileGray
import com.gatecontrol.android.ui.theme.IosTileGreen
import com.gatecontrol.android.ui.theme.IosTileOrange
import com.gatecontrol.android.util.WifiSubnetDetector

/**
 * Split-tunnel detail screen — pushed onto Settings / VPN screens.
 *
 * iOS-style layout:
 *   1. Mode segmented control (Off / Exclude / Include only) with explanatory
 *      footer that updates per mode.
 *   2. Networks section — Private 172/192 preset row, WiFi-subnet row, custom
 *      network rows, and a green "Add custom network" row.
 *   3. Apps section — list of currently selected apps (icon + label + remove
 *      button) and a green "Choose apps…" row.
 *
 * The 10.0.0.0/8 exclusion (VPN subnet conflict) is explained in the section
 * footer rather than buried in the preset checkbox UI.
 */
@Composable
fun SplitTunnelScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val wifiSubnet = remember { WifiSubnetDetector.detect(context) }

    var showAppPicker by remember { mutableStateOf(false) }
    var showAddCidrSheet by remember { mutableStateOf(false) }

    val modeOptions = listOf("off", "exclude", "include")
    val modeFooter = when (uiState.splitTunnelMode) {
        "exclude" -> stringResource(R.string.split_tunnel_exclude_footer)
        "include" -> stringResource(R.string.split_tunnel_include_footer)
        else -> stringResource(R.string.split_tunnel_off_footer)
    }

    IosDetailScaffold(
        title = stringResource(R.string.split_tunnel_title),
        onBack = onNavigateBack,
        backLabel = stringResource(R.string.settings_title),
    ) {
        Spacer(Modifier.height(4.dp))

        // ── Mode ──
        IosSectionHeader(stringResource(R.string.split_tunnel_mode_header))
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            IosSegmentedControl(
                options = modeOptions,
                selected = uiState.splitTunnelMode.ifEmpty { "off" },
                onSelect = {
                    if (!uiState.splitTunnelAdminLocked) viewModel.setSplitTunnelMode(it)
                },
                label = { value ->
                    when (value) {
                        "exclude" -> stringResource(R.string.split_tunnel_exclude_short)
                        "include" -> stringResource(R.string.split_tunnel_include_short)
                        else -> stringResource(R.string.settings_off)
                    }
                },
            )
        }
        IosSectionFooter(modeFooter)

        if (uiState.splitTunnelAdminLocked) {
            Spacer(Modifier.height(8.dp))
            IosSectionFooter(stringResource(R.string.split_tunnel_admin_locked))
        }

        if (uiState.splitTunnelMode != "off") {
            // ── Networks ──
            IosListSection(
                header = stringResource(R.string.split_tunnel_networks_header),
                footer = stringResource(R.string.split_tunnel_private_nets_hint),
            ) {
                NetworkPresetRows(
                    networks = uiState.splitTunnelNetworks,
                    wifiSubnet = wifiSubnet,
                    adminLocked = uiState.splitTunnelAdminLocked,
                    onNetworksChanged = { viewModel.setSplitTunnelNetworks(it) },
                )
                if (!uiState.splitTunnelAdminLocked) {
                    IosListRow(onClick = { showAddCidrSheet = true }) {
                        IosColoredIconTile(icon = Icons.Filled.Add, color = IosTileGreen)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.split_tunnel_add_network),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ── Apps ──
            IosListSection(header = stringResource(R.string.split_tunnel_apps_header)) {
                val pm = context.packageManager
                uiState.splitTunnelAppsV2.forEachIndexed { idx, pkg ->
                    val appLabel = remember(pkg) {
                        runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }
                            .getOrElse { pkg }
                    }
                    val appIcon: Drawable? = remember(pkg) {
                        runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                    }
                    IosListRow(showDivider = true) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon.toBitmap(72, 72).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)),
                            )
                        } else {
                            IosColoredIconTile(icon = Icons.Filled.Apps, color = IosTileGray)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = appLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (!uiState.splitTunnelAdminLocked) {
                            IconButton(onClick = {
                                viewModel.setSplitTunnelAppsV2(uiState.splitTunnelAppsV2 - pkg)
                            }) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = GateControlTheme.extraColors.text3,
                                )
                            }
                        }
                    }
                }
                IosListRow(onClick = { showAppPicker = true }) {
                    IosColoredIconTile(icon = Icons.Filled.Add, color = IosTileGreen)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.split_tunnel_add_app),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showAppPicker) {
        AppPickerSheet(
            selectedPackages = uiState.splitTunnelAppsV2.toSet(),
            onDismiss = { selected ->
                viewModel.setSplitTunnelAppsV2(selected.toList())
                showAppPicker = false
            },
        )
    }

    if (showAddCidrSheet) {
        AddNetworkSheet(
            existing = uiState.splitTunnelNetworks,
            onAdd = { newNetwork ->
                viewModel.setSplitTunnelNetworks(uiState.splitTunnelNetworks + newNetwork)
                showAddCidrSheet = false
            },
            onDismiss = { showAddCidrSheet = false },
        )
    }
}

/**
 * Renders the three iOS-style toggle rows for the built-in network presets:
 *   - "Private Networks (172/192)" — preset
 *   - "Link-Local" — preset
 *   - "WiFi Subnet" — added when the device is on WiFi
 *
 * Plus a row per custom network already in [networks] (with a remove button).
 *
 * Logic mirrors the original [NetworkPresetsSection] but renders inside an
 * iOS card rather than ad-hoc Material rows.
 */
@Composable
private fun NetworkPresetRows(
    networks: List<NetworkEntry>,
    wifiSubnet: String?,
    adminLocked: Boolean,
    onNetworksChanged: (List<NetworkEntry>) -> Unit,
) {
    val activeCidrs = networks.map { it.cidr }.toSet()
    val privateCidrs = listOf("172.16.0.0/12", "192.168.0.0/16")
    val linkLocalCidr = "169.254.0.0/16"

    val hasPrivate = privateCidrs.all { it in activeCidrs }
    val hasLinkLocal = linkLocalCidr in activeCidrs
    val hasWifi = wifiSubnet != null && wifiSubnet in activeCidrs

    // Preset rows
    PresetToggleRow(
        title = stringResource(R.string.split_tunnel_private_nets),
        subtitle = "172.16/12 · 192.168/16",
        icon = Icons.Filled.Public,
        iconBg = IosTileBlue,
        checked = hasPrivate,
        enabled = !adminLocked,
        onCheckedChange = { on ->
            val next = if (on) {
                networks + privateCidrs.filter { it !in activeCidrs }
                    .map { cidr -> NetworkEntry(cidr, presetLabel(cidr)) }
            } else {
                networks.filterNot { it.cidr in privateCidrs }
            }
            onNetworksChanged(next)
        },
        showDivider = true,
    )

    PresetToggleRow(
        title = stringResource(R.string.split_tunnel_link_local),
        subtitle = linkLocalCidr,
        icon = Icons.Filled.Public,
        iconBg = IosTileGray,
        checked = hasLinkLocal,
        enabled = !adminLocked,
        onCheckedChange = { on ->
            val next = if (on) networks + NetworkEntry(linkLocalCidr, "Link-Local")
                       else networks.filterNot { it.cidr == linkLocalCidr }
            onNetworksChanged(next)
        },
        showDivider = wifiSubnet != null || networks.isNotEmpty(),
    )

    if (wifiSubnet != null) {
        PresetToggleRow(
            title = stringResource(R.string.split_tunnel_wifi_subnet, wifiSubnet),
            subtitle = null,
            icon = Icons.Filled.Wifi,
            iconBg = IosTileOrange,
            checked = hasWifi,
            enabled = !adminLocked,
            onCheckedChange = { on ->
                val next = if (on) networks + NetworkEntry(wifiSubnet, "WiFi")
                           else networks.filterNot { it.cidr == wifiSubnet }
                onNetworksChanged(next)
            },
            showDivider = networks.any { it.cidr !in privateCidrs && it.cidr != linkLocalCidr && it.cidr != wifiSubnet },
        )
    }

    // Custom networks
    val builtin = (privateCidrs + linkLocalCidr + listOfNotNull(wifiSubnet)).toSet()
    val customNetworks = networks.filterNot { it.cidr in builtin }
    customNetworks.forEachIndexed { idx, entry ->
        IosListRow(showDivider = idx < customNetworks.lastIndex) {
            IosColoredIconTile(icon = Icons.Filled.Public, color = IosTileGray)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label.ifBlank { entry.cidr },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (entry.label.isNotBlank() && entry.label != entry.cidr) {
                    Text(
                        text = entry.cidr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!adminLocked) {
                IconButton(onClick = {
                    onNetworksChanged(networks - entry)
                }) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Close,
                        contentDescription = null,
                        tint = GateControlTheme.extraColors.text3,
                    )
                }
            }
        }
    }
}

/** Friendly preset label for a known CIDR; used when adding presets back. */
private fun presetLabel(cidr: String): String = when (cidr) {
    "172.16.0.0/12" -> "Private 172.x"
    "192.168.0.0/16" -> "Private 192.x"
    "169.254.0.0/16" -> "Link-Local"
    else -> cidr
}

@Composable
private fun PresetToggleRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: androidx.compose.ui.graphics.Color,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean,
) {
    IosListRow(
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        showDivider = showDivider,
    ) {
        IosColoredIconTile(icon = icon, color = iconBg)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                uncheckedTrackColor = GateControlTheme.extraColors.border2,
                uncheckedBorderColor = GateControlTheme.extraColors.border2,
            ),
        )
    }
}
