package com.lanyue.reader.core.engine

import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 4.7 DomParser。
 *
 * - 真 DOM：sandbox iframe，查询走 postMessage（实现见 [RealDomSession]）
 * - Jsoup 降级
 * - 会话式；HTML 阈值 2MB
 * - 动态等待 300ms + readyState + 500ms 静默，总 8s，超时 degraded
 * - enableScript 不可变更；默认禁脚本外部资源
 */
class DomParser(
    private val realDom: RealDomSession?,
    private val metrics: com.lanyue.reader.core.observability.LocalMetrics
) {

    companion object {
        const val HTML_SIZE_LIMIT = 2 * 1024 * 1024
        const val INITIAL_WAIT_MS = 300L
        const val QUIET_WINDOW_MS = 500L
        const val TOTAL_WAIT_MS = 8_000L
    }

    sealed class ParseResult {
        data class Ok(val document: Document, val degraded: Boolean) : ParseResult()
        data class Failed(val reason: String) : ParseResult()
    }

    /**
     * 4.13.3 降级链：真 DOM 失败 → jsoup。
     * [needsRealDom] 由源规则是否含 DOM 相关 JS 调用决定。
     */
    suspend fun parse(html: String, baseUrl: String?, needsRealDom: Boolean, traceId: String): ParseResult {
        if (html.length > HTML_SIZE_LIMIT) {
            return ParseResult.Failed("html exceeds 2MB limit: ${html.length}")
        }

        // 构造参数属性无法智能转换，先取出局部引用再判空
        val session = realDom
        if (needsRealDom && session != null) {
            val dom = runCatching { session.queryDocument(html, baseUrl, TOTAL_WAIT_MS) }.getOrNull()
            if (dom != null) return ParseResult.Ok(dom, degraded = false)
            metrics.degradation(traceId, "realdom->jsoup")
        }

        val doc = runCatching { Jsoup.parse(html, baseUrl ?: "") }.getOrNull()
            ?: return ParseResult.Failed("jsoup parse failed")
        return ParseResult.Ok(doc, degraded = true)
    }

    /**
     * 4.7 动态等待策略：300ms 首等 → 轮询 readyState → 500ms 静默 → 总 8s 超时 degraded。
     * 返回 true 表示页面被认为已稳定。
     */
    suspend fun awaitStable(readyStateProvider: () -> String, mutationCounter: () -> Int): Boolean {
        delay(INITIAL_WAIT_MS)
        val deadline = System.currentTimeMillis() + TOTAL_WAIT_MS
        var lastMutation = mutationCounter()
        var quietSince = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            if (readyStateProvider() == "complete") {
                val now = mutationCounter()
                if (now != lastMutation) {
                    lastMutation = now
                    quietSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - quietSince >= QUIET_WINDOW_MS) {
                    return true
                }
            }
            delay(100L)
        }
        return false
    }
}

/** 4.7 真 DOM 会话（sandbox iframe，查询走 postMessage）。 */
interface RealDomSession {
    /** 超时或失败返回 null，由调用方降级到 jsoup。 */
    suspend fun queryDocument(html: String, baseUrl: String?, timeoutMs: Long): Document?
    /** 4.7 会话式：同一会话内可多次查询。 */
    suspend fun select(sessionId: String, selector: String): List<String>
    suspend fun close(sessionId: String)
}
