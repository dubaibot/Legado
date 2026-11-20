package io.legado.app.data.drama

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ShortDramaAction
import io.legado.app.data.entities.ShortDramaInteraction
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 短剧浏览记录仓库：写入行为、聚合快照、推导标签偏好。
 *
 * 记录量有上限（[MAX_ROWS]），每次写入后顺手裁剪，避免长期累积拖慢查询。
 */
object ShortDramaHistoryStore {

    private const val MAX_ROWS = 5000
    private const val WINDOW_MS = 24L * 60 * 60 * 1000
    private const val COMPLETED_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000
    private const val RECENT_SOURCE_LIMIT = 10
    private const val TAG_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    suspend fun record(
        seriesId: String,
        sourceId: String,
        action: String,
        tags: List<String> = emptyList(),
        episodeId: String = "",
        position: Long = 0L,
        duration: Long = 0L,
        timestamp: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        runCatching {
            appDb.shortDramaInteractionDao.insert(
                ShortDramaInteraction(
                    seriesId = seriesId,
                    sourceId = sourceId,
                    episodeId = episodeId,
                    action = action,
                    tags = tags.joinToString(","),
                    position = position,
                    duration = duration,
                    timestamp = timestamp,
                )
            )
            appDb.shortDramaInteractionDao.trimTo(MAX_ROWS)
        }.onFailure { LogUtils.d("ShortDramaHistoryStore", "record failed: ${it.message}") }
    }

    suspend fun snapshot(
        sessionSkips: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): DramaHistorySnapshot = withContext(Dispatchers.IO) {
        runCatching {
            val window = now - WINDOW_MS
            val recent = appDb.shortDramaInteractionDao.since(window)
            val longTerm = appDb.shortDramaInteractionDao.since(now - TAG_WINDOW_MS)
            DramaHistorySnapshot(
                preferences = derivePreferences(longTerm),
                recentSeries = recent.map { it.seriesId }.toSet(),
                completedSeries = longTerm
                    .filter { it.action == ShortDramaAction.COMPLETE && it.timestamp >= now - COMPLETED_LOOKBACK_MS }
                    .associate { it.seriesId to it.timestamp },
                recentSourceIds = recent.map { it.sourceId }.distinct().take(RECENT_SOURCE_LIMIT),
                sessionSkips = sessionSkips,
            )
        }.onFailure {
            AppLog.put("ShortDramaHistoryStore.snapshot failed", it)
        }.getOrDefault(DramaHistorySnapshot.EMPTY)
    }

    /** 该系列是否被点过爱心（加重推荐权重的强正向信号）。 */
    suspend fun isLiked(seriesId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { appDb.shortDramaInteractionDao.since(0L) }
            .getOrDefault(emptyList())
            .any { it.seriesId == seriesId && it.action == ShortDramaAction.LIKE }
    }

    /**
     * 切换爱心（加重推荐权重）。
     * liked=true 记一条 LIKE；liked=false 删该系列最新一条 LIKE。
     */
    suspend fun toggleLike(
        seriesId: String,
        sourceId: String,
        tags: List<String>,
        liked: Boolean,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            if (liked) {
                record(seriesId, sourceId, ShortDramaAction.LIKE, tags)
            } else {
                appDb.shortDramaInteractionDao.deleteLatestOfSeriesAndAction(
                    seriesId, ShortDramaAction.LIKE
                )
            }
        }.onFailure { AppLog.put("ShortDramaHistoryStore.toggleLike failed", it) }
        Unit
    }

    /** 偏好分 = 0.6 * 平均观看完成率 + 0.4 * 标签出现次数占比。 */
    private fun derivePreferences(rows: List<ShortDramaInteraction>): Map<String, Double> {
        if (rows.isEmpty()) return emptyMap()
        val ratioSum = HashMap<String, Double>()
        val occurrences = HashMap<String, Int>()
        rows.forEach { row ->
            // 曝光(SHOW)与快速划走(SKIP)不进偏好，否则 ratio=0 会拉低标签得分
            if (row.action == ShortDramaAction.EXPOSURE || row.action == ShortDramaAction.SKIP) {
                return@forEach
            }
            val tags = row.tags.split(',').filter { it.isNotBlank() }
            if (tags.isEmpty()) return@forEach
            val isLiked = row.action == ShortDramaAction.LIKE
            val ratio = when {
                isLiked -> 1.0
                row.action == ShortDramaAction.COMPLETE -> 1.0
                row.duration > 0 && row.position > 0 ->
                    (row.position.toDouble() / row.duration).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            // 爱心是强正向信号：计数与权重都放大，让 occurrences/ratioSum 更偏向被点爱心的标签
            val w = if (isLiked) 2 else 1
            val weightedRatio = ratio * w
            tags.forEach { tag ->
                ratioSum[tag] = (ratioSum[tag] ?: 0.0) + weightedRatio
                occurrences[tag] = (occurrences[tag] ?: 0) + w
            }
        }
        val maxOccurrence = occurrences.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        return occurrences.mapValues { (tag, count) ->
            val avgRatio = (ratioSum[tag] ?: 0.0) / count
            (0.6 * avgRatio + 0.4 * count.toDouble() / maxOccurrence).coerceIn(0.0, 1.0)
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching { appDb.shortDramaInteractionDao.clearAll() }
            .onFailure { AppLog.put("ShortDramaHistoryStore.clearAll failed", it) }
        Unit
    }

    suspend fun clearSeries(seriesId: String) = withContext(Dispatchers.IO) {
        runCatching { appDb.shortDramaInteractionDao.deleteSeries(seriesId) }
        Unit
    }
}