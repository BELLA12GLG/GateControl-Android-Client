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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Single tab descriptor for [IosTabBar]. `badge` > 0 renders a red number
 * pill on the icon (e.g. unread RDP sessions).
 */
data class IosTab(
    val route: String,
    val icon: ImageVector,
    val label: String,
    val badge: Int = 0,
)

/**
 * iOS-style bottom tab bar. Renders an icon-over-label column per tab; the
 * active tab uses the accent color, inactive tabs use the tertiary text color.
 *
 * Unlike Material's NavigationBar, there's no oval indicator behind the icon —
 * iOS uses color alone for selection state, which keeps the bar visually quiet.
 */
@Composable
fun IosTabBar(
    tabs: List<IosTab>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column {
            // iOS draws a hairline (0.33pt) separator above the tab bar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(GateControlTheme.extraColors.border),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 6.dp),
            ) {
                tabs.forEach { tab ->
                    val selected = tab.route == currentRoute
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigate(tab.route) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                       else GateControlTheme.extraColors.text3,
                                modifier = Modifier.size(26.dp),
                            )
                            if (tab.badge > 0) {
                                IosTabBadge(
                                    text = tab.badge.toString(),
                                    modifier = Modifier.align(Alignment.TopEnd),
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 10.sp,
                            ),
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else GateControlTheme.extraColors.text3,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IosTabBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color = MaterialTheme.colorScheme.error, shape = CircleShape)
            .size(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
        )
    }
}
