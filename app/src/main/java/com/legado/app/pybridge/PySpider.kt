package com.legado.app.pybridge

import io.legado.app.help.http.okHttpClient
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import androidx.annotation.Keep

/**
 * py 源网络桥接基类：把 Python 的网络请求桥接到 Java 层 OkHttp。
 *
 * hipy/FongMi 源通过 `from base.spider import Spider` 导入此基类，
 * 然后用 `self.fetch()` / `self.post()` / `self.postJson()` 发请求。
 * 所有请求走 OkHttp 连接池，享受 HTTP/2 + 正常 TLS 栈。
 *
 * 不依赖 Chaquopy 的 requests（底层 HttpURLConnection），
 * 避免 TLS 指纹拦截、HTTP/2 缺失等问题。
 */
@Keep
open class Spider {

    /** GET 请求：url 拼接 query params，附加 headers */
    open fun fetch(url: String, params: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): String? {
        return httpGet(url, params, headers)
    }

    /** POST 表单：FormBody 提交，附加 headers */
    open fun post(url: String, data: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): String? {
        return httpPostForm(url, data, headers)
    }

    /** POST JSON：RequestBody JSON 提交，附加 headers */
    open fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String? {
        return httpPostJson(url, json, headers)
    }

    /** MD5 哈希（小写 hex） */
    open fun md5(text: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Base64 编码（标准） */
    open fun base64Encode(text: String): String {
        return java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    }

    /** Base64 解码（标准） */
    open fun base64Decode(text: String): String {
        return try {
            String(java.util.Base64.getDecoder().decode(text), Charsets.UTF_8)
        } catch (e: Exception) {
            text
        }
    }

    /** Base64 URL 安全编码 */
    open fun base64UrlEncode(text: String): String {
        return java.util.Base64.getUrlEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    }

    /** Base64 URL 安全解码 */
    open fun base64UrlDecode(text: String): String {
        return try {
            String(java.util.Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
        } catch (e: Exception) {
            text
        }
    }

    /** 日志输出（通过 Android LogUtils） */
    open fun log(msg: String) {
        io.legado.app.utils.LogUtils.d("PySpider", msg)
    }

    // ---- internal ----

    private fun httpGet(url: String, params: Map<String, String>, headers: Map<String, String>): String? {
        val fullUrl = buildUrlWithParams(url, params)
        return executeRequest(buildGetRequest(fullUrl, headers))
    }

    private fun httpPostForm(url: String, data: Map<String, String>, headers: Map<String, String>): String? {
        val builder = FormBody.Builder()
        data.forEach { (k, v) -> builder.add(k, v) }
        return executeRequest(buildPostRequest(url, builder.build(), headers))
    }

    private fun httpPostJson(url: String, json: String, headers: Map<String, String>): String? {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        return executeRequest(buildPostRequest(url, body, headers))
    }

    private fun buildGetRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun buildPostRequest(url: String, body: RequestBody, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun buildUrlWithParams(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val sep = if (url.contains("?")) "&" else "?"
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        return "$url$sep$query"
    }

    private fun executeRequest(request: Request): String? {
        return try {
            val client = okHttpClient.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { resp: Response ->
                if (!resp.isSuccessful) {
                    log("http ${request.url} -> ${resp.code}")
                    return null
                }
                resp.body?.string() ?: null
            }
        } catch (e: Exception) {
            log("request failed: ${request.url}: ${e.message}")
            null
        }
    }
}
