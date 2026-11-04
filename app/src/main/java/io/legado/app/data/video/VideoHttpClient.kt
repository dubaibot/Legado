package io.legado.app.data.video

import io.legado.app.help.http.SSLHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 影视源共享 HTTP 客户端 + 响应缓存。
 *
 * 使用独立 OkHttpClient（不继承 okHttpClient 的拦截器链），对齐薯条版 catvod.OkHttp：
 * - 信任所有证书（sslSocketFactory + hostnameVerifier）
 * - 不跟随重定向（followRedirects=false）
 * - 不发送 Accept-Encoding（避免 gzip 解压失败）
 * - 系统 DNS（不使用 DohDns）
 * - 30s 超时
 *
 * 响应缓存带 TTL，key = "GET:url"，避免重复网络请求。
 */
object VideoHttpClient {

    private const val TAG = "VideoHttpClient"
    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L // 5 分钟

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 缓存条目 */
    private data class CacheEntry(
        val body: String,
        val timestamp: Long,
    )

    private val responseCache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 执行 GET 请求，带缓存。
     * @param ttlMs 缓存有效期，默认 5 分钟
     */
    fun get(url: String, ttlMs: Long = DEFAULT_TTL_MS, forceRefresh: Boolean = false): String? {
        return get(url, emptyMap(), ttlMs, forceRefresh)
    }

    /**
     * 执行 GET 请求，带自定义 header，带缓存。
     * @param ttlMs 缓存有效期，默认 5 分钟
     */
    fun get(url: String, headers: Map<String, String>, ttlMs: Long = DEFAULT_TTL_MS, forceRefresh: Boolean = false): String? {
        val cacheKey = "GET:$url"
        if (!forceRefresh) {
            val entry = responseCache[cacheKey]
            if (entry != null && System.currentTimeMillis() - entry.timestamp < ttlMs) {
                return entry.body
            }
        }
        val body = doGet(url, headers) ?: return null
        if (body.isNotEmpty()) {
            responseCache[cacheKey] = CacheEntry(body, System.currentTimeMillis())
        }
        return body
    }

    /**
     * 执行 GET 请求，返回 JSON 对象。
     */
    fun getJson(url: String, ttlMs: Long = DEFAULT_TTL_MS, forceRefresh: Boolean = false): com.google.gson.JsonObject? {
        return getJson(url, emptyMap(), ttlMs, forceRefresh)
    }

    /**
     * 执行 GET 请求，带自定义 header，返回 JSON 对象。
     * 解析失败时抛出异常（含响应内容前 200 字符），便于 UI 可见错误信息。
     */
    fun getJson(url: String, headers: Map<String, String>, ttlMs: Long = DEFAULT_TTL_MS, forceRefresh: Boolean = false): com.google.gson.JsonObject? {
        val body = get(url, headers, ttlMs, forceRefresh) ?: return null
        return try {
            com.google.gson.JsonParser.parseString(body).getAsJsonObject()
        } catch (e: Exception) {
            io.legado.app.utils.LogUtils.d(TAG, "getJson parse failed: $url, body=${body.take(200)}")
            throw IllegalStateException("JSON 解析失败: $url\n响应前200字符: ${body.take(200)}")
        }
    }

    /** 清理过期缓存 */
    fun clearExpiredCache() {
        val now = System.currentTimeMillis()
        responseCache.entries.removeAll { entry ->
            now - entry.value.timestamp > DEFAULT_TTL_MS * 2
        }
    }

    /** 清空全部缓存 */
    fun clearCache() {
        responseCache.clear()
    }

    /** 清空指定 URL 前缀的缓存 */
    fun invalidatePrefix(prefix: String) {
        responseCache.entries.removeAll { entry ->
            entry.key.startsWith(prefix)
        }
    }

    private fun doGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val builder = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
            headers.forEach { (k, v) -> builder.header(k, v) }
            val request = builder.build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (body.isNullOrBlank()) {
                    io.legado.app.utils.LogUtils.d(TAG, "http $url -> code=${resp.code}, body empty")
                    null
                } else {
                    if (!resp.isSuccessful) {
                        io.legado.app.utils.LogUtils.d(TAG, "http $url -> non-2xx code=${resp.code}, bodyLen=${body.length}")
                    }
                    body
                }
            }
        } catch (e: Exception) {
            io.legado.app.utils.LogUtils.d(TAG, "doGet failed: $url: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
