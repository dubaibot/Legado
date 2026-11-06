import com.lanyue.reader.core.render.PageTurnController
import com.lanyue.reader.core.render.PageTurnController.Boundary
import com.lanyue.reader.core.render.PageTurnMode
import com.lanyue.reader.core.render.Paginator
import com.lanyue.reader.core.render.ReadingLocator
import com.lanyue.reader.core.render.ReadingProgress
import com.lanyue.reader.core.render.ReadingSettings
import com.lanyue.reader.core.render.ThemePalette
import com.lanyue.reader.core.render.ThemeRegistry
import com.lanyue.reader.core.render.TypographySpec

private var pass = 0
private var fail = 0
private val failures = ArrayList<String>()

private fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++ else {
        fail++
        failures.add("$name -> actual=$actual expected=$expected")
    }
}

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) pass++ else {
        fail++
        failures.add("$name -> $detail")
    }
}

private fun near(name: String, actual: Float, expected: Float, tol: Float = 1e-4f) {
    if (kotlin.math.abs(actual - expected) <= tol) pass++ else {
        fail++
        failures.add("$name -> actual=$actual expected=$expected")
    }
}

private fun throws(name: String, block: () -> Unit) {
    try {
        block(); fail++; failures.add("$name -> 期望抛异常但未抛出")
    } catch (e: IllegalArgumentException) {
        pass++
    } catch (e: Throwable) {
        fail++; failures.add("$name -> 异常类型不对: $e")
    }
}

/** 造一个可预测的两章文档：每页 3 行。 */
private fun doc(pages: Int, linesPerPage: Int = 3, baseChars: Int = 10): Paginator.PagedDocument {
    val lines = ArrayList<Paginator.Line>()
    for (i in 0 until pages * linesPerPage) {
        val s = i * baseChars
        lines.add(Paginator.Line("line$i", s, s + baseChars))
    }
    val list = ArrayList<Paginator.Page>()
    var p = 0
    while (p < lines.size) {
        val end = minOf(p + linesPerPage, lines.size)
        val slice = lines.subList(p, end).toList()
        list.add(Paginator.Page(list.size, slice, slice.first().startOffset, slice.last().endOffset))
        p = end
    }
    return Paginator.PagedDocument(list, lines.last().endOffset)
}

fun main() {
    // ================= PageTurnMode =================
    eq("默认翻页", PageTurnMode.Default, PageTurnMode.SLIDE)
    eq("id=slide 解析", PageTurnMode.fromId("slide"), PageTurnMode.SLIDE)
    eq("id=cover 解析", PageTurnMode.fromId("cover"), PageTurnMode.COVER)
    eq("id=none 解析", PageTurnMode.fromId("none"), PageTurnMode.NONE)
    eq("id=scroll 解析", PageTurnMode.fromId("scroll"), PageTurnMode.SCROLL)
    eq("未知 id 回落默认", PageTurnMode.fromId("hologram"), PageTurnMode.SLIDE)
    eq("null 回落默认", PageTurnMode.fromId(null), PageTurnMode.SLIDE)
    eq("空串回落默认", PageTurnMode.fromId(""), PageTurnMode.SLIDE)
    eq("slide 是分页", PageTurnMode.SLIDE.isPaged, true)
    eq("cover 是分页", PageTurnMode.COVER.isPaged, true)
    eq("none 仍分页", PageTurnMode.NONE.isPaged, true)
    eq("scroll 不是分页", PageTurnMode.SCROLL.isPaged, false)
    eq("中文标签", PageTurnMode.COVER.label, "覆盖")

    // ================= ReadingSettings 默认与转换 =================
    val d = ReadingSettings.Default
    eq("默认字号", d.fontSizeSp, TypographySpec.DEFAULT_FONT_SP)
    eq("默认主题", d.themeId, ThemePalette.DayId)
    eq("默认翻页", d.pageTurnMode, PageTurnMode.SLIDE)
    eq("默认常亮", d.keepScreenOn, true)
    eq("默认全屏", d.fullScreen, true)
    eq("默认不自动夜间", d.autoNightByClock, false)

    val typo = d.toTypography()
    eq("转换字号一致", typo.fontSizeSp, d.fontSizeSp)
    near("转换行距一致", typo.lineSpacingAdd, d.lineSpacingAdd)
    near("转换字距一致", typo.letterSpacingEm, d.letterSpacingEm)
    // 缩进/段距沿用默认
    near("缩进沿用默认", typo.indentEm, TypographySpec.Default.indentEm)

    // ================= 边界与夹紧 =================
    eq("字号上调夹到上限", d.withFontSize(999f).fontSizeSp, TypographySpec.MAX_FONT_SP)
    eq("字号下调夹到下限", d.withFontSize(-10f).fontSizeSp, TypographySpec.MIN_FONT_SP)
    eq("字号步进 +1", d.stepFontSize(1f).fontSizeSp, d.fontSizeSp + 1f)
    eq("步进撞上限被夹", d.withFontSize(TypographySpec.MAX_FONT_SP).stepFontSize(5f).fontSizeSp,
        TypographySpec.MAX_FONT_SP)
    eq("行距夹到上限", d.withLineSpacing(99f).lineSpacingAdd,
        TypographySpec.MAX_LINE_SPACING_ADD)
    eq("行距夹到 0", d.withLineSpacing(-1f).lineSpacingAdd, 0f)
    near("字距夹到上限", d.withLetterSpacing(9f).letterSpacingEm,
        TypographySpec.MAX_LETTER_SPACING_EM)

    throws("非法字号构造被拒") { ReadingSettings(fontSizeSp = 200f) }
    throws("非法行距构造被拒") { ReadingSettings(lineSpacingAdd = -1f) }
    throws("空主题 id 被拒") { ReadingSettings(themeId = " ") }

    // ================= 主题解析 =================
    eq("当前主题为纸质", d.theme().id, ThemePalette.DayId)
    eq("未知主题回落纸质", d.withTheme("no-such").theme().id, ThemePalette.DayId)
    eq("夜间主题可解析", d.withTheme(ThemePalette.NightId).theme().isDark, true)

    // 关闭自动夜间时与时间无关
    eq("关自动夜间 2 点仍是纸质", d.resolveTheme(2).id, ThemePalette.DayId)
    eq("关自动夜间 21 点仍是纸质", d.resolveTheme(21).id, ThemePalette.DayId)

    val auto = d.copy(autoNightByClock = true)
    eq("自动夜间 21 点转夜间", auto.resolveTheme(21).id, ThemePalette.NightId)
    eq("自动夜间 19 点转夜间", auto.resolveTheme(19).id, ThemePalette.NightId)
    eq("自动夜间 5 点仍是夜间", auto.resolveTheme(5).id, ThemePalette.NightId)
    eq("自动夜间 6 点转白天", auto.resolveTheme(6).id, ThemePalette.DayId)
    eq("自动夜间 12 点是白天", auto.resolveTheme(12).id, ThemePalette.DayId)
    eq("小时越界夹紧(99->23 夜间)", auto.resolveTheme(99).id, ThemePalette.NightId)
    eq("小时负数夹紧(-5->0 夜间)", auto.resolveTheme(-5).id, ThemePalette.NightId)

    // 用户原本选了护眼绿，白天保持绿，不强行改回纸质
    val greenAuto = d.withTheme("green").copy(autoNightByClock = true)
    eq("白天保留用户浅色主题", greenAuto.resolveTheme(12).id, "green")
    eq("夜间切到夜间主题", greenAuto.resolveTheme(22).id, ThemePalette.NightId)

    // ================= 序列化往返 =================
    val custom = ReadingSettings(
        fontSizeSp = 22.5f,
        lineSpacingAdd = 0.6f,
        letterSpacingEm = 0.02f,
        themeId = "green",
        pageTurnMode = PageTurnMode.COVER,
        keepScreenOn = false,
        fullScreen = false,
        autoNightByClock = true
    )
    val restored = ReadingSettings.deserialize(custom.serialize())
    eq("往返字号", restored.fontSizeSp, 22.5f)
    eq("往返行距", restored.lineSpacingAdd, 0.6f)
    eq("往返字距", restored.letterSpacingEm, 0.02f)
    eq("往返主题", restored.themeId, "green")
    eq("往返翻页", restored.pageTurnMode, PageTurnMode.COVER)
    eq("往返常亮", restored.keepScreenOn, false)
    eq("往返全屏", restored.fullScreen, false)
    eq("往返自动夜间", restored.autoNightByClock, true)
    eq("整串相等", restored, custom)

    eq("整数字号序列化无尾巴", d.serialize().contains("fontSizeSp=18"), true)
    eq("空串反序列化得默认", ReadingSettings.deserialize(""), ReadingSettings.Default)
    eq("null 反序列化得默认", ReadingSettings.deserialize(null), ReadingSettings.Default)
    // 部分损坏只回落单字段
    val partial = ReadingSettings.deserialize("fontSizeSp=abc;themeId=oled")
    eq("坏字号回落默认", partial.fontSizeSp, TypographySpec.DEFAULT_FONT_SP)
    eq("好字段仍生效", partial.themeId, "oled")
    // 越界值被夹紧而非丢弃
    val clamped = ReadingSettings.deserialize("fontSizeSp=999;lineSpacingAdd=-3")
    eq("序列化越界字号被夹", clamped.fontSizeSp, TypographySpec.MAX_FONT_SP)
    eq("序列化越界行距被夹", clamped.lineSpacingAdd, 0f)
    // 未知翻页 id 回落
    eq("未知翻页回落", ReadingSettings.deserialize("pageTurnMode=zzz").pageTurnMode,
        PageTurnMode.SLIDE)
    // 无等号片段被忽略
    eq("垃圾片段被忽略", ReadingSettings.deserialize(";;;=;x").themeId, ThemePalette.DayId)

    // ================= 翻页：页内 =================
    val prog = ReadingProgress.initial(listOf(100, 100))
    val d3 = doc(3) // 3 页，每页 3 行，行 10 字符

    val n1 = PageTurnController.nextPage(prog, d3)
    eq("页内下一页 moved", n1.moved, true)
    eq("页内下一页 boundary", n1.boundary, Boundary.NONE)
    eq("页内下一页页码", n1.progress.pageInChapter, 1)
    eq("页内下一页偏移对齐页首", n1.progress.locator.charOffset, d3.pages[1].startOffset)
    eq("页内不跨章", n1.crossedChapter, false)
    eq("章节保持不变", n1.progress.locator.chapterIndex, 0)

    val n2 = PageTurnController.nextPage(n1.progress, d3)
    eq("第二页再下一页码", n2.progress.pageInChapter, 2)

    // ================= 翻页：撞全书末页（只有一章时） =================
    val single = ReadingProgress(ReadingLocator(0, 0), listOf(1000))
    val sn2 = PageTurnController.nextPage(PageTurnController.nextPage(single, d3).progress, d3).progress
    eq("单章第3页页码", sn2.pageInChapter, 2)
    val n3 = PageTurnController.nextPage(sn2, d3)
    eq("末页且末章 moved=false", n3.moved, false)
    eq("末页且末章 boundary", n3.boundary, Boundary.BOOK_END)
    eq("末页且末章位置不变", n3.progress, sn2)
    eq("页数回填", n3.progress.pagesInChapter, d3.pageCount)

    // ================= 翻页：跨章 =================
    val crossStart = n2.progress.copy(
        chapterCharCounts = listOf(100, 100),
        locator = ReadingLocator(0, d3.pages.last().startOffset),
        pageInChapter = d3.pageCount - 1,
        pagesInChapter = d3.pageCount
    )
    val cross = PageTurnController.nextPage(crossStart, d3)
    eq("跨章 moved", cross.moved, true)
    eq("跨章 boundary", cross.boundary, Boundary.CHAPTER_END)
    eq("跨章后章节+1", cross.progress.locator.chapterIndex, 1)
    eq("跨章后回到章首", cross.progress.locator.charOffset, 0)
    eq("跨章被识别", cross.crossedChapter, true)
    eq("跨章强制标记已读", cross.progress.isChapterRead(0), true)

    // ================= 翻页：上一页 =================
    val back = PageTurnController.previousPage(n1.progress, d3)
    eq("上一页 moved", back.moved, true)
    eq("上一页页码", back.progress.pageInChapter, 0)
    eq("上一页偏移", back.progress.locator.charOffset, d3.pages[0].startOffset)

    val backStart = PageTurnController.previousPage(
        ReadingProgress(ReadingLocator(0, 0), listOf(100, 100), pageInChapter = 0, pagesInChapter = 3),
        d3
    )
    eq("书首再上一页 moved=false", backStart.moved, false)
    eq("书首 boundary", backStart.boundary, Boundary.BOOK_START)

    // 本章首页再上一页 -> 上一章末屏
    val atChapter2 = ReadingProgress(
        ReadingLocator(1, 0), listOf(100, 100), pageInChapter = 0, pagesInChapter = 3
    )
    val up = PageTurnController.previousPage(atChapter2, d3)
    eq("跨回上一章 moved", up.moved, true)
    eq("跨回上一章 boundary", up.boundary, Boundary.CHAPTER_START)
    eq("跨回章节-1", up.progress.locator.chapterIndex, 0)
    eq("跨回定位到上一章末尾", up.progress.locator.charOffset, 99)
    eq("跨回被识别", up.crossedChapter, true)

    // ================= 翻页：空文档防御 =================
    val emptyDoc = Paginator.PagedDocument(emptyList(), 0)
    val e1 = PageTurnController.nextPage(prog, emptyDoc)
    eq("空文档下一页不移动", e1.moved, false)
    eq("空文档下一页 boundary", e1.boundary, Boundary.NONE)
    eq("空文档下一页不改位置", e1.progress, prog)
    val e2 = PageTurnController.nextPage(prog, null)
    eq("null 文档不移动", e2.moved, false)
    val e3 = PageTurnController.previousPage(prog, null)
    eq("null 上翻不移动", e3.moved, false)

    // ================= 滚动模式 =================
    val scrollProg = ReadingProgress(ReadingLocator(0, 10), listOf(100, 100))
    val s1 = PageTurnController.scrollBy(scrollProg, 30)
    eq("滚动前进 moved", s1.moved, true)
    eq("滚动前进偏移", s1.progress.locator.charOffset, 40)
    eq("滚动不产生页号", s1.progress.pageInChapter, 0)
    eq("滚动章内 boundary", s1.boundary, Boundary.NONE)

    val s2 = PageTurnController.scrollBy(scrollProg, -20)
    eq("滚动回退偏移夹到0", s2.progress.locator.charOffset, 0)
    eq("滚动回退仍算移动", s2.moved, true)
    // 从偏移 10 继续回滚：位置从 10 夹到 0，属于移动
    val s3 = PageTurnController.scrollBy(scrollProg, -100)
    eq("滚动回滚越界夹到0", s3.progress.locator.charOffset, 0)
    eq("滚动回滚越界仍算移动", s3.moved, true)
    eq("滚动回滚越界 boundary", s3.boundary, Boundary.BOOK_START)
    // 已在 0 再回滚：不再移动
    val s3b = PageTurnController.scrollBy(s3.progress, -50)
    eq("已在书首再回滚不移动", s3b.moved, false)
    eq("已在书首 boundary", s3b.boundary, Boundary.BOOK_START)
    eq("滚动 0 无动作", PageTurnController.scrollBy(scrollProg, 0).moved, false)

    val s4 = PageTurnController.scrollBy(ReadingProgress(ReadingLocator(0, 95), listOf(100, 100)), 50)
    eq("滚动越章 moved", s4.moved, true)
    eq("滚动越章 boundary", s4.boundary, Boundary.CHAPTER_END)
    eq("滚动越章章节", s4.progress.locator.chapterIndex, 1)
    eq("滚动越章回章首", s4.progress.locator.charOffset, 0)

    val s5 = PageTurnController.scrollBy(
        ReadingProgress(ReadingLocator(1, 0), listOf(100, 100)), -30
    )
    eq("滚动回上章 boundary", s5.boundary, Boundary.CHAPTER_START)
    eq("滚动回上章章节", s5.progress.locator.chapterIndex, 0)
    eq("滚动回上章偏移", s5.progress.locator.charOffset, 70)

    val s6 = PageTurnController.scrollBy(
        ReadingProgress(ReadingLocator(1, 90), listOf(100, 100)), 50
    )
    eq("滚动撞全书末夹到章末", s6.progress.locator.charOffset, 100)
    eq("滚动撞末 boundary", s6.boundary, Boundary.BOOK_END)
    val s7 = PageTurnController.scrollBy(
        ReadingProgress(ReadingLocator(1, 100), listOf(100, 100)), 50
    )
    eq("已在末尾再滚不再移动", s7.moved, false)

    // ================= 设置驱动分页 =================
    val paras = listOf(
        "第一章开头的段落，用来验证分页链路是否把设置正确并入了度量。",
        "第二个段落同样参与分页，含标点、数字 123 与英文 word。",
        "第三段用于把页数推到一个以上，确保多页场景也走通。"
    )
    val docSmall = PageTurnController.paginateWithSettings(
        paras, ReadingSettings(fontSizeSp = 14f), viewportWidthDp = 360f, viewportHeightDp = 640f
    )
    check("小字号分页有内容", docSmall.pageCount >= 1 && docSmall.pages.first().lines.isNotEmpty(),
        "pageCount=${docSmall.pageCount}")
    val docBig = PageTurnController.paginateWithSettings(
        paras, ReadingSettings(fontSizeSp = 34f), viewportWidthDp = 360f, viewportHeightDp = 640f
    )
    check("大字号页数不少于小字号", docBig.pageCount >= docSmall.pageCount,
        "big=${docBig.pageCount} small=${docSmall.pageCount}")

    // 窄屏 + 大字号必须仍能分页（不能死循环/空页）
    val docNarrow = PageTurnController.paginateWithSettings(
        paras,
        ReadingSettings(fontSizeSp = 34f, lineSpacingAdd = 1.5f),
        viewportWidthDp = 60f,
        viewportHeightDp = 80f
    )
    check("极端窄屏仍出页", docNarrow.pageCount >= 1, "pageCount=${docNarrow.pageCount}")
    check("极端窄屏每页至少一行", docNarrow.pages.all { it.lines.isNotEmpty() })

    // 空段落不炸
    val docEmpty = PageTurnController.paginateWithSettings(
        listOf("", "   ", "\n"), ReadingSettings.Default, 360f, 640f
    )
    eq("全空段落页数", docEmpty.pageCount, 1)
    eq("全空段落字数", docEmpty.totalChars, 0)

    // 分页后页码与偏移保持一致：翻到第 2 页应命中第 2 页
    if (docBig.pageCount > 1) {
        val p0 = ReadingProgress.initial(listOf(docBig.totalChars))
        val moved = PageTurnController.nextPage(p0, docBig)
        eq("分页器第2页页码", moved.progress.pageInChapter, 1)
        eq("分页器页码回查一致", docBig.pageOfOffset(moved.progress.locator.charOffset), 1)
    }

    println("================ 阅读层单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
