package com.gatecontrol.android.ui.components.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.ui.theme.GateControlTheme

// =============================================================================
// IosBigToggle — Headline-sized connection toggle for the VPN main screen.
// =============================================================================
//
// Renders inside a grouped-list card with extra vertical padding so the
// connection status feels like the "hero" element on the screen. Status
// indicator (the colored dot at the start of the subtitle) plus a large
// title and a Switch on the right.
//
// Variants reflected via the colored dot:
//   • green dot → connected
//   • orange / pulsing → connecting / reconnecting
//   • gray → disconnected

enum class BigToggleState { Connected, Connecting, Disconnected }

@Composable
fun IosBigToggle(
    state: BigToggleState,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = state != BigToggleState.Connecting) {
                    onCheckedChange(!checked)
                }
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(state = state)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (state) {
                            BigToggleState.Connected -> MaterialTheme.colorScheme.primary
                            BigToggleState.Connecting -> GateControlTheme.extraColors.warn
                            BigToggleState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            when (state) {
                BigToggleState.Connecting -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = GateControlTheme.extraColors.warn,
                    strokeWidth = 2.5.dp,
                )
                else -> Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
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
    }
}

@Composable
private fun StatusDot(state: BigToggleState) {
    val color = when (state) {
        BigToggleState.Connected -> MaterialTheme.colorScheme.primary
        BigToggleState.Connecting -> GateControlTheme.extraColors.warn
        BigToggleState.Disconnected -> GateControlTheme.extraColors.text3
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color = color, shape = RoundedCornerShape(4.dp)),
    )
}
