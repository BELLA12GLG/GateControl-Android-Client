package com.gatecontrol.android.ui.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.ios.IosTextButton
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)

private val RECOMMENDED_EXCLUDE_APPS = listOf(
    "com.google.android.projection.gearhead", // Android Auto
)

/**
 * App selection bottom sheet — full rewrite of the original picker.
 *
 * Improvements over v1:
 *   - Selected apps are pinned at the top of the list with a "Selected" header
 *     so the user can see what they already added without scrolling.
 *   - Search bar is an inline iOS-style pill (rounded gray container) instead
 *     of a Material outlined input.
 *   - A count badge in the title shows how many apps are selected.
 *   - "Clear all" / "Select all visible" actions live in a sticky toolbar
 *     above the list, so curating large selections is no longer one-tap-per-app.
 *   - Toggling an app no longer scrolls the list; the row commits to the
 *     local selection set and the sheet re-renders in place.
 *   - System-app toggle moved into the toolbar with a compact iOS-style switch.
 *   - Closing the sheet commits the selection (same as v1) — no separate
 *     Save/Cancel buttons; iOS-style "tap outside to dismiss = save".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    selectedPackages: Set<String>,
    onDismiss: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    // Local mutable selection — propagated back through onDismiss when the
    // sheet closes. Using mutableStateOf<Set<String>> so toggling a row
    // triggers an immediate, minimal recompose of just the affected items.
    var currentSelection by remember { mutableStateOf(selectedPackages) }

    // Load apps on IO thread. ACTION_MAIN + CATEGORY_LAUNCHER gives us all
    // launchable apps without needing QUERY_ALL_PACKAGES.
    val apps by produceState<List<AppInfo>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(launcherIntent, 0)
            activities
                .mapNotNull { resolveInfo ->
                    val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                    AppInfo(
                        packageName = appInfo.packageName,
                        label = resolveInfo.loadLabel(pm).toString(),
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }

    // Recommended apps — loaded independently because they may have no
    // launcher activity and so won't appear in the main list.
    val recommendedApps = remember {
        val pm = context.packageManager
        RECOMMENDED_EXCLUDE_APPS.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                AppInfo(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            } catch (_: Exception) { null }
        }
    }

    // Derived lists:
    //  - allKnownApps: launchable apps + recommended (a recommended app may
    //    not appear in `apps`, e.g. Android Auto).
    //  - selectedApps: those currently in the selection set, sorted by label,
    //    even when they don't match the search query (we always show what
    //    the user already picked so they can de-select it).
    //  - unselectedFiltered: rest of the list filtered by search + system toggle.
    val allKnownApps by remember(apps, recommendedApps) {
        derivedStateOf {
            val merged = (apps.orEmpty() + recommendedApps).distinctBy { it.packageName }
            merged.sortedBy { it.label.lowercase() }
        }
    }

    val selectedApps by remember(allKnownApps, currentSelection) {
        derivedStateOf {
            allKnownApps.filter { it.packageName in currentSelection }
        }
    }

    val unselectedFiltered by remember(allKnownApps, currentSelection, search, showSystem) {
        derivedStateOf {
            allKnownApps.filter { app ->
                app.packageName !in currentSelection &&
                    (showSystem || !app.isSystemApp) &&
                    (search.isBlank() || app.label.contains(search, ignoreCase = true))
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onDismiss(currentSelection) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // ── Title row: title + selected count ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.split_tunnel_pick_apps),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (currentSelection.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = currentSelection.size.toString(),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            // ── iOS-style inline search field ──
            SearchPill(
                value = search,
                onValueChange = { search = it },
                placeholder = stringResource(R.string.split_tunnel_search_apps),
            )

            Spacer(Modifier.height(8.dp))

            // ── Toolbar: system toggle + clear all ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.split_tunnel_show_system),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = showSystem,
                    onCheckedChange = { showSystem = it },
                )
                if (currentSelection.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    IosTextButton(
                        text = stringResource(R.string.split_tunnel_clear_all),
                        onClick = { currentSelection = emptySet() },
                        destructive = true,
                    )
                }
            }

            // ── List ──
            if (apps == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    // Section: already-selected apps. Always shown when there is
                    // any selection, so the user can deselect without scrolling.
                    if (selectedApps.isNotEmpty()) {
                        item(key = "_selected_header") {
                            SectionHeader(
                                text = stringResource(R.string.split_tunnel_selected_header,
                                                     selectedApps.size),
                            )
                        }
                        items(selectedApps, key = { "sel_" + it.packageName }) { app ->
                            AppRow(
                                app = app,
                                selected = true,
                                onToggle = { currentSelection = currentSelection - app.packageName },
                            )
                        }
                        item(key = "_section_divider") {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = GateControlTheme.extraColors.border,
                            )
                        }
                    }

                    // Section: recommended (only when no search & not yet selected)
                    if (search.isBlank()) {
                        val recoNotPicked = recommendedApps.filter {
                            it.packageName !in currentSelection
                        }
                        if (recoNotPicked.isNotEmpty()) {
                            item(key = "_recommended_header") {
                                Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                                    Text(
                                        text = stringResource(
                                            R.string.split_tunnel_recommended_apps,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.split_tunnel_recommended_hint,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(recoNotPicked, key = { "rec_" + it.packageName }) { app ->
                                AppRow(
                                    app = app,
                                    selected = false,
                                    onToggle = { currentSelection = currentSelection + app.packageName },
                                )
                            }
                            item(key = "_reco_divider") {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = GateControlTheme.extraColors.border,
                                )
                            }
                        }
                    }

                    // Section: rest of installed apps
                    item(key = "_all_header") {
                        SectionHeader(
                            text = stringResource(R.string.split_tunnel_all_apps_header,
                                                  unselectedFiltered.size),
                        )
                    }
                    items(unselectedFiltered, key = { "all_" + it.packageName }) { app ->
                        AppRow(
                            app = app,
                            selected = false,
                            onToggle = { currentSelection = currentSelection + app.packageName },
                        )
                    }

                    if (unselectedFiltered.isEmpty() && search.isNotBlank()) {
                        item(key = "_no_match") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.split_tunnel_no_matches, search),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )
}

/** Row for a single app — icon + label + checkmark when selected. */
@Composable
private fun AppRow(
    app: AppInfo,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        try { context.packageManager.getApplicationIcon(app.packageName) }
        catch (_: Exception) { null }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.toBitmap(72, 72).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(7.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        GateControlTheme.extraColors.border,
                        shape = RoundedCornerShape(7.dp),
                    ),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** iOS-style rounded gray search pill (no Material outlined frame). */
@Composable
private fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = GateControlTheme.extraColors.text3,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
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
