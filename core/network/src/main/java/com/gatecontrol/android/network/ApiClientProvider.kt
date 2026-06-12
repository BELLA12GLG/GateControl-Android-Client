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
    
    // 保留原版强大的高性能高可靠内存 DNS 缓存
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    // 定义固定的 Google 保活隔离地址常量
    private val GOOGLE_KEEPALIVE_URL = "https://www.google.com/"

    /**
     * 预解析域名 IP。
     * 当采用 Google.com 兜底时，此方法直接切断执行，不解析、不污染 dnsCache。
     */
    fun preResolveDns(baseUrlStr: String) {
        // 【切断控制】如果是无效路径、斜杠或已被归拢为 Google 兜底的地址，直接切断，拒绝处理
        if (baseUrlStr.isBlank() || baseUrlStr == "/" || baseUrlStr == GOOGLE_KEEPALIVE_URL) return
        
        try {
            val url = URL(baseUrlStr)
            val host = url.host
            if (host.isNullOrBlank()) return

            val addresses = Dns.SYSTEM.lookup(host)
            // 原版核心逻辑：过滤掉 VPN 内部的私有网段（例如 10.8.x.x），只保留真正的公网出口 IP
            val filtered = addresses.filter { !it.hostAddress.startsWith("10.8.") }
            if (filtered.isNotEmpty()) {
                dnsCache[host] = filtered
            }
        } catch (_: Exception) {
            // 预解析失败不破坏主流程
        }
    }

    /**
     * 彻底清除 DNS 缓存
     */
    fun clearDnsCache() {
        dnsCache.clear()
    }

    /**
     * 获取或创建 API 客户端
     */
    fun getClient(baseUrl: String): ApiClient {
        synchronized(lock) {
            // 在路由映射入口处，如果发现是没有配置服务器（空或"/"），将其安全分流到固定的 Google 地址键值上
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
     * 构建真正的 Retrofit / OkHttp 客户端
     */
    private fun buildClient(baseUrl: String): ApiClient {
        // 二次防御性验证，确保传入 Retrofit 的协议头绝对合法
        val isGoogleFallback = baseUrl == GOOGLE_KEEPALIVE_URL
        val safeBaseUrl = if (isGoogleFallback || (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://"))) {
            GOOGLE_KEEPALIVE_URL
        } else {
            baseUrl
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (isDebuggable()) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        // 组装 OkHttpClient
        val okHttpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)

        // 【关键安全切断】
        // 如果当前是 Google 兜底的保活客户端，绝不挂载包含身份令牌、握手鉴权、主机名审计等敏感信息的 authInterceptor
        // 从而在应用网络请求的最底层筑起一道防火墙，彻底切断核心业务数据外泄至 Google 的通道。
        if (!isGoogleFallback) {
            okHttpClientBuilder.addInterceptor(authInterceptor)
        }

        // 挂载原版的自研高抗封锁 Dns 系统
        okHttpClientBuilder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: Exception) {
                    // 当 VPN 拉起导致 Android 路由发生死锁时，这里是保障 excluded 应用正常通信的最后一面网络盾牌
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
     * 动态读取当前应用的 Debuggable 状态，以便在 Release 环境下自动关闭日志，保证企业级安全。
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
}
