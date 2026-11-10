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
import com.lanyue.reader.core.engine.SourceRateLimiter
import com.lanyue.reader.core.net.HttpChannel
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.net.HttpResponse
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.source.book.LegadoBookSource
import com.lanyue.reader.source.model.ContentResult
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.model.SortRule
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.SourceType
import com.lanyue.reader.source.model.TocItem
import com.lanyue.reader.source.rule.RuleEngine
import com.lanyue.reader.source.rule.SortUrlParser
import kotlinx.coroutines.runBlocking
import java.io.File

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

private class FakeHttpChannel : HttpChannel {
    var count = 0
    var lastReq: HttpRequest? = null
    private val queue = ArrayDeque<() -> HttpResponse>()
    fun pushOk(body: String) { queue.add { HttpResponse(200, body, channel = "OKHTTP") } }
    fun pushFail(code: Int) { queue.add { HttpResponse(code, null, channel = "OKHTTP") } }
    override suspend fun execute(req: HttpRequest): HttpResponse {
        count++
        lastReq = req
        return queue.removeFirstOrNull()?.invoke()
            ?: throw AssertionError("no queued response")
    }
}

private class R19FakeJs : JsRuntime {
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

private class NullCookies : CookieBridge {
    override fun read(url: String): String? = null
    override fun write(url: String, setCookie: String) {}
    override fun markInjected(url: String, cookie: String) {}
    override fun flush() {}
}

private var seq = 0
private fun metrics() = LocalMetrics(File("/tmp/opencode/metrics-booksrc-r19${seq++}"))
private fun makeEngine(): RuleEngine {
    val m2 = metrics()
    return RuleEngine(R19FakeJs(), DomParser(null, m2), m2)
}
private fun newNet(http: FakeHttpChannel) = NetworkDispatcher(
    okHttp = http,
    cronet = null,
    webView = null,
    sticky = ChannelStickyStore(InMemoryStickyStore()),
    rateLimiter = SourceRateLimiter(),
    metrics = metrics(),
    cookieBridge = NullCookies()
)

private fun entity(
    url: String = "http://src.lanyue.test/B",
    name: String = "测试书源",
    type: Int = 0,
    searchUrl: String? = null,
    ruleSearch: String? = null,
    ruleToc: String? = null,
    ruleContent: String? = null,
    ruleBookInfo: String? = null,
    header: String? = null,
    rate: String? = null,
    exploreUrl: String? = null,
    ruleExplore: String? = null
) = BookSourceEntity(
    bookSourceUrl = url,
    bookSourceName = name,
    bookSourceType = type,
    searchUrl = searchUrl,
    ruleSearch = ruleSearch,
    ruleToc = ruleToc,
    ruleContent = ruleContent,
    ruleBookInfo = ruleBookInfo,
    header = header,
    concurrentRate = rate,
    exploreUrl = exploreUrl,
    ruleExplore = ruleExplore
)

private fun makeSrc(e: BookSourceEntity, http: FakeHttpChannel = FakeHttpChannel()): LegadoBookSource {
    return LegadoBookSource(
        entity = e,
        net = newNet(http),
        rules = makeEngine(),
        metrics = metrics()
    )
}

private val SEARCH_HTML = """
<html><body>
<div class="list">
  <ul>
    <li><a href="/book/1">剑起</a></li>
    <li><a href="/book/2">云深</a></li>
    <li>无链接条目</li>
  </ul>
</div>
</body></html>
""".trimIndent()

fun main() {
    println("===== 书源实现 LegadoBookSource =====")

    // ---------------- 身份与能力 ----------------
    val src = makeSrc(
        entity(searchUrl = "http://src.lanyue.test/search?q={{key}}", ruleSearch = "class.list@li@a"),
        FakeHttpChannel()
    )
    eq("源类型书源", src.sourceType, SourceType.BOOK)
    eq("源 id 为地址", src.sourceId, "http://src.lanyue.test/B")
    check("能力位含可搜索", src.capabilityFlags.contains(SourceCapability.SEARCHABLE))
    eq("并发限流2/1000", makeSrc(entity(rate = "2/1000"), FakeHttpChannel()).concurrentRate, RateLimit(2, 1000L))
    eq("限流逗号分隔", makeSrc(entity(rate = "1,500"), FakeHttpChannel()).concurrentRate, RateLimit(1, 500L))
    check("无并发限流 null", makeSrc(entity(rate = null), FakeHttpChannel()).concurrentRate == null)
    check("非法限流 null", makeSrc(entity(rate = "abc"), FakeHttpChannel()).concurrentRate == null)

    // ---------------- search ----------------
    val http1 = FakeHttpChannel()
    http1.pushOk(SEARCH_HTML)
    val src1 = makeSrc(
        entity(searchUrl = "http://src.lanyue.test/search?q={{key}}&pg={{page}}", ruleSearch = "class.list@li@a"),
        http1
    )
    val items = runBlocking { src1.search("a b", 2) }
    eq("搜索命中原文条目", items.size, 2)
    eq("第一条名", items[0].name, "剑起")
    eq("第一条链接补绝对地址", items[0].bookUrl, "http://src.lanyue.test/book/1")
    eq("来源回填", items[0].origin, "http://src.lanyue.test/B")
    eq("来源名回填", items[0].originName, "测试书源")
    check("URL key 占位符替换", http1.lastReq?.url?.contains("q=a+b") == true, "got ${http1.lastReq?.url}")
    check("URL page 占位符替换", http1.lastReq?.url?.contains("pg=2") == true, "got ${http1.lastReq?.url}")
    eq("请求为 GET", http1.lastReq?.method, "GET")
    eq("请求携带源 id", http1.lastReq?.sourceId, "http://src.lanyue.test/B")

    // ---------------- header 注入 ----------------
    val http2 = FakeHttpChannel()
    http2.pushOk("""<div class="list"><ul><li><a href="/b1">书一</a></li></ul></div>""")
    val src2 = makeSrc(
        entity(searchUrl = "http://a/s", ruleSearch = "class.list@li@a", header = "{\"Referer\":\"http://ref\",\"UA\":\"测试\"}"),
        http2
    )
    runBlocking { src2.search("x", 1) }
    eq("header 注入", http2.lastReq?.headers?.get("Referer"), "http://ref")
    eq("中文 header 值", http2.lastReq?.headers?.get("UA"), "测试")

    // ---------------- HTTP 失败 ----------------
    val http3 = FakeHttpChannel()
    http3.pushFail(500)
    val src3 = makeSrc(entity(searchUrl = "http://a/s", ruleSearch = "class.list@li@a"), http3)
    val ex3 = runBlocking { runCatching { src3.search("x", 1) } }.exceptionOrNull()
    check("HTTP 500 抛网络异常", ex3 is NetworkException, "got $ex3")

    // ---------------- 空态 ----------------
    val http4 = FakeHttpChannel()
    val src4 = makeSrc(entity(searchUrl = null, ruleSearch = "class.list@li@a"), http4)
    eq("无 searchUrl 零结果", runBlocking { src4.search("x", 1) }, emptyList<SearchBookItem>())
    eq("无 searchUrl 不发请求", http4.count, 0)

    val http5 = FakeHttpChannel()
        http5.pushOk(SEARCH_HTML)
    val src5 = makeSrc(entity(searchUrl = "http://a/s", ruleSearch = ""), http5)
    eq("空规则零结果", runBlocking { src5.search("x", 1) }, emptyList<SearchBookItem>())
    eq("空规则也发请求", http5.count, 1)

    // ---------------- ruleSearch 嵌套 JSON ----------------
    val http6 = FakeHttpChannel()
    http6.pushOk(SEARCH_HTML)
    val src6 = makeSrc(entity(searchUrl = "http://a/s", ruleSearch = """{"bookList":"class.list@li@a"}"""), http6)
    eq("嵌套 bookList 生效", runBlocking { src6.search("k", 1) }.size, 2)

    // ---------------- explore ----------------
    val http7 = FakeHttpChannel()
    http7.pushOk(SEARCH_HTML)
    http7.pushOk(SEARCH_HTML)
    val src7 = makeSrc(entity(exploreUrl = "http://a/e", ruleExplore = "class.list@li@a"), http7)
    val exSort = SortRule("排行", "http://a/e?pg={{page}}")
    runBlocking { src7.explore(exSort, 3) }
    check("explore URL 占位符替换", http7.lastReq?.url?.contains("pg=3") == true, "got ${http7.lastReq?.url}")
    eq("explore 条目数", runBlocking { src7.explore(exSort, 1) }.size, 2)

    // ---------------- getDetail ----------------
    val detailHtml = """<html><body><div class="info"><h1>剑起</h1><span>无名客</span></div></body></html>""".trimIndent()
    val http8 = FakeHttpChannel()
    http8.pushOk(detailHtml)
    val src8 = makeSrc(entity(ruleBookInfo = """{"name":"class.info@h1@text","author":"class.info@span@text"}"""), http8)
    val d = runBlocking { src8.getDetail("http://src.lanyue.test/B/book/1") }
    eq("详情书名", d.name, "剑起")
    eq("详情作者", d.author, "无名客")
    eq("详情 bookUrl 透传", d.bookUrl, "http://src.lanyue.test/B/book/1")
    check("未配字段为 null", d.cover == null && d.intro == null)

    // ---------------- getToc ----------------
    val tocHtml = """<html><body><div class="toc"><a href="/b/1">第一章</a><a href="/b/2">第二章</a><a href="/b/3">第三章</a></div></body></html>""".trimIndent()
    val http9 = FakeHttpChannel()
    http9.pushOk(tocHtml)
    val src9 = makeSrc(
        entity(ruleToc = """{"chapterList":"class.toc@a","chapterName":"text","chapterUrl":"attr:href"}"""),
        http9
    )
    val toc = runBlocking { src9.getToc("http://src.lanyue.test/B/book/1") }
    eq("目录三章", toc.size, 3)
    eq("第一章节名", toc[0].title, "第一章")
    eq("第章节址", toc[1].url, "/b/2")
    eq("章节索引递增", toc[2].index, 2)

    val http10 = FakeHttpChannel()
    http10.pushOk(tocHtml)
    val src10 = makeSrc(entity(ruleToc = """{"chapterList":"class.toc@a"}"""), http10)
    val toc10 = runBlocking { src10.getToc("http://src/b/1") }
    eq("缺 chapterName 默认 text", toc10[0].title, "第一章")
    eq("缺 chapterUrl 默认 href", toc10[0].url, "/b/1")

    val http11 = FakeHttpChannel()
    http11.pushOk(tocHtml)
    val src11 = makeSrc(entity(ruleToc = """{"chapterName":"text"}"""), http11)
    eq("缺 chapterList 空目录", runBlocking { src11.getToc("http://a") }, emptyList<TocItem>())
    eq("空目录先读正文", http11.count, 1)

    // ---------------- getContent ----------------
    val http12 = FakeHttpChannel()
    val src12 = makeSrc(entity(ruleContent = null), http12)
    val c12 = runBlocking { src12.getContent("http://src/b/1") }
    check("无正文规则走 WebView 兜底", c12 is ContentResult.WebViewFallback, "got $c12")
    eq("兜底 URL", (c12 as ContentResult.WebViewFallback).url, "http://src/b/1")
    eq("兜底不发请求", http12.count, 0)

    val http13 = FakeHttpChannel()
    http13.pushOk("<html><body>正文内容1</body></html>")
    val src13 = makeSrc(entity(ruleContent = "class.article"), http13)
    val c13 = runBlocking { src13.getContent("http://src/b/1") }
    check("正文 HTML 直返", c13 is ContentResult.Html && (c13 as ContentResult.Html).html.contains("正文内容"))

    val http14 = FakeHttpChannel()
    http14.pushOk("""<audio class="audio"><source src="http://cdn.lanyue.test/x.mp3"></audio>""")
    val src14 = makeSrc(entity(type = 1, ruleContent = "class.audio@source@attr:src"), http14)
    val c14 = runBlocking { src14.getContent("http://src/b/1") }
    check("音频源返回 Audio", c14 is ContentResult.Audio, "got $c14")
    eq("音频 URL", (c14 as ContentResult.Audio).url, "http://cdn.lanyue.test/x.mp3")

    val http15 = FakeHttpChannel()
    http15.pushOk("""<div class="j">{"url":"http://cdn.lanyue.test/y.mp3"}</div>""")
    val src15 = makeSrc(entity(type = 1, ruleContent = "class.j@text"), http15)
    val c15 = runBlocking { src15.getContent("http://src/b/1") }
    check("音频 JSON 形态", c15 is ContentResult.Audio && (c15 as ContentResult.Audio).url == "http://cdn.lanyue.test/y.mp3")

    val http16 = FakeHttpChannel()
    http16.pushOk("""<audio class="a"><source src='http://cdn/b.mp3,{"Referer":"http://c.com"}'></audio>""")
    val src16 = makeSrc(entity(type = 1, ruleContent = "class.a@source@attr:src"), http16)
    val c16 = runBlocking { src16.getContent("http://src/b/1") }
    check("音频 header JSON 形态", c16 is ContentResult.Audio, "got $c16")
    eq("音频直链剥离", (c16 as ContentResult.Audio).url, "http://cdn/b.mp3")
    eq("防盗链 header 注入", (c16 as ContentResult.Audio).headers["Referer"], "http://c.com")

    // ---------------- 发现分类 getSorts ----------------
    // 静态 JSON 分类
    val srcCat = makeSrc(
        entity(ruleExplore = """{"BookList":"class.list@li@a","分类":{"玄幻":"/cat/xuanhuan","都市":{"分类名称":"都市","URL":"/cat/dushi"}}}""")
    )
    val catSorts = runBlocking { srcCat.getSorts() }
    eq("静态分类条数", catSorts.size, 2)
    eq("静态分类名", catSorts[0].name, "玄幻")
    eq("静态分类相对URL", catSorts[0].url, "/cat/xuanhuan")
    eq("静态分类对象URL", catSorts[1].url, "/cat/dushi")

    // 无 sortParser 时动态分类跳过，不把 @js 脚本当 URL 返回
    val srcDyn = runBlocking { makeSrc(entity(ruleExplore = """{"分类":{"玄幻":"@js:sorts"}}""")).getSorts() }
    eq("无parser动态分类跳过", srcDyn.size, 0)

    // 静态 name::url 行解析
    val srcLines = runBlocking {
        makeSrc(entity(ruleExplore = "玄幻::/x&&都市::/d")).getSorts()
    }
    eq("静态行分类条数", srcLines.size, 2)
    eq("静态行分类2", srcLines[1].name, "都市")

    // explore 相对 URL 拼接为绝对地址
    val httpE = FakeHttpChannel()
    httpE.pushOk(SEARCH_HTML)
    val srcE = makeSrc(
        entity(
            ruleExplore = """{"BookList":"class.list@li@a","分类":{"玄幻":"/cat/xuanhuan"}}""",
            exploreUrl = "http://src.lanyue.test/cat"
        ),
        httpE
    )
    runBlocking { srcE.explore(catSorts[0], 1) }
    eq("相对分类URL拼接", httpE.lastReq?.url, "http://src.lanyue.test/cat/xuanhuan")

    // ---------------- 真实 Legado 书源格式：小写键名 + 逐项规则 ----------------
    // 书源 JSON：{"bookList":".list li","name":"@a@text","bookUrl":"@a@href", "分类":{...}}
    // 逐项计算：name/bookUrl 是相对每一项元素（li）的规则
    val http20 = FakeHttpChannel()
    http20.pushOk(SEARCH_HTML)
    val src20 = makeSrc(
        entity(
            searchUrl = "http://a/s",
            ruleSearch = """{"bookList":".list li","name":"@a@text","bookUrl":"@a@href"}"""
        ),
        http20
    )
    val items20 = runBlocking { src20.search("k", 1) }
    eq("小写bookList取书名", items20.size, 2)
    eq("小写bookList书名1", items20[0].name, "剑起")
    eq("逐项bookUrl补域名", items20[0].bookUrl, "http://a/book/1")
    eq("逐项bookUrl链接2", items20[1].bookUrl, "http://a/book/2")

    // explore 小写键名 + 分类
    val http21 = FakeHttpChannel()
    http21.pushOk(SEARCH_HTML)
    val src21 = makeSrc(
        entity(
            exploreUrl = "http://src.lanyue.test",
            ruleExplore = """{"bookList":".list li","name":"@a@text","bookUrl":"@a@href","分类":{"玄幻":"/x"}}"""
        ),
        http21
    )
    val sorts21 = runBlocking { src21.getSorts() }
    eq("小写键名分类条数", sorts21.size, 1)
    eq("小写键名分类名", sorts21[0].name, "玄幻")
    val items21 = runBlocking { src21.explore(sorts21[0], 1) }
    eq("小写键名探索出书", items21.size, 2)
    eq("小写键名探索书名", items21[0].name, "剑起")
    eq("小写键名探索绝对链接", items21[0].bookUrl, "http://src.lanyue.test/book/1")

    // 大写 BookList 兼容
    val http22 = FakeHttpChannel()
    http22.pushOk(SEARCH_HTML)
    val src22 = makeSrc(
        entity(ruleExplore = """{"BookList":".list li","name":"@a@text","bookUrl":"@a@href"}"""),
        http22
    )
    val items22 = runBlocking { src22.explore(SortRule("全部", "http://src.lanyue.test"), 1) }
    eq("大写BookList兼容", items22.size, 2)

    // searchUrl 带 @js 且无 parser -> 返回空，不把脚本传给网络
    val srcJsSearch = makeSrc(entity(searchUrl = "@js:V", ruleSearch = "class.list@li@a"))
    eq("无parser动态搜索地址跳过", runBlocking { srcJsSearch.search("x", 1) }, emptyList<SearchBookItem>())

    // explore 传入 @js 分类（无 parser）-> 返回空
    val srcJsExplore = makeSrc(entity(ruleExplore = "BookList::class.list@li@a"))
    eq("动态分类URL无parser跳过", runBlocking { srcJsExplore.explore(SortRule("x", "@js:V"), 1) }, emptyList<SearchBookItem>())

    println()
    println("PASS = $pass   FAIL = $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
