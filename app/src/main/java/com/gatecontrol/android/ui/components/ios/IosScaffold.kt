package com.gatecontrol.android.ui.components.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.ui.theme.GateControlTheme

// =============================================================================
// iOS-Style Screen Scaffold
// =============================================================================
//
// IosScreenScaffold      — top-level screen layout with large title and a
//                          grouped-list page background.
// IosDetailScaffold      — sub-screen with a chevron-back button + centered
//                          inline title (e.g. Logs, SplitTunnel detail).
// IosTopBarButton        — text button used in nav bars (e.g. "Done").
//
// Both scaffolds set the iOS grouped-list page color (gray in light, near-black
// in dark) as the background.

@Composable
fun IosScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    trailingAction: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Large title row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (trailingAction != null) {
                trailingAction()
            }
        }
        content()
        Spacer(Modifier.height(32.dp))
    }
}

/** Same as [IosScreenScaffold] but its body is a [LazyColumn], so callers can
 *  use `items(...)` for large lists (e.g. RDP route list, Services list). */
@Composable
fun IosLazyScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    trailingAction: @Composable (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 32.dp),
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (trailingAction != null) {
                trailingAction()
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

/**
 * iOS-style sub-screen with a chevron-back button on the left, a centered
 * inline title, and an optional trailing action. Used for screens you push
 * onto (e.g. Logs from Settings).
 */
@Composable
fun IosDetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String? = null,
    trailingAction: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Nav bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
        ) {
            // Back button (left)
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                if (backLabel != null) {
                    Text(
                        text = backLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Title (centered)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.Center),
            )
            // Trailing action (right)
            if (trailingAction != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
                ) { trailingAction() }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Text button styled like an iOS nav-bar action ("Done", "Edit"). */
@Composable
fun IosTopBarButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary
                    else GateControlTheme.extraColors.text3,
        )
    }
}
