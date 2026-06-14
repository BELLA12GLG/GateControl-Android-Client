package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.network.DnsResolver
import com.gatecontrol.android.ui.components.ios.IosDetailScaffold
import com.gatecontrol.android.ui.components.ios.IosListRow
import com.gatecontrol.android.ui.components.ios.IosListSection
import com.gatecontrol.android.ui.components.ios.IosTopBarButton

/**
 * DNS cache inspector — pushed from NetworkSettingsScreen.
 *
 * v6.6 adds this so users can SEE what the app-layer DNS resolver has
 * cached, including:
 *   - static-host overrides (marked "Static", never expire)
 *   - live cache entries with remaining TTL countdown
 *
 * Per-row delete only affects live cache entries; static hosts must be
 * removed via the parent screen's "Static hosts" section (because they're
 * configuration, not cache).
 *
 * Refresh: cache contents change continuously as the app does lookups;
 * rather than a reactive Flow (which would re-render every resolve)
 * the screen reads a snapshot via [SettingsViewModel.dumpDnsCache] and
 * exposes a manual "Refresh" button in the nav bar trailing area.
 *
 * Layout decision: one IosListSection per row group (Static vs Cache)
 * keeps the iOS-style header visible above each block and reads more
 * naturally than a flat list with row-level "Static" badges.
 */
@Composable
fun DnsCacheDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    // We trigger re-snapshot by bumping this counter — both on initial mount
    // and on user tap of the Refresh button.
    var refreshTick by remember { mutableStateOf(0) }
    val snapshot = remember(refreshTick) { viewModel.dumpDnsCache() }
    val (staticEntries, cacheEntries) = snapshot.partition { it.isStatic }

    IosDetailScaffold(
        title = stringResource(R.string.dns_cache_details_title),
        onBack = onNavigateBack,
        backLabel = stringResource(R.string.network_settings_title),
        trailingAction = {
            IosTopBarButton(
                text = stringResource(R.string.dns_cache_refresh),
                onClick = { refreshTick++ },
            )
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 0.dp)) {
            Spacer(Modifier.height(8.dp))

            // ── Static hosts(read-only here — managed via NetworkSettingsScreen) ──
            if (staticEntries.isNotEmpty()) {
                IosListSection(
                    header = stringResource(R.string.dns_cache_static_header),
                    footer = stringResource(R.string.dns_cache_static_footer),
                ) {
                    staticEntries.forEachIndexed { idx, entry ->
                        IosListRow(showDivider = idx < staticEntries.lastIndex) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.host,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = entry.ips.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = stringResource(R.string.dns_cache_static_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // ── Live cache entries ──
            IosListSection(
                header = stringResource(R.string.dns_cache_live_header),
                footer = stringResource(R.string.dns_cache_live_footer),
            ) {
                if (cacheEntries.isEmpty()) {
                    IosListRow {
                        Text(
                            text = stringResource(R.string.dns_cache_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    cacheEntries.forEachIndexed { idx, entry ->
                        IosListRow(showDivider = idx < cacheEntries.lastIndex) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.host,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = entry.ips.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = formatRemaining(entry.remainingSeconds),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = {
                                    viewModel.removeDnsCacheEntry(entry.host)
                                    refreshTick++  // re-snapshot after removal
                                },
                                modifier = Modifier.width(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.dns_cache_remove_entry),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pretty-print remaining TTL as "Xh Ym Zs" or "Xm Ys" or "Zs". Keeps long
 * cache windows readable without forcing the user to mentally divide.
 */
private fun formatRemaining(seconds: Long): String {
    if (seconds <= 0L) return "expired"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
