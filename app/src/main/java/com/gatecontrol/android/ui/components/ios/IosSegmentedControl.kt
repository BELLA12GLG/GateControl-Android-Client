package com.gatecontrol.android.ui.components.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * iOS-style segmented control. Renders a pill-shaped container with N equal
 * segments; the selected segment has a raised "knob" with a subtle elevation
 * effect (matched here with the surface variant color since shadows look
 * heavy on a flat list).
 *
 * Use for picking among 2–4 mutually exclusive options (e.g. split-tunnel
 * mode: Off / Exclude / Include only).
 */
@Composable
fun <T> IosSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val knob  = MaterialTheme.colorScheme.surface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(color = track, shape = RoundedCornerShape(8.dp))
            .padding(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        color = if (isSelected) knob else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(7.dp),
                    )
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
