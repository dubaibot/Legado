package com.lanyue.reader.core.render

/**
 * 阅读会话（纯逻辑，无 Android 依赖）。
 *
 * 把 T14 里\"跨进程仍然读得下去\"的三件事收在一处：
 * 1. 位置持久化——[ReadingProgress] 序列化/反序列化；
 * 2. 重排版保位置——字号/行距/视口变化后重新分页，位置按字符偏移还原；
 * 3. 进度条跳转——按全书百分比定位，而不是按页号（页号随字号变化失效）。
 *
 * 分工：本类不测量像素、不确定真值，只调度 [Paginator] 与 [ReadingProgress]。
 * 上层 Compose 只消费 [View]，不自己算页。
 */
object ReadingSession {

    /**
     * 一次排版的产物：算好的页 + 已校正页号的进度。
     *
     * 分开持有两者是刻意的——[Paginator.PagedDocument] 是\"这一版的页\"，
     * [ReadingProgress] 是\"用户在全书里的位置\"，字号一变前者作废、后者必须留存。
     */
    data class View(
        val doc: Paginator.PagedDocument,
        val progress: ReadingProgress
    ) {
        val pageCount: Int get() = doc.pageCount
    }

    // ==================== 位置持久化 ====================

    /**
     * 序列化阅读位置。
     *
     * 只存\"位置\"，不存正文与分页结果：正文可能被净化规则改写，
     * 存下来的页号在新排版下毫无意义；字符偏移才是跨版本稳定的真值。
     * 字段顺序固定，便于测试与 diff。
     */
    fun encode(progress: ReadingProgress): String = listOf(
        "chapter=${progress.locator.chapterIndex}",
        "offset=${progress.locator.charOffset}",
        "page=${progress.pageInChapter}",
        "pages=${progress.pagesInChapter}",
        "read=" + progress.readChapters.sorted().joinToString(",")
    ).joinToString(";")

    /**
     * 反序列化阅读位置。
     *
     * 任何字段缺失/非法都单独回落，且必须夹回章节长度内——
     * 旧存档遇到书源换了目录（章节变短）时，若直接把偏移写进
     * [ReadingLocator] 会立刻越过该章末尾，翻页随即跳章。
     */
    fun decode(raw: String?, chapterCharCounts: List<Int>): ReadingProgress {
        val counts = chapterCharCounts.ifEmpty { listOf(0) }
        if (raw.isNullOrBlank()) return ReadingProgress.initial(counts)

        val map = HashMap<String, String>()
        raw.split(';').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) map[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
        }

        val pages = (map["pages"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        // pageInChapter 必须落在 0 until pagesInChapter，否则构造即抛异常
        val page = (map["page"]?.toIntOrNull() ?: 0).coerceIn(0, pages - 1)
        val chapter = map["chapter"]?.toIntOrNull() ?: 0
        val offset = map["offset"]?.toIntOrNull() ?: 0
        val read = map["read"].orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in counts.indices }
            .toSet()

        val safeChapter = chapter.coerceIn(0, counts.lastIndex)
        val safeOffset = offset.coerceIn(0, counts[safeChapter])
        return ReadingProgress(
            locator = ReadingLocator(safeChapter, safeOffset),
            chapterCharCounts = counts,
            readChapters = read,
            pageInChapter = page,
            pagesInChapter = pages
        )
    }

    // ==================== 重排版保位置 ====================

    /**
     * 按当前设置重新分页，并让进度跟到新页。
     *
     * 这是\"调字号后位置不丢\"的实现：不改 [ReadingProgress.locator]，
     * 只重新算页，再用 [ReadingProgress.syncPage] 把页号指到包含该偏移的页。
     * 反过来的做法（记住页号再还原）在大字号下必然偏移，因为一页装不下原来那么多字。
     */
    fun repaginate(
        progress: ReadingProgress,
        paragraphs: List<String>,
        settings: ReadingSettings,
        viewportWidthDp: Float,
        viewportHeightDp: Float
    ): View {
        val doc = PageTurnController.paginateWithSettings(
            paragraphs = paragraphs,
            settings = settings,
            viewportWidthDp = viewportWidthDp,
            viewportHeightDp = viewportHeightDp
        )
        return View(doc, progress.syncPage(doc))
    }

    /**
     * 冷启动恢复：存档位置 -> 解码 -> 按当前设置分页 -> 校正页号。
     *
     * 存档里的 page/pages 只作参考（用于首帧显示\"3/12\"），
     * 分页算完后一律以字符偏移重新推导，避免设置改过之后页号说谎。
     */
    fun restore(
        raw: String?,
        chapterCharCounts: List<Int>,
        paragraphsOfCurrentChapter: List<String>,
        settings: ReadingSettings,
        viewportWidthDp: Float,
        viewportHeightDp: Float
    ): View {
        val decoded = decode(raw, chapterCharCounts)
        return repaginate(
            progress = decoded,
            paragraphs = paragraphsOfCurrentChapter,
            settings = settings,
            viewportWidthDp = viewportWidthDp,
            viewportHeightDp = viewportHeightDp
        )
    }

    // ==================== 进度条跳转 ====================

    /**
     * 按全书百分比跳转。
     *
     * 用 long 累加而不是浮点累乘：几千章的书里，`percent * total` 的
     * 每章截断误差会累积成整章的偏移，导致\"拖到 50% 却落在 49.6%\"。
     */
    fun jumpToPercent(progress: ReadingProgress, percent: Float): ReadingProgress {
        val total = progress.totalChars
        if (total <= 0L) return ReadingProgress.initial(progress.chapterCharCounts)

        val p = percent.coerceIn(0f, 1f)
        val target = Math.round(p.toDouble() * total.toDouble()).coerceIn(0L, total)

        var rest = target
        progress.chapterCharCounts.forEachIndexed { index, len ->
            val lenL = len.toLong()
            if (rest < lenL || index == progress.chapterCharCounts.lastIndex) {
                return progress.seekTo(index, rest.coerceIn(0L, lenL).toInt())
            }
            rest -= lenL
        }
        return progress.seekTo(0, 0)
    }

    /** 章节内百分比跳转，供目录里\"本章定位\"用。 */
    fun jumpWithinChapter(progress: ReadingProgress, percent: Float): ReadingProgress {
        val len = progress.chapterCharCounts.getOrNull(progress.locator.chapterIndex) ?: 0
        if (len <= 0) return progress
        val offset = Math.round(percent.coerceIn(0f, 1f) * len).coerceIn(0, len)
        return progress.seekTo(progress.locator.chapterIndex, offset)
    }

    /**
     * 给进度条用的全书百分比（0..100，保留一位小数）。
     * 与 [ReadingProgress.percent] 走同一真值，避免页脚和进度条两个数打架。
     */
    fun percentLabel(progress: ReadingProgress): String =
        String.format("%.1f%%", progress.percent() * 100f)

    /** 页脚页码文案，1 基（用户不理解 0 基页号）。 */
    fun pageLabel(progress: ReadingProgress): String =
        "${progress.pageInChapter + 1}/${progress.pagesInChapter}"
}
