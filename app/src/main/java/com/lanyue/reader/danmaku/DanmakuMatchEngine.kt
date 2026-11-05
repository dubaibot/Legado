package com.lanyue.reader.danmaku

import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.data.db.DanmakuDao
import com.lanyue.reader.data.db.entity.DanmakuBindingEntity
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import com.lanyue.reader.source.model.DanmakuEvent
import com.lanyue.reader.source.model.DanmakuMatch
import com.lanyue.reader.source.model.DanmakuMatchRequest
import com.lanyue.reader.source.model.IDanmakuSource
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 4.10.3 弹幕自动匹配引擎（零操作）。
 *
 * 链路：
 *   onPlaybackStarted
 *   -> 提取匹配上下文（在线：片名+集数 / 本地：文件名+hash+size+时长）
 *   -> 查 danmaku_bindings（视频标识 + 弹幕源）
 *      - 命中 -> 复用 episodeId
 *      - 未命中 -> 遍历启用弹幕源（按 sortOrder）
 *   -> 任一源命中 -> 写 danmaku_bindings -> 拉取
 *   -> 全部未命中 -> 非阻塞提示「未匹配到弹幕，点此手动选择」
 *
 * 关键规则：
 * - 多源按配置顺序尝试，首个命中即用
 * - 单源匹配超时 5s，超时跳过下一源
 * - 后台协程执行，不阻塞起播
 */
class DanmakuMatchEngine(
    private val dao: DanmakuDao,
    private val sourceFactory: (DanmakuSourceEntity) -> IDanmakuSource?,
    private val metrics: LocalMetrics
) {

    companion object {
        /** 单源匹配超时 5s */
        const val PER_SOURCE_TIMEOUT_MS = 5_000L
        /** 4.10.4 分段拉取窗口 */
        const val COMMENT_WINDOW_MS = 60_000L
        /** 剩余 < 30s 触发下一段增量拉取 */
        const val PREFETCH_THRESHOLD_MS = 30_000L
    }

    sealed class Outcome {
        /** 命中：可直接进入拉取。 */
        data class Matched(
            val match: DanmakuMatch,
            val source: DanmakuSourceEntity,
            val videoKey: String
        ) : Outcome()

        /** 全部未命中：UI 显示「未匹配到弹幕，点此手动选择」。 */
        data class NotMatched(val videoKey: String) : Outcome()
    }

    /**
     * 生成视频标识 videoKey。
     * 本地文件 = 路径+大小；在线 = 源ID+剧集URL（4.10.5）。
     */
    fun videoKeyFor(request: DanmakuMatchRequest, sourceId: String?, episodeUrl: String?): String {
        return when {
            request.fileName != null && request.fileSize != null ->
                "file:" + request.fileName + ":" + request.fileSize
            sourceId != null && episodeUrl != null ->
                "online:" + sourceId + ":" + episodeUrl
            else -> "title:" + request.title + ":" + (request.episode ?: 0)
        }
    }

    /**
     * 自动匹配入口。后台协程执行，不阻塞起播。
     */
    suspend fun autoMatch(
        request: DanmakuMatchRequest,
        videoKey: String
    ): Outcome {
        val sources = dao.enabledOrdered()
        if (sources.isEmpty()) return Outcome.NotMatched(videoKey)

        // 1) 先查匹配记忆（按 videoKey + 弹幕源）
        for (entity in sources) {
            val binding = dao.binding(videoKey, entity.id) ?: continue
            val match = DanmakuMatch(
                episodeId = binding.episodeId,
                animeTitle = request.title,
                episodeTitle = "",
                shift = binding.shift,
                sourceId = entity.id
            )
            return Outcome.Matched(match, entity, videoKey)
        }

        // 2) 未命中记忆：遍历启用源（按 sortOrder），首个命中即用
        for (entity in sources) {
            val source = sourceFactory(entity) ?: continue
            val traceId = entity.id + ":match"

            val match = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                tryMatchOne(source, request)
            }
            if (match == null) {
                metrics.degradation(traceId, "source_timeout_or_miss")
                continue
            }

            // 写入匹配记忆
            dao.upsertBinding(
                DanmakuBindingEntity(
                    videoKey = videoKey,
                    danmakuSourceId = entity.id,
                    episodeId = match.episodeId,
                    shift = match.shift,
                    matchMethod = if (request.fileHash != null) "HASH" else "TITLE",
                    boundAt = System.currentTimeMillis()
                )
            )
            return Outcome.Matched(match, entity, videoKey)
        }

        return Outcome.NotMatched(videoKey)
    }

    /**
     * 单源匹配：
     * - 本地文件：先 POST /match（hash），失败转标题搜索
     * - 在线影视：直接标题+集数搜索
     */
    private suspend fun tryMatchOne(source: IDanmakuSource, req: DanmakuMatchRequest): DanmakuMatch? {
        // 本地文件路径：先哈希匹配
        if (req.fileName != null && req.fileHash != null) {
            val hashMatch = source.match(req)
            if (hashMatch != null) return hashMatch
        }

        // 标题搜索兜底（在线影视主路径）
        val results = source.search(req.title)
        if (results.isEmpty()) return null
        return pickBest(results, req.title, req.episode)
    }

    /**
     * 4.10.3 多结果选取策略（策略单点定义，match 与 search 共用）：
     * 标题完全一致 > 集数一致 > 第一个
     */
    fun pickBest(candidates: List<DanmakuMatch>, title: String, episode: Int?): DanmakuMatch? {
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.animeTitle.equals(title, ignoreCase = true) }?.let { return it }
        if (episode != null) {
            candidates.firstOrNull { it.episodeTitle.contains(episode.toString()) }?.let { return it }
        }
        return candidates.first()
    }

    /**
     * 4.10.4 拉取。首屏 from=0，duration=60s。
     * 拉取失败静默重试 1 次，仍失败提示「弹幕加载失败」，不阻塞播放。
     */
    suspend fun fetchComments(
        source: IDanmakuSource,
        match: DanmakuMatch,
        fromMs: Long,
        shiftMs: Long = 0L
    ): List<DanmakuEvent> {
        val first = runCatching { source.getComments(match, fromMs, COMMENT_WINDOW_MS) }.getOrNull()
        val events = first ?: runCatching { source.getComments(match, fromMs, COMMENT_WINDOW_MS) }.getOrNull()
            ?: return emptyList()

        // 时间轴对齐 + shift 偏移
        return events.map { it.copy(timeMs = (it.timeMs + shiftMs).coerceAtLeast(0L)) }
    }

    /** 4.10.5 用户手动改选后覆盖绑定。 */
    suspend fun bindManually(videoKey: String, sourceId: String, match: DanmakuMatch) {
        dao.upsertBinding(
            DanmakuBindingEntity(
                videoKey = videoKey,
                danmakuSourceId = sourceId,
                episodeId = match.episodeId,
                shift = match.shift,
                matchMethod = "MANUAL",
                boundAt = System.currentTimeMillis()
            )
        )
    }
}
