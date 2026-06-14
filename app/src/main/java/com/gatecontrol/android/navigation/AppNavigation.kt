package com.gatecontrol.android.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.ios.IosTab
import com.gatecontrol.android.ui.components.ios.IosTabBar
import com.gatecontrol.android.ui.rdp.RdpScreen
import com.gatecontrol.android.ui.services.ServicesScreen
import com.gatecontrol.android.ui.settings.DnsCacheDetailScreen
import com.gatecontrol.android.ui.settings.LogsScreen
import com.gatecontrol.android.ui.settings.NetworkSettingsScreen
import com.gatecontrol.android.ui.settings.SettingsScreen
import com.gatecontrol.android.ui.settings.SplitTunnelScreen
import com.gatecontrol.android.ui.setup.QrScannerScreen
import com.gatecontrol.android.ui.setup.SetupScreen
import com.gatecontrol.android.ui.vpn.VpnScreen

private val bottomBarRoutes = setOf(
    Screen.Vpn.route,
    Screen.Rdp.route,
    Screen.Services.route,
    Screen.Settings.route,
)

@Composable
fun AppNavigation(
    navController: NavHostController,
    isSetupComplete: Boolean,
    hasRdpPermission: Boolean,
    hasServicesPermission: Boolean,
    onlineRdpHostCount: Int = 0,
) {
    val startDestination = if (isSetupComplete) Screen.Vpn.route else Screen.Setup.route

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomBarRoutes

    // Build the visible tab set in route order. Tabs gated on permissions
    // (RDP, Services) are filtered out when unavailable.
    val tabs = buildList {
        add(IosTab(
            route = Screen.Vpn.route,
            icon = Icons.Filled.Shield,
            label = stringResource(R.string.nav_vpn),
        ))
        if (hasRdpPermission) {
            add(IosTab(
                route = Screen.Rdp.route,
                icon = Icons.Filled.Computer,
                label = stringResource(R.string.nav_rdp),
                badge = onlineRdpHostCount,
            ))
        }
        if (hasServicesPermission) {
            add(IosTab(
                route = Screen.Services.route,
                icon = Icons.Filled.Apps,
                label = stringResource(R.string.nav_services),
            ))
        }
        add(IosTab(
            route = Screen.Settings.route,
            icon = Icons.Filled.Settings,
            label = stringResource(R.string.nav_settings),
        ))
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                IosTabBar(
                    tabs = tabs,
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Setup.route) { backStackEntry ->
                val qrResult = backStackEntry.savedStateHandle.get<String>("qr_result")
                SetupScreen(
                    onSetupComplete = {
                        navController.navigate(Screen.Vpn.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                    onNavigateToQr = { navController.navigate(Screen.QrScanner.route) },
                    qrResult = qrResult,
                )
            }

            composable(Screen.QrScanner.route) {
                QrScannerScreen(
                    onQrScanned = { scannedData ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("qr_result", scannedData)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Vpn.route) {
                VpnScreen(
                    onTokenInvalid = {
                        navController.navigate(Screen.Setup.route) {
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        }
                    },
                    onNavigateToSplitTunnel = {
                        navController.navigate(Screen.SplitTunnel.route)
                    },
                )
            }

            composable(Screen.Rdp.route) { RdpScreen() }

            composable(Screen.Services.route) { ServicesScreen() }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                    onNavigateToQrScanner = { navController.navigate(Screen.QrScanner.route) },
                    onNavigateToSplitTunnel = { navController.navigate(Screen.SplitTunnel.route) },
                    onNavigateToNetwork = { navController.navigate(Screen.Network.route) },
                )
            }

            composable(Screen.Logs.route) {
                LogsScreen(onNavigateBack = { navController.popBackStack() })
            }

            composable(Screen.SplitTunnel.route) {
                SplitTunnelScreen(onNavigateBack = { navController.popBackStack() })
            }

            composable(Screen.Network.route) {
                NetworkSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDnsCache = { navController.navigate(Screen.DnsCacheDetail.route) },
                )
            }

            composable(Screen.DnsCacheDetail.route) {
                DnsCacheDetailScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}
