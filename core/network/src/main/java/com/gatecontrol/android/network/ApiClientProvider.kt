package com.gatecontrol.android.network

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiClientProvider @Inject constructor(
    private val authInterceptor: AuthInterceptor,
    @ApplicationContext private val context: Context
) {
    private val cache = mutableMapOf<String, ApiClient>()
    private val lock = Any()

    /**
     * DNS cache for VPN-safe resolution. When the VPN is active, Android's
     * system DNS points to the VPN-internal resolver (10.8.0.1) but the
     * GateControl app is excluded from the VPN (VpnService requirement).
     * System DNS lookups fail with EAI_NODATA because 10.8.0.1 is unreachable
     * outside the tunnel. We cache DNS results resolved BEFORE the VPN starts
     * and fallback to them if system DNS lookup fails.
     */
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    /**
     * Pre-resolves the domain in [baseUrlStr] and populates [dnsCache].
     * Excludes VPN-internal private IPs (10.8.x.x) to ensure connectivity.
     * * 【切断控制】: 采用 Google 兜底策略时，该方法必须瞬间拦截，不解析、不污染 dnsCache。
     */
    fun preResolveDns(baseUrlStr: String) {
        // 安全隔离防线：如果是空路径、默认斜杠或已转换的 Google 保活地址，立刻切断，绝不执行后续 DNS 解析
        if (baseUrlStr.isBlank() || baseUrlStr == "/" || baseUrlStr == GOOGLE_KEEPALIVE_URL) return

        try {
            val url = URL(baseUrlStr)
            val host = url.host
            if (host.isNullOrBlank()) return

            val addresses = Dns.SYSTEM.lookup(host)
            // 原版核心技术：过滤隧道内部私有网段，确保 excluded 应用通信不陷入 Android 网卡死锁
            val filtered = addresses.filter { !it.hostAddress.startsWith("10.8.") }
            if (filtered.isNotEmpty()) {
                dnsCache[host] = filtered
            }
        } catch (_: Exception) {
            // Pre-resolution failure should not break the app flow
        }
    }

    /**
     * Clears all cached DNS records.
     */
    fun clearDnsCache() {
        dnsCache.clear()
    }

    /**
     * Gets or creates an [ApiClient] for the given [baseUrl].
     */
    fun getClient(baseUrl: String): ApiClient {
        synchronized(lock) {
            // 路由入口隔离：检测到用户尚未配置服务器（为空或为默认 "/"）时，将其分流到特定的 Google 安全键上
            // 从而保护原始 cache 池，当后续正常的服务器 URL 传入时，依然能够无缝走正常的后续执行分支
            val targetKey = if (baseUrl.isBlank() || baseUrl == "/") {
                GOOGLE_KEEPALIVE_URL
            } else {
                baseUrl
            }

            return cache.getOrPut(targetKey) {
                buildClient(targetKey)
            }
        }
    }

    /**
     * Builds a [Retrofit] client for the specified [baseUrl].
     */
    private fun buildClient(baseUrl: String): ApiClient {
        // 二次防御：判定当前是否处于 Google 兜底状态，并确保喂给 Retrofit 的 Url 绝对合法
        val isGoogleFallback = baseUrl == GOOGLE_KEEPALIVE_URL
        val safeBaseUrl = if (isGoogleFallback || (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://"))) {
            GOOGLE_KEEPALIVE_URL
        } else {
            baseUrl
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (isDebuggable()) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        // 开始组装专属的 OkHttpClient 架构
        val okHttpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)

        // 【关键业务切断防线】
        // 如果当前是 Google 兜底保活客户端，绝对不挂载包含核心业务身份、鉴权 Token、主机名审计等敏感数据的 authInterceptor。
        // 这在网络请求的最底层构筑了一道绝缘层，彻底切断核心敏感数据流向公网公共域名的任何可能。
        if (!isGoogleFallback) {
            okHttpClientBuilder.addInterceptor(authInterceptor)
        }

        // 挂载原版完备的高可靠内存自定义加密对抗 DNS 逻辑
        okHttpClientBuilder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: Exception) {
                    // 当 VPN 运行时整机 DNS 瘫痪时，这是外壳 excluded 进程唯一的网络自救盾牌
                    dnsCache[hostname] ?: throw e
                }
            }
        })

        val okHttpClient = okHttpClientBuilder.build()

        val gson = GsonBuilder()
            .registerTypeAdapterFactory(LenientBooleanAdapterFactory())
            .create()

        return Retrofit.Builder()
            .baseUrl(safeBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiClient::class.java)
    }

    /**
     * Checks if the application is debuggable.
     */
    private fun isDebuggable(): Boolean {
        return try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            false
        }
    }

    /** Factory that applies LenientBooleanAdapter to both Boolean and Boolean? fields. */
    private class LenientBooleanAdapterFactory : TypeAdapterFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != Boolean::class.java && type.rawType != Boolean::class.javaPrimitiveType) {
                return null
            }
            return LenientBooleanAdapter() as TypeAdapter<T>
        }
    }

    /** Reads JSON booleans, numbers (0/1), and strings ("true"/"false") as Boolean. */
    private class LenientBooleanAdapter : TypeAdapter<Boolean>() {
        override fun write(out: JsonWriter, value: Boolean?) {
            out.value(value)
        }

        override fun read(reader: JsonReader): Boolean {
            return when (reader.peek()) {
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NUMBER -> reader.nextInt() != 0
                JsonToken.STRING -> reader.nextString().equals("true", ignoreCase = true)
                JsonToken.NULL -> { reader.nextNull(); false }
                else -> { reader.skipValue(); false }
            }
        }
    }

    // 💡 必须在 companion object 中声明为编译期常量 (const val)
    // 这样才能让 Retrofit 的 Lint 静态注解处理器以及 Hilt 生成代码在编译期顺利读取 URL
    companion object {
        private const val GOOGLE_KEEPALIVE_URL = "https://www.google.com/"
    }
}
