import com.lanyue.reader.core.engine.ProductErrorCode
import com.lanyue.reader.core.engine.ProductException
import com.lanyue.reader.core.engine.RateLimit
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.source.SourceRouter
import com.lanyue.reader.source.model.BookDetail
import com.lanyue.reader.source.model.ContentResult
import com.lanyue.reader.source.model.DanmakuEvent
import com.lanyue.reader.source.model.DanmakuMatch
import com.lanyue.reader.source.model.DanmakuMatchRequest
import com.lanyue.reader.source.model.IDanmakuSource
import com.lanyue.reader.source.model.IBookSource
import com.lanyue.reader.source.model.ISubscriptionSource
import com.lanyue.reader.source.model.IVideoSource
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.SourceHealth
import com.lanyue.reader.source.model.SourceType
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.model.SortRule
import com.lanyue.reader.source.model.SubscriptionItem
import com.lanyue.reader.source.model.VideoEpisode
import com.lanyue.reader.source.model.VideoItem
import com.lanyue.reader.source.model.PlayKind
import com.lanyue.reader.source.model.PlayResult
import com.lanyue.reader.source.model.TocItem
import com.lanyue.reader.source.model.Filter
import com.lanyue.reader.source.model.Category
import com.lanyue.reader.source.model.VideoDetail
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private var pass = 0
private var fail = 0

private fun eq(label: String, actual: Any?, expected: Any?) {
    if (actual == expected) { pass++; println("[PASS] $label") }
    else { fail++; println("[FAIL] $label -> actual=$actual expected=$expected") }
}

private fun check(label: String, cond: Boolean, detail: String = "") {
    if (cond) { pass++; println("[PASS] $label") }
    else { fail++; println("[FAIL] $label -> $detail") }
}

private var TMP_SEQ = 0
private fun metricsDir(tag: String) = File("/tmp/opencode/metrics-r15-$tag-${TMP_SEQ++}")

fun main() = runBlocking {
    val h = SourceHealth(3)
    check("初始不隔离", !h.isIsolated(SourceType.BOOK, "s1"))
    eq("零跟踪", h.trackedCount(), 0)
    eq("零隔离", h.isolatedCount(), 0)

    check("第1次未隔离", h.markFail(SourceType.BOOK, "s1") == 1)
    eq("第2次累计", h.markFail(SourceType.BOOK, "s1"), 2)
    check("第2次未隔离", !h.isIsolated(SourceType.BOOK, "s1"))
    eq("第3次累计", h.markFail(SourceType.BOOK, "s1"), 3)
    check("达到阈值隔离", h.isIsolated(SourceType.BOOK, "s1"))
    eq("隔离计数", h.isolatedCount(), 1)
    eq("跟踪数", h.trackedCount(), 1)

    h.markSuccess(SourceType.BOOK, "s1")
    check("成功清零", !h.isIsolated(SourceType.BOOK, "s1"))
    eq("清零后跟踪", h.trackedCount(), 0)

    h.markFail(SourceType.BOOK, "dup")
    h.markFail(SourceType.BOOK, "dup")
    h.markFail(SourceType.BOOK, "dup")
    check("书源隔离", h.isIsolated(SourceType.BOOK, "dup"))
    check("同名影视不受影响", !h.isIsolated(SourceType.VIDEO, "dup"))
    h.markFail(SourceType.VIDEO, "dup")
    check("影视1次未隔离", !h.isIsolated(SourceType.VIDEO, "dup"))

    h.reset("dup")
    check("reset按id清全部类型", !h.isIsolated(SourceType.BOOK, "dup") && !h.isIsolated(SourceType.VIDEO, "dup"))
    eq("reset后跟踪", h.trackedCount(), 0)

    h.clear()
    eq("clear清空", h.trackedCount(), 0)
    var errThreshold: Throwable? = null
    try { SourceHealth(0) } catch (t: Throwable) { errThreshold = t }
    check("阈值0拒绝", errThreshold is IllegalArgumentException, "err=$errThreshold")

    val router = SourceRouter(LocalMetrics(metricsDir("router")))
    val okA = FakeBook("A")
    val badB = FakeBook("B", forceFail = true)
    val noSearch = FakeBook("C", searchable = false)

    val r1 = router.searchBooks(listOf(okA, badB, noSearch), "kw", 1, "t1")
    eq("按源顺序合并", r1.map { it.origin }, listOf("A"))
    eq("坏源调用一次", badB.searches, 1)
    eq("不可搜源排除", noSearch.searches, 0)
    check("通过一次合作源清零", !router.isIsolated(SourceType.BOOK, "B"))

    for (i in 1..3) router.searchBooks(listOf(badB, okA), "kw", 1, "t-$i")
    check("坏源3次达到阈值", router.isIsolated(SourceType.BOOK, "B"))
    eq("坏源总调用", badB.searches, 3)
    val r4 = router.searchBooks(listOf(badB, okA), "kw", 1, "t4")
    eq("隔离后不再调用坏源", r4.map { it.origin }, listOf("A"))
    eq("坏源调用停在3", badB.searches, 3)
    eq("好源调用", okA.searches, 5)

    router.resetIsolation("B")
    check("reset后不隔离", !router.isIsolated(SourceType.BOOK, "B"))
    eq("reset后隔离计数", router.isolatedCount(), 0)

    val dupBook = FakeBook("dup", forceFail = true)
    for (i in 1..3) router.searchBooks(listOf(dupBook), "k", 1, "t15-$i")
    check("同名书源隔离", router.isIsolated(SourceType.BOOK, "dup"))
    check("同名书源不污染影视", !router.isIsolated(SourceType.VIDEO, "dup"))

    val routerT = SourceRouter(LocalMetrics(metricsDir("timeout")), perSourceTimeoutMs = 60L)
    val slow = FakeBook("slow", latencyMs = 500)
    val fast = FakeBook("fast")
    val rt = routerT.searchBooks(listOf(slow, fast), "k", 1, "tt")
    eq("超时源不贡献结果", rt.map { it.origin }, listOf("fast"))
    for (i in 1..3) routerT.searchBooks(listOf(FakeBook("slow", latencyMs = 500)), "k", 1, "tt-$i")
    check("超时累积到阈值被隔离", routerT.isIsolated(SourceType.BOOK, "slow"))

    val routerC = SourceRouter(LocalMetrics(metricsDir("content")))
    val ac = FakeBook("audio", hasContent = false)
    check("无内容能力转WebView", routerC.getContent(ac, "http://u", "tc") is ContentResult.WebViewFallback)
    val hs = FakeBook("html")
    check("有内容能力返回Html", routerC.getContent(hs, "http://u", "tc") is ContentResult.Html)
    val badC = FakeBook("bad", forceFail = true)
    var thrownC: Throwable? = null
    try {
        routerC.getContent(badC, "http://u", "tc")
    } catch (t: Throwable) {
        thrownC = t
    }
    check(
        "正文解析失败抛PARSE_FAILED",
        thrownC is ProductException && thrownC.code == ProductErrorCode.PARSE_FAILED,
        "thrown=$thrownC"
    )

    val routerV = SourceRouter(LocalMetrics(metricsDir("video")))
    val vOk = FakeVideo("v1")
    val vBad = FakeVideo("v2", searchFail = true)
    val rv = routerV.searchVideos(listOf(vOk, vBad), "k", 1, "tv")
    eq("坏影视源被过滤", rv.map { it.siteKey }, listOf("v1"))
    eq("坏影视源调用一次", vBad.searches, 1)

    var playErr: Throwable? = null
    try {
        routerV.getPlay(FakeVideo("playbad", playFail = true), VideoEpisode("ep1", "http://inner"), "tp")
    } catch (t: Throwable) {
        playErr = t
    }
    check(
        "播放失败抛PARSE_FAILED",
        playErr is ProductException && playErr.code == ProductErrorCode.PARSE_FAILED,
        "err=$playErr"
    )
    val pr = routerV.getPlay(vOk, VideoEpisode("ep1", "http://u"), "tp")
    check("直链直通", pr.kind == PlayKind.DIRECT && pr.url == "http://u")

    routerV.searchVideos(listOf(vBad, vOk), "k", 1, "tv2")
    routerV.searchVideos(listOf(vBad, vOk), "k", 1, "tv3")
    check("影视失败隔离", routerV.isIsolated(SourceType.VIDEO, "v2"))

    val routerD = SourceRouter(LocalMetrics(metricsDir("danmaku")))
    val m1 = FakeDanmaku("m1")
    val m2 = FakeDanmaku("m2")
    val md = routerD.matchDanmaku(listOf(m1, m2), DANMAKU_REQ, "td")
    eq("弹幕首命中取第一个源", md.sourceId, "M-m1")
    eq("弹幕episodeId保持不变", md.episodeId, 42L)

    var dErr: Throwable? = null
    try {
        routerD.matchDanmaku(listOf(FakeDanmaku("allbad", allFail = true)), DANMAKU_REQ, "td2")
    } catch (t: Throwable) {
        dErr = t
    }
    check(
        "弹幕无命中抛NOT_MATCHED",
        dErr is ProductException && dErr.code == ProductErrorCode.DANMAKU_NOT_MATCHED,
        "err=$dErr"
    )

    val ev = routerD.loadDanmaku(m1, md, 0, 10_000L)
    check("弹幕加载返回事件", ev.size == 1 && ev[0].source == "m1")

    val routerS = SourceRouter(LocalMetrics(metricsDir("sub")))
    val sub1 = FakeSubscription("s1")
    val sub2 = FakeSubscription("s2")
    val rs = routerS.searchSubscriptions(listOf(sub1, sub2), "k", 1, "ts")
    eq("订阅搜索结果稳定", rs.map { it.origin }, listOf("s1", "s2"))
    eq("订阅不写隔离", routerS.isolatedCount(), 0)
    val rsFail = routerS.searchSubscriptions(listOf(FakeSubscription("sfail", searchFail = true)), "k", 1, "ts2")
    check("订阅源失败不隔离", routerS.isolatedCount() == 0 && rsFail.isEmpty())

    val routerG = SourceRouter(LocalMetrics(metricsDir("gate")), maxConcurrency = 2)
    val active = AtomicInteger(0)
    val peak = AtomicInteger(0)
    val gateSources = List(8) {
        FakeBook("g$it").also { b ->
            b.gateHook = {
                val now = active.incrementAndGet()
                if (peak.get() < now) peak.set(now)
                delay(20L)
                active.decrementAndGet()
            }
        }
    }
    routerG.searchBooks(gateSources, "k", 1, "tg")
    eq("并发闸门峰值", peak.get(), 2)
    eq("闸门下全部源执行", gateSources.sumOf { it.searches }, 8)

    println("===== 源调度层 SourceRouter 与健康隔离 =====")
    println("PASS = $pass   FAIL = $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}

private val DANMAKU_REQ = DanmakuMatchRequest(title = "T", episode = 1)

private class FakeBook(
    id: String,
    private val searchable: Boolean = true,
    private val hasContent: Boolean = true,
    private val forceFail: Boolean = false,
    private val latencyMs: Long = 0
) : IBookSource {
    override val sourceId = id
    override val sourceType = SourceType.BOOK
    override val concurrentRate: RateLimit? = null

    var searches = 0
    var gateHook: suspend () -> Unit = { }

    override val capabilityFlags: Set<SourceCapability> get() = buildSet {
        if (searchable) add(SourceCapability.SEARCHABLE)
        if (hasContent) add(SourceCapability.HAS_CONTENT)
    }

    override suspend fun search(key: String, page: Int): List<SearchBookItem> {
        searches++
        gateHook()
        if (latencyMs > 0) delay(latencyMs)
        if (forceFail) throw RuntimeException("down")
        return listOf(SearchBookItem("name-$sourceId", null, "u-$sourceId", null, null, null, sourceId, null))
    }

    override suspend fun explore(sort: SortRule, page: Int): List<SearchBookItem> = emptyList()
    override suspend fun getDetail(url: String): BookDetail = BookDetail("d", null, null, null, null, null, null, url)
    override suspend fun getToc(url: String): List<TocItem> = emptyList()
    override suspend fun getContent(url: String): ContentResult {
        if (forceFail) throw RuntimeException("parse")
        return ContentResult.Html("ok")
    }
}

private class FakeSubscription(
    id: String,
    private val searchFail: Boolean = false
) : ISubscriptionSource {
    override val sourceId = id
    override val sourceType = SourceType.SUBSCRIPTION
    override val concurrentRate: RateLimit? = null
    override val capabilityFlags: Set<SourceCapability> = setOf(SourceCapability.SEARCHABLE)

    override suspend fun getSorts(): List<SortRule> = listOf(SortRule("最新", "/n"))
    override suspend fun getArticles(sort: SortRule?, page: Int): List<SubscriptionItem> = emptyList()
    override suspend fun search(key: String, page: Int): List<SubscriptionItem> {
        if (searchFail) throw RuntimeException("down")
        return listOf(SubscriptionItem("s1", "http://x", null, null, null, null, sourceId))
    }
    override suspend fun getContent(url: String): ContentResult = ContentResult.Html("ok")
}

private class FakeVideo(
    id: String,
    private val searchFail: Boolean = false,
    private val playFail: Boolean = false
) : IVideoSource {
    override val sourceId = id
    override val sourceType = SourceType.VIDEO
    override val concurrentRate: RateLimit? = null
    override val capabilityFlags: Set<SourceCapability> = setOf(SourceCapability.SEARCHABLE)

    var searches = 0

    override suspend fun getCategories(): List<Category> = emptyList()
    override suspend fun explore(category: Category, filter: Filter?, page: Int): List<VideoItem> = emptyList()
    override suspend fun search(key: String, page: Int): List<VideoItem> {
        searches++
        if (searchFail) throw RuntimeException("down")
        return listOf(VideoItem("v-$sourceId", "http://u-$sourceId", null, null, sourceId, "/c"))
    }
    override suspend fun getDetail(url: String): VideoDetail = VideoDetail("v-$sourceId")
    override suspend fun getPlay(episode: VideoEpisode): PlayResult {
        if (playFail) throw RuntimeException("解析失败")
        return PlayResult(PlayKind.DIRECT, episode.url)
    }
}

private class FakeDanmaku(
    id: String,
    private val allFail: Boolean = false
) : IDanmakuSource {
    override val sourceId = id
    override val sourceType = SourceType.DANMAKU
    override val concurrentRate: RateLimit? = null
    override val capabilityFlags: Set<SourceCapability> = setOf(SourceCapability.SEARCHABLE)

    override suspend fun match(req: DanmakuMatchRequest): DanmakuMatch? {
        if (allFail) throw RuntimeException("fallback")
        return DanmakuMatch(42L, "anime", "ep1", sourceId = "M-$sourceId")
    }
    override suspend fun search(key: String): List<DanmakuMatch> = emptyList()
    override suspend fun getComments(match: DanmakuMatch, fromMs: Long, durationMs: Long): List<DanmakuEvent> =
        listOf(DanmakuEvent(1L, 1, 0xFFFFFF.toInt(), "hi", sourceId))
}
