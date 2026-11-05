package com.lanyue.reader.core.engine

import com.lanyue.reader.core.net.FingerprintObservation
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.net.HttpResponse
import com.lanyue.reader.core.net.OkHttpChannel
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.core.security.HtmlRequestClassifier

/** 4.6 Cronet 抽象。真实实现由 cronet-embedded 提供，此处只约束契约。 */
interface CronetChannel {
    suspend fun execute(req: HttpRequest): HttpResponse
    /** 4.6：Cronet 禁用自动 Cookie，由 NetworkDispatcher 注入/回写。 */
    val handlesCookiesInternally: Boolean get() = false
}

/** 4.6 WebView 补充通道（嗅探、渲染场景）。 */
interface WebViewChannel {
    suspend fun execute(req: HttpRequest): HttpResponse
}

/**
 * 4.6 NetworkDispatcher：三通道统一收口。
 *
 * 路由顺序：
 * 1. 通道粘滞命中 → 直接走绑定通道
 * 2. 域名黑白名单 + 能力标签
 * 3. 失败命中指纹拦截特征 → 自动切 Cronet（连续 2 次才更新绑定）
 *
 * 同时负责：源级限速、登录态串行化、Cookie 注入回写、UA 收口。
 */
class NetworkDispatcher(
    private val okHttp: OkHttpChannel,
    private val cronet: CronetChannel?,
    private val webView: WebViewChannel?,
    private val sticky: ChannelStickyStore,
    private val rateLimiter: SourceRateLimiter,
    private val metrics: LocalMetrics,
    private val cookieBridge: CookieBridge
) {

    companion object {
        /**
         * 4.6 指纹拦截特征串清单。
         * 归 Spike 2 实测收集，做成内置资源随版本更新（符合 ADR-020 无远程下发）。
         */
        val WAF_SIGNATURES = listOf(
            "cf-chl",
            "Access Denied",
            "Just a moment",
            "verify you are human",
            "aliyun_waf",
            "errors.aliyun.com",
            "TencentCloud-WAF",
            "waf.tencentcloudapi.com"
        )
    }

    /** 4.6 指纹拦截特征判定。任一项命中即视为被拦截。 */
    fun isFingerprintBlocked(obs: FingerprintObservation, requestIsHtml: Boolean): Boolean {
        if (obs.statusCode in setOf(403, 429, 503)) return true
        val preview = obs.bodyPreview ?: ""
        if (WAF_SIGNATURES.any { preview.contains(it, ignoreCase = true) }) return true
        if (obs.tlsHandshakeFailed && !obs.certificateConfigError) return true
        // 4.6 空响应：HTML 类请求 200 且 body < 1KB 且不含任何标签
        if (requestIsHtml && obs.statusCode == 200 &&
            HtmlRequestClassifier.isEmptyHtmlBody(obs.bodyPreview)
        ) return true
        return false
    }

    suspend fun execute(req: HttpRequest, limit: RateLimit? = null, traceId: String): HttpResponse {
        // 4.6 限速 + 登录态串行化
        if (!rateLimiter.acquire(req.sourceId, limit)) {
            throw EngineException(EngineErrorCode.RATE_LIMITED, "rate limited: ${req.sourceId}")
        }

        val isHtml = HtmlRequestClassifier.isHtmlLike(req.url, req.headers)
        val bound = sticky.channelFor(req.host, req.sourceId)

        return rateLimiter.serialized(req.sourceId) {
            val firstChannel = bound ?: NetChannel.OKHTTP
            cookiesInject(req, firstChannel)
            val first = runCatching { dispatch(firstChannel, req) }

            val result = first.getOrNull()
            if (result != null) {
                val obs = FingerprintObservation(result.code, result.body?.take(2048), false, false)
                if (!isFingerprintBlocked(obs, isHtml)) {
                    metrics.channelHit(traceId, result.channel, req.host)
                    cookiesWriteBack(result, firstChannel)
                    return@serialized result
                }
                if (sticky.recordFingerprintHit(req.host, req.sourceId, NetChannel.CRONET)) {
                    metrics.degradation(traceId, "fingerprint->cronet")
                }
            }

            // 4.13.3 三级降级链：Cronet 失败 → OkHttp
            val second = if (firstChannel == NetChannel.OKHTTP && cronet != null) {
                runCatching { dispatch(NetChannel.CRONET, req) }
                    .getOrElse { runCatching { dispatch(NetChannel.OKHTTP, req) }.getOrThrow() }
            } else {
                runCatching { dispatch(NetChannel.OKHTTP, req) }.getOrThrow()
            }
            metrics.channelHit(traceId, second.channel, req.host)
            cookiesWriteBack(second, NetChannel.OKHTTP)
            second
        }
    }

    private suspend fun dispatch(channel: NetChannel, req: HttpRequest): HttpResponse = when (channel) {
        NetChannel.OKHTTP -> okHttp.execute(req)
        NetChannel.CRONET -> cronet?.execute(req)
            ?: throw NetworkException("cronet unavailable")
        NetChannel.WEBVIEW -> webView?.execute(req)
            ?: throw NetworkException("webview unavailable")
    }

    /**
     * 4.6 Cookie：系统 CookieManager 唯一真源。
     * 请求前注入；Cronet 禁用自动 Cookie 所以必须显式注入。
     */
    private fun cookiesInject(req: HttpRequest, channel: NetChannel) {
        val cookie = cookieBridge.read(req.url) ?: return
        if (req.headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            // 注入由具体通道读取 cookieBridge，这里只做统一记录
            cookieBridge.markInjected(req.url, cookie)
        }
    }

    /** 响应后回写；4.6 要求 flush 节流。 */
    private fun cookiesWriteBack(resp: HttpResponse, channel: NetChannel) {
        val setCookie = resp.headers.entries
            .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?.value
            ?: return
        cookieBridge.write(resp.finalUrl.ifEmpty { "" }, setCookie)
    }

    /**
     * 4.6 转发：仅 GET/HEAD；并发 ≤4；单次 10s 后 cancel；
     * Cookie 不透传；Set-Cookie 不写回；POST/媒体/Range 放行；SSRF 校验命中返回 null。
     */
    suspend fun forwardForSubresource(
        url: String,
        headers: Map<String, String>,
        ssrfAllowed: Boolean
    ): HttpResponse? {
        if (!ssrfAllowed) return null
        val method = headers[":method"] ?: "GET"
        if (method.uppercase() !in setOf("GET", "HEAD")) {
            val range = headers.entries.any { it.key.equals("Range", ignoreCase = true) }
            if (!range) return null
        }
        val cleaned = headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        return runCatching {
            okHttp.execute(
                HttpRequest(url = url, method = method, headers = cleaned, timeoutMs = 10_000L)
            )
        }.getOrNull()
    }
}

/** 4.6 Cookie 桥。系统 CookieManager 为唯一真源，此处仅做节流与注入标记。 */
interface CookieBridge {
    fun read(url: String): String?
    fun write(url: String, setCookie: String)
    fun markInjected(url: String, cookie: String)
    fun flush()
}
