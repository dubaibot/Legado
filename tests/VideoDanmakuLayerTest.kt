import com.lanyue.reader.data.db.entity.VideoApiEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import com.lanyue.reader.danmaku.render.DanmakuTimelineScheduler
import com.lanyue.reader.danmaku.render.DanmakuTimelineScheduler.Mode
import com.lanyue.reader.source.model.DanmakuEvent
import com.lanyue.reader.source.video.TvBoxParser
import com.lanyue.reader.source.video.TvBoxSiteInstaller

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

private fun site(
    key: String,
    name: String = "站点$key",
    api: String? = "https://api.test.com/$key",
    type: Int = 1,
    enabled: Boolean = true,
    searchable: Boolean = true,
    quickSearch: Boolean = true,
    jar: String? = null,
    ext: String? = null,
    timeout: Long = 0L,
    configUrl: String = "https://cfg.test.com/a.json"
) = VideoSiteEntity(
    configUrl = configUrl,
    siteKey = key,
    name = name,
    type = type,
    api = api,
    searchable = searchable,
    quickSearch = quickSearch,
    filterable = true,
    ext = ext,
    jar = jar,
    timeout = timeout,
    enabled = enabled,
    unsupported = false
)

private fun cfg(
    apiUrl: String = "https://cfg.test.com/a.json",
    sites: List<VideoSiteEntity>
): TvBoxParser.ParsedConfig = TvBoxParser.ParsedConfig(
    api = VideoApiEntity(url = apiUrl, name = "测试仓", lastUpdate = 1000L),
    sites = sites,
    lives = emptyList(),
    dropsJarSites = 0,
    parses = emptyList(),
    flags = emptyList()
)

private fun ev(t: Long, text: String, mode: Int = 1, src: String? = null) =
    DanmakuEvent(timeMs = t, mode = mode, color = 0xFFFFFF, text = text, source = src)

fun main() {
    // ================= 站点复合键 =================
    eq("复合键拼接",
        TvBoxSiteInstaller.siteIdentity("https://a.com/c.json", "k1"),
        "https://a.com/c.json\u0001k1")
    check("复合键可区分同 key 不同仓",
        TvBoxSiteInstaller.siteIdentity("https://a.com", "k1") !=
            TvBoxSiteInstaller.siteIdentity("https://b.com", "k1"))

    // ================= 类型归一 =================
    eq("合法类型 0 保持", TvBoxSiteInstaller.normalizeType(0, "https://a.com/api"), 0)
    eq("合法类型 1 保持", TvBoxSiteInstaller.normalizeType(1, "https://a.com/api"), 1)
    eq("合法类型 3 保持", TvBoxSiteInstaller.normalizeType(3, "https://a.com/s.js"), 3)
    eq("合法类型 4 保持", TvBoxSiteInstaller.normalizeType(4, "https://a.com/api"), 4)
    eq("越界+js 推断为 3", TvBoxSiteInstaller.normalizeType(9, "https://a.com/spider.js"), 3)
    eq("越界+py 推断为 3", TvBoxSiteInstaller.normalizeType(9, "https://a.com/spider.py"), 3)
    eq("越界+jar 推断为 3", TvBoxSiteInstaller.normalizeType(9, "https://a.com/spider.jar"), 3)
    eq("越界+普通 api 推断为 1", TvBoxSiteInstaller.normalizeType(9, "https://a.com/api.php"), 1)
    eq("负值+普通 api 推断为 1", TvBoxSiteInstaller.normalizeType(-1, "https://a.com/api"), 1)
    eq("null api 推断为 1", TvBoxSiteInstaller.normalizeType(99, null), 1)

    // ================= 单站点归一 =================
    val jarSite = TvBoxSiteInstaller.normalize(
        site("js1", type = 3, jar = "https://cdn.test.com/spider.jar")
    )
    eq("jar 站点标不支持", jarSite.unsupported, true)
    eq("jar 站点不可搜索", jarSite.searchable, false)

    val normalSite = TvBoxSiteInstaller.normalize(site("n1"))
    eq("普通站点支持", normalSite.unsupported, false)
    eq("普通站点可搜索", normalSite.searchable, true)

    val blankName = TvBoxSiteInstaller.normalize(site("k9", name = "  "))
    eq("空名回落到 key", blankName.name, "k9")

    val paddedKey = TvBoxSiteInstaller.normalize(site("  k7  "))
    eq("key 去空白", paddedKey.siteKey, "k7")

    val negTimeout = TvBoxSiteInstaller.normalize(site("k8", timeout = -5))
    eq("负超时归零", negTimeout.timeout, 0L)

    // ================= 安装：全部新增 =================
    val c1 = cfg("https://cfg.test.com/a.json", listOf(site("s1"), site("s2")))
    val (sites1, rep1) = TvBoxSiteInstaller.install(emptyList(), listOf(c1), now = 2000L)
    eq("全部新增条数", rep1.added.size, 2)
    eq("全部新增无更新", rep1.updated.size, 0)
    eq("结果站点数", sites1.size, 2)
    eq("报告非空", rep1.isEmpty, false)
    check("摘要含新增", rep1.summary().contains("新增"), rep1.summary())
    eq("接口地址记录", rep1.apis.size, 1)

    // ================= 安装：幂等（再导一次不变） =================
    val (sites2, rep2) = TvBoxSiteInstaller.install(sites1, listOf(c1), now = 3000L)
    eq("重复导入全部不变", rep2.unchanged.size, 2)
    eq("重复导入无新增", rep2.added.size, 0)
    eq("重复导入无更新", rep2.updated.size, 0)

    // ================= 安装：定义变更 -> 更新，但继承用户开关 =================
    val disabledFirst = sites1.map {
        if (it.siteKey == "s1") it.copy(enabled = false, quickSearch = false) else it
    }
    val c2 = cfg(
        "https://cfg.test.com/a.json",
        listOf(site("s1", name = "改名后的S1"), site("s2"))
    )
    val (sites3, rep3) = TvBoxSiteInstaller.install(disabledFirst, listOf(c2), now = 4000L)
    eq("变更触发更新", rep3.updated.size, 1)
    val s1After = sites3.first { it.siteKey == "s1" }
    eq("名称已更新", s1After.name, "改名后的S1")
    eq("用户停用状态被继承", s1After.enabled, false)
    eq("用户快搜偏好被继承", s1After.quickSearch, false)

    // ================= 安装：拒绝无 key / 无 api =================
    val badKey = cfg("https://cfg.test.com/b.json", listOf(site("  ", name = "无键")))
    val (_, repBadKey) = TvBoxSiteInstaller.install(emptyList(), listOf(badKey), now = 1L)
    eq("无 key 被拒", repBadKey.rejected.size, 1)
    check("拒绝原因含 key", repBadKey.rejected.first().reason.contains("key"),
        repBadKey.rejected.first().reason)

    val badApi = cfg(
        "https://cfg.test.com/c.json",
        listOf(site("noapi", api = null, jar = null))
    )
    val (_, repBadApi) = TvBoxSiteInstaller.install(emptyList(), listOf(badApi), now = 1L)
    eq("无 api 被拒", repBadApi.rejected.size, 1)
    check("拒绝原因含 api", repBadApi.rejected.first().reason.contains("api"),
        repBadApi.rejected.first().reason)

    // jar 站点即使 api 为空也应被接受（api 走 jar）
    val jarOnly = cfg(
        "https://cfg.test.com/d.json",
        listOf(site("jaronly", api = null, type = 3, jar = "https://cdn.test.com/s.jar"))
    )
    val (_, repJarOnly) = TvBoxSiteInstaller.install(emptyList(), listOf(jarOnly), now = 1L)
    eq("仅 jar 的站点被接受", repJarOnly.added.size, 1)
    eq("并计入不支持", repJarOnly.unsupported >= 1, true)

    // ================= 安装：跨仓同 key 不冲突 =================
    val ca = cfg("https://cfg.test.com/a.json", listOf(site("same", configUrl = "https://cfg.test.com/a.json")))
    val cb = cfg("https://cfg.test.com/b.json", listOf(site("same", configUrl = "https://cfg.test.com/b.json")))
    val (sitesAB, repAB) = TvBoxSiteInstaller.install(emptyList(), listOf(ca, cb), now = 1L)
    eq("跨仓同 key 形成两条", sitesAB.size, 2)
    eq("跨仓同 key 均新增", repAB.added.size, 2)
    eq("接口地址两种", repAB.apis.size, 2)

    // ================= 安装：本次导入内部去重 =================
    val dupIn = cfg(
        "https://cfg.test.com/e.json",
        listOf(site("dup", name = "第一版"), site("dup", name = "第二版"))
    )
    val (sitesDup, repDup) = TvBoxSiteInstaller.install(emptyList(), listOf(dupIn), now = 1L)
    eq("内部重复只留一条", sitesDup.size, 1)
    eq("重复计数", repDup.duplicates, 1)
    eq("后者生效", sitesDup.first().name, "第二版")

    // ================= 概览 / 筛选 / 分组 =================
    val mixed = listOf(
        site("m1", enabled = true, searchable = true),
        site("m2", enabled = false, searchable = true),
        site("m3", enabled = true, searchable = false),
        site("m4", type = 3, jar = "https://cdn.test.com/x.jar")
    ).map { TvBoxSiteInstaller.normalize(it) }
    val st = TvBoxSiteInstaller.stats(mixed)
    eq("统计总数", st.total, 4)
    eq("统计启用", st.enabled, 3)
    eq("统计不支持", st.unsupported, 1)
    check("可搜索不含 jar", TvBoxSiteInstaller.searchable(mixed).none { it.siteKey == "m4" },
        TvBoxSiteInstaller.searchable(mixed).map { it.siteKey }.toString())
    check("可搜索不含停用", TvBoxSiteInstaller.searchable(mixed).none { it.siteKey == "m2" })
    eq("可搜索只剩 m1", TvBoxSiteInstaller.searchable(mixed).size, 1)

    val grouped = TvBoxSiteInstaller.byApi(mixed)
    eq("分组数", grouped.size, 1)

    // 删整仓
    val afterRemove = TvBoxSiteInstaller.removeApi(mixed, "https://cfg.test.com/a.json")
    eq("删仓后清空", afterRemove.size, 0)
    val keepOther = TvBoxSiteInstaller.removeApi(mixed, "https://other.com/z.json")
    eq("删其它仓不影响", keepOther.size, 4)

    // 启停
    val toggled = TvBoxSiteInstaller.setEnabled(mixed, "https://cfg.test.com/a.json", "m1", false)
    eq("停用生效", toggled.first { it.siteKey == "m1" }.enabled, false)
    eq("其它不受影响", toggled.first { it.siteKey == "m3" }.enabled, true)

    // ================= 站点校验 =================
    eq("空 key 校验", TvBoxSiteInstaller.validate(site("   ")), "缺少站点标识")
    eq("jar 站点校验", TvBoxSiteInstaller.validate(jarSite), "jar 类型站点暂不支持")
    eq("空 api 校验", TvBoxSiteInstaller.validate(site("x", api = "")), "缺少 api 地址")
    eq("正常站点校验", TvBoxSiteInstaller.validate(normalSite), "可用")
    eq("相对路径可用",
        TvBoxSiteInstaller.validate(site("rel", api = "/api.php/provide/vod/")), "可用")
    eq("不可搜索提示",
        TvBoxSiteInstaller.validate(site("ns", searchable = false)), "未开启搜索")
    check("非法 api 被指出",
        TvBoxSiteInstaller.validate(site("bad", api = "not a url")).contains("无法识别"))

    // ================= 弹幕：模式判定 =================
    eq("mode 1 滚动", Mode.isScroll(1), true)
    eq("mode 2 滚动", Mode.isScroll(2), true)
    eq("mode 3 滚动", Mode.isScroll(3), true)
    eq("mode 5 非滚动", Mode.isScroll(5), false)
    eq("mode 5 顶部", Mode.isTop(5), true)
    eq("mode 4 底部", Mode.isBottom(4), true)
    eq("mode 4 非顶部", Mode.isTop(4), false)

    // ================= 弹幕：占宽估算 =================
    eq("纯 ASCII 宽度", DanmakuTimelineScheduler.measureUnits("abcd"), 4f)
    eq("纯中文宽度", DanmakuTimelineScheduler.measureUnits("中文"), 4f)
    eq("混排宽度", DanmakuTimelineScheduler.measureUnits("中a"), 3f)
    eq("空串宽度", DanmakuTimelineScheduler.measureUnits(""), 0f)

    // ================= 弹幕：基础调度 =================
    val basic = DanmakuTimelineScheduler.schedule(
        listOf(ev(1000, "第一条"), ev(1000, "第二条"), ev(2000, "第三条")),
        DanmakuTimelineScheduler.Config(scrollLanes = 10)
    )
    eq("基础全部显示", basic.total, 3)
    eq("基础无丢弃", basic.dropped, 0)
    check("轨道号在范围内", basic.scheduled.all { it.lane in 0 until 10 })
    check("进场时间等于事件时间", basic.scheduled.all { it.enterMs == it.timeMs })
    check("离场晚于进场", basic.scheduled.all { it.exitMs > it.enterMs })

    // ================= 弹幕：空文本丢弃 =================
    val withEmpty = DanmakuTimelineScheduler.schedule(
        listOf(ev(0, "好"), ev(100, ""), ev(200, "   "))
    )
    eq("空文本丢弃数", withEmpty.droppedEmpty, 2)
    eq("空文本后剩余", withEmpty.total, 1)

    // ================= 弹幕：去重 =================
    val dups = DanmakuTimelineScheduler.schedule(
        listOf(ev(1000, "重复"), ev(1050, "重复"), ev(1000, "重复"))
    )
    eq("同瞬间重复被合并", dups.total, 1)
    eq("去重计数", dups.deduped, 2)

    // 不同时间桶的相同文本不算重复
    val sameText = DanmakuTimelineScheduler.schedule(
        listOf(ev(1000, "哈哈"), ev(5000, "哈哈"))
    )
    eq("不同时间同文本都保留", sameText.total, 2)
    eq("不同时间无去重", sameText.deduped, 0)

    // ================= 弹幕：轨道不重叠（追尾检测） =================
    // 两条长弹幕同一时刻，轨道充足 -> 分到不同轨道
    val twoLanes = DanmakuTimelineScheduler.schedule(
        listOf(ev(0, "一二三四五六七八九十"), ev(0, "甲乙丙丁戊己庚辛壬癸")),
        DanmakuTimelineScheduler.Config(scrollLanes = 10)
    )
    val lanesUsed = twoLanes.scheduled.map { it.lane }.toSet()
    eq("同刻两条分到不同轨道", lanesUsed.size, 2)

    // 轨道全忙且不允许重叠 -> 丢弃
    val oneLane = DanmakuTimelineScheduler.schedule(
        listOf(ev(0, "很长很长很长很长很长"), ev(10, "很急很急")),
        DanmakuTimelineScheduler.Config(
            scrollLanes = 1, allowOverlap = false, laneGapMs = 250
        )
    )
    eq("单轨道只放一条", oneLane.total, 1)
    eq("另一条因轨道丢弃", oneLane.droppedByLanes, 1)

    // 轨道全忙但允许重叠 -> 降级显示并标记
    val overlap = DanmakuTimelineScheduler.schedule(
        listOf(ev(0, "很长很长很长很长很长"), ev(10, "很急很急")),
        DanmakuTimelineScheduler.Config(scrollLanes = 1, allowOverlap = true)
    )
    eq("允许重叠都显示", overlap.total, 2)
    check("第二条标记重叠", overlap.scheduled.any { it.overlapped })
    eq("允许重叠无轨道丢弃", overlap.droppedByLanes, 0)

    // 轨道释放后可复用：足够长的间隔应能进同一条轨道
    val reuse = DanmakuTimelineScheduler.schedule(
        listOf(ev(0, "短"), ev(20000, "后来")),
        DanmakuTimelineScheduler.Config(scrollLanes = 1, scrollDurationMs = 8000)
    )
    eq("间隔足够可复用轨道", reuse.total, 2)
    eq("复用同一轨道", reuse.scheduled.map { it.lane }.distinct().size, 1)
    eq("且均未重叠", reuse.scheduled.count { it.overlapped }, 0)

    // ================= 弹幕：密度上限 =================
    val dense = DanmakuTimelineScheduler.schedule(
        (0 until 30).map { ev(1000L, "第${it}条内容") },
        DanmakuTimelineScheduler.Config(scrollLanes = 30, maxPerSecond = 5)
    )
    eq("密度上限生效", dense.total, 5)
    eq("密度丢弃计数", dense.droppedByDensity, 25)

    // 跨秒不受影响
    val crossSec = DanmakuTimelineScheduler.schedule(
        listOf(ev(900, "A"), ev(2500, "B"), ev(3500, "C")),
        DanmakuTimelineScheduler.Config(maxPerSecond = 1)
    )
    eq("每秒各一条均保留", crossSec.total, 3)

    // ================= 弹幕：模式分流轨道 =================
    val mixedModes = DanmakuTimelineScheduler.schedule(
        listOf(
            ev(0, "滚动弹幕", mode = 1),
            ev(0, "顶部弹幕", mode = 5),
            ev(0, "底部弹幕", mode = 4)
        ),
        DanmakuTimelineScheduler.Config(scrollLanes = 5, topLanes = 2, bottomLanes = 2)
    )
    eq("三种模式都显示", mixedModes.total, 3)
    val top = mixedModes.scheduled.first { it.event.mode == 5 }
    val bottom = mixedModes.scheduled.first { it.event.mode == 4 }
    val scroll = mixedModes.scheduled.first { it.event.mode == 1 }
    check("顶部轨道号在 top 范围内", top.lane in 0 until 2, top.lane.toString())
    check("底部轨道号在 bottom 范围内", bottom.lane in 0 until 2, bottom.lane.toString())
    check("滚动轨道号在 scroll 范围内", scroll.lane in 0 until 5, scroll.lane.toString())
    eq("固定弹幕时长较短", top.durationMs, DanmakuTimelineScheduler.Config().fixedDurationMs)
    eq("滚动弹幕时长", scroll.durationMs, DanmakuTimelineScheduler.Config().scrollDurationMs)

    // topLanes = 0 时顶部弹幕被丢弃
    val noTop = DanmakuTimelineScheduler.schedule(
        listOf(ev(0, "顶部弹幕", mode = 5)),
        DanmakuTimelineScheduler.Config(topLanes = 0)
    )
    eq("无顶部轨道则丢弃", noTop.total, 0)
    eq("计入轨道丢弃", noTop.droppedByLanes, 1)

    // 未知模式按滚动处理
    val unknownMode = DanmakuTimelineScheduler.schedule(listOf(ev(0, "怪模式", mode = 99)))
    eq("未知模式仍显示", unknownMode.total, 1)

    // ================= 弹幕：时间排序 =================
    val unordered = DanmakuTimelineScheduler.schedule(
        listOf(ev(5000, "第三"), ev(1000, "第一"), ev(3000, "第二"))
    )
    val times = unordered.scheduled.map { it.timeMs }
    eq("调度结果按时间升序", times, times.sorted())

    // ================= 弹幕：空输入 =================
    val emptyRes = DanmakuTimelineScheduler.schedule(emptyList())
    eq("空输入无结果", emptyRes.total, 0)
    eq("空输入无丢弃", emptyRes.dropped, 0)
    check("空摘要可读", emptyRes.summary().contains("显示 0 条"))

    // ================= 弹幕：在屏查询 =================
    val screen = DanmakuTimelineScheduler.schedule(
        listOf(ev(0, "早"), ev(5000, "中"), ev(12000, "晚")),
        DanmakuTimelineScheduler.Config(scrollDurationMs = 8000)
    )
    eq("t=1000 只显示早", DanmakuTimelineScheduler.activeAt(screen, 1000).size, 1)
    eq("t=6000 早未离场故显示两条", DanmakuTimelineScheduler.activeAt(screen, 6000).size, 2)
    eq("t=13000 显示晚", DanmakuTimelineScheduler.activeAt(screen, 13000).size, 1)
    eq("t=20000 全部离场", DanmakuTimelineScheduler.activeAt(screen, 20000).size, 0)

    // ================= 弹幕：窗口切片 =================
    val all = listOf(ev(0, "a"), ev(3000, "b"), ev(9000, "c"))
    eq("切片前半", DanmakuTimelineScheduler.slice(all, 0, 5000).size, 2)
    eq("切片后半", DanmakuTimelineScheduler.slice(all, 5000, 10000).size, 1)
    eq("切片空窗", DanmakuTimelineScheduler.slice(all, 10000, 20000).size, 0)

    // ================= 弹幕：分段 =================
    val segs = DanmakuTimelineScheduler.segment(10000, 3000)
    eq("分段数", segs.size, 4)
    eq("首段起点", segs.first().first, 0L)
    eq("末段终点", segs.last().last, 9999L)
    check("分段连续", segs.zipWithNext().all { (a, b) -> b.first == a.last + 1 })
    eq("零时长无分段", DanmakuTimelineScheduler.segment(0, 1000).size, 0)
    eq("零段长无分段", DanmakuTimelineScheduler.segment(1000, 0).size, 0)

    // ================= Config 边界 =================
    try {
        DanmakuTimelineScheduler.Config(scrollLanes = 0)
        fail++; failures.add("0 轨道应被拒绝")
    } catch (e: IllegalArgumentException) { pass++ }
    try {
        DanmakuTimelineScheduler.Config(scrollDurationMs = 0)
        fail++; failures.add("0 时长应被拒绝")
    } catch (e: IllegalArgumentException) { pass++ }
    try {
        DanmakuTimelineScheduler.Config(maxPerSecond = 0)
        fail++; failures.add("0 密度应被拒绝")
    } catch (e: IllegalArgumentException) { pass++ }
    eq("默认配置轨道数", DanmakuTimelineScheduler.Config().scrollLanes, 10)

    println("================ R8 影视站点/弹幕调度 单测 ================")
    println("PASS = $pass   FAIL = $fail")
    java.io.File("/mnt/work/out/failures.txt").writeText(
        if (failures.isEmpty()) "NONE" else failures.joinToString("\n"), Charsets.UTF_8)
    if (fail > 0) kotlin.system.exitProcess(1)
}
