package com.gatecontrol.android.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.ios.IosDetailScaffold
import com.gatecontrol.android.ui.components.ios.IosSegmentedControl
import com.gatecontrol.android.ui.components.ios.IosTopBarButton
import java.io.File
import java.util.concurrent.TimeUnit

enum class LogPeriod(val labelRes: Int) {
    All(R.string.logs_all),
    H24(R.string.logs_24h),
    H12(R.string.logs_12h),
    H1(R.string.logs_1h)
}

/**
 * Diagnostic log viewer — iOS-style detail screen pushed from Settings.
 *
 * Layout:
 *   - Nav bar with chevron back ("Settings"), centered "Logs" title, and a
 *     trailing "Share" text-button that exports the current view as a file.
 *   - Segmented control to filter by time window (All / 24h / 12h / 1h).
 *   - Monospaced log content card filling the rest of the screen.
 */
@Composable
fun LogsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current

    var selectedPeriod by remember { mutableStateOf(LogPeriod.All) }
    var logContent by remember { mutableStateOf("") }

    fun loadLogs(period: LogPeriod) {
        val cutoffMs = when (period) {
            LogPeriod.All -> 0L
            LogPeriod.H24 -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
            LogPeriod.H12 -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(12)
            LogPeriod.H1 -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        }
        val logsDir = File(context.cacheDir, "logs")
        if (!logsDir.exists()) {
            logContent = context.getString(R.string.logs_empty)
            return
        }
        val files = logsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            logContent = context.getString(R.string.logs_empty)
            return
        }
        val sb = StringBuilder()
        for (logFile in files) {
            try {
                logFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (cutoffMs == 0L) {
                            sb.appendLine(line)
                        } else {
                            sb.appendLine(line)
                        }
                    }
                }
            } catch (_: Exception) { /* skip */ }
        }
        logContent = if (sb.isBlank()) context.getString(R.string.logs_empty) else sb.toString()
    }

    fun exportLogs() {
        val logsDir = File(context.cacheDir, "logs")
        if (!logsDir.exists()) return
        val files = logsDir.listFiles() ?: emptyArray()
        if (files.isEmpty()) return
        val export = File(context.cacheDir, "logs-export.txt").apply {
            writeText(logContent)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            export,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.logs_export))
            )
        }
    }

    LaunchedEffect(selectedPeriod) { loadLogs(selectedPeriod) }

    IosDetailScaffold(
        title = stringResource(R.string.logs_title),
        onBack = onNavigateBack,
        backLabel = stringResource(R.string.settings_title),
        trailingAction = {
            IosTopBarButton(
                text = stringResource(R.string.logs_export),
                onClick = { exportLogs() },
                enabled = logContent.isNotBlank(),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            IosSegmentedControl(
                options = LogPeriod.entries.toList(),
                selected = selectedPeriod,
                onSelect = { selectedPeriod = it },
                label = { stringResource(it.labelRes) },
            )

            Spacer(Modifier.height(14.dp))

            // Log body — monospaced text in a card-like surface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = logContent.ifBlank { stringResource(R.string.logs_empty) },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    com.gatecontrol.android.ui.components.ios.IosTintedButton(
                        text = stringResource(R.string.logs_refresh),
                        onClick = { loadLogs(selectedPeriod) },
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    com.gatecontrol.android.ui.components.ios.IosTintedButton(
                        text = stringResource(R.string.logs_export),
                        onClick = { exportLogs() },
                        enabled = logContent.isNotBlank(),
                    )
                }
            }
        }
    }
}
