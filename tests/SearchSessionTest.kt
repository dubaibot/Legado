import com.lanyue.reader.source.aggregate.SearchAggregator
import com.lanyue.reader.source.aggregate.SearchController
import com.lanyue.reader.source.aggregate.SearchSession
import com.lanyue.reader.source.aggregate.SearchSession.Outcome
import com.lanyue.reader.source.aggregate.SearchSession.Phase
import com.lanyue.reader.source.aggregate.SearchSession.Seed
import com.lanyue.reader.source.model.SearchBookItem

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
    name: String,
    author: String? = "某作者",
    url: String = "https://x.com/$name",
    origin: String = "o1",
) = SearchBookItem(name = name, author = author, bookUrl = url, origin = origin)

private val T0 = 1_000_000L

private fun seeds(vararg pairs: Pair<String, String>) =
    pairs.mapIndexed { i, p -> Seed(p.first, p.second, i) }

fun main() {
    // ================= 一、初始状态：一个都没回 =================
    val s = SearchSession("斗破", 1, seeds("a" to "源A", "b" to "源B"), T0)
    val init = s.snapshot()
    eq("初始阶段 LOADING", init.phase, Phase.LOADING)
    eq("初始已结算 0", init.settled, 0)
    eq("初始总数 2", init.total, 2)
    eq("初始转圈", init.showSpinner, true)
    eq("初始未完成", init.isDone, false)
    eq("初始不是致命", init.fatal, false)
    eq("初始进度文案", init.progressLabel(), "已返回 0/2 个源")
    eq("初始两槽都 PENDING", init.slots.map { it.outcome }, listOf(Outcome.PENDING, Outcome.PENDING))

    // ================= 二、渐进：一个源回来就能画 =================
    // 这是 T12 的核心：快的源不必等慢的
    val aItems = listOf(
        book("斗破苍穹", url = "https://a.com/1", origin = "a"),
        book("斗破2", author = "乙", url = "https://a.com/2", origin = "a"),
    )
    eq("A 返回 ok", s.onResult("a", aItems, T0 + 200), Outcome.OK)
    val partial = s.snapshot()
    eq("部分返回阶段 PARTIAL", partial.phase, Phase.PARTIAL)
    eq("部分返回已结算 1", partial.settled, 1)
    eq("部分返回仍转圈", partial.showSpinner, true)
    eq("部分返回未完成", partial.isDone, false)
    eq("部分返回有内容", partial.aggregate.clusters.isNotEmpty(), true)
    // B 还在搜，不能算进「x/y 个源有结果」
    eq("部分返回只算已收尾的源", partial.aggregate.pages.size, 1)

    // ================= 三、单源预算隔离：慢源不拖死整页 =================
    // 单源预算 8s：第 8001ms 时 B 仍没回 → 隔离，页面收尾
    val swept = s.sweep(T0 + 8_001)
    eq("扫掉 1 个超时源", swept, 1)
    val done = s.snapshot()
    eq("隔离后 DONE", done.phase, Phase.DONE)
    eq("隔离后停止转圈", done.showSpinner, false)
    eq("隔离后完成", done.isDone, true)
    eq("B 判为 ISOLATED", done.slots[1].outcome, Outcome.ISOLATED)
    check("隔离说明含单源预算", done.slots[1].message.contains("8000"), done.slots[1].message)
    eq("隔离不算致命(有源成功)", done.fatal, false)
    eq("隔离后状态条含被隔离数", done.statusLine().contains("1 个源被隔离"), true, )

    // 已隔离的源迟到的结果不许并入列表。
    // 结局保留 ISOLATED 而不是改写成 DROPPED：用户该看到的是「这个源超时了」，
    // 而不是「结果来晚了」——后者会让人以为再等等还有戏。
    eq("隔离后迟到结果不再采信", s.onResult("b", listOf(book("斗破", origin = "b")), T0 + 9_000), Outcome.ISOLATED)
    eq("迟到后结局仍为 ISOLATED", s.snapshot().slots[1].outcome, Outcome.ISOLATED)
    eq("迟到结果未被并入", s.snapshot().slots[1].items.size, 0)
    // A 交了两本不同的书，故为 2 个聚类；关键是 B 的迟到结果没让这个数变成 3
    eq("迟到结果未增加聚类", s.snapshot().aggregate.clusters.size, 2)
    eq("隔离源计入坏源", s.snapshot().slots[1].outcome.broken, true)

    // ================= 四、重复回调不覆盖既有结局 =================
    eq("重复回调返回原结局", s.onResult("a", listOf(book("另一本", origin = "a")), T0 + 300), Outcome.OK)
    eq("重复回调不改条数", s.snapshot().slots[0].items.size, 2)

    // ================= 五、失败隔离：一个源报错不影响其他 =================
    val s2 = SearchSession("书", 1, seeds("a" to "源A", "b" to "源B", "c" to "源C"), T0)
    eq("A 失败", s2.onFailure("a", "连接超时", T0 + 100), Outcome.FAILED)
    eq("C 仍成功", s2.onResult("c", listOf(book("书", origin = "c")), T0 + 150), Outcome.OK)
    val s2v = s2.snapshot()
    eq("失败源保留原因", s2v.slots[0].message, "连接超时")
    eq("三源结算二", s2v.settled, 2)
    eq("仍有源在搜", s2v.phase, Phase.PARTIAL)
    eq("失败源算 broken", s2v.slots[0].outcome.broken, true)
    eq("成功源不算 broken", s2v.slots[2].outcome.broken, false)

    // 已失败的源再报超时不再覆盖理由（保留最先的原因）
    s2.sweep(T0 + 8_001)
    eq("已失败源不被超时覆盖", s2.snapshot().slots[0].outcome, Outcome.FAILED)
    eq("失败原因不被覆盖", s2.snapshot().slots[0].message, "连接超时")

    // ================= 六、空结果是正常结局，不是失败 =================
    val s3 = SearchSession("冷门", 1, seeds("a" to "源A"), T0)
    eq("空结果判 EMPTY", s3.onResult("a", emptyList(), T0 + 100), Outcome.EMPTY)
    val s3v = s3.snapshot()
    eq("空结果已收尾", s3v.phase, Phase.DONE)
    eq("空结果不算致命", s3v.fatal, false)
    eq("空结果不算 broken", s3v.slots[0].outcome.broken, false)

    // ================= 七、致命：全部源都没给内容 =================
    val s4 = SearchSession("x", 1, seeds("a" to "源A", "b" to "源B"), T0)
    s4.onFailure("a", "解析失败", T0 + 50)
    s4.sweep(T0 + 8_001)
    val s4v = s4.snapshot()
    eq("全失败判致命", s4v.fatal, true)
    check("致命提示含源数", s4v.fatalHint().contains("全部 2 个源"), s4v.fatalHint())
    eq("致命时已收尾", s4v.isDone, true)
    eq("致命时停止转圈", s4v.showSpinner, false)
    // 一个源成功就不算致命
    val s4b = SearchSession("x", 1, seeds("a" to "源A", "b" to "源B"), T0)
    s4b.onFailure("a", "err", T0 + 50)
    s4b.onResult("b", listOf(book("有", origin = "b")), T0 + 60)
    eq("有源成功则不致命", s4b.snapshot().fatal, false)

    // ================= 八、整页预算：剩余源一律收尾 =================
    val s5 = SearchSession("y", 1, seeds("a" to "A", "b" to "B", "c" to "C"), T0)
    // 20s 总预算到点，即便单源预算早已过
    eq("整页预算扫掉 3 个", s5.sweep(T0 + 20_001), 3)
    eq("整页预算后完成", s5.snapshot().isDone, true)
    check("整页预算说明", s5.snapshot().slots[0].message.contains("20000"), s5.snapshot().slots[0].message)
    // 整页收尾后返回同样不被采信（结局保留已判定的 ISOLATED）
    eq("收尾后返回不被采信", s5.onResult("a", listOf(book("z", origin = "a")), T0 + 20_002), Outcome.ISOLATED)
    eq("收尾后结果未并入", s5.snapshot().slots[0].items.size, 0)

    // ================= 九、单源条数上限 =================
    val cfg = SearchSession.Config(maxItemsPerSource = 3)
    val s6 = SearchSession("z", 1, seeds("a" to "A"), T0, cfg)
    val many = (1..10).map { book("书$it", author = "作者$it", url = "https://a.com/$it", origin = "a") }
    eq("超限仍判 OK", s6.onResult("a", many, T0 + 10), Outcome.OK)
    eq("截断到上限", s6.snapshot().slots[0].items.size, 3)
    eq("截断计数", s6.snapshot().truncated, 7)
    check("状态条含截断", s6.snapshot().statusLine().contains("截断 7 条"), s6.snapshot().statusLine())
    // 恰好等于上限不算截断
    val s6b = SearchSession("z", 1, seeds("a" to "A"), T0, cfg)
    s6b.onResult("a", many.take(3), T0 + 10)
    eq("等于上限不截断", s6b.snapshot().truncated, 0)

    // ================= 十、未知 origin =================
    eq("未知源返回 null", s.onResult("nope", listOf(book("q")), T0 + 10), null)
    eq("未知源失败返回 null", s.onFailure("nope", "e", T0 + 10), null)

    // ================= 十一、跨源聚类：同一本书归并 =================
    // T12 验收第二句：多个源都有这本书，必须能合并显示
    val s7 = SearchSession("斗破苍穹", 1, seeds("a" to "源A", "b" to "源B"), T0)
    s7.onResult("a", listOf(book("斗破苍穹", "天蚕土豆", "https://a.com/1", "a")), T0 + 100)
    s7.onResult("b", listOf(book("斗破苍穹", "天蚕土豆", "https://b.com/9", "b")), T0 + 200)
    val s7v = s7.snapshot()
    eq("跨源合并为一个聚类", s7v.aggregate.clusters.size, 1)
    eq("聚类含两个源", s7v.aggregate.clusters[0].sourceCount, 2)
    eq("换源表有两项", s7v.aggregate.clusters[0].alternatives.size, 2)
    eq("合并计数记 1", s7v.aggregate.mergedAcrossSources, 1)
    check("总结含合并", s7v.aggregate.summary().contains("合并重复 1 条"), s7v.aggregate.summary())

    // 书名相同但作者不同 → 不该合并（防同名不同书）
    val s8 = SearchSession("同名", 1, seeds("a" to "A", "b" to "B"), T0)
    s8.onResult("a", listOf(book("同名书", "作者甲", "https://a.com/1", "a")), T0 + 10)
    s8.onResult("b", listOf(book("同名书", "作者乙", "https://b.com/1", "b")), T0 + 20)
    eq("同名不同作者不合并", s8.snapshot().aggregate.clusters.size, 2)

    // ================= 十二、代次控制：换关键词后旧结果必须被拒 =================
    val ctrl = SearchController()
    val first = ctrl.begin("旧词", seeds("a" to "A"), T0)
    eq("首次代次 1", first.epoch, 1L)
    eq("首会话是当前", ctrl.isCurrent(first), true)

    // 用户改词重搜
    val second = ctrl.begin("新词", seeds("a" to "A"), T0 + 500)
    eq("二代次 2", second.epoch, 2L)
    eq("旧会话不再当前", ctrl.isCurrent(first), false)
    eq("新会话当前", ctrl.isCurrent(second), true)

    // 旧搜索的结果迟到，必须按代次挡掉
    val stale = ctrl.offerResult(1L, "a", listOf(book("旧结果")), T0 + 900)
    eq("旧代结果被拒", stale.accepted, false)
    eq("旧代拒绝原因", stale.reject, SearchController.Reject.STALE_EPOCH)
    eq("旧结果未污染新会话", ctrl.snapshot()!!.aggregate.clusters.size, 0)

    // 新代正常通过
    val good = ctrl.offerResult(2L, "a", listOf(book("新结果", origin = "a")), T0 + 1000)
    eq("新代结果被接受", good.accepted, true)
    eq("新代结局 OK", good.outcome, Outcome.OK)
    eq("新会话有条目", ctrl.snapshot()!!.aggregate.clusters.size, 1)

    // 未知源
    val bad = ctrl.offerResult(2L, "ghost", listOf(book("x")), T0 + 1100)
    eq("未知源被拒", bad.reject, SearchController.Reject.UNKNOWN_SOURCE)

    // 无会话
    val emptyCtrl = SearchController()
    eq("无会话时快照为空", emptyCtrl.snapshot(), null)
    eq("无会话时推进返回 0", emptyCtrl.sweep(T0), 0)
    eq(
        "无会话时提供结果被拒",
        emptyCtrl.offerResult(1L, "a", emptyList(), T0).reject,
        SearchController.Reject.NO_SESSION,
    )

    // 代次不符的失败回调也要拒
    eq("旧代失败被拒", ctrl.offerFailure(1L, "a", "err", T0 + 2000).reject, SearchController.Reject.STALE_EPOCH)

    // ================= 十三、默认配置边界 =================
    val defCfg = SearchSession.Config()
    eq("默认单源 8s", defCfg.perSourceTimeoutMs, 8_000L)
    eq("默认整页 20s", defCfg.globalTimeoutMs, 20_000L)
    eq("默认条数上限 200", defCfg.maxItemsPerSource, 200)
    check("整页预算大于单源预算", defCfg.globalTimeoutMs > defCfg.perSourceTimeoutMs)

    // 恰好等于单源预算不算超时（边界）
    val s9 = SearchSession("b", 1, seeds("a" to "A"), T0)
    eq("恰在预算点仍可采信", s9.onResult("a", listOf(book("t", origin = "a")), T0 + 8_000), Outcome.OK)
    // 超 1ms 即丢弃
    val s10 = SearchSession("b", 1, seeds("a" to "A"), T0)
    eq("超预算 1ms 丢弃", s10.onResult("a", listOf(book("t", origin = "a")), T0 + 8_001), Outcome.DROPPED)

    // ================= 十四、空源列表 =================
    val none = SearchSession("空", 1, emptyList(), T0).snapshot()
    eq("无源时直接 DONE", none.phase, Phase.DONE)
    eq("无源不转圈", none.showSpinner, false)
    eq("无源不致命", none.fatal, false)
    eq("无源总结", none.aggregate.summary(), "没有可搜索的源")

    // ================= 十五、耗时统计 =================
    val s11 = SearchSession("t", 1, seeds("a" to "A"), T0)
    s11.onResult("a", listOf(book("k", origin = "a")), T0 + 250)
    eq("耗时记录", s11.snapshot().slots[0].elapsedMs, 250L)

    println("================ T12 搜索编排与多源聚合 单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
