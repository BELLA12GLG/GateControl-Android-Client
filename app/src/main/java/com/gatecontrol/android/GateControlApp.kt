package com.gatecontrol.android

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.service.TunnelStateHolder
import com.gatecontrol.android.tunnel.TunnelManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class GateControlApp : Application() {

    override fun attachBaseContext(base: Context) {
        // Install crash handler as early as possible — before Hilt init in super.attachBaseContext
        installCrashLogger(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        try {
            super.onCreate()
        } catch (e: Throwable) {
            writeCrashToFile("onCreate", e)
            throw e
        }

        try {
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Timber.plant(Timber.DebugTree())
            }
            // Always plant a file-based tree so logs are available in release builds
            Timber.plant(FileLoggingTree(this))
        } catch (e: Throwable) {
            writeCrashToFile("timber_init", e)
            throw e
        }

        // Register singletons for Quick Settings tile (which can't use Hilt DI)
        try {
            val entryPoint = EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java)
            TunnelStateHolder.tunnelManager = entryPoint.tunnelManager()
            TunnelStateHolder.setupRepository = entryPoint.setupRepository()

            // v6.2: pick up the persisted "file logging" toggle and apply it
            // to the planted FileLoggingTree. Default is true so first launch
            // (no preference set) keeps the previous behavior.
            //
            // Done on a background dispatcher because DataStore reads suspend
            // on the IO pool. We don't block app startup waiting for the
            // first emission — if a log line happens before this completes,
            // the default of `enabled = true` writes it as before.
            val settingsRepo = entryPoint.settingsRepository()
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                settingsRepo.getLoggingEnabled().collect { on ->
                    FileLoggingTree.setEnabled(on)
                }
            }

            // v6.7: bootstrap DnsResolver config from DataStore.
            //
            // Before v6.7, DnsResolver's config (static hosts / DoH URL /
            // cache enabled / cache TTL) was ONLY set by SettingsViewModel
            // when the user opened the Settings screen. If the user never
            // opened Settings — common when the tunnel auto-connects via
            // boot receiver or Quick Tile — the resolver kept its default-
            // empty config, so static hosts didn't apply, DoH didn't run,
            // and the cache (while functionally enabled) was invisible
            // because no resolve calls ever happened through it.
            //
            // This collector runs once at App start AND on every change,
            // making DnsResolver's config track DataStore independently of
            // any UI lifecycle.
            val dnsResolver = entryPoint.dnsResolver()
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.flow.combine(
                    settingsRepo.getStaticHostsJson(),
                    settingsRepo.getDohUpstreamUrl(),
                    settingsRepo.getDnsCacheEnabled(),
                    settingsRepo.getDnsCacheTtlSeconds(),
                ) { json, doh, cacheOn, ttl ->
                    com.gatecontrol.android.network.DnsResolver.Config(
                        staticHosts = com.gatecontrol.android.network.DnsResolver
                            .parseStaticHostsJson(json),
                        dohUpstreamUrl = doh,
                        cacheEnabled = cacheOn,
                        cacheTtlSeconds = ttl,
                    )
                }.collect { config ->
                    dnsResolver.updateConfig(config)
                    Timber.d(
                        "DnsResolver config updated: static=%d hosts, doh=%s, cache=%s, ttl=%ds",
                        config.staticHosts.size,
                        if (config.dohUpstreamUrl.isBlank()) "system" else "doh",
                        config.cacheEnabled,
                        config.cacheTtlSeconds,
                    )
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to register TunnelStateHolder singletons")
        }

        // Initialize FreeRDP's GlobalApp.sessionMap, which is normally set in
        // GlobalApp.onCreate(). Since GateControlApp extends Application (Hilt
        // requirement), not GlobalApp, that lifecycle never fires. Without this,
        // GlobalApp.createSession() NPEs on the null sessionMap when a user taps
        // an RDP route. Reflection avoids modifying the freerdp submodule.
        try {
            val sessionMapField = com.freerdp.freerdpcore.application.GlobalApp::class.java
                .getDeclaredField("sessionMap")
            sessionMapField.isAccessible = true
            if (sessionMapField.get(null) == null) {
                sessionMapField.set(null, java.util.Collections.synchronizedMap(
                    java.util.HashMap<Long, Any>()
                ))
                Timber.d("FreeRDP sessionMap initialized")
            }
        } catch (e: Throwable) {
            Timber.w(e, "FreeRDP GlobalApp init failed — embedded RDP may crash")
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun tunnelManager(): TunnelManager
        fun setupRepository(): SetupRepository
        fun settingsRepository(): com.gatecontrol.android.data.SettingsRepository
        fun dnsResolver(): com.gatecontrol.android.network.DnsResolver
    }

    private fun installCrashLogger(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashToFile("uncaught_${thread.name}", throwable)
            } catch (_: Exception) {
                // Last resort
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private fun writeCrashToFile(tag: String, throwable: Throwable) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "gatecontrol_crash_${tag}_$timestamp.txt"

            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("=== GateControl Crash Report ===")
            pw.println("Tag: $tag")
            pw.println("Time: $timestamp")
            pw.println("Android: ${android.os.Build.VERSION.SDK_INT}")
            pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            pw.println()
            throwable.printStackTrace(pw)
            pw.flush()
            val content = sw.toString()

            // Try multiple locations — at least one should work
            val candidates = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStorageDirectory(),
                File("/sdcard/Download"),
                File("/storage/emulated/0/Download"),
            )

            for (dir in candidates) {
                try {
                    dir.mkdirs()
                    File(dir, fileName).writeText(content)
                    return // success
                } catch (_: Exception) {
                    // try next
                }
            }
        }
    }
}
