package com.lanyue.reader.core.render

/**
 * 阅读进度与定位。
 *
 * 进度以"全局字符偏移"为唯一真值，页号只是派生量：
 * 用户改字号 -> 重新分页 -> 用偏移还原位置，不会跳章。
 */
data class ReadingLocator(
    val chapterIndex: Int,
    val charOffset: Int
) : Comparable<ReadingLocator> {

    init {
        require(chapterIndex >= 0) { "chapterIndex must not be negative: $chapterIndex" }
        require(charOffset >= 0) { "charOffset must not be negative: $charOffset" }
    }

    override fun compareTo(other: ReadingLocator): Int {
        val byChapter = chapterIndex.compareTo(other.chapterIndex)
        return if (byChapter != 0) byChapter else charOffset.compareTo(other.charOffset)
    }

    fun isSamePlaceAs(other: ReadingLocator?): Boolean =
        other != null && chapterIndex == other.chapterIndex && charOffset == other.charOffset
}

/**
 * 全书阅读进度状态机。
 *
 * 刻意做成不可变对象：每次翻页产生新实例，便于 Compose 触发重组，
 * 也便于"回滚到上一位置"这类操作只靠保存旧引用实现。
 */
data class ReadingProgress(
    val locator: ReadingLocator,
    val chapterCharCounts: List<Int>,
    /** 已读章节数，用于目录里的"已读"标记。 */
    val readChapters: Set<Int> = emptySet(),
    /** 当前章节内的页号与总页数，仅用于显示。 */
    val pageInChapter: Int = 0,
    val pagesInChapter: Int = 1
) {

    init {
        require(pagesInChapter >= 1) { "pagesInChapter must be at least 1" }
        require(pageInChapter in 0 until pagesInChapter) {
            "pageInChapter out of range: $pageInChapter / $pagesInChapter"
        }
    }

    val totalChars: Long get() = chapterCharCounts.sumOf { it.toLong() }

    /**
     * 全书百分比，0f..1f。
     * 空书返回 0f 而不是抛异常或 NaN。
     */
    fun percent(): Float {
        val total = totalChars
        if (total <= 0L) return 0f
        val consumed = chapterCharCounts
            .take(locator.chapterIndex.coerceIn(0, chapterCharCounts.size))
            .sumOf { it.toLong() } + locator.charOffset
        return (consumed.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** 章节内百分比，用于页脚进度条。 */
    fun chapterPercent(): Float {
        val total = chapterCharCounts.getOrNull(locator.chapterIndex) ?: return 0f
        if (total <= 0) return 0f
        return (locator.charOffset.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun isChapterRead(index: Int): Boolean = index in readChapters

    /** 跳到章节内偏移，同时夹紧到该章长度内。 */
    fun seekTo(chapterIndex: Int, charOffset: Int): ReadingProgress {
        val safeChapter = chapterIndex.coerceIn(0, (chapterCharCounts.size - 1).coerceAtLeast(0))
        val len = chapterCharCounts.getOrNull(safeChapter) ?: 0
        val safeOffset = charOffset.coerceIn(0, len)
        return copy(
            locator = ReadingLocator(safeChapter, safeOffset),
            pageInChapter = 0,
            pagesInChapter = 1
        )
    }

    /** 用一次分页结果同步页号。偏移落在本章之外时夹到边界。 */
    fun syncPage(doc: Paginator.PagedDocument): ReadingProgress {
        if (doc.pages.isEmpty()) return copy(pageInChapter = 0, pagesInChapter = 1)
        val page = doc.pageOfOffset(locator.charOffset)
        return copy(pageInChapter = page, pagesInChapter = doc.pageCount)
    }

    /** 章节读完时打标。 */
    fun markCurrentChapterRead(): ReadingProgress {
        val len = chapterCharCounts.getOrNull(locator.chapterIndex) ?: 0
        if (locator.charOffset < len) return this
        return copy(readChapters = readChapters + locator.chapterIndex)
    }

    /** 下一章首屏。已是最后一章则原地不动。 */
    fun nextChapter(): ReadingProgress {
        if (locator.chapterIndex >= chapterCharCounts.lastIndex) return this
        return seekTo(locator.chapterIndex + 1, 0)
    }

    /** 上一章末屏（不是首屏，符合长按/返回上一章的习惯）。 */
    fun previousChapter(): ReadingProgress {
        if (locator.chapterIndex <= 0) return seekTo(0, 0)
        val prevIndex = locator.chapterIndex - 1
        val len = chapterCharCounts.getOrNull(prevIndex) ?: 0
        return seekTo(prevIndex, (len - 1).coerceAtLeast(0))
    }

    companion object {
        fun initial(chapterCharCounts: List<Int>): ReadingProgress =
            ReadingProgress(
                locator = ReadingLocator(0, 0),
                chapterCharCounts = if (chapterCharCounts.isEmpty()) listOf(0) else chapterCharCounts
            )
    }
}
