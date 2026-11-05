package com.lanyue.reader.core.security

import java.net.InetAddress
import java.net.URI
import java.util.UUID

/**
 * 5.1 桥白名单：仅 3 个异步方法，仅 Runner 及沙箱。
 * Sniffer / 渲染 WebView 不加桥（调用方保证）。
 */
object BridgeWhitelist {
    const val NATIVE_LOG = "nativeLog"
    const val NATIVE_FETCH = "nativeFetch"
    const val NATIVE_POST_MESSAGE = "nativePostMessage"

    /** 5.1 原生 → JS 回调用固定名，并冻结（5.3 防伪造）。 */
    const val DISPATCH_RESULT = "__dispatchResult"

    val ALLOWED = setOf(NATIVE_LOG, NATIVE_FETCH, NATIVE_POST_MESSAGE)
}

/** 5.2 nativeFetch 校验结果。 */
sealed class FetchVerdict {
    data class Allow(val normalizedUrl: String, val method: String) : FetchVerdict()
    data class Deny(val reason: String) : FetchVerdict()
}

/**
 * 5.2 nativeFetch 校验链：Scheme → 内网拦截 → DNS 后 IP → 重定向逐跳
 * → 域名白名单 → 大小限制 → JSON Schema。
 *
 * ADR-021：默认仅 GET/HEAD；书源/订阅源规则内 method=POST 即视为源显式声明；
 * 弹幕源对 dandanplay v2 端点放行 POST；drpy 源 req() 默认放行 GET/POST。
 */
class NativeFetchGuard(
    private val domainWhitelist: (host: String) -> Boolean,
    private val maxResponseBytes: Long = 8L * 1024 * 1024
) {

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https")
    }

    fun verify(url: String, method: String, declaredByRule: Boolean, postAllowed: Boolean): FetchVerdict {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return FetchVerdict.Deny("malformed url")
        val scheme = uri.scheme?.lowercase() ?: return FetchVerdict.Deny("missing scheme")
        if (scheme !in ALLOWED_SCHEMES) return FetchVerdict.Deny("scheme not allowed: $scheme")

        val host = uri.host ?: return FetchVerdict.Deny("missing host")
        if (isInternalHost(host)) return FetchVerdict.Deny("internal host blocked: $host")
        if (!domainWhitelist(host)) return FetchVerdict.Deny("domain not whitelisted: $host")

        val normalized = method.uppercase()
        val isWrite = normalized !in setOf("GET", "HEAD")
        if (isWrite) {
            if (!declaredByRule && !postAllowed) {
                return FetchVerdict.Deny("method $normalized requires explicit source declaration")
            }
            // 5.2：POST 仍必须过域名白名单与 SSRF 校验（上面已做）
        }
        return FetchVerdict.Allow(url, normalized)
    }

    /**
     * 内网 / 回环 / 链路本地拦截。
     * 注意：LocalParseProxy 的 127.0.0.1 播放地址只给 Media3 用，不经过本闸门。
     */
    fun isInternalHost(host: String): Boolean {
        val literal = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return literal.isLoopbackAddress ||
            literal.isSiteLocalAddress ||
            literal.isLinkLocalAddress ||
            literal.isAnyLocalAddress
    }

    /** 重定向逐跳校验：每次 Location 都要重新过 verify，不允许一跳放行到底。 */
    fun verifyHop(currentUrl: String, location: String): String? {
        val next = runCatching { URI(currentUrl).resolve(location).toString() }.getOrNull() ?: return null
        return when (verify(next, "GET", declaredByRule = true, postAllowed = false)) {
            is FetchVerdict.Allow -> next
            is FetchVerdict.Deny -> null
        }
    }

    fun maxBytes(): Long = maxResponseBytes
}

/**
 * 5.3 防伪造与限流。
 * - nativePostMessage 仅 SANDBOX / DOM_QUERY 通道可用
 * - taskId 为 UUID 闭包，不可预测
 * - 提交侧限流：单源 10/s、单域名 5/s、沙箱 20/s；结果回调不限流
 */
class SubmitGuard(
    private val perSourcePerSec: Int = 10,
    private val perDomainPerSec: Int = 5,
    private val perSandboxPerSec: Int = 20
) {
    private val sourceWindows = mutableMapOf<String, Window>()
    private val domainWindows = mutableMapOf<String, Window>()
    private val sandboxWindows = mutableMapOf<String, Window>()

    private class Window(var startMs: Long, var count: Int)

    @Synchronized
    fun allowSubmit(sourceId: String, host: String, sandboxId: String, now: Long = System.currentTimeMillis()): Boolean {
        fun check(map: MutableMap<String, Window>, key: String, limit: Int): Boolean {
            val w = map.getOrPut(key) { Window(now, 0) }
            if (now - w.startMs >= 1000L) {
                w.startMs = now
                w.count = 0
            }
            if (w.count >= limit) return false
            w.count += 1
            return true
        }
        return check(sourceWindows, sourceId, perSourcePerSec) &&
            check(domainWindows, host, perDomainPerSec) &&
            check(sandboxWindows, sandboxId, perSandboxPerSec)
    }

    fun newTaskId(): String = UUID.randomUUID().toString()
}

/**
 * 4.6 与 4.10 共用的「HTML 类请求」判定口径。
 * 文档明确要求：C.5 转发判定与 4.6 空响应判定共用同一函数，不允许各自发明。
 */
object HtmlRequestClassifier {

    private val HTML_SUFFIXES = setOf("html", "htm", "xhtml", "shtml", "php", "asp", "aspx", "jsp")

    fun isHtmlLike(url: String, headers: Map<String, String>): Boolean {
        val accept = headers.entries
            .firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value
            ?.lowercase()
            ?: ""
        if (accept.contains("text/html") || accept.contains("application/xhtml")) return true

        val path = url.substringBefore('?').substringBefore('#')
        val suffix = path.substringAfterLast('.', "").lowercase()
        if (suffix.isNotEmpty() && suffix in HTML_SUFFIXES) return true

        // 无后缀且无 Accept 声明：按 HTML 视作可疑，进入空响应判定
        return suffix.isEmpty()
    }

    /** 4.6 空响应判定：HTML 类请求 200 且 body < 1KB 且不含任何标签。 */
    fun isEmptyHtmlBody(body: String?): Boolean {
        if (body == null) return true
        if (body.length >= 1024) return false
        return !body.contains('<')
    }
}
