package com.lanyue.reader.core.render

/**
 * 翻页状态机（纯逻辑，无 Android 依赖）。
 *
 * 职责边界：
 * - [Paginator] 只负责"把一章切成页"；
 * - [ReadingProgress] 只负责"记录在哪"；
 * - 本类负责"翻一页会发生什么"——页内前进、跨章、撞到书首书尾。
 *
 * 把这三件事拆开的好处：字号变化时只需重新分页，翻页行为本身不受影响，
 * 也就不会出现"改字号后翻页跳章"这类老问题。
 */
object PageTurnController {

    /** 翻页时越过的边界类型。 */
    enum class Boundary {
        /** 页内正常翻动。 */
        NONE,
        /** 本页是本章最后一页，已翻到下一章首屏。 */
        CHAPTER_END,
        /** 本页是本章第一页，已退回上一章末屏。 */
        CHAPTER_START,
        /** 已是全书最后一页，无法继续。 */
        BOOK_END,
        /** 已是全书第一页，无法继续。 */
        BOOK_START
    }

    /** 一次翻页的结果。 */
    data class TurnResult(
        val progress: ReadingProgress,
        /** 位置是否真的变了。false 表示撞边界被拦下。 */
        val moved: Boolean,
        val boundary: Boundary
    ) {
        /** 是否跨越了章节（上层据此触发章节标题刷新、预取下一章）。 */
        val crossedChapter: Boolean
            get() = boundary == Boundary.CHAPTER_END || boundary == Boundary.CHAPTER_START
    }

    /**
     * 下一页。
     *
     * [doc] 必须与 [progress] 的当前章一致；[doc] 为空或页数为 0 时按撞边界处理，
     * 不让调用方因为"内容还没解析好"就崩掉。
     */
    fun nextPage(progress: ReadingProgress, doc: Paginator.PagedDocument?): TurnResult {
        if (doc == null || doc.pageCount == 0) {
            return TurnResult(progress, false, Boundary.NONE)
        }
        val page = progress.pageInChapter.coerceIn(0, doc.pageCount - 1)
        if (page < doc.pageCount - 1) {
            val target = doc.pages[page + 1]
            return TurnResult(
                progress = progress.copy(
                    locator = ReadingLocator(progress.locator.chapterIndex, target.startOffset),
                    pageInChapter = page + 1,
                    pagesInChapter = doc.pageCount
                ),
                moved = true,
                boundary = Boundary.NONE
            )
        }
        // 已在末页：还有下一章就跨章，否则停在原地
        if (progress.locator.chapterIndex >= progress.chapterCharCounts.lastIndex) {
            return TurnResult(progress, false, Boundary.BOOK_END)
        }
        // 已在末页还继续翻，说明本章确实读完：按章节号强制打标，
        // 不依赖 charOffset 是否刚好等于章节长度（分页会裁掉尾部空白）。
        val next = progress.markChapterRead(progress.locator.chapterIndex).nextChapter()
        return TurnResult(next, true, Boundary.CHAPTER_END)
    }

    /**
     * 上一页。
     *
     * 本章第一页再往前退 -> 上一章末屏（与纸质书往回翻的手感一致）。
     * 注意跨章后的页号由上层重新分页后通过 [ReadingProgress.syncPage] 校正，
     * 这里只把偏移定位到上一章末尾。
     */
    fun previousPage(progress: ReadingProgress, doc: Paginator.PagedDocument?): TurnResult {
        if (doc == null || doc.pageCount == 0) {
            return TurnResult(progress, false, Boundary.NONE)
        }
        val page = progress.pageInChapter.coerceIn(0, doc.pageCount - 1)
        if (page > 0) {
            val target = doc.pages[page - 1]
            return TurnResult(
                progress = progress.copy(
                    locator = ReadingLocator(progress.locator.chapterIndex, target.startOffset),
                    pageInChapter = page - 1,
                    pagesInChapter = doc.pageCount
                ),
                moved = true,
                boundary = Boundary.NONE
            )
        }
        if (progress.locator.chapterIndex <= 0) {
            return TurnResult(progress, false, Boundary.BOOK_START)
        }
        return TurnResult(progress.previousChapter(), true, Boundary.CHAPTER_START)
    }

    /**
     * 滚动阅读模式下的"翻一屏"。
     *
     * 滚动模式没有页概念，用固定字符步长模拟：
     * 章节内步进，越界则整章翻动。返回值里的 pageInChapter 固定为 0。
     */
    fun scrollBy(progress: ReadingProgress, chars: Int): TurnResult {
        if (chars == 0) return TurnResult(progress, false, Boundary.NONE)
        val chapter = progress.locator.chapterIndex
        val len = progress.chapterCharCounts.getOrNull(chapter) ?: 0
        val target = progress.locator.charOffset + chars
        if (target in 0..len) {
            return TurnResult(
                progress = progress.copy(
                    locator = ReadingLocator(chapter, target),
                    pageInChapter = 0,
                    pagesInChapter = 1
                ),
                moved = true,
                boundary = Boundary.NONE
            )
        }
        if (target > len) {
            if (chapter >= progress.chapterCharCounts.lastIndex) {
                // 全书末尾：停在最后一个字符，而不是停在原位（滚动手感更自然）
                return TurnResult(
                    progress.copy(locator = ReadingLocator(chapter, len)),
                    progress.locator.charOffset != len,
                    Boundary.BOOK_END
                )
            }
            val next = progress.markChapterRead(chapter).seekTo(chapter + 1, 0)
            return TurnResult(next, true, Boundary.CHAPTER_END)
        }
        if (chapter <= 0) {
            // 全书开头：夹到 0，避免用户往回滚后仍停在原位置
            return TurnResult(
                progress.copy(locator = ReadingLocator(0, 0)),
                progress.locator.charOffset != 0,
                Boundary.BOOK_START
            )
        }
        val prevLen = progress.chapterCharCounts.getOrNull(chapter - 1) ?: 0
        return TurnResult(
            progress.seekTo(chapter - 1, (prevLen + target).coerceAtLeast(0)),
            true,
            Boundary.CHAPTER_START
        )
    }

    /**
     * 按阅读设置统一分页。
     *
     * 把"设置 -> 度量 -> 分页盒 -> 分页"这条链收在一处，
     * 避免每个界面各自算一遍导致字号与行列数不一致。
     */
    fun paginateWithSettings(
        paragraphs: List<String>,
        settings: ReadingSettings,
        viewportWidthDp: Float,
        viewportHeightDp: Float
    ): Paginator.PagedDocument {
        val typography = settings.toTypography()
        val columns = typography.columnsForWidth(viewportWidthDp)
        val usableHeight = (
            viewportHeightDp - typography.marginTopDp - typography.marginBottomDp
            ).coerceAtLeast(typography.fontSizeSp * typography.lineHeightMultiplier)
        val lineHeight = typography.fontSizeSp * typography.lineHeightMultiplier
        val linesPerPage = (usableHeight / lineHeight).toInt().coerceAtLeast(1)
        return Paginator.paginate(
            paragraphs = paragraphs,
            box = Paginator.PageBox(columns, linesPerPage),
            typography = typography
        )
    }
}
