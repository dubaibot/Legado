package com.lanyue.reader.core.net

import com.lanyue.reader.core.engine.EngineErrorCode
import com.lanyue.reader.core.engine.EngineException
import com.lanyue.reader.core.engine.NetworkException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.conscrypt.Conscrypt
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * 4.6 通道一：OkHttp + Conscrypt。默认通道，普通 API 源走这里。
 */
class OkHttpChannel(
    private val uaProvider: (String) -> String? = { null },
    private val cookieProvider: (String) -> String? = { null }
) {
    private val client: OkHttpClient by lazy {
        runCatching { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun execute(req: HttpRequest): HttpResponse = try {
        val builder = Request.Builder().url(req.url)
        req.headers.forEach { (k, v) -> builder.header(k, v) }

        // 4.13.1 UA 收口：源声明 UA 则覆盖，否则用内核原生 UA
        uaProvider(req.sourceId)?.let { builder.header("User-Agent", it) }

        // 4.6 Cookie 注入：系统 CookieManager 为唯一真源
        if (req.headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            cookieProvider(req.url)?.let { builder.header("Cookie", it) }
        }

        val body = when (req.method.uppercase()) {
            "POST", "PUT", "PATCH" -> (req.body ?: ByteArray(0)).toRequestBody()
            else -> null
        }

        client.newCall(builder.method(req.method.uppercase(), body).build()).execute().use { resp ->
            HttpResponse(
                code = resp.code,
                body = resp.body?.string(),
                headers = resp.headers.toMultimap()
                    .mapValues { entry -> entry.value.firstOrNull() ?: "" },
                finalUrl = resp.request.url.toString(),
                channel = "okhttp"
            )
        }
    } catch (e: java.net.SocketTimeoutException) {
        throw EngineException(EngineErrorCode.TIMEOUT, "okhttp timeout: ${req.url}", e)
    } catch (e: NetworkException) {
        throw e
    } catch (e: Exception) {
        throw NetworkException("okhttp failed: ${e.message}", null, e)
    }
}
