import com.lanyue.reader.core.render.ReadingLocator
import com.lanyue.reader.core.render.ReadingProgress
import com.lanyue.reader.feature.detail.BookDetailModel
import com.lanyue.reader.feature.detail.BookDetailModel.DetailInput
import com.lanyue.reader.feature.detail.TocModel
import com.lanyue.reader.feature.detail.TocModel.Order

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

private fun book(
    title: String = "斗破苍穹",
    author: String = "天蚕土豆",
    url: String = "https://x.com/book/1",
    inShelf: Boolean = false,
    intro: String = "简介",
    total: Int = 100,
) = DetailInput(
    bookUrl = url,
    title = title,
    author = author,
    intro = intro,
    originName = "源A",
    latestChapterTitle = "第100章 大结局",
    totalChapters = total,
    inBookshelf = inShelf,
)

private fun prog(chapters: Int, current: Int = 0, read: Set<Int> = emptySet()) = ReadingProgress(
    locator = ReadingLocator(current, 0),
    chapterCharCounts = List(chapters) { 100 },
    readChapters = read,
)

private fun titles(n: Int) = (1..n).map { "第${it}章" }

fun main() {
    // ================= 一、字段规整 =================
    eq("空作者显示佚名", BookDetailModel.authorText(""), "佚名")
    eq("空白作者显示佚名", BookDetailModel.authorText("   "), "佚名")
    eq("正常作者原样", BookDetailModel.authorText(" 张三 "), "张三")
    eq("空标题兜底", BookDetailModel.titleText(""), "未命名")
    eq("正常标题原样", BookDetailModel.titleText(" 书 "), "书")

    eq("无封面", BookDetailModel.hasCover(book().copy(coverUrl = "")), false)
    eq("空白封面视为无", BookDetailModel.hasCover(book().copy(coverUrl = " ")), false)
    eq("有封面", BookDetailModel.hasCover(book().copy(coverUrl = "https://c.jpg")), true)

    // ================= 二、简介折叠 =================
    val short = BookDetailModel.introSummary("很短")
    eq("短简介不截断", short.truncated, false)
    eq("短简介原样", short.text, "很短")

    val long = BookDetailModel.introSummary("字".repeat(200))
    eq("长简介截断", long.truncated, true)
    check("截断带省略号", long.text.endsWith("…"), long.text.takeLast(3))
    eq("截断长度", long.text.length, BookDetailModel.INTRO_COLLAPSE_AT + 1)

    // 恰好等于上限不算截断（边界）
    val exact = BookDetailModel.introSummary("字".repeat(BookDetailModel.INTRO_COLLAPSE_AT))
    eq("恰好上限不截断", exact.truncated, false)
    eq("恰好上限长度", exact.text.length, BookDetailModel.INTRO_COLLAPSE_AT)
    // 上限 +1 就截断
    eq("上限加一就截断", BookDetailModel.introSummary("字".repeat(BookDetailModel.INTRO_COLLAPSE_AT + 1)).truncated, true)

    // 首尾空白不该占用户可见长度
    val padded = BookDetailModel.introSummary("  短简介  ")
    eq("去首尾空白", padded.text, "短简介")

    val emptyIntro = BookDetailModel.introSummary("")
    eq("空简介无内容", emptyIntro.text, "")
    eq("空简介不截断", emptyIntro.truncated, false)
    eq("空简介不可展开", BookDetailModel.canExpand(""), false)

    eq("长简介可展开", BookDetailModel.canExpand("字".repeat(200)), true)
    eq("短简介不可展开", BookDetailModel.canExpand("短"), false)
    eq("展开给全文", BookDetailModel.introFull("  " + "字".repeat(200) + "  ").length, 200)
    eq("limit 为 0 时返回空", BookDetailModel.introSummary("abc", 0).text, "")
    eq("limit 为 0 时标记截断", BookDetailModel.introSummary("abc", 0).truncated, true)

    // ================= 三、元信息 =================
    eq("0 章显示未知", BookDetailModel.chaptersText(0), "章数未知")
    eq("负数章也显示未知", BookDetailModel.chaptersText(-3), "章数未知")
    eq("有章数", BookDetailModel.chaptersText(100), "共 100 章")
    eq("无最新章不显示半截", BookDetailModel.latestText(""), "")
    eq("有最新章", BookDetailModel.latestText(" 第9章 "), "最新：第9章")
    eq("无来源不显示", BookDetailModel.originText(""), "")
    eq("有来源", BookDetailModel.originText(" 源A "), "来源：源A")

    val items = BookDetailModel.metaItems(book())
    eq("元信息项数", items.size, 4)
    eq("元信息首项", items[0].label, "作者")

    // 来源与最新章都空时，只保留作者和章数
    val lean = BookDetailModel.metaItems(book().copy(originName = "", latestChapterTitle = ""))
    eq("空项被剔除", lean.size, 2)
    eq("剔除后仍是作者在前", lean[0].label, "作者")

    check("元信息一行拼装", BookDetailModel.metaLine(book()).contains(" · "), BookDetailModel.metaLine(book()))
    check("一行含作者", BookDetailModel.metaLine(book()).contains("天蚕土豆"), BookDetailModel.metaLine(book()))

    // ================= 四、加入书架 =================
    eq("未在书架按钮文案", BookDetailModel.shelfLabel(false), "加入书架")
    eq("已在书架按钮文案", BookDetailModel.shelfLabel(true), "已在书架")
    eq("未在书架可点", BookDetailModel.shelfEnabled(false), true)
    eq("已在书架置灰", BookDetailModel.shelfEnabled(true), false)

    val added = BookDetailModel.addToShelf(book())
    eq("加入成功", added.changed, true)
    eq("加入后状态", added.input.inBookshelf, true)
    eq("加入提示", added.toast, "已加入书架")
    eq("加入无失败原因", added.reason, "")

    // 重复加入不产生写入
    val again = BookDetailModel.addToShelf(book(inShelf = true))
    eq("重复加入不改变", again.changed, false)
    eq("重复加入原因", again.reason, "already-in-shelf")

    // 缺唯一标识不能入库：落库后无法定位
    val noUrl = BookDetailModel.addToShelf(book(url = ""))
    eq("缺地址拒绝加入", noUrl.changed, false)
    eq("缺地址原因", noUrl.reason, "no-url")
    check("缺地址有提示", noUrl.toast.isNotBlank(), noUrl.toast)

    // 缺标题会在书架里留空行
    val noTitle = BookDetailModel.addToShelf(book(title = ""))
    eq("缺标题拒绝加入", noTitle.changed, false)
    eq("缺标题原因", noTitle.reason, "no-title")

    val removed = BookDetailModel.removeFromShelf(book(inShelf = true))
    eq("移出成功", removed.changed, true)
    eq("移出后状态", removed.input.inBookshelf, false)
    eq("移出提示", removed.toast, "已移出书架")

    val removeAbsent = BookDetailModel.removeFromShelf(book(inShelf = false))
    eq("不在书架不移除", removeAbsent.changed, false)
    eq("不在书架原因", removeAbsent.reason, "not-in-shelf")

    // 切换双向
    eq("切换：未在→加入", BookDetailModel.toggleShelf(book(inShelf = false)).input.inBookshelf, true)
    eq("切换：已在→移出", BookDetailModel.toggleShelf(book(inShelf = true)).input.inBookshelf, false)

    // ================= 五、阅读入口 =================
    eq("无进度显示开始", BookDetailModel.readButtonLabel(hasProgress = false, atStart = true), "开始阅读")
    eq("有进度但开头仍显示开始", BookDetailModel.readButtonLabel(hasProgress = true, atStart = true), "开始阅读")
    eq("有进度且非开头显示继续", BookDetailModel.readButtonLabel(hasProgress = true, atStart = false), "继续阅读")
    eq("无进度非开头仍显示开始", BookDetailModel.readButtonLabel(hasProgress = false, atStart = false), "开始阅读")

    eq("有章可读", BookDetailModel.canRead(10), true)
    eq("无章不可读", BookDetailModel.canRead(0), false)
    eq("可读时无拦截提示", BookDetailModel.readBlockedHint(10), "")
    check("无目录有拦截提示", BookDetailModel.readBlockedHint(0).contains("目录"), BookDetailModel.readBlockedHint(0))

    // ================= 六、目录：正序 =================
    val t5 = titles(5)
    val p0 = prog(5, 2, setOf(0, 1))
    val asc = TocModel.rows(t5, p0, Order.ASC)
    eq("正序行数", asc.size, 5)
    eq("正序首行章号", asc[0].number, 1)
    eq("正序首行下标", asc[0].index, 0)
    eq("正序末行下标", asc[4].index, 4)
    eq("已读标记 0", asc[0].isRead, true)
    eq("已读标记 1", asc[1].isRead, true)
    eq("未读标记 2", asc[2].isRead, false)
    eq("当前章标记", asc[2].isCurrent, true)
    eq("非当前章不标记", asc[0].isCurrent, false)

    // ================= 七、目录：倒序（关键易错点） =================
    val desc = TocModel.rows(t5, p0, Order.DESC)
    eq("倒序行数不变", desc.size, 5)
    // 显示顺序反转，但 index/number 必须保持原样
    eq("倒序首行是末章", desc[0].index, 4)
    eq("倒序首行章号仍是5", desc[0].number, 5)
    eq("倒序末行是首章", desc[4].index, 0)
    eq("倒序末行章号仍是1", desc[4].number, 1)

    // 这是最容易出的 bug：在倒序列表里点第一行，必须跳到第 5 章而不是第 1 章
    val jumped = TocModel.jumpTo(desc[0], p0)
    eq("倒序点首行跳到末章", jumped.locator.chapterIndex, 4)
    val jumpedLast = TocModel.jumpTo(desc[4], p0)
    eq("倒序点末行跳到首章", jumpedLast.locator.chapterIndex, 0)

    // 已读标记在倒序下依然正确（查原始下标）
    eq("倒序末行(第1章)已读", desc[4].isRead, true)
    eq("倒序首行(第5章)未读", desc[0].isRead, false)

    // 倒序下当前章标记仍落在正确那一行
    eq("倒序当前章下标", TocModel.currentRow(desc)!!.index, 2)
    eq("倒序当前章章号", TocModel.currentRow(desc)!!.number, 3)

    // ================= 八、目录：关键词过滤 =================
    val named = listOf("第一章 起点", "第二章 风波", "第三章 风起")
    val filtered = TocModel.rows(named, prog(3), Order.ASC, "风")
    eq("过滤命中两条", filtered.size, 2)
    eq("过滤保留原下标", filtered.map { it.index }, listOf(1, 2))
    eq("过滤保留原章号", filtered.map { it.number }, listOf(2, 3))

    // 过滤 + 倒序：下标仍然不能变
    val filteredDesc = TocModel.rows(named, prog(3), Order.DESC, "风")
    eq("过滤倒序顺序", filteredDesc.map { it.index }, listOf(2, 1))
    eq("过滤倒序跳转正确", TocModel.jumpTo(filteredDesc[0], prog(3)).locator.chapterIndex, 2)

    eq("关键词无命中", TocModel.rows(named, prog(3), Order.ASC, "雨").size, 0)
    eq("空关键词不过滤", TocModel.rows(named, prog(3), Order.ASC, "  ").size, 3)

    // ================= 九、目录：标题兜底 =================
    eq("空标题兜底", TocModel.titleOf(listOf(""), 0), "第 1 章")
    eq("越界兜底", TocModel.titleOf(emptyList(), 3), "第 4 章")
    eq("正常标题原样", TocModel.titleOf(listOf(" 序章 "), 0), "序章")

    // ================= 十、目录：概览 =================
    val s = TocModel.summary(t5, p0)
    eq("概览总章数", s.total, 5)
    eq("概览已读数", s.readCount, 2)
    eq("概览当前章号", s.currentNumber, 3)
    eq("概览已读百分比", s.readPercent, 40)
    eq("概览当前标签", s.currentLabel, "第 3/5 章")
    eq("概览已读标签", s.readLabel, "已读 2/5")

    val sEmpty = TocModel.summary(emptyList(), prog(1))
    eq("空目录已读百分比", sEmpty.readPercent, 0)
    eq("空目录当前标签", sEmpty.currentLabel, "无目录")

    // 已读集合里越界的下标不该计入
    val sOut = TocModel.summary(t5, prog(5, 0, setOf(0, 99)))
    eq("越界已读不计入", sOut.readCount, 1)

    // ================= 十一、目录：翻章边界 =================
    eq("首章无上一章", TocModel.canGoPrevious(prog(5, 0)), false)
    eq("非首章有上一章", TocModel.canGoPrevious(prog(5, 1)), true)
    eq("末章无下一章", TocModel.canGoNext(t5, prog(5, 4)), false)
    eq("非末章有下一章", TocModel.canGoNext(t5, prog(5, 3)), true)

    eq("下一章", TocModel.neighbour(t5, prog(5, 2), 1).locator.chapterIndex, 3)
    eq("上一章", TocModel.neighbour(t5, prog(5, 2), -1).locator.chapterIndex, 1)
    // 边界夹紧，不越界也不抛错
    eq("首章再上一章原地", TocModel.neighbour(t5, prog(5, 0), -1).locator.chapterIndex, 0)
    eq("末章再下一章原地", TocModel.neighbour(t5, prog(5, 4), 1).locator.chapterIndex, 4)
    eq("空目录翻章不动", TocModel.neighbour(emptyList(), prog(1, 0), 1).locator.chapterIndex, 0)
    // 原地不动时返回同一实例，避免无谓重组
    val p00 = prog(5, 0)
    check("边界不动返回原实例", TocModel.neighbour(t5, p00, -1) === p00)

    // ================= 十二、目录：已读标记复用 =================
    val marked = TocModel.markRead(3, prog(5))
    eq("标记后已读", marked.isChapterRead(3), true)
    eq("标记不影响其他", marked.isChapterRead(2), false)
    eq("越界下标不标记", TocModel.markRead(99, prog(5)).readChapters.isEmpty(), true)

    // ================= 十三、目录：空态提示 =================
    val eh = TocModel.emptyHint(emptyList()).orEmpty()
    check("空目录提示", eh.contains("刷新目录"), eh)
    eq("有目录无空态", TocModel.emptyHint(t5), null)
    eq("过滤有结果无提示", TocModel.filterEmptyHint(named, 2, "风"), null)
    val fh = TocModel.filterEmptyHint(named, 0, "雨").orEmpty()
    check("过滤无结果提示放宽", fh.contains("雨"), fh)
    eq("目录本身为空不重复提示", TocModel.filterEmptyHint(emptyList(), 0, "雨"), null)

    // ================= 十四、定位器 =================
    val row = TocModel.rows(t5, p0, Order.ASC)[3]
    eq("定位器章号", TocModel.locatorOf(row).chapterIndex, 3)
    eq("定位器偏移", TocModel.locatorOf(row).charOffset, 0)
    eq("按索引跳转", TocModel.jumpToIndex(2, p0).locator.chapterIndex, 2)

    println("================ T13 详情页与目录 单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
