import com.lanyue.reader.core.engine.ChannelStickyStore
import com.lanyue.reader.core.engine.CookieBridge
import com.lanyue.reader.core.engine.InMemoryStickyStore
import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.engine.NetworkException
import com.lanyue.reader.core.engine.RateLimit
import com.lanyue.reader.core.engine.SourceRateLimiter
import com.lanyue.reader.core.net.HttpChannel
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.net.HttpResponse
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import com.lanyue.reader.source.model.Category
import com.lanyue.reader.source.model.Filter
import com.lanyue.reader.source.model.FilterValue
import com.lanyue.reader.source.model.PlayKind
import com.lanyue.reader.source.model.PlayResult
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.SourceType
import com.lanyue.reader.source.model.VideoDetail
import com.lanyue.reader.source.model.VideoEpisode
import com.lanyue.reader.source.model.VideoItem
import com.lanyue.reader.source.model.VideoPlayLine
import com.lanyue.reader.source.video.CmsVideoSource
import com.lanyue.reader.source.video.TvBoxParser
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

private class R20FakeHttpChannel : HttpChannel {
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

private fun R20FakeHttpChannel.pushHtmlLike(body: String) {
    pushOk(body)
    pushOk(body)
}

private class R20NullCookies : CookieBridge {
    override fun read(url: String): String? = null
    override fun write(url: String, setCookie: String) {}
    override fun markInjected(url: String, cookie: String) {}
    override fun flush() {}
}

private var seq = 0
private fun metrics() = LocalMetrics(File("/tmp/opencode/metrics-cms-r20${seq++}"))
private fun newNet(http: R20FakeHttpChannel) = NetworkDispatcher(
    okHttp = http,
    cronet = null,
    webView = null,
    sticky = ChannelStickyStore(InMemoryStickyStore()),
    rateLimiter = SourceRateLimiter(),
    metrics = metrics(),
    cookieBridge = R20NullCookies()
)

private fun entity(
    configUrl: String = "http://cms.lanyue.test/video.txt",
    siteKey: String = "lanyue-cms",
    api: String? = "http://a/s",
    type: Int = 1,
    searchable: Boolean = true,
    quickSearch: Boolean = true,
    filterable: Boolean = false,
    ext: String? = null,
    jar: String? = null,
    timeout: Long = 0,
    enabled: Boolean = true,
    unsupported: Boolean = false
) = VideoSiteEntity(
    configUrl = configUrl,
    siteKey = siteKey,
    name = "测试影视源",
    type = type,
    api = api,
    searchable = searchable,
    quickSearch = quickSearch,
    filterable = filterable,
    ext = ext,
    jar = jar,
    timeout = timeout,
    enabled = enabled,
    unsupported = unsupported
)

private fun makeSrc(
    e: VideoSiteEntity,
    http: R20FakeHttpChannel,
    parses: List<TvBoxParser.ParseRule> = emptyList(),
    flags: List<String> = emptyList()
): CmsVideoSource = CmsVideoSource(
    entity = e,
    net = newNet(http),
    metrics = metrics(),
    parses = parses,
    flags = flags
)

fun main() {
    println("===== 影视源适配 CmsVideoSource（第七轮） =====")

    // ---------------- 身份与能力 ----------------
    val src = makeSrc(entity(), R20FakeHttpChannel())
    eq("源类型影视", src.sourceType, SourceType.VIDEO)
    eq("源 id 为 config#siteKey", src.sourceId, "http://cms.lanyue.test/video.txt#lanyue-cms")
    check("可搜索能力位", src.capabilityFlags.contains(SourceCapability.SEARCHABLE))
    val src2 = makeSrc(entity(filterable = true, searchable = false), R20FakeHttpChannel())
    check("筛选能力位", src2.capabilityFlags.contains(SourceCapability.FILTERABLE))
    check("不可搜索不挂位", !src2.capabilityFlags.contains(SourceCapability.SEARCHABLE))
    eq("并发限流恒空", src.concurrentRate, null)

    // ---------------- getCategories ----------------
    val listJson = """{"class":[
        {"type_id":"1","type_name":"电影","vod_story":"<p>剧情单</p>"},
        {"type_id":"2","type_name":"剧集"},
        {"type_id":"3","type_name":"综艺"},
        {"type_name":"无 id 段"}
    ]}"""
    val h1 = R20FakeHttpChannel(); h1.pushOk(listJson)
    val src1 = makeSrc(entity(), h1)
    val cats = runBlocking { src1.getCategories() }
    eq("分类过滤无 id", cats.size, 3)
    eq("分类数标题", cats[0].name, "电影")
    eq("分类数 id", cats[0].id, "1")
    check("分类 URL 带 ac=list", h1.lastReq?.url?.contains("ac=list") == true, "got ${h1.lastReq?.url}")
    eq("分类请求为 GET", h1.lastReq?.method, "GET")
    eq("分类请求携带源 id", h1.lastReq?.sourceId, "http://cms.lanyue.test/video.txt#lanyue-cms")
    eq("分类二标题", cats[1].name, "剧集")
    eq("分类三标题", cats[2].name, "综艺")

    val h2 = R20FakeHttpChannel(); h2.pushHtmlLike("""{"classQ":"?"}""")
    val src2b = makeSrc(entity(), h2)
    eq("分类缺失空", runBlocking { src2b.getCategories() }, emptyList<Category>())

    val h3 = R20FakeHttpChannel(); h3.pushHtmlLike("""{"class":null}""")
    val src3b = makeSrc(entity(), h3)
    eq("分类 null 空", runBlocking { src3b.getCategories() }, emptyList<Category>())

    val h4 = R20FakeHttpChannel(); h4.pushHtmlLike("""{"class":[{"type_id":"1"}]}""")
    val src4b = makeSrc(entity(), h4)
    eq("分类名缺省回落 id", runBlocking { src4b.getCategories() }, listOf(Category("1", "1")))

    // ---------------- explore ----------------
    val videoJson = """{"list":[
        {"vod_id":"1","vod_name":"电影一","vod_pic":"http://img/1.jpg","vod_remarks":"HD"},
        {"vod_id":"2","vod_name":"剧集二","vod_remarks":"2024"},
        {"vod_name":"无 id 段"},
        {"vod_id":"3","vod_name":"综艺三","vod_remarks":"4K"}
    ]}"""
    val h5 = R20FakeHttpChannel(); h5.pushHtmlLike(videoJson)
    val src5 = makeSrc(entity(), h5)
    val src5A = runBlocking { src5.explore(Category("电影", "1"), null, 2) }
    eq("列表过滤无 id", src5A.size, 3)
    eq("列表一标题", src5A[0].name, "电影一")
    eq("列表一 URL", src5A[0].url, "http://a/s?ac=detail&ids=1")
    check("列表 URL t/pg", h5.lastReq?.url?.contains("t=1") == true && h5.lastReq?.url?.contains("pg=2") == true, "got ${h5.lastReq?.url}")
    eq("列表一封面", src5A[0].cover, "http://img/1.jpg")
    eq("列表二备注", src5A[1].remark, "2024")

    val h6 = R20FakeHttpChannel(); h6.pushHtmlLike(videoJson)
    val src6 = makeSrc(entity(filterable = true), h6)
    val f = Filter("year", "年份", listOf(FilterValue("2024", "2024")))
    runBlocking { src6.explore(Category("电影", "1"), f, 1) }
    check("列表带筛选 f 参数", h6.lastReq?.url?.contains("f=year%3D2024") == true, "got ${h6.lastReq?.url}")

    val h7 = R20FakeHttpChannel(); h7.pushHtmlLike("""{"list":[]}""")
    val src7 = makeSrc(entity(), h7)
    eq("列表空列表", runBlocking { src7.explore(Category("电影", "1"), null, 1) }, emptyList<VideoItem>())

    // ---------------- search ----------------
    val h8 = R20FakeHttpChannel(); h8.pushHtmlLike(videoJson)
    val src8 = makeSrc(entity(), h8)
    val src8A = runBlocking { src8.search("a b", 2) }
    eq("搜索条目数", src8A.size, 3)
    check("搜索 URL wd 占位符", h8.lastReq?.url?.contains("wd=a+b") == true, "got ${h8.lastReq?.url}")
    check("搜索 URL pg", h8.lastReq?.url?.contains("pg=2") == true, "got ${h8.lastReq?.url}")

    val h9 = R20FakeHttpChannel(); h9.pushHtmlLike("""{"list":[{"vod_name":"无 id"},{"vod_id":"7","vod_name":"剧"}]}""")
    val src9 = makeSrc(entity(), h9)
    eq("搜索过滤无 id", runBlocking { src9.search("b", 2) }, listOf(VideoItem(
        "剧", "http://a/s?ac=detail&ids=7", null, null, "lanyue-cms", "http://cms.lanyue.test/video.txt")
    ))

    // ---------------- getDetail ----------------
    val D = "$"
    val detailJson = """{"list":[{
        "vod_name":"电影一",
        "vod_pic":"http://img/1.jpg",
        "vod_content":"<p>剧情简介</p>",
        "vod_actor":"张三",
        "vod_director":"李四",
        "vod_year":"2023",
        "vod_area":"美国",
        "vod_remarks":"HD",
        "vod_play_from":"源一${D}${D}${D}源二${D}${D}${D}源三",
        "vod_play_url":"第1${D}http://a/s/1.m3u8#第2${D}http://a/s/2.m3u8${D}${D}${D}第3${D}http://a/s/3.m3u8${D}${D}${D}1${D}http://a/s/4.m3u8"
    }]}"""
    val h10 = R20FakeHttpChannel(); h10.pushOk(detailJson)
    val src10 = makeSrc(entity(), h10)
    val movie = runBlocking { src10.getDetail("http://a/s?ac=detail&ids=1") }
    eq("详情名称", movie.name, "电影一")
    eq("详情封面", movie.cover, "http://img/1.jpg")
    eq("详情 简介去标签", movie.intro, "剧情简介")
    eq("详情演员", movie.actor, "张三")
    eq("详情导演", movie.director, "李四")
    eq("详情年份", movie.year, "2023")
    eq("详情地区", movie.area, "美国")
    eq("详情备注", movie.remark, "HD")
    eq("详情线路数", movie.lines.size, 3)
    eq("详情线路一选集数", movie.lines[0].episodes.size, 2)
    eq("详情选集名", movie.lines[0].episodes[0].name, "第1")
    eq("详情选集 url", movie.lines[0].episodes[0].url, "http://a/s/1.m3u8")
    eq("详情线路二选集数", movie.lines[1].episodes.size, 1)
    eq("详情线路三选集数", movie.lines[2].episodes.size, 1)

    val h11 = R20FakeHttpChannel(); h11.pushHtmlLike("""{"note":"无 list"}""")
    val src11 = makeSrc(entity(), h11)
    val m11 = runBlocking { src11.getDetail("http://a/s?ids=1") }
    eq("详情缺 list 名空串", m11.name, "")
    eq("详情缺 list 无线路", m11.lines.size, 0)

    val h12 = R20FakeHttpChannel(); h12.pushHtmlLike("""{"list":[{"vod_name":"标题"}]}""")
    val src12 = makeSrc(entity(), h12)
    val m12 = runBlocking { src12.getDetail("http://a/s?ids=1") }
    eq("详情字段名覆盖", m12.name, "标题")
    check("详情未配字段为 null", m12.cover == null && m12.intro == null)

    // ---------------- getPlay ----------------
    val die = VideoEpisode("第1集", "http://a/s/1.m3u8")
    val srcP = makeSrc(entity(), R20FakeHttpChannel())
    val prDirect = runBlocking { srcP.getPlay(die) }
    eq("直链 m3u8 返回", prDirect.kind, PlayKind.DIRECT)
    eq("直链 url 透传", prDirect.url, "http://a/s/1.m3u8")
    eq("直链无 flag", prDirect.flag, null)

    val directMp4 = VideoEpisode("第2集", "http://a/s/2.mp4")
    val prDirect2 = runBlocking { srcP.getPlay(directMp4) }
    eq("直链 mp4 返回", prDirect2.kind, PlayKind.DIRECT)

    val nonMedia = VideoEpisode("第3集", "http://a/s/detail?ac=detail&ids=3")

    // parse rtype 1 + flags 匹配 → PARSE 带 flag
    val srcP2 = makeSrc(entity(), R20FakeHttpChannel(), parses = listOf(TvBoxParser.ParseRule("p", 1, "http://parse", null)), flags = listOf("LANYUE"))
    val prParse = runBlocking { srcP2.getPlay(VideoEpisode("第4集", "LANYUE://cdn/x")) }
    eq("解析链返回", prParse.kind, PlayKind.PARSE)
    eq("解析链 flag", prParse.flag, "LANYUE")

    // flags 未命中 → PARSE 带 flag null
    val prParse2 = runBlocking { srcP2.getPlay(nonMedia) }
    eq("解析链无 flag", prParse2.kind, PlayKind.PARSE)
    eq("解析链 flag", prParse2.flag, null)

    // parse type 3 → SNIFF
    val srcP3 = makeSrc(entity(), R20FakeHttpChannel(), parses = listOf(TvBoxParser.ParseRule("p", 3, "http://parse", null)))
    val prSniff = runBlocking { srcP3.getPlay(nonMedia) }
    eq("解析链 type3 嗅探", prSniff.kind, PlayKind.SNIFF)
    eq("嗅探 flag", prSniff.flag, null)

    // 无解析链 → SNIFF
    val srcP4 = makeSrc(entity(), R20FakeHttpChannel())
    val prNoParse = runBlocking { srcP4.getPlay(nonMedia) }
    eq("无解析链嗅探", prNoParse.kind, PlayKind.SNIFF)
    eq("无解析链不发参数", prNoParse.url, nonMedia.url)
    eq("无解析链 flag 空", prNoParse.flag, null)

    cout("B:", "共 $pass 过 $fail 败")
    if (fail > 0) System.exit(1)
}

private fun cout(a: String, b: String) {
    println(a + b)
}
