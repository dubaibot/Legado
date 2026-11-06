package com.lanyue.reader.source.model

/**
 * 4.x 源健康度与失败隔离（纯逻辑，无 Android / 无 IO）。
 *
 * 从 SourceRouter 里抽出来的原因：
 * 1. 原实现共用一个 id -> 计数的 Map，导致书源失败会把同名影视频道一起隔离，
 *    这是真实缺陷（不同源类型的 sourceId 完全可能重复）。
 * 2. 隔离/恢复规则属于业务规则，应当可单测，而不是埋在协程编排里。
 *
 * 语义约定：
 * - 连续失败达到 [failThreshold] 次 -> 隔离，本次会话内不再调用
 * - 任意一次成功 -> 立刻清除该源的失败计数（失败不必连续累积到会话结束）
 * - 隔离不持久化：进程重启即恢复，与「本次会话内降级」的设计一致
 */
class SourceHealth(
    private val failThreshold: Int = DEFAULT_FAIL_THRESHOLD
) {

    init {
        require(failThreshold >= 1) { "failThreshold must be >= 1: $failThreshold" }
    }

    /** 按「源类型 + 源 id」分开计数，避免跨类型串味。 */
    private val failCounts = HashMap<Key, Int>()

    private data class Key(val type: SourceType, val id: String)

    /**
     * 是否已隔离。
     * 未知源一律视为未隔离（不能因为没记录就当成坏的）。
     */
    fun isIsolated(type: SourceType, sourceId: String): Boolean =
        (failCounts[Key(type, sourceId)] ?: 0) >= failThreshold

    /**
     * 记录一次失败，返回该源当前累计失败次数。
     * 达到阈值时调用方应同时埋点记录「已隔离」。
     */
    fun markFail(type: SourceType, sourceId: String): Int {
        val key = Key(type, sourceId)
        val next = (failCounts[key] ?: 0) + 1
        failCounts[key] = next
        return next
    }

    /** 记录一次成功：清零该源在**本类型**下的失败计数。 */
    fun markSuccess(type: SourceType, sourceId: String) {
        failCounts.remove(Key(type, sourceId))
    }

    /** 用户手动「重试该源」：清除该源在所有类型下的隔离状态。 */
    fun reset(sourceId: String) {
        failCounts.keys.removeAll { it.id == sourceId }
    }

    /** 当前被隔离的源数量（诊断用）。 */
    fun isolatedCount(): Int = failCounts.count { it.value >= failThreshold }

    /** 当前失败计数的源数量（诊断用，含未达阈值的）。 */
    fun trackedCount(): Int = failCounts.size

    /** 清理全部状态（退出登录 / 切换账号时）。 */
    fun clear() = failCounts.clear()

    companion object {
        /** 连续失败 3 次才隔离：容忍偶发网络抖动。 */
        const val DEFAULT_FAIL_THRESHOLD = 3
    }
}
