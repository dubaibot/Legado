package com.lanyue.reader.feature.detail

import com.lanyue.reader.core.render.ReadingLocator
import com.lanyue.reader.core.render.ReadingProgress

/**
 * 目录（TOC）的展示与跳转逻辑（T13）。
 *
 * 目录最容易出的两个错都在这里挡住：
 * 1. **倒序后章号被重排**：用户看到「第 1 章」在列表最上面，点下去却跳到了书末。
 *    所以倒序只反转「显示顺序」，[Row.index] 始终保留原始章节下标；
 * 2. **已读标记对不上**：标记必须查原始下标，不能查显示位置。
 *
 * 跳转交给 [ReadingProgress.seekTo]，不自己算偏移——引用同一套定位真值，
 * 避免目录和翻页两处逻辑各算一套而对不上。
 */
object TocModel {

    /** 目录显示顺序。 */
    enum class Order(val id: String, val label: String) {
        ASC("asc", "正序"),
        DESC("desc", "倒序"),
    }

    /** 目录里的一行。 */
    data class Row(
        /** 原始章节下标（0 基）。跳转与已读标记都用它，倒序也不变。 */
        val index: Int,
        /** 展示用章号（1 基）。 */
        val number: Int,
        val title: String,
        val isRead: Boolean,
        val isCurrent: Boolean,
    )

    /** 目录概览，用于顶部统计。 */
    data class Summary(
        val total: Int,
        val readCount: Int,
        val currentNumber: Int,
    ) {
        /** 已读百分比，0..100 的整数，用于进度条。 */
        val readPercent: Int
            get() = if (total <= 0) 0 else (readCount * 100 / total)

        val currentLabel: String get() = if (total <= 0) "无目录" else "第 $currentNumber/$total 章"

        val readLabel: String get() = "已读 $readCount/$total"
    }

    /** 章节标题缺失时的兜底，避免目录出现空行。 */
    fun titleOf(titles: List<String>, index: Int): String {
        val raw = titles.getOrNull(index)?.trim().orEmpty()
        return raw.ifEmpty { "第 ${index + 1} 章" }
    }

    /**
     * 生成目录行。
     *
     * @param titles 章节标题，长度即章数
     * @param progress 当前阅读进度，提供已读集合与当前章
     * @param order 正序 / 倒序
     * @param keyword 章节名过滤；空串不过滤
     */
    fun rows(
        titles: List<String>,
        progress: ReadingProgress,
        order: Order = Order.ASC,
        keyword: String = "",
    ): List<Row> {
        val k = keyword.trim()
        val rows = titles.indices
            .filter { k.isEmpty() || titleOf(titles, it).contains(k, ignoreCase = true) }
            .map { idx ->
                Row(
                    index = idx,
                    number = idx + 1,
                    title = titleOf(titles, idx),
                    isRead = progress.isChapterRead(idx),
                    isCurrent = idx == progress.locator.chapterIndex,
                )
            }
        // 倒序只翻显示顺序：index 已被固定在 Row 里，不会跟着变
        return if (order == Order.DESC) rows.reversed() else rows
    }

    /** 跳到某一章章首。行里的 index 是原始下标，倒序状态下也不会跳错。 */
    fun jumpTo(row: Row, progress: ReadingProgress): ReadingProgress =
        progress.seekTo(row.index, 0)

    /** 按章节下标跳转。越界由 seekTo 自行夹紧，这里不重复判断。 */
    fun jumpToIndex(index: Int, progress: ReadingProgress): ReadingProgress =
        progress.seekTo(index, 0)

    /** 当前章在目录里的那一行；没有则 null。 */
    fun currentRow(rows: List<Row>): Row? = rows.firstOrNull { it.isCurrent }

    /** 概览统计。 */
    fun summary(titles: List<String>, progress: ReadingProgress): Summary = Summary(
        total = titles.size,
        readCount = progress.readChapters.count { it in titles.indices },
        currentNumber = (progress.locator.chapterIndex + 1).coerceAtMost(titles.size.coerceAtLeast(1)),
    )

    /** 上一章 / 下一章是否可达，界面据此置灰翻页按钮。 */
    fun canGoPrevious(progress: ReadingProgress): Boolean = progress.locator.chapterIndex > 0

    fun canGoNext(titles: List<String>, progress: ReadingProgress): Boolean =
        progress.locator.chapterIndex < titles.lastIndex

    /**
     * 阅读时跨章：读完当前章就打已读标记。
     *
     * 用 [ReadingProgress.markChapterRead] 而不是「按偏移判断读完了」：
     * 分页可能只覆盖到章节尾部之前（尾段落为空白被裁掉），按偏移判会把
     * 确实读完的一章漏标。
     */
    fun markRead(index: Int, progress: ReadingProgress): ReadingProgress =
        progress.markChapterRead(index)

    /**
     * 相邻章节定位：+1 下一章、-1 上一章。
     * 上下章都走 seekTo，保证目录跳转与翻页跳转落到同一套定位真值上。
     */
    fun neighbour(titles: List<String>, progress: ReadingProgress, delta: Int): ReadingProgress {
        if (titles.isEmpty()) return progress
        val target = (progress.locator.chapterIndex + delta)
            .coerceIn(0, titles.lastIndex)
        if (target == progress.locator.chapterIndex) return progress
        return progress.seekTo(target, 0)
    }

    /** 目录为空时给用户的提示；非空返回 null。 */
    fun emptyHint(titles: List<String>, keyword: String = ""): String? {
        if (titles.isNotEmpty()) return null
        return "还没有目录，请先刷新目录"
    }

    /** 过滤后为空但目录本身有内容：提示放宽关键词，而不是让人以为书没章节。 */
    fun filterEmptyHint(titles: List<String>, shown: Int, keyword: String): String? {
        if (titles.isEmpty()) return null
        if (shown > 0) return null
        return if (keyword.isBlank()) "没有可显示的章节" else "没有章节名包含「${keyword.trim()}」"
    }

    /** 定位器，供阅读器直接使用。 */
    fun locatorOf(row: Row): ReadingLocator = ReadingLocator(row.index, 0)
}
