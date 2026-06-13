package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.common.IpValidation
import com.gatecontrol.android.ui.components.ios.IosDetailScaffold
import com.gatecontrol.android.ui.components.ios.IosListSection
import com.gatecontrol.android.ui.components.ios.IosListRow
import com.gatecontrol.android.ui.components.ios.IosNavigationRow
import com.gatecontrol.android.ui.components.ios.IosToggleRow
import com.gatecontrol.android.ui.components.ios.IosTextButton

/**
 * Network preferences detail screen — pushed from Settings.
 *
 * Contains 5 grouped sections (v6.4):
 *   1. IP Protocol — single-choice list (Auto / IPv6 preferred /
 *      IPv4 only / IPv6 only). Tunnel-layer.
 *   2. DNS Servers — primary/secondary inputs + presets. Tunnel-layer.
 *   3. **App DNS** (v6.4) — DoH upstream URL + presets. App-layer only:
 *      controls how the GateControl app itself resolves the SERVER
 *      hostname, NOT what runs through the WireGuard tunnel. Documented
 *      in the section footer so users don't misread it.
 *   4. **Static hosts** (v6.4) — pinned host → IP overrides for app-layer
 *      DNS. Highest priority — short-circuits DoH and system DNS both.
 *      Survives the VPN going up.
 *   5. **DNS cache** (v6.4) — enable/disable + TTL preset + manual clear.
 *      App-layer only.
 */
@Composable
fun NetworkSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    IosDetailScaffold(
        title = stringResource(R.string.network_settings_title),
        onBack = onNavigateBack,
        backLabel = stringResource(R.string.settings_title),
    ) {
        Spacer(Modifier.height(4.dp))
        IpProtocolSection(
            current = uiState.ipProtocol,
            onSelect = viewModel::setIpProtocol,
        )
        DnsSection(
            primary = uiState.dnsPrimary,
            secondary = uiState.dnsSecondary,
            onPrimaryChange = viewModel::setDnsPrimary,
            onSecondaryChange = viewModel::setDnsSecondary,
        )
        // v6.4: App-layer DNS sections — independent of tunnel DNS above.
        AppDohSection(
            current = uiState.dohUpstreamUrl,
            onSet = viewModel::setDohUpstreamUrl,
        )
        StaticHostsSection(
            hosts = uiState.staticHosts,
            onAdd = viewModel::addStaticHost,
            onRemove = viewModel::removeStaticHost,
        )
        DnsCacheSection(
            enabled = uiState.dnsCacheEnabled,
            ttlSeconds = uiState.dnsCacheTtlSeconds,
            onEnabledChange = viewModel::setDnsCacheEnabled,
            onTtlChange = viewModel::setDnsCacheTtlSeconds,
            onClear = viewModel::clearDnsCache,
        )
    }
}

@Composable
private fun IpProtocolSection(
    current: String,
    onSelect: (String) -> Unit,
) {
    // Order matters: "auto" first so it's the obvious default; the two
    // "only" options last because they're the most restrictive.
    val options = listOf(
        "auto" to stringResource(R.string.ip_protocol_auto),
        "ipv6_preferred" to stringResource(R.string.ip_protocol_ipv6_preferred),
        "ipv4_only" to stringResource(R.string.ip_protocol_ipv4_only),
        "ipv6_only" to stringResource(R.string.ip_protocol_ipv6_only),
    )
    val descriptions = mapOf(
        "auto" to stringResource(R.string.ip_protocol_auto_desc),
        "ipv6_preferred" to stringResource(R.string.ip_protocol_ipv6_preferred_desc),
        "ipv4_only" to stringResource(R.string.ip_protocol_ipv4_only_desc),
        "ipv6_only" to stringResource(R.string.ip_protocol_ipv6_only_desc),
    )

    IosListSection(
        header = stringResource(R.string.ip_protocol_header),
        footer = descriptions[current],
    ) {
        options.forEachIndexed { idx, (value, label) ->
            ChoiceRow(
                label = label,
                selected = value == current,
                onClick = { onSelect(value) },
                showDivider = idx < options.lastIndex,
            )
        }
    }
}

/** A row with the label on the left and a green check on the right when selected. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    IosListRow(onClick = onClick, showDivider = showDivider) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun DnsSection(
    primary: String,
    secondary: String,
    onPrimaryChange: (String) -> Unit,
    onSecondaryChange: (String) -> Unit,
) {
    // Local field buffers — we commit to the ViewModel on every change so
    // the field always reflects what's persisted, but we don't reformat the
    // user's input mid-typing.
    var primaryField by remember(primary) { mutableStateOf(primary) }
    var secondaryField by remember(secondary) { mutableStateOf(secondary) }

    LaunchedEffect(primary) { primaryField = primary }
    LaunchedEffect(secondary) { secondaryField = secondary }

    val primaryValid = primaryField.isBlank() || IpValidation.isValidIpAddress(primaryField.trim())
    val secondaryValid = secondaryField.isBlank() || IpValidation.isValidIpAddress(secondaryField.trim())

    IosListSection(
        header = stringResource(R.string.dns_header),
        footer = stringResource(R.string.dns_footer),
    ) {
        // Primary DNS field row
        IosListRow {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                OutlinedTextField(
                    value = primaryField,
                    onValueChange = {
                        primaryField = it
                        onPrimaryChange(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dns_primary_label)) },
                    placeholder = { Text("1.1.1.1") },
                    singleLine = true,
                    isError = !primaryValid,
                    supportingText = if (!primaryValid) {
                        { Text(stringResource(R.string.dns_invalid)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
        }
        // Secondary DNS field row
        IosListRow {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                OutlinedTextField(
                    value = secondaryField,
                    onValueChange = {
                        secondaryField = it
                        onSecondaryChange(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dns_secondary_label)) },
                    placeholder = { Text("1.0.0.1") },
                    singleLine = true,
                    isError = !secondaryValid,
                    supportingText = if (!secondaryValid) {
                        { Text(stringResource(R.string.dns_invalid)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        }
    }

    // Presets section — one-tap to fill both fields with a known provider.
    IosListSection(
        header = stringResource(R.string.dns_presets_header),
    ) {
        DnsPresetRow(
            name = stringResource(R.string.dns_preset_system),
            value = "—",
            onClick = {
                onPrimaryChange("")
                onSecondaryChange("")
            },
            showDivider = true,
        )
        DnsPresetRow(
            name = "Cloudflare",
            value = "1.1.1.1 · 1.0.0.1",
            onClick = {
                onPrimaryChange("1.1.1.1")
                onSecondaryChange("1.0.0.1")
            },
            showDivider = true,
        )
        DnsPresetRow(
            name = "Google",
            value = "8.8.8.8 · 8.8.4.4",
            onClick = {
                onPrimaryChange("8.8.8.8")
                onSecondaryChange("8.8.4.4")
            },
            showDivider = true,
        )
        DnsPresetRow(
            name = "Quad9",
            value = "9.9.9.9 · 149.112.112.112",
            onClick = {
                onPrimaryChange("9.9.9.9")
                onSecondaryChange("149.112.112.112")
            },
            showDivider = true,
        )
        DnsPresetRow(
            name = "AdGuard",
            value = "94.140.14.14 · 94.140.15.15",
            onClick = {
                onPrimaryChange("94.140.14.14")
                onSecondaryChange("94.140.15.15")
            },
            showDivider = false,
        )
    }
}

@Composable
private fun DnsPresetRow(
    name: String,
    value: String,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    IosNavigationRow(
        title = name,
        trailingText = value,
        onClick = onClick,
        showDivider = showDivider,
    )
}

// ─── v6.4: App-layer DNS sections ────────────────────────────────────────────
//
// These three sections control how the GateControl app itself resolves the
// SERVER hostname for its REST API calls. They do NOT affect the WireGuard
// tunnel DNS (sections 1 & 2 above) — that goes through Android's system
// resolver and is out of this app's reach once the tunnel is up.
//
// The distinction is critical because users will naturally assume "DNS" means
// "the DNS that runs through my VPN". The footer text on each section calls
// this out in plain language to prevent the misread.

@Composable
private fun AppDohSection(
    current: String,
    onSet: (String) -> Unit,
) {
    var field by remember(current) { mutableStateOf(current) }
    LaunchedEffect(current) { field = current }

    val presets = listOf(
        "" to stringResource(R.string.dns_preset_system),
        "https://1.1.1.1/dns-query" to "Cloudflare",
        "https://dns.google/dns-query" to "Google",
        "https://dns.quad9.net/dns-query" to "Quad9",
        "https://dns.adguard-dns.com/dns-query" to "AdGuard",
    )

    IosListSection(
        header = stringResource(R.string.dns_app_doh_header),
        footer = stringResource(R.string.dns_app_doh_footer),
    ) {
        IosListRow {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        onSet(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dns_app_doh_url_label)) },
                    placeholder = { Text("https://1.1.1.1/dns-query") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        }
    }

    IosListSection(header = stringResource(R.string.dns_app_doh_presets_header)) {
        presets.forEachIndexed { idx, (url, name) ->
            IosNavigationRow(
                title = name,
                trailingText = if (url.isEmpty()) "—" else url.removePrefix("https://").take(28),
                onClick = { onSet(url) },
                showDivider = idx < presets.lastIndex,
            )
        }
    }
}

@Composable
private fun StaticHostsSection(
    hosts: Map<String, String>,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    IosListSection(
        header = stringResource(R.string.dns_static_hosts_header),
        footer = stringResource(R.string.dns_static_hosts_footer),
    ) {
        if (hosts.isEmpty()) {
            IosListRow {
                Text(
                    text = stringResource(R.string.dns_static_hosts_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            val entries = hosts.entries.toList()
            entries.forEachIndexed { idx, (host, ip) ->
                IosListRow(showDivider = idx < entries.lastIndex) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = host,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = ip,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onRemove(host) },
                        modifier = Modifier.width(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.dns_static_hosts_remove),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    // "+ Add entry" row in its own section so it visually separates from the list.
    IosListSection {
        IosListRow(onClick = { showAddDialog = true }) {
            Text(
                text = stringResource(R.string.dns_static_hosts_add),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showAddDialog) {
        AddStaticHostDialog(
            existingHosts = hosts.keys,
            onAdd = { h, i ->
                onAdd(h, i)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun AddStaticHostDialog(
    existingHosts: Set<String>,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    val hostOk = host.isNotBlank() && host.lowercase() !in existingHosts
    val ipOk = ip.isNotBlank() && IpValidation.isValidIpAddress(ip.trim())
    val canSubmit = hostOk && ipOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dns_static_hosts_add_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.dns_static_hosts_host_label)) },
                    placeholder = { Text("server.example.com") },
                    singleLine = true,
                    isError = host.isNotBlank() && !hostOk,
                    supportingText = if (host.isNotBlank() && !hostOk) {
                        { Text(stringResource(R.string.dns_static_hosts_duplicate)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text(stringResource(R.string.dns_static_hosts_ip_label)) },
                    placeholder = { Text("192.0.2.1") },
                    singleLine = true,
                    isError = ip.isNotBlank() && !ipOk,
                    supportingText = if (ip.isNotBlank() && !ipOk) {
                        { Text(stringResource(R.string.dns_invalid)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(host.trim(), ip.trim()) },
                enabled = canSubmit,
            ) { Text(stringResource(R.string.dns_static_hosts_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DnsCacheSection(
    enabled: Boolean,
    ttlSeconds: Int,
    onEnabledChange: (Boolean) -> Unit,
    onTtlChange: (Int) -> Unit,
    onClear: () -> Unit,
) {
    // TTL preset table — the only 4 values the UI exposes. The repository
    // accepts anything in [60, 86400] for forward compat, but we pin to
    // these four so users don't agonize over a slider.
    val ttlPresets = listOf(
        300 to stringResource(R.string.dns_cache_ttl_5m),
        1800 to stringResource(R.string.dns_cache_ttl_30m),
        3600 to stringResource(R.string.dns_cache_ttl_1h),
        21600 to stringResource(R.string.dns_cache_ttl_6h),
    )

    IosListSection(
        header = stringResource(R.string.dns_cache_header),
        footer = stringResource(R.string.dns_cache_footer),
    ) {
        IosToggleRow(
            title = stringResource(R.string.dns_cache_enabled_label),
            checked = enabled,
            onCheckedChange = onEnabledChange,
            showDivider = enabled,
        )
        if (enabled) {
            ttlPresets.forEachIndexed { idx, (sec, label) ->
                IosListRow(onClick = { onTtlChange(sec) }, showDivider = idx < ttlPresets.lastIndex) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (sec == ttlSeconds) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }
        }
    }

    IosListSection {
        IosListRow(onClick = onClear) {
            Text(
                text = stringResource(R.string.dns_cache_clear),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
