package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Network preferences detail screen — pushed from Settings.
 *
 * Contains two grouped sections:
 *   1. IP Protocol — single-choice list (Auto / IPv6 preferred /
 *      IPv4 only / IPv6 only). The choice gates which address family the
 *      tunnel layer will resolve and bind, both for the WireGuard endpoint
 *      and for routes added inside the VPN.
 *   2. DNS Servers — two text fields for primary / secondary DNS, plus
 *      single-tap presets (System default, Cloudflare, Google, Quad9).
 *      Empty fields mean "use system DNS / use WireGuard config DNS".
 *
 * Persisted via SettingsRepository (DataStore); the tunnel layer is
 * responsible for applying them at next connect — see TunnelConnector.
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
