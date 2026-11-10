package com.lanyue.reader.danmaku.render

import com.lanyue.reader.source.model.DanmakuEvent

/**
 * 4.10.7 屏蔽规则与合并重复。
 * 独立成类便于单测，且保证 UI 层不做过滤逻辑。
 */
class DanmakuFilter(private val params: DanmakuRenderParams) {

    private val regexes = params.blockKeywords.map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) } +
        params.blockRegexes.mapNotNull { runCatching { Regex(it) }.getOrNull() }

    fun accept(event: DanmakuEvent): Boolean {
        if (params.blockScroll && event.mode == 1) return false
        if (params.blockTop && event.mode == 3) return false
        if (params.blockBottom && event.mode == 2) return false
        if (event.text.isBlank()) return false
        if (regexes.any { it.containsMatchIn(event.text) }) return false
        return true
    }

    /** 合并重复：同一时刻同一文本只保留一条。 */
    fun dedupe(events: List<DanmakuEvent>): List<DanmakuEvent> {
        if (!params.mergeDuplicates) return events
        val seen = HashSet<String>()
        return events.filter { ev ->
            val key = ev.timeMs.toString() + "|" + ev.text
            seen.add(key)
        }
    }

    fun apply(events: List<DanmakuEvent>): List<DanmakuEvent> {
        val processed = dedupe(events.filter { accept(it) }).sortedBy { it.timeMs }
        // 同屏密度上限：保留最早进入屏幕的 maxOnScreen 条，丢弃最新超出部分（保帧）
        if (params.maxOnScreen <= 0) return processed
        return processed.take(params.maxOnScreen)
    }
}
