package com.gatecontrol.android.ui.components.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.ui.theme.GateControlTheme

// =============================================================================
// iOS-Style Buttons
// =============================================================================
//
// Primary buttons in iOS are full-width, large, pill-shaped (radius 14pt),
// filled with the accent color, white text. Destructive uses red.
// Secondary buttons are transparent text-only buttons that show only the
// label in accent color — no border.

/** Filled accent button. Use for the main CTA on a screen ("Connect", "Save"). */
@Composable
fun IosPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    destructive: Boolean = false,
) {
    val bg = if (destructive) MaterialTheme.colorScheme.error
             else MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = Color.White,
            disabledContainerColor = bg.copy(alpha = 0.4f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/** Tinted (filled with low-opacity accent) button, used as the second CTA. */
@Composable
fun IosTintedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error
                 else MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.15f),
            contentColor = accent,
            disabledContainerColor = accent.copy(alpha = 0.06f),
            disabledContentColor = accent.copy(alpha = 0.4f),
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
        )
    }
}

/**
 * Plain text button — no background, no border. Use for tertiary actions
 * embedded inline (e.g. "Cancel" next to "Save"). For destructive choices
 * (e.g. "Remove server"), set [destructive] true to color the label red.
 */
@Composable
fun IosTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> GateControlTheme.extraColors.text3
                destructive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}
