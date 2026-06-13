package com.gatecontrol.android.ui.services

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.network.VpnService
import com.gatecontrol.android.ui.components.ios.IosColoredIconTile
import com.gatecontrol.android.ui.components.ios.IosLazyScreenScaffold
import com.gatecontrol.android.ui.components.ios.IosListCard
import com.gatecontrol.android.ui.components.ios.IosListRow
import com.gatecontrol.android.ui.components.ios.IosSectionHeader
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.IosTileBlue
import com.gatecontrol.android.ui.theme.IosTileOrange

/**
 * Services screen — iOS grouped list of accessible internal services.
 *
 * Each service is a list row with a colored icon tile, a primary line (service
 * name), a secondary line (domain) and (if auth-protected) an orange "Auth"
 * badge on the right. Tap a row to open the service URL in the system browser.
 *
 * Loading / error / empty states render in the center of the screen with the
 * same styling as the rest of the iOS-themed pages.
 */
@Composable
fun ServicesScreen(viewModel: ServicesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { url ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    IosLazyScreenScaffold(title = stringResource(R.string.services_title)) {
        item { Spacer(Modifier.height(4.dp)) }

        when {
            uiState.isLoading && uiState.services.isEmpty() -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            uiState.services.isEmpty() -> {
                item {
                    EmptyServicesState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                    )
                }
            }

            else -> {
                item { IosSectionHeader(stringResource(R.string.services_section_available)) }
                item {
                    IosListCard {
                        uiState.services.forEachIndexed { idx, service ->
                            ServiceRow(
                                service = service,
                                showDivider = idx < uiState.services.lastIndex,
                                onClick = { viewModel.openService(service.url) },
                            )
                        }
                    }
                }
            }
        }

        // DNS leak test result banner & trigger
        item {
            Spacer(Modifier.height(16.dp))
            DnsTestResultBanner(
                result = uiState.dnsTestResult,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            Spacer(Modifier.height(8.dp))
            DnsTestButtonRow(
                enabled = uiState.dnsTestResult !is DnsTestResult.Testing,
                onClick = { viewModel.runDnsLeakTest() },
            )
        }
    }
}

@Composable
private fun ServiceRow(
    service: VpnService,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    IosListRow(onClick = onClick, showDivider = showDivider) {
        IosColoredIconTile(icon = Icons.Filled.Apps, color = IosTileBlue)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = service.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = service.domain,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (service.hasAuth) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = IosTileOrange.copy(alpha = 0.18f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = IosTileOrange,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.services_auth_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = IosTileOrange,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun EmptyServicesState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.services_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun DnsTestButtonRow(enabled: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
        com.gatecontrol.android.ui.components.ios.IosTintedButton(
            text = stringResource(R.string.dns_leak_test),
            onClick = onClick,
            enabled = enabled,
        )
    }
}

@Composable
private fun DnsTestResultBanner(result: DnsTestResult, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    when (result) {
        DnsTestResult.Idle -> Unit
        DnsTestResult.Testing -> Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.dns_testing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        is DnsTestResult.Pass -> ResultBanner(
            modifier = modifier,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.dns_pass),
            detail = if (result.servers.isNotBlank())
                stringResource(R.string.dns_servers, result.servers) else null,
        )
        is DnsTestResult.Fail -> ResultBanner(
            modifier = modifier,
            accent = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.dns_fail),
            detail = if (result.servers.isNotBlank())
                stringResource(R.string.dns_servers, result.servers) else null,
        )
    }
}

@Composable
private fun ResultBanner(
    modifier: Modifier,
    accent: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String?,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = accent)
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
