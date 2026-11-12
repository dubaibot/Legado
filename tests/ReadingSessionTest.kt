import com.lanyue.reader.core.render.PageTurnController
import com.lanyue.reader.core.render.ReadingLocator
import com.lanyue.reader.core.render.ReadingProgress
import com.lanyue.reader.core.render.ReadingSession
import com.lanyue.reader.core.render.ReadingSettings
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

private fun approx(name: String, actual: Float, expected: Float, eps: Float = 1e-4f) {
    if (kotlin.math.abs(actual - expected) <= eps) pass++ else {
        fail++
        failures.add("$name -> actual=$actual expected=$expected")
    }
}

/** 三章：[1000, 2000, 1500]，全书 4500 字。 */
private val counts = listOf(1000, 2000, 1500)

private fun prog(chapter: Int = 0, offset: Int = 0, read: Set<Int> = emptySet()) =
    ReadingProgress(
        locator = ReadingLocator(chapter, offset),
        chapterCharCounts = counts,
        readChapters = read
    )

/** 一段 200 字 + 一段 150 字，用于真实分页。 */
private val paras = listOf("甲".repeat(200), "乙".repeat(150))

fun main() {
    // ================= 一、序列化往返 =================
    val p = prog(1, 1234, setOf(0))
    val s = ReadingSession.encode(p)
    eq("编码章号", s.contains("chapter=1"), true)
    eq("编码偏移", s.contains("offset=1234"), true)
    eq("编码已读", s.contains("read=0"), true)
    val back = ReadingSession.decode(s, counts)
    eq("往返章号", back.locator.chapterIndex, 1)
    eq("往返偏移", back.locator.charOffset, 1234)
    eq("往返已读", back.readChapters, setOf(0))
    eq("往返整对象", back.locator, p.locator)

    eq("初始编码", ReadingSession.encode(ReadingProgress.initial(counts)),
        "chapter=0;offset=0;page=0;pages=1;read=")
    eq("编码已读升序", ReadingSession.encode(prog(0, 0, setOf(2, 0))).endsWith("read=0,2"), true)

    // ================= 二、反序列化防御 =================
    eq("空串得初始", ReadingSession.decode("", counts).locator, ReadingLocator(0, 0))
    eq("null 得初始", ReadingSession.decode(null, counts).locator, ReadingLocator(0, 0))
    eq("空章表得单章", ReadingSession.decode(null, emptyList()).chapterCharCounts, listOf(0))
    eq("垃圾串得初始", ReadingSession.decode("这不是存档", counts).locator, ReadingLocator(0, 0))

    val clampChapter = ReadingSession.decode("chapter=99;offset=10", counts)
    eq("章号越界夹到末章", clampChapter.locator.chapterIndex, 2)
    eq("章号越界后偏移夹紧", clampChapter.locator.charOffset, 10)

    val longOffset = ReadingSession.decode("chapter=0;offset=99999", counts)
    eq("偏移超过本章夹到章尾", longOffset.locator.charOffset, 1000)

    val negChapter = ReadingSession.decode("chapter=-5;offset=8", counts)
    eq("负章号夹到 0", negChapter.locator.chapterIndex, 0)

    val negOffset = ReadingSession.decode("chapter=1;offset=-3", counts)
    eq("负偏移夹到 0", negOffset.locator.charOffset, 0)

    // page/pages 必须自洽，否则 ReadingProgress 构造即抛异常
    val badPage = ReadingSession.decode("chapter=0;offset=10;page=99;pages=5", counts)
    eq("页号超界夹到末页", badPage.pageInChapter, 4)
    eq("页数保留", badPage.pagesInChapter, 5)

    val zeroPages = ReadingSession.decode("chapter=0;offset=10;page=3;pages=0", counts)
    eq("页数为 0 兜底为 1", zeroPages.pagesInChapter, 1)
    eq("页数 0 时页号归 0", zeroPages.pageInChapter, 0)

    val badRead = ReadingSession.decode("chapter=0;offset=0;page=0;pages=1;read=0,9,-1,abc", counts)
    eq("已读过滤越界下标", badRead.readChapters, setOf(0))

    // ================= 三、重排版保位置 =================
    val small = ReadingSettings.Default.withFontSize(16f)
    val big = ReadingSettings.Default.withFontSize(28f)
    val start = prog(0, 0).seekTo(0, 130)

    val vSmall = ReadingSession.repaginate(start, paras, small, 360f, 640f)
    val vBig = ReadingSession.repaginate(start, paras, big, 360f, 640f)

    eq("小字号位置不变", vSmall.progress.locator, start.locator)
    eq("大字号位置不变", vBig.progress.locator, start.locator)
    check("大字号页数不少于小字号", vBig.pageCount >= vSmall.pageCount,
        "big=${vBig.pageCount} small=${vSmall.pageCount}")
    check("字号变化确实改了分页", vBig.pageCount != vSmall.pageCount,
        "big=${vBig.pageCount} small=${vSmall.pageCount}")

    // 页号必须指向包含该偏移的页，而不是随便一页
    val page = vBig.doc.pages[vBig.progress.pageInChapter]
    check("页号指向包含偏移的页",
        vBig.progress.locator.charOffset >= page.startOffset &&
            vBig.progress.locator.charOffset < page.endOffset,
        "offset=${vBig.progress.locator.charOffset} range=${page.startOffset}..${page.endOffset}")
    eq("页数同步进进度", vBig.progress.pagesInChapter, vBig.pageCount)
    check("页号在合法区间",
        vBig.progress.pageInChapter in 0 until vBig.progress.pagesInChapter)

    // ================= 四、分页完整性（不截断、不重叠） =================
    val v = vSmall
    eq("正文无截断无重复",
        v.doc.pages.joinToString("") { pg -> pg.text },
        "甲".repeat(200) + "乙".repeat(150))
    eq("总字数一致", v.doc.totalChars, 350)

    var monotonic = true
    var prevEnd = -1
    v.doc.pages.forEach { pg ->
        if (pg.startOffset < prevEnd) monotonic = false
        if (pg.endOffset < pg.startOffset) monotonic = false
        prevEnd = pg.endOffset
    }
    check("页偏移单调不回退", monotonic)
    eq("首页从 0 开始", v.doc.pages.first().startOffset, 0)
    eq("页码连续", v.doc.pages.map { it.index }, (0 until v.pageCount).toList())

    // ================= 五、冷启动恢复 =================
    val saved = ReadingSession.encode(prog(1, 900, setOf(0)))
    val restored = ReadingSession.restore(saved, counts, paras, small, 360f, 640f)
    eq("恢复章号", restored.progress.locator.chapterIndex, 1)
    eq("恢复偏移", restored.progress.locator.charOffset, 900)
    check("恢复后页号合法",
        restored.progress.pageInChapter in 0 until restored.progress.pagesInChapter)
    check("恢复后页数来自本次分页", restored.progress.pagesInChapter >= 1)
    check("恢复后页存在", restored.doc.pages.isNotEmpty())

    // 存档页号在新字号下不可信，必须被重算覆盖
    val stale = ReadingSession.restore("chapter=1;offset=900;page=7;pages=9", counts, paras, big, 360f, 640f)
    eq("旧页号被重算覆盖", stale.progress.pagesInChapter, stale.pageCount)

    // 空存档冷启动不崩
    val fresh = ReadingSession.restore(null, counts, paras, small, 360f, 640f)
    eq("空存档回第 0 章", fresh.progress.locator.chapterIndex, 0)
    eq("空存档回开头", fresh.progress.locator.charOffset, 0)

    // ================= 六、进度条跳转 =================
    val zero = prog(0, 0)
    eq("跳 0%", ReadingSession.jumpToPercent(zero, 0f).locator, ReadingLocator(0, 0))
    eq("跳 50% 落第 2 章", ReadingSession.jumpToPercent(zero, 0.5f).locator,
        ReadingLocator(1, 1250))
    eq("跳 100% 落全书末", ReadingSession.jumpToPercent(zero, 1f).locator,
        ReadingLocator(2, 1500))

    val at10 = ReadingSession.jumpToPercent(zero, 0.1f).locator
    eq("跳 10% 章号", at10.chapterIndex, 0)
    eq("跳 10% 偏移", at10.charOffset, 450)

    eq("负百分比夹到 0", ReadingSession.jumpToPercent(zero, -2f).locator, ReadingLocator(0, 0))
    eq("超 100% 夹到末页", ReadingSession.jumpToPercent(zero, 9f).locator,
        ReadingLocator(2, 1500))

    val jumpPages = ReadingSession.jumpToPercent(zero, 0.5f)
    eq("跳转后页号重置", jumpPages.pageInChapter, 0)
    eq("跳转后页数重置", jumpPages.pagesInChapter, 1)
    eq("跳转不清已读", ReadingSession.jumpToPercent(prog(0, 0, setOf(0, 1)), 0.5f).readChapters,
        setOf(0, 1))

    // 空书防御：不应抛异常
    val emptyBook = ReadingProgress.initial(listOf(0))
    eq("空书跳 50% 不崩", ReadingSession.jumpToPercent(emptyBook, 0.5f).locator,
        ReadingLocator(0, 0))

    // 单章书：50% 落在章内
    val one = ReadingProgress(locator = ReadingLocator(0, 0), chapterCharCounts = listOf(800))
    eq("单章书 50%", ReadingSession.jumpToPercent(one, 0.5f).locator, ReadingLocator(0, 400))

    // 百分比往返：跳到 33.3% 再读百分比，误差不超过一个字
    val t33 = ReadingSession.jumpToPercent(zero, 0.333f)
    approx("33.3% 往返", t33.percent(), 0.333f, 0.001f)

    // ================= 七、章内跳转 =================
    val inCh = ReadingSession.jumpWithinChapter(prog(1, 0), 0.5f)
    eq("章内 50%", inCh.locator, ReadingLocator(1, 1000))
    eq("章内 100%", ReadingSession.jumpWithinChapter(prog(1, 0), 1f).locator,
        ReadingLocator(1, 2000))
    eq("章内负值夹到 0", ReadingSession.jumpWithinChapter(prog(1, 500), -1f).locator,
        ReadingLocator(1, 0))
    eq("章内不换章", ReadingSession.jumpWithinChapter(prog(2, 0), 0.3f).locator.chapterIndex, 2)

    // ================= 八、展示文案 =================
    eq("页码 1 基", ReadingSession.pageLabel(ReadingProgress.initial(counts)), "1/1")
    val labeled = ReadingProgress(
        locator = ReadingLocator(0, 0),
        chapterCharCounts = counts,
        pageInChapter = 2,
        pagesInChapter = 7
    )
    eq("页码中间页", ReadingSession.pageLabel(labeled), "3/7")
    eq("百分比文案", ReadingSession.percentLabel(prog(1, 1250)), "50.0%")
    eq("零进度文案", ReadingSession.percentLabel(ReadingProgress.initial(counts)), "0.0%")
    eq("满进度文案", ReadingSession.percentLabel(prog(2, 1500)), "100.0%")

    // ================= 九、与翻页控制器协作 =================
    // 重排版后再翻页，仍走同一套位置真值
    val afterBig = ReadingSession.repaginate(prog(0, 0), paras, big, 360f, 640f)
    val turned = PageTurnController.nextPage(afterBig.progress, afterBig.doc)
    check("重排版后可翻页", turned.moved, "boundary=${turned.boundary}")
    eq("翻页后仍在第 0 章", turned.progress.locator.chapterIndex, 0)
    check("翻页后偏移前进",
        turned.progress.locator.charOffset > afterBig.progress.locator.charOffset)

    val backTurn = PageTurnController.previousPage(turned.progress, afterBig.doc)
    eq("翻回原页", backTurn.progress.pageInChapter, afterBig.progress.pageInChapter)

    // ================= 十、保存-重开-再保存 稳定性 =================
    val once = ReadingSession.encode(prog(1, 777, setOf(0)))
    val twice = ReadingSession.encode(ReadingSession.decode(once, counts))
    eq("二次编码不变", twice, once)
    eq("二次解码不变", ReadingSession.decode(twice, counts).locator, ReadingLocator(1, 777))

    // 章节变短（书源换目录）后旧存档不能越界
    val shrunk = listOf(500, 100)
    val shrunkBack = ReadingSession.decode("chapter=1;offset=1900", shrunk)
    eq("目录变短后章号夹紧", shrunkBack.locator.chapterIndex, 1)
    eq("目录变短后偏移夹到新章长", shrunkBack.locator.charOffset, 100)

    // ================= 十一、字号档位全量自洽 =================
    var allOk = true
    var prevCount = 0
    var sp = TypographySpec.MIN_FONT_SP
    while (sp <= TypographySpec.MAX_FONT_SP) {
        val st = ReadingSettings.Default.withFontSize(sp)
        val view = ReadingSession.repaginate(prog(0, 0).seekTo(0, 60), paras, st, 360f, 640f)
        if (view.pageCount <= 0) allOk = false
        if (view.progress.pageInChapter !in 0 until view.progress.pagesInChapter) allOk = false
        if (view.progress.locator.charOffset != 60) allOk = false
        // 字号越大，一页装得越少，页数单调不减
        if (view.pageCount < prevCount) allOk = false
        prevCount = view.pageCount
        sp += 2f
    }
    check("全字号档位分页自洽且单调", allOk)

    println("================ T14 阅读会话（持久化/重排版/跳转）单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
