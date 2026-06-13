package com.gatecontrol.android.ui.components.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.ui.theme.GateControlTheme

// =============================================================================
// iOS-Style Grouped List Components
// =============================================================================
//
// Building blocks for the iOS Settings-style inset-grouped list:
//
//   IosListSection            — the [HEADER] + card + [FOOTER] envelope.
//   IosListCard               — pure rounded-corner card (when no header needed).
//   IosListRow                — a single row inside a card; auto-renders the
//                                 hairline divider when not the last child.
//   IosColoredIconTile        — the rounded square colored tile that holds an
//                                 icon at the start of a row.
//   IosNavigationRow          — row that ends with a chevron (tap → screen).
//   IosToggleRow              — row that ends with an iOS-styled Switch.
//   IosValueRow               — row that shows a secondary value on the right
//                                 (e.g. "Theme  System ›").
//   IosSectionHeader/Footer   — standalone "ABOVE THE CARD" caption text.
//
// All components are surface-aware; they read the right colors from
// GateControlTheme so they automatically swap between light/dark mode.

/** Standard inset padding around grouped-list cards. iOS = 16pt on iPhone. */
private val GroupInsetH = 16.dp

/** Card corner radius — iOS uses 10pt for inset-grouped lists. */
private val CardCorner  = 10.dp

/** Minimum row height — iOS list rows are 44pt. */
private val RowMinHeight = 44.dp

/**
 * A section composed of an optional uppercase header, a rounded card containing
 * one or more rows, and an optional sub-card footer paragraph. Mirrors the iOS
 * UITableView grouped-style section.
 *
 * Use it as the basic vertical building block on settings-style screens.
 */
@Composable
fun IosListSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (header != null) {
            IosSectionHeader(text = header)
        }
        IosListCard { content() }
        if (footer != null) {
            IosSectionFooter(text = footer)
        }
    }
}

/**
 * A rounded card with the iOS grouped-list surface color. Children should
 * usually be a vertical stack of [IosListRow] or its convenience variants.
 */
@Composable
fun IosListCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GroupInsetH),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(CardCorner),
    ) {
        Column { content() }
    }
}

/**
 * Uppercase caption rendered above a list card. Use through [IosListSection];
 * call directly only when you need ad-hoc placement.
 */
@Composable
fun IosSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = GroupInsetH + 16.dp,
            end = GroupInsetH + 16.dp,
            top = 22.dp,
            bottom = 6.dp,
        ),
    )
}

/** Footnote-style explanatory text rendered below a list card. */
@Composable
fun IosSectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = GroupInsetH + 16.dp,
            end = GroupInsetH + 16.dp,
            top = 6.dp,
            bottom = 0.dp,
        ),
    )
}

/**
 * Generic row used inside a card. The row has a fixed 44dp min-height,
 * iOS-style padding, and (if [showDivider] is true) draws a hairline divider
 * inset on the leading side to match the indentation of the first text/icon.
 *
 * Most consumers should reach for the higher-level [IosNavigationRow],
 * [IosToggleRow], or [IosValueRow] instead.
 */
@Composable
fun IosListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    dividerInsetStart: Float = 56f, // pt of left inset; defaults to align past 30pt icon + 12pt gap
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(clickModifier)
                .heightIn(min = RowMinHeight)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = dividerInsetStart.dp)
                    .height(0.5.dp)
                    .background(GateControlTheme.extraColors.border),
            )
        }
    }
}

/**
 * The colored rounded-square icon tile used at the start of iOS settings rows.
 * 29dp square (iOS uses ~29pt), 7dp radius, white icon on the supplied [color].
 */
@Composable
fun IosColoredIconTile(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(29.dp)
            .background(color = color, shape = RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Row that ends with the iOS-style right chevron (›). Tap the whole row to
 * trigger [onClick]. Optional [trailingText] shows a muted value before the
 * chevron, matching rows like "Theme  System ›".
 */
@Composable
fun IosNavigationRow(
    title: String,
    icon: ImageVector? = null,
    iconBg: Color? = null,
    trailingText: String? = null,
    showDivider: Boolean = false,
    onClick: () -> Unit,
) {
    IosListRow(onClick = onClick, showDivider = showDivider) {
        if (icon != null && iconBg != null) {
            IosColoredIconTile(icon = icon, color = iconBg)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = GateControlTheme.extraColors.text3,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Row that ends with an iOS-styled toggle Switch. The whole row is tappable.
 * The optional [description] line appears as muted subtext below [title] —
 * useful for one-line explanations of what the switch does.
 */
@Composable
fun IosToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconBg: Color? = null,
    description: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = false,
) {
    IosListRow(
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        showDivider = showDivider,
    ) {
        if (icon != null && iconBg != null) {
            IosColoredIconTile(icon = icon, color = iconBg)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = GateControlTheme.extraColors.border2,
                uncheckedBorderColor = GateControlTheme.extraColors.border2,
            ),
        )
    }
}

/**
 * Read-only key/value row: "Label … Value". No chevron, no tap action.
 * Used for things like "Version  1.4.2".
 */
@Composable
fun IosValueRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
    iconBg: Color? = null,
    showDivider: Boolean = false,
) {
    IosListRow(showDivider = showDivider) {
        if (icon != null && iconBg != null) {
            IosColoredIconTile(icon = icon, color = iconBg)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
