package io.legado.app.data.drama

/**
 * 浏览历史快照，由 DAO 数据聚合而来，供纯函数引擎使用。
 *
 * @param preferences 标签 -> 偏好分（0~1），由观看时长占比与完播累加
 * @param recentSeries 24 小时内已曝光的 seriesId
 * @param completedSeries seriesId -> 完播时间
 * @param recentSourceIds 最近 10 条曝光的源 id，用于跨批多样性
 * @param sessionSkips session 内被快速划走的 seriesId
 */
data class DramaHistorySnapshot(
    val preferences: Map<String, Double> = emptyMap(),
    val recentSeries: Set<String> = emptySet(),
    val completedSeries: Map<String, Long> = emptyMap(),
    val recentSourceIds: List<String> = emptyList(),
    val sessionSkips: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = DramaHistorySnapshot()
    }
}

/** 引擎打分结果，附带补充标记，便于埋点与测试断言。 */
data class DramaRankResult(
    val candidates: List<DramaCandidate>,
    val supplement: Boolean,
)

/**
 * 短剧推荐引擎（纯 Kotlin，无 Android 依赖）。
 *
 * 只负责去重、排序与多样性约束，不做相似召回：
 * 1. 硬去重：24 小时曝光窗口、本次 session 已曝光、当前 feed 已有
 * 2. 软降序：7 天内完播过的系列排到队尾
 * 3. 打分：标签偏好 50% + 新鲜度 20% + 完播衰减 15% + 底分 15%
 * 4. 多样性：单源占比不超过 50%，同一标签连续不超过 3 条
 * 5. 补充：候选不足时逐级放宽去重窗口，直至无可用候选
 */
object ShortDramaRecommendEngine {

    const val BATCH_SIZE = 10
    private const val MAX_PER_SOURCE_RATIO = 0.5
    private const val MAX_SAME_SOURCE_IN_A_ROW = 2
    private const val MAX_SAME_TAG_IN_A_ROW = 3
    private const val COMPLETE_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

    fun rank(
        candidates: List<DramaCandidate>,
        snapshot: DramaHistorySnapshot,
        sessionExposed: List<String>,
        currentFeed: List<DramaCandidate>,
        limit: Int = BATCH_SIZE,
        now: Long = System.currentTimeMillis(),
    ): DramaRankResult {
        // 硬约束：本会话已曝光、当前信息流、本批次内均不得重复
        val seenSeries = LinkedHashSet<String>().apply {
            addAll(currentFeed.map { it.seriesId })
            addAll(sessionExposed)
        }
        // 软约束：24 小时窗口，仅当严格轮取不到内容时放宽
        val strict = filter(candidates, snapshot.recentSeries, seenSeries)
        // 标题去重：当前 feed 已有标题视为重复，两轮 pick 共用同一集合
        val seenTitles = LinkedHashSet<String>()
        currentFeed.forEach { persistTitle(it.title, seenTitles) }
        val picked = pick(strict, limit, snapshot, now, seenTitles)
        picked.forEach { seenSeries.add(it.seriesId) }

        if (picked.size >= limit) {
            return DramaRankResult(picked, supplement = false)
        }

        val relaxedPool = filter(candidates, emptySet(), seenSeries)
        val relaxed = pick(relaxedPool, limit - picked.size, snapshot, now, seenTitles)
        return DramaRankResult(picked + relaxed, supplement = relaxed.isNotEmpty())
    }

    private fun filter(
        candidates: List<DramaCandidate>,
        excluded: Set<String>,
        seenSeries: Set<String>,
    ): List<DramaCandidate> {
        return candidates.filter { candidate ->
            candidate.seriesId !in seenSeries && candidate.seriesId !in excluded
        }
    }

    private fun pick(
        candidates: List<DramaCandidate>,
        limit: Int,
        snapshot: DramaHistorySnapshot,
        now: Long,
        seenTitles: LinkedHashSet<String> = LinkedHashSet(),
    ): List<DramaCandidate> {
        if (limit <= 0) return emptyList()
        val scored = candidates
            .map { it to score(it, snapshot, now) }
            .sortedWith(
                compareByDescending<Pair<DramaCandidate, Double>> { it.second }
                    .thenBy { it.first.seriesId }
            )

        val result = ArrayList<DramaCandidate>(limit)
        val addedTitles = seenTitles
        val sourceCount = HashMap<String, Int>()
        val perSourceCap = (limit * MAX_PER_SOURCE_RATIO).toInt().coerceAtLeast(1)
        var lastSource = ""
        var sameSourceRun = 0
        val tagRun = HashMap<String, Int>()
        var lastTag = ""

        fun resetRuns() {
            lastSource = ""
            sameSourceRun = 0
            lastTag = ""
            tagRun.clear()
        }

        for ((candidate, _) in scored) {
            if (result.size >= limit) break
            val sourceId = candidate.source.id
            if ((sourceCount[sourceId] ?: 0) >= perSourceCap) continue
            if (sourceId == lastSource && sameSourceRun >= MAX_SAME_SOURCE_IN_A_ROW) continue
            val primaryTag = candidate.tags.firstOrNull().orEmpty()
            if (primaryTag.isNotEmpty() && primaryTag == lastTag &&
                (tagRun[primaryTag] ?: 0) >= MAX_SAME_TAG_IN_A_ROW
            ) continue
            val titleKey = normalizeTitle(candidate.title)
            if (titleKey.isNotBlank() && titleKey in addedTitles) continue

            result.add(candidate)
            if (titleKey.isNotBlank()) addedTitles.add(titleKey)
            sourceCount[sourceId] = (sourceCount[sourceId] ?: 0) + 1
            if (sourceId == lastSource) sameSourceRun++ else { lastSource = sourceId; sameSourceRun = 1 }
            if (primaryTag.isNotEmpty()) {
                if (primaryTag == lastTag) tagRun[primaryTag] = (tagRun[primaryTag] ?: 0) + 1
                else { lastTag = primaryTag; tagRun[primaryTag] = 1 }
            }
        }

        if (result.size < limit) {
            resetRuns()
            for ((candidate, _) in scored) {
                if (result.size >= limit) break
                if (result.any { it.seriesId == candidate.seriesId }) continue
                val titleKey = normalizeTitle(candidate.title)
                if (titleKey.isNotBlank() && titleKey in addedTitles) continue
                result.add(candidate)
                if (titleKey.isNotBlank()) addedTitles.add(titleKey)
            }
        }
        return result
    }

    /** 标题归一化：小写、去标点空白、去集数/画质等噪声，截断 24 字，用于跨条目去重。 */
    private fun normalizeTitle(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.lowercase()
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
            .replace(Regex("第?\\d+集?"), "")
            .replace(Regex("全集|完整版|电影版|高清|国语|粤语"), "")
            .take(24)
    }

    private fun persistTitle(raw: String, into: MutableSet<String>) {
        normalizeTitle(raw).takeIf { it.isNotBlank() }?.let { into.add(it) }
    }

    /** 0~1 归一化分数，权重 0.5 / 0.2 / 0.15 / 0.15。 */
    fun score(candidate: DramaCandidate, snapshot: DramaHistorySnapshot = DramaHistorySnapshot.EMPTY, now: Long = System.currentTimeMillis()): Double {
        val pref = if (candidate.tags.isEmpty()) 0.0
        else candidate.tags.maxOf { snapshot.preferences[it] ?: 0.0 }.coerceIn(0.0, 1.0)

        val freshness = when {
            candidate.publishedAt <= 0L -> 0.5
            else -> {
                val ageHours = ((now - candidate.publishedAt).coerceAtLeast(0L)) / 3_600_000.0
                (1.0 - ageHours / 168.0).coerceIn(0.0, 1.0)
            }
        }

        val completedAt = snapshot.completedSeries[candidate.seriesId]
        val completePenalty = when {
            completedAt == null -> 0.0
            now - completedAt > COMPLETE_COOLDOWN_MS -> 0.0
            else -> 1.0 - (now - completedAt).toDouble() / COMPLETE_COOLDOWN_MS
        }

        val skipPenalty = if (candidate.seriesId in snapshot.sessionSkips) 0.3 else 0.0

        return (0.5 * pref + 0.2 * freshness + 0.15 * (1.0 - completePenalty) + 0.15 - skipPenalty)
            .coerceIn(0.0, 1.0)
    }
}