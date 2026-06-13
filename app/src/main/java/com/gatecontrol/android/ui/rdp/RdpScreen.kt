package com.gatecontrol.android.ui.rdp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.ios.IosLazyScreenScaffold
import com.gatecontrol.android.ui.components.ios.IosSegmentedControl
import com.gatecontrol.android.ui.components.ios.IosTintedButton
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * RDP host list — iOS-style screen.
 *
 * Layout:
 *   - Large title "Remote Desktops"
 *   - iOS-style rounded search field (inline gray pill, not a Material outlined input)
 *   - Segmented control (All / Online / Offline)
 *   - Vertically-stacked list of [RdpHostCard]s (kept as-is to avoid disturbing
 *     the host-row visuals which are already information-dense). Card spacing
 *     and outer padding adjusted so cards sit on the iOS grouped-list page bg.
 *
 * The session sheet (RdpConnectSheet) is unchanged.
 */
@Composable
fun RdpScreen(viewModel: RdpViewModel = hiltViewModel()) {
    val filteredRoutes by viewModel.filteredRoutes.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val selectedRoute by viewModel.selectedRoute.collectAsState()
    val connectState by viewModel.connectState.collectAsState()
    val activeSessions by viewModel.activeSessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadRoutes() }

    IosLazyScreenScaffold(
        title = stringResource(R.string.rdp_title),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            IosSearchField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = stringResource(R.string.rdp_search),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                IosSegmentedControl(
                    options = listOf(StatusFilter.ALL, StatusFilter.ONLINE, StatusFilter.OFFLINE),
                    selected = statusFilter,
                    onSelect = { viewModel.setStatusFilter(it) },
                    label = {
                        when (it) {
                            StatusFilter.ALL -> stringResource(R.string.rdp_filter_all)
                            StatusFilter.ONLINE -> stringResource(R.string.rdp_filter_online)
                            StatusFilter.OFFLINE -> stringResource(R.string.rdp_filter_offline)
                        }
                    },
                )
            }
        }

        when {
            isLoading && filteredRoutes.isEmpty() -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            }

            error != null && filteredRoutes.isEmpty() -> item {
                ErrorState(
                    errorType = error!!,
                    onRetry = { viewModel.loadRoutes() },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }

            filteredRoutes.isEmpty() -> item {
                EmptyState(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }

            else -> {
                val activeIds = activeSessions.map { it.routeId }.toSet()
                items(
                    count = filteredRoutes.size,
                    key = { idx -> filteredRoutes[idx].id },
                ) { idx ->
                    val route = filteredRoutes[idx]
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                        RdpHostCard(
                            route = route,
                            isSessionActive = route.id in activeIds,
                            onConnect = { viewModel.selectRoute(route) },
                            onWol = { viewModel.sendWol(route.id) },
                            onClick = { viewModel.selectRoute(route) },
                        )
                    }
                }
            }
        }
    }

    selectedRoute?.let { route ->
        RdpConnectSheet(
            route = route,
            connectState = connectState,
            onConnect = { password, forceBypass ->
                viewModel.connect(route.id, password, forceBypass)
            },
            onDisconnect = { viewModel.disconnect(route.id) },
            onDismiss = { viewModel.dismissSheet() },
            onWol = { viewModel.sendWol(route.id) },
        )
    }
}

/**
 * iOS-style search field — rounded gray pill, leading magnifier, optional
 * trailing clear (×) button. Replaces the Material OutlinedTextField the old
 * RDP screen used.
 */
@Composable
private fun IosSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = GateControlTheme.extraColors.text3,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = GateControlTheme.extraColors.text3,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = GateControlTheme.extraColors.text3,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorState(
    errorType: ErrorType,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (errorType) {
        ErrorType.Forbidden -> stringResource(R.string.rdp_error_forbidden)
        ErrorType.Network -> stringResource(R.string.rdp_error_network)
        ErrorType.ServerError -> stringResource(R.string.rdp_error_server)
    }
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth(0.6f)) {
            IosTintedButton(
                text = stringResource(R.string.retry),
                onClick = onRetry,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.rdp_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 32.dp),
    )
}
