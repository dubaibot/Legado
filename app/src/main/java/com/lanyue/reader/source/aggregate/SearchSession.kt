package com.lanyue.reader.source.aggregate

import com.lanyue.reader.source.model.SearchBookItem

/**
 * 搜索页的编排层（T12）。
 *
 * [SearchAggregator] 只负责「拿到一批已经填好的源页面之后怎么归并」，它假定每个源
 * 都已经有结果了。但搜索页真正的麻烦在于**结果是一个一个回来的**：快的源 200ms
 * 就回，慢的源可能永远不回。T12 的验收原话是「一个源超时不应让整页转圈到死」，
 * 这句话没有代码承接就等于没做。
 *
 * 这一层负责三件事：
 * 1. **渐进**：每回来一个源就产出一份快照，界面立刻能画，不必等最慢的那个；
 * 2. **隔离**：超过单源预算就把它踢出等待队列并如实记录，页面继续往下走；
 * 3. **丢弃迟到**：已经判定隔离的源再返回结果时不再塞进列表——用户可能正在点
 *    某一行，列表在指尖下重排是最糟的体验。
 *
 * 刻意不做 IO、不持线程：调用方把「某源返回了」「某源失败了」「时间到点了」喂进来，
 * 这样全部规则都能在纯 JVM 里断言。
 */
class SearchSession(
    val keyword: String,
    /** 会话代次。用户换关键词重搜时递增，旧会话的迟到结果据此被拒。 */
    val epoch: Long,
    seeds: List<Seed>,
    /** 开始时刻（毫秒），调用方给，便于测试注入。 */
    val startedAtMs: Long,
    val config: Config = Config(),
) {

    /** 一个待搜索的源。order 越小越靠前，与书源列表里的用户排序一致。 */
    data class Seed(val origin: String, val sourceName: String, val order: Int = 0)

    data class Config(
        /** 单源预算：超过就隔离，不再等。 */
        val perSourceTimeoutMs: Long = 8_000,
        /** 整页预算：到点无论还剩几个源都收尾，避免无限转圈。 */
        val globalTimeoutMs: Long = 20_000,
        /** 单源最多收多少条，防某个源一次吐几万行把列表拖死。 */
        val maxItemsPerSource: Int = 200,
    )

    /** 单个源的结局。与 [SearchAggregator.SourceStatus] 不是同一个东西：这里多了「搜索中」和「迟到丢弃」。 */
    enum class Outcome(val id: String, val label: String) {
        PENDING("pending", "搜索中"),
        OK("ok", "有结果"),
        EMPTY("empty", "无结果"),
        FAILED("failed", "失败"),
        TIMEOUT("timeout", "超时"),
        ISOLATED("isolated", "已隔离"),
        DROPPED("dropped", "迟到丢弃"),
        ;

        /** 是否已经不需要再等（终态）。 */
        val settled: Boolean get() = this != PENDING

        /** 是否算「这个源没能给页面提供内容」。 */
        val broken: Boolean get() = this == FAILED || this == TIMEOUT || this == ISOLATED
    }

    enum class Phase {
        /** 一个都没回，页面上只有骨架和转圈。 */
        LOADING,
        /** 回来了一部分，可以边看边等。 */
        PARTIAL,
        /** 全部有结局，停止转圈。 */
        DONE,
    }

    /** 一份可直接绘制的快照。 */
    data class Snapshot(
        val keyword: String,
        val epoch: Long,
        val phase: Phase,
        val slots: List<Slot>,
        val aggregate: SearchAggregator.AggregateResult,
        val settled: Int,
        val total: Int,
        val truncated: Int,
    ) {
        val isDone: Boolean get() = phase == Phase.DONE

        /** 只有还在等才转圈——这是「不转圈到死」的直接体现。 */
        val showSpinner: Boolean get() = phase != Phase.DONE

        /** 一个源都没成功：页面该显示错误页而不是继续转。 */
        val fatal: Boolean
            get() = total > 0 && settled == total &&
                slots.none { it.outcome == Outcome.OK || it.outcome == Outcome.EMPTY }

        val okSources: Int get() = slots.count { it.outcome == Outcome.OK }

        fun progressLabel(): String = "已返回 $settled/$total 个源"

        fun fatalHint(): String =
            if (fatal) "全部 $total 个源都没有返回结果，请检查网络或稍后重试" else ""

        /** 底部状态条：直接取聚合器的总结，再补上截断信息。 */
        fun statusLine(): String {
            val base = aggregate.summary()
            return if (truncated > 0) "$base，超出上限截断 $truncated 条" else base
        }
    }

    /** 快照里的一行源状态。 */
    data class Slot(
        val origin: String,
        val sourceName: String,
        val order: Int,
        val outcome: Outcome,
        val items: List<SearchBookItem>,
        val message: String,
        val elapsedMs: Long,
    )

    // ============================ 内部状态 ============================

    private class SlotState(val seed: Seed, val deadline: Long) {
        var outcome: Outcome = Outcome.PENDING
        var items: List<SearchBookItem> = emptyList()
        var message: String = ""
        var elapsedMs: Long = -1L
    }

    private val slots: List<SlotState> = seeds.map {
        SlotState(it, startedAtMs + config.perSourceTimeoutMs)
    }
    private val byOrigin: Map<String, SlotState> = slots.associateBy { it.seed.origin }
    private val globalDeadline: Long = startedAtMs + config.globalTimeoutMs
    private var truncatedTotal: Int = 0

    val total: Int get() = slots.size

    val isDone: Boolean get() = slots.all { it.outcome.settled }

    // ============================ 回填 ============================

    /**
     * 某源候选返回。返回该源此前的结局；null 表示这个 origin 不属于本会话。
     *
     * 三种情况不予采纳：
     * - 该源已有结局（重复回调）；
     * - 已过整页预算（页面已经收尾，再塞数据会让列表在用户眼前跳）；
     * - 已过单源预算（该源已按超时处理，迟到结果不再采信）。
     */
    fun onResult(origin: String, items: List<SearchBookItem>, atMs: Long): Outcome? {
        val slot = byOrigin[origin] ?: return null
        if (slot.outcome != Outcome.PENDING) return slot.outcome

        if (atMs >= globalDeadline) {
            slot.outcome = Outcome.DROPPED
            slot.message = "整体搜索已结束，迟到结果丢弃"
            slot.elapsedMs = atMs - startedAtMs
            return slot.outcome
        }
        if (atMs > slot.deadline) {
            slot.outcome = Outcome.DROPPED
            slot.message = "超过单源 ${config.perSourceTimeoutMs}ms 预算，迟到结果丢弃"
            slot.elapsedMs = atMs - startedAtMs
            return slot.outcome
        }

        val cap = config.maxItemsPerSource
        val kept = if (items.size > cap) {
            truncatedTotal += items.size - cap
            items.subList(0, cap).toList()
        } else {
            items
        }
        slot.items = kept
        slot.outcome = if (kept.isEmpty()) Outcome.EMPTY else Outcome.OK
        slot.elapsedMs = atMs - startedAtMs
        return slot.outcome
    }

    /** 某源报错。已被隔离的源再报错不再覆盖结局，保留最先的原因。 */
    fun onFailure(origin: String, message: String, atMs: Long): Outcome? {
        val slot = byOrigin[origin] ?: return null
        if (slot.outcome != Outcome.PENDING) return slot.outcome
        if (atMs >= slot.deadline) {
            // 报错本身来得太晚，按超时口径处理，少一种歧义
            slot.outcome = Outcome.ISOLATED
            slot.message = "单源 ${config.perSourceTimeoutMs}ms 未返回，已隔离"
        } else {
            slot.outcome = Outcome.FAILED
            slot.message = message
        }
        slot.elapsedMs = atMs - startedAtMs
        return slot.outcome
    }

    /**
     * 时间推进：把到点的源判为隔离。由调用方的定时器周期调用。
     *
     * 整页预算到点时，剩余源一律隔离并说明原因——这样页面必然收尾，
     * 不会出现「永远差一个源」的转圈。
     */
    fun sweep(atMs: Long): Int {
        var changed = 0
        val globalOut = atMs >= globalDeadline
        slots.forEach { slot ->
            if (slot.outcome != Outcome.PENDING) return@forEach
            val timedOut = globalOut || atMs > slot.deadline
            if (!timedOut) return@forEach
            slot.outcome = Outcome.ISOLATED
            slot.message = if (globalOut) {
                "整体 ${config.globalTimeoutMs}ms 预算用尽，已隔离"
            } else {
                "单源 ${config.perSourceTimeoutMs}ms 未返回，已隔离"
            }
            slot.elapsedMs = atMs - startedAtMs
            changed++
        }
        return changed
    }

    // ============================ 快照 ============================

    fun snapshot(): Snapshot {
        val views = slots.map { s ->
            Slot(
                origin = s.seed.origin,
                sourceName = s.seed.sourceName,
                order = s.seed.order,
                outcome = s.outcome,
                items = s.items,
                message = s.message,
                elapsedMs = s.elapsedMs,
            )
        }
        val settled = views.count { it.outcome.settled }
        val phase = when {
            settled == 0 -> if (total == 0) Phase.DONE else Phase.LOADING
            settled < total -> Phase.PARTIAL
            else -> Phase.DONE
        }
        return Snapshot(
            keyword = keyword,
            epoch = epoch,
            phase = phase,
            slots = views,
            aggregate = SearchAggregator.aggregate(sourcePages(views), keyword),
            settled = settled,
            total = total,
            truncated = truncatedTotal,
        )
    }

    /**
     * 转成聚合器认的源页面。
     *
     * 只交已经收尾的源：还在搜索中的源交过去会被算成「空结果」，
     * 底部状态条的「x/y 个源有结果」就会失真。
     */
    private fun sourcePages(views: List<Slot>): List<SearchAggregator.SourcePage> =
        views.mapNotNull { v ->
            val status = when (v.outcome) {
                Outcome.PENDING -> return@mapNotNull null
                Outcome.OK -> SearchAggregator.SourceStatus.OK
                Outcome.EMPTY -> SearchAggregator.SourceStatus.EMPTY
                Outcome.FAILED -> SearchAggregator.SourceStatus.FAILED
                Outcome.TIMEOUT -> SearchAggregator.SourceStatus.TIMEOUT
                Outcome.ISOLATED -> SearchAggregator.SourceStatus.ISOLATED
                // 迟到结果不进统计：它既不算成功也不该污染失败计数
                Outcome.DROPPED -> return@mapNotNull null
            }
            SearchAggregator.SourcePage(
                origin = v.origin,
                sourceName = v.sourceName,
                status = status,
                items = v.items,
                message = v.message,
                order = v.order,
            )
        }
}

/**
 * 搜索控制器：管「当前是哪一次搜索」。
 *
 * 存在的理由很具体——用户改关键词重搜时，上一次搜索的请求还在飞。如果不管，
 * 旧结果会落到新列表里，出现「搜 B 却看到 A 的结果」。这里用代次号把它们挡掉。
 */
class SearchController(private val config: SearchSession.Config = SearchSession.Config()) {

    private var seq: Long = 0
    var current: SearchSession? = null
        private set

    /** 代次号，测试与日志用。 */
    val epoch: Long get() = seq

    /** 开始一次新搜索，自动取代上一次。空白关键词也允许开始（由界面判空）。 */
    fun begin(keyword: String, seeds: List<SearchSession.Seed>, nowMs: Long): SearchSession {
        seq += 1
        return SearchSession(keyword, seq, seeds, nowMs, config).also { current = it }
    }

    fun isCurrent(session: SearchSession?): Boolean =
        session != null && current === session && session.epoch == seq

    /** 回调被拒的原因，便于日志定位。 */
    enum class Reject { NONE, NO_SESSION, STALE_EPOCH, UNKNOWN_SOURCE }

    data class Offer(val accepted: Boolean, val reject: Reject, val outcome: SearchSession.Outcome?)

    /** 把一次源返回交给当前会话；代次不符直接丢弃。 */
    fun offerResult(
        epoch: Long,
        origin: String,
        items: List<SearchBookItem>,
        atMs: Long,
    ): Offer {
        val s = current ?: return Offer(false, Reject.NO_SESSION, null)
        if (epoch != s.epoch) return Offer(false, Reject.STALE_EPOCH, null)
        val outcome = s.onResult(origin, items, atMs)
            ?: return Offer(false, Reject.UNKNOWN_SOURCE, null)
        return Offer(true, Reject.NONE, outcome)
    }

    fun offerFailure(
        epoch: Long,
        origin: String,
        message: String,
        atMs: Long,
    ): Offer {
        val s = current ?: return Offer(false, Reject.NO_SESSION, null)
        if (epoch != s.epoch) return Offer(false, Reject.STALE_EPOCH, null)
        val outcome = s.onFailure(origin, message, atMs)
            ?: return Offer(false, Reject.UNKNOWN_SOURCE, null)
        return Offer(true, Reject.NONE, outcome)
    }

    /** 定时器推进；没有当前会话时返回 0。 */
    fun sweep(atMs: Long): Int = current?.sweep(atMs) ?: 0

    fun snapshot(): SearchSession.Snapshot? = current?.snapshot()
}
