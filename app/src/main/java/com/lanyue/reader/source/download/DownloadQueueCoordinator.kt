package com.lanyue.reader.source.download

/**
 * 下载项状态。
 *
 * 状态流转：PENDING → DOWNLOADING → COMPLETED 或 FAILED。
 * FAILED 可经 [DownloadQueueCoordinator.retry] 回到 PENDING。
 * 任意非终态可被 [DownloadQueueCoordinator.cancel] 转为 CANCELLED。
 */
enum class DownloadStatus {
    /** 排队等待（含退避冷却期）。 */
    PENDING,
    /** 下载中。 */
    DOWNLOADING,
    /** 已完成。 */
    COMPLETED,
    /** 失败（重试次数已耗尽）。 */
    FAILED,
    /** 用户取消。 */
    CANCELLED
}

/**
 * 下载项：一个待下载的章节或资源。
 *
 * [id] 通常是章节 URL，用于去重与已下载标记。
 * [sourceId] 标识所属书源，生产环境下用于与搜索共用 [com.lanyue.reader.core.engine.SourceRateLimiter]。
 */
data class DownloadItem(
    val id: String,
    val url: String,
    val targetPath: String,
    val sourceId: String = "",
    val title: String = ""
)

/**
 * 下载项运行时状态。
 *
 * [attempts] 是 1 基的：首次下载后为 1，每重试一次 +1。
 * [nextRetryAt] 为下次可尝试的时间戳（毫秒）；0 表示立即可尝试。
 * [totalBytes] 为 -1 表示未知大小。
 */
data class DownloadEntry(
    val item: DownloadItem,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val nextRetryAt: Long = 0L
) {
    /** 是否处于退避冷却期（PENDING 但未到重试时间）。 */
    val isCoolingDown: Boolean
        get() = status == DownloadStatus.PENDING && nextRetryAt > 0L && nextRetryAt > System.currentTimeMillis()
}

/**
 * 队列配置。
 *
 * [maxConcurrent]：最大同时下载数。T40 要求「下载与搜索共用限流」——
 * 生产环境应把此值与 [com.lanyue.reader.core.engine.GlobalConcurrencyGate] 协调，
 * 不要另开一套并发池把搜索请求挤掉。
 *
 * [maxRetries]：最大重试次数（不含首次）。maxRetries=3 意味着首次失败后还能重试 3 次，
 * 共 4 次尝试。
 */
data class DownloadQueueConfig(
    val maxConcurrent: Int = 3,
    val maxRetries: Int = 3,
    val initialBackoffMs: Long = 1000L,
    val maxBackoffMs: Long = 30_000L
) {
    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(initialBackoffMs > 0) { "initialBackoffMs must be positive" }
        require(maxBackoffMs > 0) { "maxBackoffMs must be positive" }
    }
}

/**
 * 队列快照：供 UI 展示当前下载状态。
 */
data class QueueSnapshot(
    val entries: List<DownloadEntry>,
    val totalItems: Int,
    val pendingCount: Int,
    val downloadingCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val cancelledCount: Int,
    val totalDownloadedBytes: Long
) {
    /** 全部处理完（无待下载、无下载中）。 */
    val isAllDone: Boolean
        get() = pendingCount == 0 && downloadingCount == 0

    /** 一句话结论，供通知栏或结果页展示。 */
    fun summary(): String = when {
        totalItems == 0 -> "队列为空"
        isAllDone && failedCount == 0 -> "全部完成（$completedCount 项）"
        isAllDone -> "完成 $completedCount 项，失败 $failedCount 项"
        else -> "等待 $pendingCount，下载中 $downloadingCount，完成 $completedCount，失败 $failedCount"
    }
}

/**
 * 批量下载队列协调器。
 *
 * 职责边界：
 * - **只管队列与状态**：决定「下一个该下载什么」「失败了怎么办」「全部到什么程度了」。
 * - **不管实际下载**：不碰 HTTP、不碰文件 IO、不碰前台服务。
 *   生产环境由调用方在 `startNext` → 实际下载 → `finishSuccess`/`finishFailure` 循环中接线。
 * - **不管 UI**：只提供 [snapshot]，渲染是 Compose 的事。
 *
 * 设计要点：
 * - **已下载标记**：`enqueue` 时自动跳过 `completedIds` 中的项，避免重复下载。
 *   断网重进时调用方只需把数据库里已标记完成的 ID 塞进 `preloadCompleted`。
 * - **指数退避**：失败后 `nextRetryAt = now + min(initialBackoff × 2^(attempts-1), maxBackoff)`，
 *   `startNext` 只返回退避到期的项。
 * - **并发上限**：`startNext` 在 `downloadingCount >= maxConcurrent` 时返回 null，
 *   调用方据此停止取新任务。
 */
class DownloadQueueCoordinator(
    private val config: DownloadQueueConfig = DownloadQueueConfig()
) {
    private val entries = LinkedHashMap<String, DownloadEntry>()
    private val completedIds = mutableSetOf<String>()

    /**
     * 预加载已完成的 ID（从数据库恢复）。
     *
     * 断网重进时，调用方把数据库里 `downloadStatus = 1` 的章节 ID 传入，
     * 之后 `enqueue` 会自动跳过这些项。
     */
    fun preloadCompleted(ids: Set<String>) {
        completedIds.addAll(ids)
    }

    /**
     * 入队一批下载项。
     *
     * 已在 `completedIds` 中的自动跳过（已下载标记）；
     * 已在队列中的跳过（去重）。
     *
     * @return 实际新增入队的数量。
     */
    fun enqueue(items: List<DownloadItem>): Int {
        var added = 0
        for (item in items) {
            if (item.id in completedIds) continue
            if (entries.containsKey(item.id)) continue
            entries[item.id] = DownloadEntry(item)
            added++
        }
        return added
    }

    /**
     * 取下一个待下载项并标记为 DOWNLOADING。
     *
     * 受并发上限约束（downloadingCount < maxConcurrent）；
     * 只返回 PENDING 且退避已到期的项；
     * 多个可选项中取 `nextRetryAt` 最早的（先入先出 + 退避优先）。
     *
     * @return 下一个待下载项（已标记 DOWNLOADING），或 null（队列空 / 并发已满 / 全在冷却）。
     */
    fun startNext(now: Long = System.currentTimeMillis()): DownloadEntry? {
        val downloading = entries.values.count { it.status == DownloadStatus.DOWNLOADING }
        if (downloading >= config.maxConcurrent) return null

        val candidate = entries.values
            .filter { it.status == DownloadStatus.PENDING && it.nextRetryAt <= now }
            .minByOrNull { it.nextRetryAt }
            ?: return null

        val updated = candidate.copy(
            status = DownloadStatus.DOWNLOADING,
            attempts = candidate.attempts + 1
        )
        entries[candidate.item.id] = updated
        return updated
    }

    /**
     * 标记下载成功。
     */
    fun finishSuccess(id: String, bytes: Long) {
        val entry = entries[id] ?: return
        entries[id] = entry.copy(
            status = DownloadStatus.COMPLETED,
            downloadedBytes = bytes,
            totalBytes = bytes,
            lastError = null
        )
        completedIds.add(id)
    }

    /**
     * 标记下载失败。
     *
     * @return true 表示已安排重试（退避后可再次 `startNext`）；
     *         false 表示重试次数耗尽，已标记 FAILED。
     */
    fun finishFailure(id: String, error: String, now: Long = System.currentTimeMillis()): Boolean {
        val entry = entries[id] ?: return false
        // attempts 是 1 基的（startNext 时 +1），maxRetries 是不含首次的重试上限
        // 总尝试次数 = maxRetries + 1（首次 + maxRetries 次重试）
        if (entry.attempts >= config.maxRetries + 1) {
            entries[id] = entry.copy(status = DownloadStatus.FAILED, lastError = error)
            return false
        }
        val backoff = calculateBackoff(entry.attempts)
        entries[id] = entry.copy(
            status = DownloadStatus.PENDING,
            lastError = error,
            nextRetryAt = now + backoff
        )
        return true
    }

    /**
     * 取消单个下载项。PENDING 和 DOWNLOADING 都可取消。
     */
    fun cancel(id: String) {
        val entry = entries[id] ?: return
        if (entry.status == DownloadStatus.COMPLETED || entry.status == DownloadStatus.CANCELLED) return
        entries[id] = entry.copy(status = DownloadStatus.CANCELLED)
    }

    /** 取消所有未完成的项。 */
    fun cancelAll() {
        entries.forEach { (id, entry) ->
            if (entry.status == DownloadStatus.PENDING || entry.status == DownloadStatus.DOWNLOADING) {
                entries[id] = entry.copy(status = DownloadStatus.CANCELLED)
            }
        }
    }

    /**
     * 手动重试一个失败项。
     *
     * 重置 attempts 与退避，回到 PENDING 立即可尝试。
     */
    fun retry(id: String): Boolean {
        val entry = entries[id] ?: return false
        if (entry.status != DownloadStatus.FAILED) return false
        entries[id] = entry.copy(
            status = DownloadStatus.PENDING,
            attempts = 0,
            nextRetryAt = 0L,
            lastError = null
        )
        return true
    }

    /** 检查某项是否已下载完成。 */
    fun isCompleted(id: String): Boolean = id in completedIds

    /** 全部处理完？ */
    fun isAllDone(): Boolean = snapshot().isAllDone

    /** 获取当前快照。 */
    fun snapshot(): QueueSnapshot {
        val list = entries.values.toList()
        return QueueSnapshot(
            entries = list,
            totalItems = list.size,
            pendingCount = list.count { it.status == DownloadStatus.PENDING },
            downloadingCount = list.count { it.status == DownloadStatus.DOWNLOADING },
            completedCount = list.count { it.status == DownloadStatus.COMPLETED },
            failedCount = list.count { it.status == DownloadStatus.FAILED },
            cancelledCount = list.count { it.status == DownloadStatus.CANCELLED },
            totalDownloadedBytes = list
                .filter { it.status == DownloadStatus.COMPLETED }
                .sumOf { it.downloadedBytes }
        )
    }

    /** 清空已完成和已取消的历史记录（保留失败项供查看/重试）。 */
    fun clearHistory() {
        entries.entries.removeIf {
            it.value.status == DownloadStatus.COMPLETED || it.value.status == DownloadStatus.CANCELLED
        }
    }

    /**
     * 计算指数退避：initialBackoff × 2^(attempts-1)，不超过 maxBackoff。
     *
     * attempts=1 → initialBackoff × 1
     * attempts=2 → initialBackoff × 2
     * attempts=3 → initialBackoff × 4
     */
    private fun calculateBackoff(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1)  // 2^(attempt-1)
        val raw = config.initialBackoffMs * multiplier
        return minOf(raw, config.maxBackoffMs)
    }
}
