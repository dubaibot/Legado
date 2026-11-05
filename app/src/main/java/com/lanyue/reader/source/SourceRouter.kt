package com.lanyue.reader.source

import com.lanyue.reader.core.engine.ProductErrorCode
import com.lanyue.reader.core.engine.ProductException
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.source.model.ContentResult
import com.lanyue.reader.source.model.DanmakuEvent
import com.lanyue.reader.source.model.DanmakuMatch
import com.lanyue.reader.source.model.DanmakuMatchRequest
import com.lanyue.reader.source.model.IDanmakuSource
import com.lanyue.reader.source.model.IBookSource
import com.lanyue.reader.source.model.ISubscriptionSource
import com.lanyue.reader.source.model.IVideoSource
import com.lanyue.reader.source.model.PlayResult
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.VideoEpisode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 4.x 源调度层（唯一调用入口）。
 *
 * 职责边界：
 * - 能力判定前置：不具备能力就不调用（1.4.2），避免源内部抛异常
 * - 并发编排：多源搜索并行、结果按源顺序合并、单源失败不影响其它源
 * - 失败隔离：连续失败达阈值后本次会话内降级（不写库，写库由 DAO 负责）
 * - 内容形态分流：ContentResult.Audio 直连播放器，不做 HTML 解析
 * - 弹幕匹配：无匹配时抛 DANMAKU_NOT_MATCHED，UI 显示「未匹配到弹幕」
 *
 * 明确不做：
 * - 不实现解析规则（规则引擎在内核）
 * - 不实现播放（PlayerManager）
 * - 不做 UI 提示文案（只给错误码，文案在 strings.xml）
 */
class SourceRouter(
    private val metrics: LocalMetrics,
    /** 单源搜索超时，超时源视为本次失败但不影响其它源。 */
    private val perSourceTimeoutMs: Long = 12_000L,
    /** 连续失败阈值：达到后本次会话内不再调用该源。 */
    private val failThreshold: Int = 3
) {

    private val bookFailCount = HashMap<String, Int>()
    private val videoFailCount = HashMap<String, Int>()

    // ---------------- 书源 ----------------

    /**
     * 多源并行搜索。返回顺序保持 sources 顺序（用户可见稳定），
     * 不按耗时排序，避免每次结果顺序抖动。
     */
    suspend fun searchBooks(
        sources: List<IBookSource>,
        key: String,
        page: Int,
        traceId: String
    ): List<SearchBookItem> = coroutineScope {
        val usable = sources.filter { it.capabilityFlags.contains(SourceCapability.SEARCHABLE) && !isIsolated(it.sourceId) }

        val deferred = usable.map { source ->
            source to async {
                withTimeoutOrNull(perSourceTimeoutMs) {
                    runCatching { source.search(key, page) }
                        .onSuccess { markSuccess(source.sourceId) }
                        .onFailure {
                            markFail(source.sourceId)
                            metrics.failure(traceId, "search", "SOURCE_INVALID")
                        }
                        .getOrDefault(emptyList())
                } ?: run {
                    markFail(source.sourceId)
                    metrics.failure(traceId, "search", "TIMEOUT")
                    emptyList()
                }
            }
        }
        deferred.flatMap { it.second.await() }
    }

    /**
     * 正文获取。核心分流点：
     * - Audio：音频书源，直接交给播放器（1.4.7）
     * - WebViewFallback：无 ruleContent（含订阅源），原样打开
     * - Html：正常阅读器渲染
     */
    suspend fun getContent(source: IBookSource, url: String, traceId: String): ContentResult {
        if (!source.capabilityFlags.contains(SourceCapability.HAS_CONTENT)) {
            // 能力缺失不是错误：走 WebView 兜底
            return ContentResult.WebViewFallback(url)
        }
        return runCatching { source.getContent(url) }
            .onSuccess { metrics.success(traceId, "content", 0L) }
            .onFailure {
                metrics.failure(traceId, "content", "PARSE_FAILED")
                throw ProductException(ProductErrorCode.PARSE_FAILED, "正文解析失败")
            }
            .getOrThrow()
    }

    // ---------------- 订阅源 ----------------

    /**
     * 订阅搜索。与书源差异：无 HAS_CONTENT 也必须可读
     * （RSS_FALLBACK 源正文走 WebView，见 ADR-016）。
     */
    suspend fun searchSubscriptions(
        sources: List<ISubscriptionSource>,
        key: String,
        page: Int,
        traceId: String
    ): List<com.lanyue.reader.source.model.SubscriptionItem> = coroutineScope {
        sources
            .filter { it.capabilityFlags.contains(SourceCapability.SEARCHABLE) }
            .map { source ->
                source to async {
                    withTimeoutOrNull(perSourceTimeoutMs) {
                        runCatching { source.search(key, page) }.getOrDefault(emptyList())
                    } ?: emptyList()
                }
            }
            .flatMap { it.second.await() }
    }

    // ---------------- 影视 ----------------

    suspend fun searchVideos(
        sources: List<IVideoSource>,
        key: String,
        page: Int,
        traceId: String
    ): List<com.lanyue.reader.source.model.VideoItem> = coroutineScope {
        val usable = sources.filter {
            it.capabilityFlags.contains(SourceCapability.SEARCHABLE) && !isIsolated(it.sourceId)
        }
        usable.map { source ->
            source to async {
                withTimeoutOrNull(perSourceTimeoutMs) {
                    runCatching { source.search(key, page) }
                        .onSuccess { markSuccess(source.sourceId) }
                        .onFailure {
                            markFail(source.sourceId)
                            metrics.failure(traceId, "vsearch", "SOURCE_INVALID")
                        }
                        .getOrDefault(emptyList())
                } ?: run {
                    markFail(source.sourceId)
                    emptyList()
                }
            }
        }.flatMap { it.second.await() }
    }

    /**
     * 播放地址。失败一律映射为 PARSE_FAILED / DECODE_ERROR，
     * UI 只按 4.9 口径提示，不暴露源内部细节。
     */
    suspend fun getPlay(source: IVideoSource, episode: VideoEpisode, traceId: String): PlayResult =
        runCatching { source.getPlay(episode) }
            .onFailure {
                markFail(source.sourceId)
                metrics.failure(traceId, "play", "PARSE_FAILED")
                throw ProductException(ProductErrorCode.PARSE_FAILED, "影片解析失败")
            }
            .getOrThrow()

    // ---------------- 弹幕 ----------------

    /**
     * 按源顺序尝试匹配，首个成功即返回。
     * 全部失败抛 DANMAKU_NOT_MATCHED（不视为播放失败）。
     */
    suspend fun matchDanmaku(
        sources: List<IDanmakuSource>,
        req: DanmakuMatchRequest,
        traceId: String
    ): DanmakuMatch {
        for (source in sources) {
            val hit = runCatching { source.match(req) }.getOrNull()
            if (hit != null) return hit
        }
        metrics.failure(traceId, "danmaku", "DANMAKU_NOT_MATCHED")
        throw ProductException(ProductErrorCode.DANMAKU_NOT_MATCHED, "未匹配到弹幕")
    }

    suspend fun loadDanmaku(
        source: IDanmakuSource,
        match: DanmakuMatch,
        fromMs: Long,
        durationMs: Long
    ): List<DanmakuEvent> =
        runCatching { source.getComments(match, fromMs, durationMs) }.getOrDefault(emptyList())

    // ---------------- 失败隔离 ----------------

    /** 本次会话内是否已隔离（不持久化，重启即恢复）。 */
    private fun isIsolated(sourceId: String): Boolean =
        (bookFailCount[sourceId] ?: 0) >= failThreshold ||
            (videoFailCount[sourceId] ?: 0) >= failThreshold

    private fun markFail(sourceId: String) {
        bookFailCount[sourceId] = (bookFailCount[sourceId] ?: 0) + 1
        videoFailCount[sourceId] = (videoFailCount[sourceId] ?: 0) + 1
    }

    private fun markSuccess(sourceId: String) {
        bookFailCount.remove(sourceId)
        videoFailCount.remove(sourceId)
    }

    /** 供设置页「重试该源」使用：清除隔离。 */
    fun resetIsolation(sourceId: String) {
        bookFailCount.remove(sourceId)
        videoFailCount.remove(sourceId)
    }
}
