package io.legado.app.data.video

import io.legado.app.help.http.okHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 影视源共享 HTTP 客户端 + 响应缓存。
 *
 * 所有 VideoSource 实现共用一个连接池（okHttpClient），避免每个源各自创建 OkHttpClient。
 * 响应缓存带 TTL，key = "GET:url"，避免重复网络请求。
 */
object VideoHttpClient {

    private const val TAG = "VideoHttpClient"
    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L // 5 分钟

    private val client: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
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
        val cacheKey = "GET:$url"
        if (!forceRefresh) {
            val entry = responseCache[cacheKey]
            if (entry != null && System.currentTimeMillis() - entry.timestamp < ttlMs) {
                return entry.body
            }
        }
        val body = doGet(url) ?: return null
        if (body.isNotEmpty()) {
            responseCache[cacheKey] = CacheEntry(body, System.currentTimeMillis())
        }
        return body
    }

    /**
     * 执行 GET 请求，返回 JSON 对象。
     */
    fun getJson(url: String, ttlMs: Long = DEFAULT_TTL_MS, forceRefresh: Boolean = false): com.google.gson.JsonObject? {
        val body = get(url, ttlMs, forceRefresh) ?: return null
        return try {
            com.google.gson.JsonParser.parseString(body).getAsJsonObject()
        } catch (e: Exception) {
            null
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

    private fun doGet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .header("Accept", "*/*")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    io.legado.app.utils.LogUtils.d(TAG, "http $url -> ${resp.code}")
                    return null
                }
                resp.body?.string() ?: null
            }
        } catch (e: Exception) {
            io.legado.app.utils.LogUtils.d(TAG, "doGet failed: $url: ${e.message}")
            null
        }
    }
}
