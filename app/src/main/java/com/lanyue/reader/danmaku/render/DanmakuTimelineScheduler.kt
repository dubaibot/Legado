package com.lanyue.reader.danmaku.render

import com.lanyue.reader.source.model.DanmakuEvent

/**
 * 弹幕时间轴调度（纯逻辑，无 Android 依赖）。
 *
 * [DanmakuFilter] 决定「哪些弹幕该显示」，本类决定「显示在哪条轨道、什么时候进、多久出」。
 * 这两件事分开是因为它们的变化频率完全不同：
 * 过滤规则随用户设置变，轨道分配随屏幕尺寸与播放进度变。
 *
 * 核心难点是滚动弹幕不能重叠：如果两条长弹幕被塞进同一条轨道，
 * 后一条会追上前一条并重叠成一坨。真实播放器的做法是「轨道占用时间」模型：
 * 一条弹幕进场后，要等它的尾巴也完全进入屏幕，这条轨道才能接下一辆。
 *
 * 因此这里维护每条轨道的「可再次使用时间」：
 *   freeAt = 进场时间 + 自身宽度 / 屏幕宽度 * 滚动时长 + 安全间隔
 * 只有 freeAt 已过的轨道才会被复用；全部轨道都忙时按策略丢弃或重叠。
 */
object DanmakuTimelineScheduler {

    /** 弹幕显示模式（沿用 B 站通用约定）。 */
    object Mode {
        /** 滚动（右到左）。1 / 2 / 3 都按滚动处理。 */
        const val SCROLL = 1
        /** 顶部固定。 */
        const val TOP = 5
        /** 底部固定。 */
        const val BOTTOM = 4

        /** 是否为滚动类。 */
        fun isScroll(mode: Int): Boolean = mode == 1 || mode == 2 || mode == 3

        fun isTop(mode: Int): Boolean = mode == TOP

        fun isBottom(mode: Int): Boolean = mode == BOTTOM
    }

    /** 调度参数。全部有默认值，界面只暴露其中几项。 */
    data class Config(
        /** 滚动弹幕轨道数。 */
        val scrollLanes: Int = 10,
        /** 顶部固定弹幕轨道数。 */
        val topLanes: Int = 3,
        /** 底部固定弹幕轨道数。 */
        val bottomLanes: Int = 3,
        /** 滚动时长（毫秒）：弹幕从右侧完全进入并滑出左侧的时间。 */
        val scrollDurationMs: Long = 8000,
        /** 固定弹幕停留时长（毫秒）。 */
        val fixedDurationMs: Long = 4000,
        /** 同轨道两条弹幕之间的安全间隔（毫秒）。 */
        val laneGapMs: Long = 250,
        /** 每秒最多显示的弹幕数（密度上限，超出即丢弃）。 */
        val maxPerSecond: Int = 12,
        /** 轨道全忙时是否允许重叠（false 则丢弃）。 */
        val allowOverlap: Boolean = false,
        /** 屏幕可用宽度（以半角字符为单位），用于估算弹幕占宽。 */
        val screenWidthUnits: Float = 40f
    ) {
        init {
            require(scrollLanes > 0) { "scrollLanes must be positive" }
            require(topLanes >= 0) { "topLanes must not be negative" }
            require(bottomLanes >= 0) { "bottomLanes must not be negative" }
            require(scrollDurationMs > 0) { "scrollDurationMs must be positive" }
            require(fixedDurationMs > 0) { "fixedDurationMs must be positive" }
            require(maxPerSecond > 0) { "maxPerSecond must be positive" }
            require(screenWidthUnits > 0f) { "screenWidthUnits must be positive" }
        }
    }

    /** 一条已排好位置的弹幕。 */
    data class Scheduled(
        val event: DanmakuEvent,
        /** 轨道序号；滚动/顶/底各自独立编号。 */
        val lane: Int,
        /** 本条弹幕的进场时间（毫秒，与 event.timeMs 对齐）。 */
        val enterMs: Long,
        /** 离场时间。 */
        val exitMs: Long,
        /** 估算占宽（半角字符数），用于渲染层算速度。 */
        val widthUnits: Float,
        /** 是否因轨道繁忙被降级为重叠显示。 */
        val overlapped: Boolean = false
    ) {
        val mode: Int get() = event.mode
        val text: String get() = event.text
        val timeMs: Long get() = event.timeMs
        val durationMs: Long get() = exitMs - enterMs
    }

    /** 调度结果。 */
    data class Result(
        val scheduled: List<Scheduled>,
        /** 因密度上限丢弃的条数。 */
        val droppedByDensity: Int,
        /** 因轨道全忙丢弃的条数。 */
        val droppedByLanes: Int,
        /** 重复弹幕被合并的条数。 */
        val deduped: Int,
        /** 空文本被丢弃的条数。 */
        val droppedEmpty: Int
    ) {
        val total: Int get() = scheduled.size
        val dropped: Int get() = droppedByDensity + droppedByLanes + droppedEmpty

        /** 一句话结论，供调试面板显示。 */
        fun summary(): String =
            "显示 " + total + " 条，丢弃 " + dropped + " 条（密度 " + droppedByDensity +
                "，轨道 " + droppedByLanes + "，空文本 " + droppedEmpty + "），去重 " + deduped
    }

    /**
     * 主入口：为一整个播放窗口排布弹幕。
     *
     * 输入不要求有序，内部会按时间排序后再分配轨道——
     * 因为多源聚合后（[DanmakuMatchEngine]）顺序必然打乱。
     */
    fun schedule(events: List<DanmakuEvent>, config: Config = Config()): Result {
        if (events.isEmpty()) return Result(emptyList(), 0, 0, 0, 0)

        var droppedEmpty = 0
        var deduped = 0
        var droppedByDensity = 0
        var droppedByLanes = 0

        // 1) 去空 + 去重（同一毫秒内的相同文本视为同一条：多源聚合的典型产物）
        val seen = HashSet<String>()
        val cleaned = ArrayList<DanmakuEvent>(events.size)
        events.forEach { e ->
            val text = e.text.trim()
            if (text.isEmpty()) {
                droppedEmpty++
                return@forEach
            }
            // 时间按 100ms 分桶：同一瞬间刷屏的雷同弹幕只留一条
            val bucket = e.timeMs / 100
            val sig = bucket.toString() + "|" + e.mode + "|" + text
            if (!seen.add(sig)) {
                deduped++
                return@forEach
            }
            cleaned.add(e)
        }

        // 2) 按时间升序；同一时刻保持原始相对顺序（稳定排序）
        val ordered = cleaned.sortedBy { it.timeMs }

        // 3) 逐条分配轨道
        val scrollFreeAt = LongArray(config.scrollLanes) { Long.MIN_VALUE }
        val topFreeAt = LongArray(config.topLanes) { Long.MIN_VALUE }
        val bottomFreeAt = LongArray(config.bottomLanes) { Long.MIN_VALUE }

        val out = ArrayList<Scheduled>(ordered.size)
        // 每秒密度计数：秒 -> 已排条数
        val perSecond = HashMap<Long, Int>()

        ordered.forEach { e ->
            val text = e.text.trim()
            val width = measureUnits(text)

            // 密度闸门
            val sec = e.timeMs / 1000
            val used = perSecond[sec] ?: 0
            if (used >= config.maxPerSecond) {
                droppedByDensity++
                return@forEach
            }

            val modeClass = when {
                Mode.isScroll(e.mode) -> 0
                Mode.isTop(e.mode) -> 1
                Mode.isBottom(e.mode) -> 2
                // 未知模式按滚动处理，总比丢掉好
                else -> 0
            }

            val lanes = when (modeClass) {
                0 -> config.scrollLanes
                1 -> config.topLanes
                else -> config.bottomLanes
            }
            if (lanes <= 0) {
                droppedByLanes++
                return@forEach
            }

            val freeAt = when (modeClass) {
                0 -> scrollFreeAt
                1 -> topFreeAt
                else -> bottomFreeAt
            }

            // 找一条已空闲的轨道：优先取「空闲时间最早」的，让轨道占用尽量均匀
            var chosen = -1
            var bestFree = Long.MAX_VALUE
            for (i in 0 until lanes) {
                if (freeAt[i] <= e.timeMs && freeAt[i] < bestFree) {
                    chosen = i
                    bestFree = freeAt[i]
                }
            }
            var overlapped = false
            if (chosen < 0) {
                // 全忙
                if (!config.allowOverlap) {
                    droppedByLanes++
                    return@forEach
                }
                // 允许重叠时挑最早空闲的那条，标记为重叠
                chosen = 0
                for (i in 1 until lanes) if (freeAt[i] < freeAt[chosen]) chosen = i
                overlapped = true
            }

            val duration = if (modeClass == 0) config.scrollDurationMs else config.fixedDurationMs
            val exitMs = e.timeMs + duration

            // 占用计算：滚动弹幕要等「尾巴完全进入」后才能复用轨道，
            // 这样才能保证不追尾。固定弹幕则等整条停留时间结束。
            freeAt[chosen] = if (modeClass == 0) {
                val ratio = (width / config.screenWidthUnits).coerceIn(0.02f, 1f)
                val enterComplete = (duration.toFloat() * ratio).toLong()
                e.timeMs + enterComplete + config.laneGapMs
            } else {
                exitMs + config.laneGapMs
            }

            perSecond[sec] = used + 1
            out.add(
                Scheduled(
                    event = e,
                    lane = chosen,
                    enterMs = e.timeMs,
                    exitMs = exitMs,
                    widthUnits = width,
                    overlapped = overlapped
                )
            )
        }

        return Result(
            scheduled = out,
            droppedByDensity = droppedByDensity,
            droppedByLanes = droppedByLanes,
            deduped = deduped,
            droppedEmpty = droppedEmpty
        )
    }

    /**
     * 取指定时刻应该显示的弹幕。
     *
     * 用于逐帧/逐秒推进时只要「当前在屏」的那几条，
     * 避免每帧遍历整条时间轴。
     */
    fun activeAt(result: Result, timeMs: Long): List<Scheduled> =
        result.scheduled.filter { timeMs >= it.enterMs && timeMs < it.exitMs }

    /** 按时间窗口切片：只保留 [fromMs, toMs) 内的弹幕，用于分段预取。 */
    fun slice(events: List<DanmakuEvent>, fromMs: Long, toMs: Long): List<DanmakuEvent> =
        events.filter { it.timeMs >= fromMs && it.timeMs < toMs }

    /**
     * 估算占宽（以半角字符为单位）。
     *
     * 与 [com.lanyue.reader.core.render.Paginator] 的度量思路一致：
     * 中文/全角算 1 个汉字位（=2 半角），ASCII 算 1 半角，其余折中。
     * 弹幕只需要相对宽度，因此不做字体级测量。
     */
    fun measureUnits(text: String): Float {
        var w = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += when {
                cp in 0x2E80..0x9FFF -> 2f      // CJK
                cp in 0xAC00..0xD7AF -> 2f      // 韩文
                cp in 0xF900..0xFAFF -> 2f      // 兼容汉字
                cp in 0xFF00..0xFF60 -> 2f      // 全角标点
                cp in 0x3000..0x303F -> 2f      // CJK 标点
                cp in 0x20..0x7E -> 1f          // ASCII
                cp < 0x20 -> 0f
                else -> 1.4f
            }
            i += Character.charCount(cp)
        }
        return w
    }

    /**
     * 把整条时间轴按固定时长分段，供播放器边播边预取。
     * 返回每段的起止时间，末段不足一段也返回。
     */
    fun segment(totalMs: Long, segmentMs: Long): List<LongRange> {
        if (totalMs <= 0L || segmentMs <= 0L) return emptyList()
        val out = ArrayList<LongRange>()
        var start = 0L
        while (start < totalMs) {
            val end = minOf(start + segmentMs, totalMs) - 1
            out.add(start..end)
            start = end + 1
        }
        return out
    }
}
