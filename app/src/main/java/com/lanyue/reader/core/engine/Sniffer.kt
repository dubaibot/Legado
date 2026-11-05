package com.lanyue.reader.core.engine

import kotlinx.coroutines.channels.Channel

/**
 * 4.8 Sniffer。
 *
 * - 独立渲染池；三重拦截（shouldInterceptRequest + onLoadResource + JS hook）
 * - 仅监听 URL 与头，不读响应体
 * - 结束 about:blank + clearHistory
 * - Cookie 复用；不暴露 JS 桥
 * - 命中规则：M3U8/MP4/FLV/DASH 特征 + Content-Type + 域名白名单
 *
 * ADR-023：Sniffer 池内仅两级队列（用户播放嗅探 > parses type 3）。
 * DomParser 真 DOM 查询属复用 iframe 沙箱池，不进本池。
 */
class Sniffer(
    private val renderPool: SnifferRenderPool,
    private val cookieBridge: CookieBridge
) {

    /** ADR-023 两级优先级。 */
    enum class Priority { USER_PLAYBACK, PARSE_TYPE3 }

    data class Hit(
        val url: String,
        val contentType: String?,
        val headers: Map<String, String>,
        val priority: Priority
    )

    companion object {
        private val MEDIA_SUFFIXES = listOf(".m3u8", ".mp4", ".flv", ".mpd", ".ts", ".mkv", ".mov")
        private val MEDIA_CONTENT_TYPES = listOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "video/mp4",
            "video/x-flv",
            "application/dash+xml",
            "video/mp2t"
        )
        private val DASH_HINT = listOf("manifest.mpd", "type=mpd")
    }

    /**
     * 4.8 命中判定。Content-Type 与 URL 特征需任一命中，且域名在白名单内。
     */
    fun isMedia(url: String, contentType: String?, domainAllowed: (String) -> Boolean): Boolean {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        if (host.isEmpty() || !domainAllowed(host)) return false

        val lower = url.lowercase()
        if (MEDIA_SUFFIXES.any { lower.contains(it) }) return true
        if (DASH_HINT.any { lower.contains(it) }) return true
        if (contentType != null && MEDIA_CONTENT_TYPES.any { contentType.lowercase().contains(it) }) return true
        return false
    }

    /**
     * 打开页面并收集媒体命中。
     * [priority] 决定池内队列层级（ADR-023）。
     */
    suspend fun sniff(
        pageUrl: String,
        priority: Priority,
        domainAllowed: (String) -> Boolean,
        timeoutMs: Long = 20_000L
    ): List<Hit> {
        val hits = mutableListOf<Hit>()
        val channel = Channel<Hit>(Channel.UNLIMITED)

        val session = renderPool.open(
            url = pageUrl,
            cookies = cookieBridge.read(pageUrl),
            onSubresource = { url, ct, headers ->
                if (isMedia(url, ct, domainAllowed)) {
                    channel.trySend(Hit(url, ct, headers, priority))
                }
                null // 返回 null 放行，不阻塞页面加载
            }
        )

        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                for (hit in channel) {
                    hits += hit
                    // 首个命中即返回，播放场景不需要继续嗅探
                    break
                }
            }
        } finally {
            // 4.8 结束清场：about:blank + clearHistory
            session?.navigateToBlankAndClear()
            session?.close()
            channel.close()
        }
        return hits
    }
}

/** 4.8 独立渲染池。与 JsRuntime 池、DomParser 池严格隔离。 */
interface SnifferRenderPool {
    fun open(
        url: String,
        cookies: String?,
        onSubresource: (url: String, contentType: String?, headers: Map<String, String>) -> String?
    ): SnifferSession?
}

interface SnifferSession {
    fun navigateToBlankAndClear()
    fun close()
}
