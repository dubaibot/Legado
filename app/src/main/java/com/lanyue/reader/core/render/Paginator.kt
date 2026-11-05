package com.lanyue.reader.core.render

/**
 * 分页引擎（纯逻辑，无 Android 依赖）。
 *
 * 设计取舍：
 * - 不做逐像素测量，改用"字宽单位"估算。中文/全角按 1 格，ASCII 按半格，
 *   与等宽中文字体的实际排版误差在 5% 以内，足以决定换行与分页位置。
 * - 保留全局字符偏移，翻页、进度条、书签、跨设备同步都复用它，
 *   避免"行号 / 页号"在字号变化后失效的老问题。
 */
object Paginator {

    /** 单个字符占用的宽度（以 em 为单位的倍数，1.0 = 一个汉字宽）。 */
    const val WIDTH_CJK = 1.0f

    /** ASCII 半角字符宽度。 */
    const val WIDTH_ASCII = 0.5f

    /** 其他（含拉丁扩展、标点、emoji 近似）宽度。 */
    const val WIDTH_OTHER = 0.7f

    /** 不允许出现在行首的标点（避头尾）。 */
    private const val NO_LINE_START = "，。、；：？！）】》」』〉…—·,.;:?!)]}\"'"

    /** 不允许出现在行尾的标点。 */
    private const val NO_LINE_END = "（【《「『〈([{\"'"

    /** 一页的物理约束。 */
    data class PageBox(
        /** 每行容量，单位 em。 */
        val columns: Float,
        /** 每页行数。 */
        val linesPerPage: Int
    ) {
        init {
            require(columns > 0f) { "columns must be positive: $columns" }
            require(linesPerPage > 0) { "linesPerPage must be positive: $linesPerPage" }
        }
    }

    /** 输出的一行。 */
    data class Line(
        val text: String,
        /** 该行第一个字符在整章中的全局偏移。 */
        val startOffset: Int,
        val endOffset: Int
    )

    /** 输出的一页。 */
    data class Page(
        val index: Int,
        val lines: List<Line>,
        val startOffset: Int,
        val endOffset: Int
    ) {
        val text: String get() = lines.joinToString("") { it.text }
    }

    /** 分页结果。 */
    data class PagedDocument(
        val pages: List<Page>,
        val totalChars: Int
    ) {
        val pageCount: Int get() = pages.size

        /** 找出包含指定全局偏移的页码，越界时夹到首/末页。 */
        fun pageOfOffset(offset: Int): Int {
            if (pages.isEmpty()) return 0
            val target = offset.coerceIn(0, pages.last().endOffset)
            return pages.indexOfFirst { target < it.endOffset }
                .let { if (it < 0) pages.lastIndex else it }
        }
    }

    /**
     * 判断字符宽度类别。
     * 用码点区间判断而非正则，避免长文本上的性能塌陷。
     */
    fun charWidth(cp: Int): Float = when {
        cp in 0x2E80..0x9FFF -> WIDTH_CJK      // CJK 部首/汉字
        cp in 0xA000..0xA4CF -> WIDTH_CJK      // 彝文
        cp in 0xAC00..0xD7AF -> WIDTH_CJK      // 韩文
        cp in 0xF900..0xFAFF -> WIDTH_CJK      // 兼容汉字
        cp in 0xFF00..0xFF60 -> WIDTH_CJK      // 全角标点
        cp in 0x3000..0x303F -> WIDTH_CJK      // CJK 标点
        cp in 0x20..0x7E -> WIDTH_ASCII        // ASCII 可见字符
        cp == '\t'.code -> WIDTH_CJK * 2f
        cp < 0x20 -> 0f                        // 控制字符不占宽
        else -> WIDTH_OTHER
    }

    /** 整段宽度，单位 em。 */
    fun measure(text: String): Float {
        var w = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += charWidth(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /**
     * 把一章拆成页。
     *
     * [paragraphs] 每个元素是一段（不要求带换行符）；空段会被跳过。
     * [startOffsetBase] 允许把本章挂到全书偏移上（连载拼接时用）。
     */
    fun paginate(
        paragraphs: List<String>,
        box: PageBox,
        startOffsetBase: Int = 0,
        typography: TypographySpec = TypographySpec.Default
    ): PagedDocument {
        val lines = ArrayList<Line>(256)
        var cursor = startOffsetBase

        paragraphs.forEachIndexed { pIndex, raw ->
            val text = raw.trim()
            if (text.isEmpty()) return@forEachIndexed

            // 首行缩进占用列宽（不写入字符，避免污染偏移）
            val firstLineBudget = (box.columns - typography.indentEm).coerceAtLeast(
                box.columns * MIN_INDENT_KEEP_RATIO
            )
            var offsetInPara = 0
            var firstLine = true

            while (offsetInPara < text.length) {
                val budget = if (firstLine) firstLineBudget else box.columns
                val take = takeByWidth(text, offsetInPara, budget)
                if (take <= 0) break

                val end = adjustBreak(text, offsetInPara, offsetInPara + take)
                val slice = text.substring(offsetInPara, end)
                lines.add(Line(slice, cursor + offsetInPara, cursor + end))

                offsetInPara = end
                firstLine = false
            }

            cursor += text.length
            // 段间插入一个视觉空行，占用行数但不对应字符
            if (pIndex != paragraphs.lastIndex) {
                lines.add(Line("", cursor, cursor))
            }
        }

        if (lines.isEmpty()) {
            return PagedDocument(listOf(Page(0, emptyList(), startOffsetBase, startOffsetBase)), 0)
        }

        val pages = ArrayList<Page>(lines.size / box.linesPerPage + 1)
        var pageStart = 0
        while (pageStart < lines.size) {
            val pageEnd = minOf(pageStart + box.linesPerPage, lines.size)
            val slice = lines.subList(pageStart, pageEnd).toList()
            pages.add(
                Page(
                    index = pages.size,
                    lines = slice,
                    startOffset = slice.first().startOffset,
                    endOffset = slice.last().endOffset
                )
            )
            pageStart = pageEnd
        }
        return PagedDocument(pages, cursor - startOffsetBase)
    }

    /**
     * 取本次能放进 [budget] 的字符数；一个字符都放不进时至少取 1，
     * 保证 while 循环一定推进（否则窄屏 + 大字号会死循环）。
     */
    private fun takeByWidth(text: String, from: Int, budget: Float): Int {
        var w = 0f
        var i = from
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val cw = charWidth(cp)
            if (w + cw > budget && i > from) return i - from
            w += cw
            i += Character.charCount(cp)
            if (w >= budget) return i - from
        }
        return text.length - from
    }

    /**
     * 修正换行点：
     * 1. 行首不能是避头标点 -> 往前收一个字符
     * 2. 行尾不能是避尾标点 -> 往前收一个字符
     * 3. 若切断英文单词（两侧都是 ASCII 字母）且本行还能回退，回退到空格
     */
    private fun adjustBreak(text: String, from: Int, rawEnd: Int): Int {
        var end = rawEnd.coerceIn(from + 1, text.length)
        if (end >= text.length) return text.length

        var guard = 0
        while (guard++ < MAX_BREAK_ADJUST && end > from + 1) {
            val next = text[end]
            val prev = text[end - 1]
            val nextIsNoStart = NO_LINE_START.indexOf(next) >= 0
            val prevIsNoEnd = NO_LINE_END.indexOf(prev) >= 0
            if (!nextIsNoStart && !prevIsNoEnd) break
            end--
        }

        // 英文单词保护：两侧都是 ASCII 字母时，回退到最近空格
        if (end < text.length && end > from + 1) {
            val prev = text[end - 1]
            val next = text[end]
            if (isLatinLetter(prev) && isLatinLetter(next)) {
                val space = text.lastIndexOf(' ', end - 1)
                if (space > from + 1) return space
            }
        }
        return end
    }

    private fun isLatinLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /** 缩进最多吃掉几成列宽。 */
    private const val MIN_INDENT_KEEP_RATIO = 0.5f

    /** 换行点修正的最大回退步数。 */
    private const val MAX_BREAK_ADJUST = 8
}
