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
    
    // 保留文件1强大的高可靠内存 DNS 缓存
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    /**
     * 预解析域名 IP，防止拉起 VPN 后 excluded 应用遭遇 Android 系统 DNS 路由死锁
     */
    fun preResolveDns(baseUrlStr: String) {
        if (baseUrlStr.isBlank() || baseUrlStr == "/") return
        try {
            val url = URL(baseUrlStr)
            val host = url.host
            if (host.isNullOrBlank()) return

            val addresses = Dns.SYSTEM.lookup(host)
            // 过滤掉 VPN 内部的私有网段（例如 10.8.x.x），只保留真正的公网出口 IP
            val filtered = addresses.filter { !it.hostAddress.startsWith("10.8.") }
            if (filtered.isNotEmpty()) {
                dnsCache[host] = filtered
            }
        } catch (_: Exception) {
            // 预解析失败不破坏主流程，交由运行时动态处理
        }
    }

    fun clearDnsCache() {
        dnsCache.clear()
    }

    /**
     * 获取或创建 API 客户端
     */
    fun getClient(baseUrl: String): ApiClient {
        synchronized(lock) {
            // 核心修复：如果传进来的是空或斜杠，我们在最外层就统一收敛其 Key，避免缓存污染
            val targetKey = if (baseUrl.isBlank() || baseUrl == "/") "https://www.google.com/" else baseUrl
            return cache.getOrPut(targetKey) {
                buildClient(targetKey)
            }
        }
    }

    /**
     * 构建真正的 Retrofit 客户端
     */
    private fun buildClient(baseUrl: String): ApiClient {
        // 健壮性防御：二次确保协议头合法
        val safeBaseUrl = if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            "https://www.google.com/"
        } else {
            baseUrl
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (isDebuggable()) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        // 组装兼顾“系统级突围”与“自研内存DNS兜底”的 OkHttpClient
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .addInterceptor(authInterceptor)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        Dns.SYSTEM.lookup(hostname)
                    } catch (e: Exception) {
                        // 当 VPN 拉起导致系统 DNS 瘫痪时，这里是整个 App 的最后一面网络盾牌
                        dnsCache[hostname] ?: throw e
                    }
                }
            })
            .build()

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
     * 修复文件2中缺失的动态 Debug 状态读取函数
     */
    private fun isDebuggable(): Boolean {
        return try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            false
        }
    }

    /** 允许宽松解析 Boolean 的适配器工厂 */
    private class LenientBooleanAdapterFactory : TypeAdapterFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != Boolean::class.java && type.rawType != Boolean::class.javaPrimitiveType) {
                return null
            }
            return LenientBooleanAdapter() as TypeAdapter<T>
        }
    }

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
