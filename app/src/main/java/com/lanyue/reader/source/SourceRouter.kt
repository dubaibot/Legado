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
import com.lanyue.reader.source.model.SourceHealth
import com.lanyue.reader.source.model.SourceType
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val failThreshold: Int = 3,
    /** 多源并发上限：源数量可能上百，必须限流，否则协程与连接瞬间打满。 */
    private val maxConcurrency: Int = 6
) {

    /**
     * 失败隔离按「源类型 + 源 id」分开计数。
     * 早期实现共用一个 id -> 计数表，导致书源连续失败会把同 id 的影视频道
     * 一起隔离（两类源的 id 完全可能重复），属于真实缺陷。
     */
    private val health = SourceHealth(failThreshold)

    /** 并发闸门：跨所有源共用一个信号量。 */
    private val gate = Semaphore(maxConcurrency.coerceAtLeast(1))

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
        val usable = sources.filter {
            it.capabilityFlags.contains(SourceCapability.SEARCHABLE) &&
                !health.isIsolated(SourceType.BOOK, it.sourceId)
        }

        val deferred = usable.map { source ->
            source to async {
                gate.withPermit {
                    withTimeoutOrNull(perSourceTimeoutMs) {
                        runCatching { source.search(key, page) }
                            .onSuccess { health.markSuccess(SourceType.BOOK, source.sourceId) }
                            .onFailure {
                                val n = health.markFail(SourceType.BOOK, source.sourceId)
                                metrics.failure(traceId, "search", "SOURCE_INVALID")
                                if (n == failThreshold) {
                                    metrics.degradation(traceId, "book-isolated:" + source.sourceId)
                                }
                            }
                            .getOrDefault(emptyList())
                    } ?: run {
                        val n = health.markFail(SourceType.BOOK, source.sourceId)
                        metrics.failure(traceId, "search", "TIMEOUT")
                        if (n == failThreshold) {
                            metrics.degradation(traceId, "book-isolated:" + source.sourceId)
                        }
                        emptyList()
                    }
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
            it.capabilityFlags.contains(SourceCapability.SEARCHABLE) &&
                !health.isIsolated(SourceType.VIDEO, it.sourceId)
        }
        usable.map { source ->
            source to async {
                gate.withPermit {
                    withTimeoutOrNull(perSourceTimeoutMs) {
                        runCatching { source.search(key, page) }
                            .onSuccess { health.markSuccess(SourceType.VIDEO, source.sourceId) }
                            .onFailure {
                                val n = health.markFail(SourceType.VIDEO, source.sourceId)
                                metrics.failure(traceId, "vsearch", "SOURCE_INVALID")
                                if (n == failThreshold) {
                                    metrics.degradation(traceId, "video-isolated:" + source.sourceId)
                                }
                            }
                            .getOrDefault(emptyList())
                    } ?: run {
                        val n = health.markFail(SourceType.VIDEO, source.sourceId)
                        if (n == failThreshold) {
                            metrics.degradation(traceId, "video-isolated:" + source.sourceId)
                        }
                        emptyList()
                    }
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
                health.markFail(SourceType.VIDEO, source.sourceId)
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
    fun isIsolated(type: SourceType, sourceId: String): Boolean =
        health.isIsolated(type, sourceId)

    /** 供设置页「重试该源」使用：清除隔离。 */
    fun resetIsolation(sourceId: String) {
        health.reset(sourceId)
    }

    /** 当前被隔离的源数量（诊断 / 设置页展示用）。 */
    fun isolatedCount(): Int = health.isolatedCount()
}
