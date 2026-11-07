import com.lanyue.reader.bookshelf.ShelfOps
import com.lanyue.reader.bookshelf.ShelfOps.SortMode
import com.lanyue.reader.bookshelf.SourceSwapper
import com.lanyue.reader.source.aggregate.SearchAggregator
import com.lanyue.reader.source.aggregate.SearchAggregator.SourcePage
import com.lanyue.reader.source.aggregate.SearchAggregator.SourceStatus
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.model.TocItem

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

private fun item(
    name: String, author: String?, url: String, origin: String,
    latest: String? = null
) = SearchBookItem(
    name = name, author = author, bookUrl = url, origin = origin,
    originName = origin, latestChapter = latest
)

fun main() {
    // ================= 归一化 =================
    eq("去装饰与空白", SearchAggregator.normalizeText("《斗 破 苍 穹》"), "斗破苍穹")
    eq("全角转半角", SearchAggregator.normalizeText("ＡＢＣ１２３"), "abc123")
    eq("折叠大小写", SearchAggregator.normalizeText("DouPo"), "doupo")
    eq("空串安全", SearchAggregator.normalizeText(null), "")
    eq("中点删除", SearchAggregator.normalizeText("斗・破"), "斗破")

    eq("作者剥后缀", SearchAggregator.normalizeAuthor("天蚕土豆 著"), "天蚕土豆")
    eq("作者斜杠著", SearchAggregator.normalizeAuthor("天蚕土豆/著"), "天蚕土豆")
    eq("作者空", SearchAggregator.normalizeAuthor("  "), "")
    eq("作者名字含著不误删", SearchAggregator.normalizeAuthor("著迷"), "著迷")

    // ================= 单源去重 =================
    val page = listOf(
        item("斗破苍穹", "天蚕土豆", "u1", "A"),
        item("斗破苍穹", "天蚕土豆", "u1", "A"),
        item("斗破苍穹", "天蚕土豆", "u2", "A"),
        item("武动乾坤", "天蚕土豆", "u3", "A"),
        item("斗破苍穹", "天蚕土豆", "", "A")
    )
    val (kept, removed) = SearchAggregator.dedupPage(page)
    eq("同URL去重", kept.size, 2)
    eq("去重计数", removed, 3)

    // ================= 跨源聚类 =================
    val pages = listOf(
        SourcePage("A", "源A", SourceStatus.OK, order = 0, items = listOf(
            item("《斗破苍穹》", "天蚕土豆", "a/1", "A"),
            item("武动乾坤", "天蚕土豆", "a/2", "A")
        )),
        SourcePage("B", "源B", SourceStatus.OK, order = 1, items = listOf(
            item("斗破苍穹", "天蚕土豆 著", "b/1", "B")
        )),
        SourcePage("C", "源C", SourceStatus.TIMEOUT, order = 2, message = "超时"),
        SourcePage("D", "源D", SourceStatus.OK, order = 3, items = listOf(
            item("斗破苍穹", null, "d/1", "D")
        )),
        SourcePage("E", "源E", SourceStatus.ISOLATED, order = 4, message = "隔离")
    )
    val agg = SearchAggregator.aggregate(pages, keyword = "斗破苍穹")

    eq("聚类数", agg.clusters.size, 2)
    val doupo = agg.clusters.first()
    eq("多源命中排前", doupo.primary.name, "《斗破苍穹》")
    eq("聚类源数", doupo.sourceCount, 3)
    eq("备选表", doupo.alternatives.size, 3)
    eq("代表来自高优先源", doupo.primary.bookUrl, "a/1")
    eq("缺失作者并入", doupo.alternatives.containsKey("D"), true)
    eq("合并计数", agg.mergedAcrossSources, 2)
    eq("源级报告数", agg.pages.size, 5)
    eq("有结果源数", agg.okSources, 3)
    check("摘要含隔离", agg.summary().contains("隔离"), agg.summary())

    // 缺失作者但存在两个同名聚类时不并入
    val ambiguous = listOf(
        SourcePage("A", "源A", SourceStatus.OK, order = 0, items = listOf(
            item("无极", "作者甲", "a/1", "A")
        )),
        SourcePage("B", "源B", SourceStatus.OK, order = 1, items = listOf(
            item("无极", "作者乙", "b/1", "B")
        )),
        SourcePage("C", "源C", SourceStatus.OK, order = 2, items = listOf(
            item("无极", null, "c/1", "C")
        ))
    )
    val agg2 = SearchAggregator.aggregate(ambiguous)
    eq("同名多作者不并入", agg2.clusters.size, 3)
    eq("三源各自独立", agg2.okSources, 3)
    eq("无跨源合并", agg2.mergedAcrossSources, 0)

    // 部分命中排在精确命中之后
    val partial = listOf(
        SourcePage("A", "源A", SourceStatus.OK, order = 0, items = listOf(
            item("斗破苍穹前传", null, "a/x", "A"),
            item("斗破苍穹", null, "a/1", "A")
        ))
    )
    val agg3 = SearchAggregator.aggregate(partial, keyword = "斗破苍穹")
    eq("精确命中在前", agg3.clusters.first().primary.bookUrl, "a/1")

    // ================= 书架：加书架冲突语义 =================
    val shelf = ShelfOps()
    val book1 = ShelfOps.ShelfBook(
        bookUrl = "a/1", name = "斗破苍穹", author = "天蚕土豆", origin = "A",
        groupName = "玄幻", totalChapter = 1600
    )
    val r1 = shelf.add(book1)
    eq("新增", r1.outcome, ShelfOps.AddOutcome.ADDED)
    eq("新增排序接尾", shelf.find("a/1")!!.sortOrder, 1)

    val r2 = shelf.add(book1.copy(cover = "c.png", latestChapter = "第1601章", totalChapter = 1601))
    eq("已在书架走更新", r2.outcome, ShelfOps.AddOutcome.UPDATED)
    eq("元信息刷新", shelf.find("a/1")!!.latestChapter, "第1601章")
    eq("用户分组保留", shelf.find("a/1")!!.groupName, "玄幻")
    eq("用户排序保留", shelf.find("a/1")!!.sortOrder, 1)

    val book2 = book1.copy(bookUrl = "b/1", origin = "B", groupName = "全部")
    shelf.add(book2)
    eq("软移出", shelf.remove("a/1"), true)
    eq("移出后不可见", shelf.size(), 1)
    eq("parked 含移出书", shelf.parked().size, 1)
    eq("重复移出无效", shelf.remove("a/1"), false)

    val r3 = shelf.add(book1.copy(bookUrl = "a/1", latestChapter = "第1602章"))
    eq("重新加回", r3.outcome, ShelfOps.AddOutcome.RESTORED)
    eq("分组保留", shelf.find("a/1")!!.groupName, "玄幻")
    eq("进度域保留", shelf.find("a/1")!!.sortOrder, 1)
    eq("恢复后可见", shelf.size(), 2)

    var threw = false
    try {
        shelf.add(book1.copy(bookUrl = "", name = "x"))
    } catch (e: IllegalArgumentException) {
        threw = true
    }
    check("空地址拒绝", threw, "应抛 IllegalArgumentException")
    threw = false
    try {
        shelf.add(book1.copy(bookUrl = "z/1", name = " "))
    } catch (e: IllegalArgumentException) {
        threw = true
    }
    check("空书名拒绝", threw, "应抛 IllegalArgumentException")

    // ================= 书架：分组 =================
    eq("移动分组", shelf.moveToGroup("b/1", "都市"), true)
    eq("空分组回默认", shelf.moveToGroup("b/1", ""), true)
    eq("默认分组名", shelf.find("b/1")!!.groupName, "全部")
    eq("同组移动无效", shelf.moveToGroup("b/1", "全部"), false)

    shelf.moveToGroup("b/1", "旧组")
    eq("分组重命名", shelf.renameGroup("旧组", "新组"), 1)
    eq("改名生效", shelf.find("b/1")!!.groupName, "新组")
    eq("删除分组归默认", shelf.removeGroup("新组"), 1)
    eq("默认组删除保护", shelf.removeGroup("全部"), 0)

    // a/1 仍在「玄幻」，b/1 已回「全部」
    eq("分组统计", shelf.groupSummary()["全部"], 1)
    eq("分组列表", shelf.groups(), listOf("玄幻", "全部"))

    // ================= 书架：排序 =================
    val s2 = ShelfOps()
    s2.add(ShelfOps.ShelfBook(bookUrl = "1", name = "乙", origin = "A"))
    s2.add(ShelfOps.ShelfBook(bookUrl = "2", name = "甲", origin = "B"))
    s2.add(ShelfOps.ShelfBook(bookUrl = "3", name = "丙", origin = "C"))

    s2.reorder(listOf("3", "1", "2"))
    eq("手动排序", s2.shelf(SortMode.MANUAL).map { it.bookUrl }, listOf("3", "1", "2"))
    // 未列出书保持原相对顺序接尾
    s2.reorder(listOf("1"))
    eq("部分重排", s2.shelf(SortMode.MANUAL).map { it.bookUrl }, listOf("1", "3", "2"))
    eq("空重排无效", s2.reorder(emptyList()), false)
    eq("未知地址无效", s2.reorder(listOf("nope")), false)

    s2.touch("2", at = 100L)
    s2.touch("1", at = 200L)
    eq("最近阅读排序", s2.shelf(SortMode.RECENT).map { it.bookUrl }, listOf("1", "2", "3"))
    // 名称按码点排序：丙(U+4E19) < 乙(U+4E59) < 甲(U+7532)
    eq("名称排序", s2.shelf(SortMode.NAME).map { it.bookUrl }, listOf("3", "1", "2"))

    // ================= 换源：归一化与章节号 =================
    eq("章题去标点", SourceSwapper.normalizeChapterTitle("第一章：起点！"), "第一章起点")
    eq("全角章号", SourceSwapper.normalizeChapterTitle("第１２章"), "第12章")

    eq("阿拉伯章号", SourceSwapper.extractChapterNumber("第12章"), 12L)
    eq("回目章号", SourceSwapper.extractChapterNumber("第30回"), 30L)
    eq("前导零章号", SourceSwapper.extractChapterNumber("第0102章"), 102L)
    eq("中文数字", SourceSwapper.extractChapterNumber("第四十五章"), 45L)
    eq("中文含零", SourceSwapper.extractChapterNumber("第一百零三章"), 103L)
    eq("中文含十", SourceSwapper.extractChapterNumber("第十五章"), 15L)
    eq("中文两千零一十", SourceSwapper.extractChapterNumber("第两千零一十章"), 2010L)
    eq("纯数字题", SourceSwapper.extractChapterNumber("1. 起点"), 1L)
    eq("英文章", SourceSwapper.extractChapterNumber("Chapter 12"), 12L)
    eq("卷内章号", SourceSwapper.extractChapterNumber("卷3 第45章"), 45L)
    eq("番外无号", SourceSwapper.extractChapterNumber("番外"), null)
    eq("空标题", SourceSwapper.extractChapterNumber(""), null)

    // ================= 换源：三级匹配 =================
    val oldToc = (1..10).map { TocItem("第${it}章 起点$it", "old/$it", it - 1) }
    val newToc = (1..12).map { TocItem("第${it}章 起点$it！", "new/$it", it - 1) }

    // 标题仅差标点 -> EXACT
    val p1 = SourceSwapper.mapProgress(
        oldToc, newToc, SourceSwapper.ReadPosition(2, "第1章：起点1", 100, 3000)
    )
    eq("EXACT 命中", p1.confidence, SourceSwapper.Confidence.EXACT)
    eq("EXACT 章序", p1.newChapterIndex, 0)
    eq("EXACT 偏移保留", p1.newCharOffset, 100)
    check("EXACT 说明含章名", p1.message.contains("起点1"), p1.message)

    // 中文章号对阿拉伯章号 -> NUMBER
    val p1b = SourceSwapper.mapProgress(
        oldToc, newToc, SourceSwapper.ReadPosition(2, "第一章 起点1", 100, 3000)
    )
    eq("NUMBER 命中", p1b.confidence, SourceSwapper.Confidence.NUMBER)
    eq("NUMBER 章序", p1b.newChapterIndex, 0)

    // 新源写法完全不同但章号一致 -> NUMBER
    val newTocAlt = (1..10).map { TocItem("$it. 起点$it", "n/$it", it - 1) }
    val p2 = SourceSwapper.mapProgress(
        oldToc, newTocAlt, SourceSwapper.ReadPosition(4, "第五章 起点5", 200, 3000)
    )
    eq("NUMBER 异构命中", p2.confidence, SourceSwapper.Confidence.NUMBER)
    eq("NUMBER 异构章序", p2.newChapterIndex, 4)

    // 多个同号候选取离原章序最近的
    val oldDup = listOf(
        TocItem("第一卷 第1章 开局", "d/0", 0),
        TocItem("第一卷 第5章", "d/4", 1),
        TocItem("第二卷 第1章 重启", "d/5", 2),
        TocItem("第二卷 第5章", "d/9", 3)
    )
    val newDup = listOf(
        TocItem("第一卷 1", "d/0", 0),
        TocItem("第一卷 5", "d/4", 1),
        TocItem("第二卷 1", "d/5", 2),
        TocItem("第二卷 5", "d/9", 3)
    )
    val p3 = SourceSwapper.mapProgress(
        oldDup, newDup, SourceSwapper.ReadPosition(3, "第二卷 第5章", 0, 0)
    )
    eq("同号取最近", p3.confidence, SourceSwapper.Confidence.NUMBER)
    eq("最近章序", p3.newChapterIndex, 3)

    // 重复标题（卷名「公告」出现两次）不走 EXACT，退回章号仍无解 -> INDEX
    val repToc = listOf(
        TocItem("公告", "r/0", 0),
        TocItem("第1章 A", "r/1", 1),
        TocItem("公告", "r/2", 2),
        TocItem("第2章 B", "r/3", 3)
    )
    val p4 = SourceSwapper.mapProgress(
        repToc, repToc, SourceSwapper.ReadPosition(2, "公告", 0, 0)
    )
    eq("重复标题退级", p4.confidence, SourceSwapper.Confidence.INDEX)
    eq("退级定位", p4.newChapterIndex, 2)

    // 无任何对应 -> INDEX，需核对
    val p5 = SourceSwapper.mapProgress(
        oldToc, listOf(TocItem("公告", "x/0", 0), TocItem("番外", "x/1", 1)),
        SourceSwapper.ReadPosition(1, "第二章 起点2", 0, 0)
    )
    eq("INDEX 兜底", p5.confidence, SourceSwapper.Confidence.INDEX)
    eq("INDEX 章序", p5.newChapterIndex, 1)
    eq("INDEX 需核对", p5.needsReview, true)

    // 新书章节更少且无对应 -> RESET
    val p6 = SourceSwapper.mapProgress(
        oldToc, listOf(TocItem("公告", "y/0", 0)),
        SourceSwapper.ReadPosition(5, "第六章", 0, 0)
    )
    eq("RESET 兜底", p6.confidence, SourceSwapper.Confidence.RESET)
    eq("RESET 从头", p6.newChapterIndex, 0)
    check("RESET 说明", p6.message.contains("从头"), p6.message)

    // 新目录为空
    val p7 = SourceSwapper.mapProgress(oldToc, emptyList(), SourceSwapper.ReadPosition(0, "第一章", 0, 0))
    eq("空目录 RESET", p7.confidence, SourceSwapper.Confidence.RESET)
    check("空目录说明", p7.message.contains("为空"), p7.message)

    // 标题缺失时回退原目录标题
    val p8 = SourceSwapper.mapProgress(
        oldToc, newToc, SourceSwapper.ReadPosition(3, null, 0, 0)
    )
    eq("标题回退匹配", p8.confidence, SourceSwapper.Confidence.EXACT)
    eq("回退定位", p8.newChapterIndex, 3)

    // ================= 换源：偏移精修 =================
    eq("偏移精修", SourceSwapper.refineOffset(150, 300, 200), 100)
    eq("精修夹紧", SourceSwapper.refineOffset(300, 300, 150), 150)
    eq("旧章长未知", SourceSwapper.refineOffset(100, 0, 200), 0)
    eq("新章长未知", SourceSwapper.refineOffset(100, 200, 0), 0)
    eq("零偏移", SourceSwapper.refineOffset(0, 300, 200), 0)

    // 报告组装
    val rep = SourceSwapper.report("A", "B", p1, newToc.size)
    eq("报告来源", rep.fromOrigin, "A")
    eq("报告目标", rep.toOrigin, "B")
    eq("报告新目录大小", rep.newTocSize, 12)

    // ================= 结论 =================
    println("================ R8 聚合搜索/书架/换源 单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
