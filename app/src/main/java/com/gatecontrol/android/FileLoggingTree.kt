package com.gatecontrol.android

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timber tree that writes all log messages to a file in the app's cache directory.
 * The log file is rotated when it exceeds 2 MB.
 *
 * ## v6.2: runtime on/off via [setEnabled]
 *
 * The Settings screen exposes a toggle that flips this flag at runtime. When
 * off, [log] returns immediately without touching the filesystem — Timber
 * machinery (priority check, tag plumbing) still runs because we can't remove
 * a planted tree without re-planting trees, but the I/O cost goes to zero.
 *
 * Logcat output (Timber.DebugTree on debuggable builds) is a separate tree
 * and is NOT affected by this toggle.
 */
class FileLoggingTree(context: Context) : Timber.Tree() {

    private val logDir = File(context.cacheDir, "logs").apply { mkdirs() }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val maxFileSize = 2 * 1024 * 1024L // 2 MB

    companion object {
        /**
         * Master switch read on every log line. Defaults to true so behavior
         * before v6.2 is preserved when no preference is loaded yet.
         *
         * Settings UI calls [setEnabled] which flips this; the SettingsRepository
         * also reads from DataStore on app start and calls setEnabled with the
         * persisted value so a previously-disabled state survives restart.
         */
        @Volatile
        private var enabled: Boolean = true

        fun setEnabled(value: Boolean) {
            enabled = value
        }

        fun isEnabled(): Boolean = enabled
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!enabled) return
        try {
            val logFile = File(logDir, "gatecontrol.log")

            // Rotate if too large
            if (logFile.exists() && logFile.length() > maxFileSize) {
                val oldFile = File(logDir, "gatecontrol.log.1")
                oldFile.delete()
                logFile.renameTo(oldFile)
            }

            val level = when (priority) {
                Log.VERBOSE -> "V"
                Log.DEBUG -> "D"
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                Log.ASSERT -> "A"
                else -> "?"
            }

            val timestamp = dateFormat.format(Date())
            val line = "$timestamp $level/$tag: $message\n"

            FileWriter(logFile, true).use { writer ->
                writer.append(line)
                if (t != null) {
                    writer.append("$timestamp $level/$tag: ${t.stackTraceToString()}\n")
                }
            }
        } catch (_: Exception) {
            // Don't crash on logging failure
        }
    }
}
