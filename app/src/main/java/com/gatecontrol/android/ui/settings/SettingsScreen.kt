package com.gatecontrol.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.ios.IosListSection
import com.gatecontrol.android.ui.components.ios.IosNavigationRow
import com.gatecontrol.android.ui.components.ios.IosScreenScaffold
import com.gatecontrol.android.ui.components.ios.IosToggleRow
import com.gatecontrol.android.ui.components.ios.IosValueRow
import com.gatecontrol.android.ui.theme.IosTileBlue
import com.gatecontrol.android.ui.theme.IosTileGray
import com.gatecontrol.android.ui.theme.IosTileGreen
import com.gatecontrol.android.ui.theme.IosTileIndigo
import com.gatecontrol.android.ui.theme.IosTileOrange
import com.gatecontrol.android.ui.theme.IosTilePink
import com.gatecontrol.android.ui.theme.IosTilePurple
import com.gatecontrol.android.ui.theme.IosTileRed
import com.gatecontrol.android.ui.theme.IosTileYellow

/**
 * Settings screen — iOS Settings.app aesthetic.
 *
 * The page is broken into short grouped sections; long-form panels (split
 * tunnel, logs, server / token editing) are pushed onto their own detail
 * screens via chevron rows. Each row renders an iOS-style colored icon tile
 * on the left for quick visual scanning.
 *
 *   Section "Account"   — server URL, API token, QR import
 *   Section "General"   — Auto-connect, Kill-switch, Split tunneling
 *   Section "Appearance"— Theme, Language
 *   Section "License"   — Pro status
 *   Section "Diagnostics"— Logs, Update check
 *   Section "About"     — Version
 */
@Composable
fun SettingsScreen(
    onNavigateToLogs: () -> Unit,
    onNavigateToQrScanner: () -> Unit,
    onNavigateToSplitTunnel: () -> Unit,
    onNavigateToNetwork: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importConfigFromUri(context, uri) }

    val requestFilePicker by viewModel.requestFilePicker.collectAsStateWithLifecycle()
    LaunchedEffect(requestFilePicker) {
        if (requestFilePicker) {
            viewModel.onFilePickerLaunched()
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    var showAccountSheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: uiState.appVersion
    }

    val systemIsDark = androidx.compose.foundation.isSystemInDarkTheme()
    val themeDisplayText = when (uiState.theme) {
        "dark" -> stringResource(R.string.settings_theme_dark)
        "light" -> stringResource(R.string.settings_theme_light)
        else -> stringResource(R.string.settings_theme_system)
    }
    val languageDisplayText = when (uiState.locale) {
        "de" -> "Deutsch"
        else -> "English"
    }

    val displayedServer = uiState.serverUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .ifBlank { stringResource(R.string.settings_server_unset) }

    IosScreenScaffold(title = stringResource(R.string.settings_title)) {
        Spacer(Modifier.height(4.dp))

        // ── Account ──
        IosListSection(header = stringResource(R.string.settings_section_account)) {
            IosNavigationRow(
                title = stringResource(R.string.settings_server),
                icon = Icons.Filled.Cloud,
                iconBg = IosTileBlue,
                trailingText = displayedServer,
                showDivider = true,
                onClick = { showAccountSheet = true },
            )
            IosNavigationRow(
                title = stringResource(R.string.settings_import_qr),
                icon = Icons.Filled.QrCodeScanner,
                iconBg = IosTilePurple,
                onClick = onNavigateToQrScanner,
            )
        }

        // ── General ──
        IosListSection(header = stringResource(R.string.settings_section_general)) {
            IosToggleRow(
                title = stringResource(R.string.settings_auto_connect),
                description = stringResource(R.string.settings_auto_connect_desc),
                icon = Icons.Filled.Bolt,
                iconBg = IosTileOrange,
                checked = uiState.autoConnect,
                onCheckedChange = { viewModel.setAutoConnect(it) },
                showDivider = true,
            )
            IosToggleRow(
                title = stringResource(R.string.vpn_kill_switch),
                description = stringResource(R.string.vpn_kill_switch_desc),
                icon = Icons.Filled.Lock,
                iconBg = IosTileRed,
                checked = uiState.killSwitch,
                onCheckedChange = { viewModel.setKillSwitch(it) },
                showDivider = true,
            )
            IosNavigationRow(
                title = stringResource(R.string.split_tunnel_title),
                icon = Icons.Filled.Route,
                iconBg = IosTileGreen,
                trailingText = when (uiState.splitTunnelMode) {
                    "exclude" -> stringResource(R.string.split_tunnel_exclude_short)
                    "include" -> stringResource(R.string.split_tunnel_include_short)
                    else -> stringResource(R.string.settings_off)
                },
                showDivider = true,
                onClick = onNavigateToSplitTunnel,
            )
            IosNavigationRow(
                title = stringResource(R.string.network_settings_title),
                icon = Icons.Filled.Public,
                iconBg = IosTileBlue,
                trailingText = when (uiState.ipProtocol) {
                    "ipv4_only" -> stringResource(R.string.ip_protocol_ipv4_only_short)
                    "ipv6_only" -> stringResource(R.string.ip_protocol_ipv6_only_short)
                    "ipv6_preferred" -> stringResource(R.string.ip_protocol_ipv6_preferred_short)
                    else -> stringResource(R.string.ip_protocol_auto_short)
                },
                onClick = onNavigateToNetwork,
            )
        }

        // ── Appearance ──
        IosListSection(header = stringResource(R.string.settings_section_appearance)) {
            IosNavigationRow(
                title = stringResource(R.string.settings_theme),
                icon = Icons.Filled.Palette,
                iconBg = IosTileIndigo,
                trailingText = themeDisplayText,
                showDivider = true,
                onClick = { showThemeSheet = true },
            )
            IosNavigationRow(
                title = stringResource(R.string.settings_language),
                icon = Icons.Filled.Language,
                iconBg = IosTilePink,
                trailingText = languageDisplayText,
                onClick = { showLanguageSheet = true },
            )
        }

        // ── License ──
        IosListSection(header = stringResource(R.string.settings_license)) {
            IosValueRow(
                title = if (uiState.isPro) stringResource(R.string.settings_license_pro)
                        else stringResource(R.string.settings_license_community),
                value = "",
                icon = Icons.Filled.Star,
                iconBg = if (uiState.isPro) IosTileYellow else IosTileGray,
                showDivider = true,
            )
            IosNavigationRow(
                title = if (uiState.isPro) stringResource(R.string.settings_license_refresh)
                        else stringResource(R.string.settings_license_activate),
                icon = Icons.Filled.Receipt,
                iconBg = IosTileGray,
                onClick = { viewModel.refreshLicense() },
            )
        }

        // ── Diagnostics ──
        IosListSection(header = stringResource(R.string.settings_section_diagnostics)) {
            IosNavigationRow(
                title = stringResource(R.string.settings_logs_view),
                icon = Icons.Filled.FileDownload,
                iconBg = IosTileGray,
                showDivider = true,
                onClick = onNavigateToLogs,
            )
            IosNavigationRow(
                title = stringResource(R.string.settings_check_for_updates),
                icon = Icons.Filled.NewReleases,
                iconBg = IosTileGreen,
                trailingText = uiState.updateInfo?.let { info ->
                    if (info.available) "•" else null
                },
                onClick = { viewModel.checkForUpdate(versionName) },
            )
        }

        // ── About ──
        IosListSection(
            header = stringResource(R.string.settings_about),
            footer = uiState.error,
        ) {
            IosValueRow(
                title = stringResource(R.string.settings_version_label),
                value = versionName,
                icon = Icons.Outlined.Info,
                iconBg = IosTileGray,
                showDivider = uiState.updateInfo?.available == true,
            )
            if (uiState.updateInfo?.available == true) {
                val updateInfo = uiState.updateInfo!!
                IosNavigationRow(
                    title = stringResource(
                        R.string.settings_update_available,
                        updateInfo.version ?: ""
                    ),
                    icon = Icons.Filled.UploadFile,
                    iconBg = IosTileBlue,
                    onClick = {
                        updateInfo.downloadUrl?.let { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                )
            }
        }
    }

    if (showAccountSheet) {
        AccountEditorSheet(
            currentUrl = uiState.serverUrl,
            currentToken = uiState.apiToken,
            connectionTestStatus = uiState.connectionTestStatus,
            isLoading = uiState.isLoading,
            onTest = { url, token -> viewModel.testConnection(url, token) },
            onSave = { url, token -> viewModel.saveServer(url, token) },
            onImportFile = { viewModel.requestConfigImport() },
            onDismiss = { showAccountSheet = false },
        )
    }

    if (showThemeSheet) {
        ThemePickerSheet(
            current = uiState.theme,
            onSelect = {
                viewModel.setTheme(it)
                showThemeSheet = false
            },
            onDismiss = { showThemeSheet = false },
        )
    }

    if (showLanguageSheet) {
        LanguagePickerSheet(
            current = uiState.locale,
            onSelect = {
                viewModel.setLocale(it)
                showLanguageSheet = false
            },
            onDismiss = { showLanguageSheet = false },
        )
    }
}
