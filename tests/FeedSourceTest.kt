import com.lanyue.reader.core.engine.ChannelStickyStore
import com.lanyue.reader.core.engine.CookieBridge
import com.lanyue.reader.core.engine.DomParser
import com.lanyue.reader.core.engine.InMemoryStickyStore
import com.lanyue.reader.core.engine.JsExecMode
import com.lanyue.reader.core.engine.JsRuntime
import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.engine.NetworkException
import com.lanyue.reader.core.engine.PoolTier
import com.lanyue.reader.core.engine.RateLimit
import com.lanyue.reader.core.engine.RssFallbackParser
import com.lanyue.reader.core.engine.SourceRateLimiter
import com.lanyue.reader.core.net.HttpChannel
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.net.HttpResponse
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.source.model.ContentResult
import com.lanyue.reader.source.model.RssContentType
import com.lanyue.reader.source.model.SortRule
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.SourceType
import com.lanyue.reader.source.model.SubscriptionItem
import com.lanyue.reader.source.rss.LegadoRssSource
import com.lanyue.reader.source.rule.RuleEngine
import com.lanyue.reader.source.rule.SortUrlParser
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat

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

private class FeedHttp : HttpChannel {
    var count = 0
    var lastReq: HttpRequest? = null
    val queue = ArrayDeque<() -> HttpResponse>()
    fun pushOk(body: String) { queue.add { HttpResponse(200, body, channel = "OKHTTP") } }
    fun pushOkLike(body: String) {
        queue.add { HttpResponse(200, body, channel = "OKHTTP") }
        queue.add { HttpResponse(200, body, channel = "OKHTTP") }
    }
    fun pushFail(code: Int) { queue.add { HttpResponse(code, null, channel = "OKHTTP") } }
    override suspend fun execute(req: HttpRequest): HttpResponse {
        count++
        lastReq = req
        return queue.removeFirstOrNull()?.invoke()
            ?: throw AssertionError("no queued response")
    }
}

private class FeedCookies : CookieBridge {
    override fun read(url: String): String? = null
    override fun write(url: String, setCookie: String) {}
    override fun markInjected(url: String, cookie: String) {}
    override fun flush() {}
}

private class FeedJs : JsRuntime {
    override suspend fun eval(
        script: String,
        mode: JsExecMode,
        tier: PoolTier,
        traceId: String,
        dispatch: (String, String) -> Boolean
    ): String = ""
    override fun onRenderProcessGone(error: Throwable) {}
    override suspend fun drainPending(): Int = 0
}

private var seq = 0
private fun metrics() = LocalMetrics(File("/tmp/opencode/metrics-feed-r21${seq++}"))

private fun makeNet(http: FeedHttp) = NetworkDispatcher(
    okHttp = http,
    cronet = null,
    webView = null,
    sticky = ChannelStickyStore(InMemoryStickyStore()),
    rateLimiter = SourceRateLimiter(),
    metrics = metrics(),
    cookieBridge = FeedCookies()
)

private class RssStub {
    val parser = RssFallbackParser()
}

private fun entity(
    sourceUrl: String = "http://rss.lanyue.test/feed",
    sortUrl: String? = null,
    ruleArticles: String? = null,
    ruleTitle: String? = null,
    rulePubDate: String? = null,
    ruleImage: String? = null,
    ruleLink: String? = null,
    ruleDescription: String? = null,
    ruleContent: String? = null,
    searchUrl: String? = null,
    searchDegraded: Boolean = false,
    header: String? = null,
    concurrentRate: String? = null,
    type: Int = 0
) = RssSourceEntity(
    sourceUrl = sourceUrl,
    sourceName = "蓝阅RSS",
    sortUrl = sortUrl,
    ruleArticles = ruleArticles,
    ruleTitle = ruleTitle,
    rulePubDate = rulePubDate,
    ruleImage = ruleImage,
    ruleLink = ruleLink,
    ruleDescription = ruleDescription,
    ruleContent = ruleContent,
    searchUrl = searchUrl,
    searchDegraded = searchDegraded,
    header = header,
    concurrentRate = concurrentRate,
    type = type
)

private fun makeSrc(e: RssSourceEntity, http: FeedHttp, stub: RssStub): LegadoRssSource {
    val m2 = metrics()
    val rules = RuleEngine(FeedJs(), DomParser(null, m2), m2)
    val sortParser = SortUrlParser(FeedJs(), m2)
    return LegadoRssSource(e, makeNet(http), rules, sortParser, stub.parser, m2)
}

private fun listHtml(): String {
    val sb = StringBuilder("<html><body><ul class=\"list\">")
    for (i in 1..40) {
        sb.append("<li><a class=\"item\" href=\"/feed/").append(i).append("\">新闻标题")
        sb.append(i).append("</a></li>")
    }
    sb.append("</ul></body></html>")
    return sb.toString()
}

private fun main() {
    // ---------- 身份与能力 ----------
    val e0 = entity()
    val src0 = makeSrc(e0, FeedHttp(), RssStub())
    eq("订阅源 sourceId = sourceUrl", src0.sourceId, e0.sourceUrl)
    eq("订阅源类型", src0.sourceType, SourceType.SUBSCRIPTION)
    eq("订阅源能力", src0.capabilityFlags, setOf(SourceCapability.RSS_FALLBACK))

    val eCap = entity(
        sortUrl = "a::http://a/1",
        ruleArticles = "class.list@li@a",
        ruleContent = "p@text",
        searchUrl = "http://s.lanyue.test/s",
    )
    eq("订阅源能力全开", makeSrc(eCap, FeedHttp(), RssStub()).capabilityFlags,
        setOf(SourceCapability.SEARCHABLE, SourceCapability.DISCOVERABLE, SourceCapability.HAS_CONTENT))

    val eDeg = entity(sortUrl = "a::http://a/1", searchUrl = "http://s.lanyue.test/s", searchDegraded = true)
    check("searchDegraded 去掉 SEARCHABLE", SourceCapability.SEARCHABLE !in makeSrc(eDeg, FeedHttp(), RssStub()).capabilityFlags)

    // ---------- 并发限速 ----------
    eq("并发限速", makeSrc(entity(concurrentRate = "5/1000"), FeedHttp(), RssStub()).concurrentRate, RateLimit(5, 1000))
    eq("并发限速缺省 null", makeSrc(entity(), FeedHttp(), RssStub()).concurrentRate, null)
    eq("并发限速坏值", makeSrc(entity(concurrentRate = "bad"), FeedHttp(), RssStub()).concurrentRate, null)

    // ---------- getSorts ----------
    runBlocking {
        val http = FeedHttp()
        val src = makeSrc(
            entity(sortUrl = "电影::http://rss.lanyue.test/m\n剧集::http://rss.lanyue.test/t&&动画::http://rss.lanyue.test/d"),
            http, RssStub()
        )
        val sorts = src.getSorts()
        eq("getSorts 数量", sorts.size, 3)
        eq("排序1", sorts[0], SortRule("电影", "http://rss.lanyue.test/m"))
        eq("排序2", sorts[1], SortRule("剧集", "http://rss.lanyue.test/t"))
        eq("排序3", sorts[2], SortRule("动画", "http://rss.lanyue.test/d"))

        // 坏行跳过、无效分隔
        val http2 = FeedHttp()
        val src2 = makeSrc(entity(sortUrl = "movie::u1\nbadline\n::\nu::u\nvn::"), http2, RssStub())
        val s2 = src2.getSorts()
        eq("getSorts 坏行跳过", s2, listOf(SortRule("movie", "u1"), SortRule("u", "u")))

        // 空 sortUrl
        eq("getSorts 无 sortUrl", makeSrc(entity(), FeedHttp(), RssStub()).getSorts(), emptyList<SortRule>())

        // 动态 @js: 失败降级（fakeJs 返回 "" → 失败）
        val http3 = FeedHttp()
        val src3 = makeSrc(entity(sortUrl = "@js:function(){return 'a::http://x'}"), http3, RssStub())
        eq("getSorts 动态失败降级", src3.getSorts(), emptyList<SortRule>())
        eq("getSorts 动态无请求", http3.count, 0)
    }

    // ---------- getArticles：兜底 ----------
    runBlocking {
        // 兜底 feed：2 条，其中 1 条无 url 过滤
        val stub = RssStub()
        stub.parser.feed = RssFallbackParser.RssFeed("蓝阅", listOf(
            RssFallbackParser.RssItem("标题A", "http://rss.lanyue.test/a", "2023-01-01 00:00:00", "摘要A", "http://cdn/a.png"),
            RssFallbackParser.RssItem("标题B", "", null, null, null)
        ))
        val http = FeedHttp()
        http.pushOkLike(listHtml())
        val src = makeSrc(entity(), http, stub)
        val items = src.getArticles(null, 1)
        eq("兜底 URL = sourceUrl", http.lastReq?.url, e0.sourceUrl)
        eq("兜底过滤空 url", items.size, 1)
        eq("兜底 title", items[0].title, "标题A")
        eq("兜底 url", items[0].url, "http://rss.lanyue.test/a")
        eq("兜底 description", items[0].description, "摘要A")
        eq("兜底 cover", items[0].cover, "http://cdn/a.png")
        eq("兜底 contentType", items[0].contentType, RssContentType.WEB)
        eq("兜底 sort  nil", items[0].sort, null)
        eq("兜底 origin", items[0].origin, "http://rss.lanyue.test/feed")
        val expectTs = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse("2023-01-01 00:00:00").time
        eq("兜底 pubDate 解析", items[0].pubDate, expectTs)

        // 全空 link → 过滤空
        val stub2 = RssStub()
        stub2.parser.feed = RssFallbackParser.RssFeed("x", listOf(RssFallbackParser.RssItem("无链接", "", "", "", "")))
        val http2 = FeedHttp()
        http2.pushOkLike("x")
        eq("兜底全空过滤", makeSrc(entity(), http2, stub2).getArticles(null, 1), emptyList<SubscriptionItem>())

        // stub 返回 null
        val http3 = FeedHttp()
        http3.pushOkLike("x")
        eq("兜底 null 解析", makeSrc(entity(), http3, RssStub()).getArticles(null, 1), emptyList<SubscriptionItem>())

        // contentType 按 type
        val stub4 = RssStub()
        stub4.parser.feed = RssFallbackParser.RssFeed("x", listOf(RssFallbackParser.RssItem("t", "http://l/a", null, null, null)))
        val http4 = FeedHttp()
        http4.pushOkLike("x")
        val img = makeSrc(entity(type = 1), http4, stub4).getArticles(null, 1)
        eq("type1=IMAGE", img[0].contentType, RssContentType.IMAGE)
        val http4b = FeedHttp()
        http4b.pushOkLike("x")
        val vid = makeSrc(entity(type = 2), http4b, stub4).getArticles(null, 1)
        eq("type2=VIDEO", vid[0].contentType, RssContentType.VIDEO)

        // sort 渲染分页
        val http5 = FeedHttp()
        http5.pushOkLike("x")
        val src5 = makeSrc(entity(), http5, stub4)
        src5.getArticles(SortRule("专题", "http://rss.lanyue.test/feed?page={{page}}"), 3)
        eq("getArticles 分页", http5.lastReq?.url, "http://rss.lanyue.test/feed?page=3")
    }

    // ---------- getArticles：规则解析 ----------
    runBlocking {
        val http = FeedHttp()
        http.pushOkLike(listHtml())
        val src = makeSrc(entity(ruleArticles = "class.list@li@a"), http, RssStub())
        val items = src.getArticles(SortRule("栏目", "http://rss.lanyue.test/c"), 1)
        eq("规则解析条数", items.size, 40)
        eq("规则解析 title", items[0].title, "新闻标题1")
        eq("规则解析 url", items[0].url, "/feed/1")
        eq("规则解析 sort", items[0].sort, "栏目")
        eq("规则解析 origin", items[0].origin, "http://rss.lanyue.test/feed")
        eq("规则解析 contentType", items[0].contentType, RssContentType.WEB)

        // 带结构规则字段
        val http2 = FeedHttp()
        val html2 = "<html><body><ul class=\"list\"><li><a class=\"item\" href=\"/f/1\" title=\"摘要1\">标题1</a></li><li><a class=\"item\">没有href</a></li></ul></body></html>"
        http2.pushOkLike(html2)
        val src2 = makeSrc(
            entity(
                ruleArticles = "class.list@li@a",
                ruleDescription = "attr:title"
            ),
            http2, RssStub()
        )
        val items2 = src2.getArticles(null, 1)
        eq("规则解析多字段条数（无href跳过）", items2.size, 1)
        eq("规则解析 desc", items2[0].description, "摘要1")
    }

    // ---------- search ----------
    runBlocking {
        // key 模板
        val http = FeedHttp()
        http.pushOkLike(listHtml())
        val src = makeSrc(entity(ruleArticles = "class.list@li@a", searchUrl = "http://s.lanyue.test/s?k={{key}}"), http, RssStub())
        val items = src.search("技术", 1)
        eq("search URL 编码", http.lastReq?.url, "http://s.lanyue.test/s?k=" + urlEncode("技术"))
        eq("search 结果复用列表规则", items.size, 40)
        eq("search origin", items[0].origin, "http://rss.lanyue.test/feed")

        // 空 searchUrl
        val http2 = FeedHttp()
        eq("解无 searchUrl", makeSrc(entity(ruleArticles = "class.list@li@a"), http2, RssStub()).search("x", 1), emptyList<SubscriptionItem>())

        // 兜底 search（无 ruleArticles）
        val stub = RssStub()
        stub.parser.feed = RssFallbackParser.RssFeed("s", listOf(RssFallbackParser.RssItem("t", "http://l/a", null, null, null)))
        val http3 = FeedHttp()
        http3.pushOkLike("x")
        eq(
            "search 兜底模式",
            makeSrc(entity(searchUrl = "http://s.lanyue.test/s?k={{key}}"), http3, stub).search("x", 1).size,
            1
        )

        // 失败
        val http4 = FeedHttp()
        http4.pushFail(500)
        var threw = false
        runCatching {
            makeSrc(entity(ruleArticles = "class.list@li@a", searchUrl = "http://s.lanyue.test/s?k={{key}}"), http4, RssStub())
                .search("x", 1)
        }
            .onFailure { threw = it is NetworkException }
        check("search 500 抛 NetworkException", threw)
    }

    // ---------- getContent ----------
    runBlocking {
        // 无 ruleContent → WebViewFallback
        val http = FeedHttp()
        val src = makeSrc(entity(), http, RssStub())
        val res = src.getContent("http://rss.lanyue.test/a")
        check("无 ruleContent 回退 WebView", res is ContentResult.WebViewFallback)
        eq("ruleContent 无请求", http.count, 0)

        // 非空规则解析 → Html
        val http2 = FeedHttp()
        http2.pushOkLike("<html><body><div class=\"article\"><p>正文内容</p></div></body></html>")
        val src2 = makeSrc(entity(ruleContent = "div.article@html"), http2, RssStub())
        val html = src2.getContent("http://rss.lanyue.test/a")
        check("ruleContent 返回 Html", html is ContentResult.Html)
        eq("ruleContent 正文", (html as ContentResult.Html).html.contains("正文内容"), true)

        // 规则结果空 → WebViewFallback
        val http3 = FeedHttp()
        http3.pushOkLike("<html><body><div>无匹配</div></body></html>")
        val src3 = makeSrc(entity(ruleContent = "div.article@html"), http3, RssStub())
        val web = src3.getContent("http://rss.lanyue.test/a")
        check("ruleContent 空结果回退", web is ContentResult.WebViewFallback)
    }

    // ---------- header 注入 ----------
    runBlocking {
        val http = FeedHttp()
        http.pushOkLike("x")
        val src = makeSrc(entity(header = "{\"X-Feed\":\"v1\",\"User-Agent\":\"FeedUA\"}"), http, RssStub())
        src.getArticles(null, 1)
        val hdrs = http.lastReq?.headers ?: emptyMap()
        eq("header 注入 X-Feed", hdrs["X-Feed"], "v1")
        eq("header 注入 UA", hdrs["User-Agent"], "FeedUA")

        val http2 = FeedHttp()
        http2.pushOkLike("x")
        val src2 = makeSrc(entity(header = "not-a-json"), http2, RssStub())
        src2.getArticles(null, 1)
        eq("header 坏 JSON 不崩溃", (http2.lastReq?.headers ?: emptyMap()).isEmpty(), true)
    }

    // ---------- 截断 ----------
    runBlocking {
        val builder = StringBuilder()
        for (i in 1..110) builder.append(" cat$i::http://rss.lanyue.test/$i\n")
        val http = FeedHttp()
        val src = makeSrc(entity(sortUrl = builder.toString()), http, RssStub())
        eq("getSorts 超过100截断", src.getSorts().size, 100)
    }

    println("")
    println("订阅源测试: $pass 通过, $fail 失败")
    if (fail > 0) kotlin.system.exitProcess(1)
}

private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

private val e0 = entity()
