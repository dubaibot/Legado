import com.lanyue.reader.core.engine.ChannelStickyStore
import com.lanyue.reader.core.engine.CookieBridge
import com.lanyue.reader.core.engine.InMemoryStickyStore
import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.engine.RateLimit
import com.lanyue.reader.core.engine.SourceRateLimiter
import com.lanyue.reader.core.net.HttpChannel
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.net.HttpResponse
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import com.lanyue.reader.source.danmaku.DandanplaySource
import com.lanyue.reader.source.model.DanmakuMatch
import com.lanyue.reader.source.model.DanmakuMatchRequest
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.SourceType
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

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

private class DandanHttp : HttpChannel {
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

private class DmCookies : CookieBridge {
    override fun read(url: String): String? = null
    override fun write(url: String, setCookie: String) {}
    override fun markInjected(url: String, cookie: String) {}
    override fun flush() {}
}

private var seq = 0
private fun metrics() = LocalMetrics(File("/tmp/opencode/metrics-danmaku-r21${seq++}"))

private fun makeSrc(
    apiBase: String = "http://dm.lanyue.test",
    appId: String? = null,
    secret: String? = null
): Pair<DandanplaySource, DandanHttp> {
    val http = DandanHttp()
    val src = DandanplaySource(
        DanmakuSourceEntity(id = "dan-1", name = "弹弹play", apiBase = apiBase, appId = appId),
        makeNet(http),
        metrics(),
        { secret }
    )
    return Pair(src, http)
}

private fun makeNet(http: DandanHttp) = NetworkDispatcher(
    okHttp = http,
    cronet = null,
    webView = null,
    sticky = ChannelStickyStore(InMemoryStickyStore()),
    rateLimiter = SourceRateLimiter(),
    metrics = metrics(),
    cookieBridge = DmCookies()
)

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun bodyText(http: DandanHttp): String =
    http.lastReq?.body?.toString(Charsets.UTF_8) ?: ""

private fun main() {
    // ---------- 身份 ----------
    val src0 = makeSrc().first
    eq("弹幕源 sourceId", src0.sourceId, "dan-1")
    eq("弹幕源类型", src0.sourceType, SourceType.DANMAKU)
    eq("弹幕源能力", src0.capabilityFlags, setOf(SourceCapability.SEARCHABLE, SourceCapability.HAS_CONTENT, SourceCapability.HIGH_PRIORITY))
    eq("弹幕源限速", src0.concurrentRate, RateLimit(5, 1000))

    // ---------- match ----------
    runBlocking {
        val (src, http) = makeSrc(appId = "app-9", secret = "sec")
        http.pushOk("{\"isMatched\":true,\"matches\":[{\"episodeId\":11,\"animeTitle\":\"葬送之芙莉莲\",\"episodeTitle\":\"第1话\",\"shift\":0}],\"x\":\"<1\"}")
        val res = src.match(
            DanmakuMatchRequest(title = "葬送之芙莉莲", episode = 1, fileName = "ep01.mkv", fileHash = "hash-1", fileSize = 100L, durationMs = 2400L)
        )
        eq("match URL", http.lastReq?.url, "http://dm.lanyue.test/api/v2/match")
        eq("match 方法", http.lastReq?.method, "POST")
        val hdrs = http.lastReq?.headers ?: emptyMap()
        eq("match 头 X-AppId", hdrs["X-AppId"], "app-9")
        eq("match 头 Content-Type", hdrs["Content-Type"], "application/json")
        check("match 头 X-Timestamp 秒级", (hdrs["X-Timestamp"]?.length ?: 0) in 8..12, hdrs["X-Timestamp"].orEmpty())
        val ts = hdrs["X-Timestamp"] ?: ""
        eq("match 头 X-Signature", hdrs["X-Signature"], sha256Hex("app-9" + ts + "sec"))
        val bt = bodyText(http)
        check("match body fileName", bt.contains("ep01.mkv"), bt)
        check("match body fileHash", bt.contains("hash-1"), bt)
        check("match body fileSize", bt.contains("100"), bt)
        check("match body duration", bt.contains("2400"), bt)
        check("match body matchMode", bt.contains("hashAndFileName"), bt)
        check("match 命中", res != null)
        eq("match episodeId", res?.episodeId, 11L)
        eq("match animeTitle", res?.animeTitle, "葬送之芙莉莲")
        eq("match sourceId", res?.sourceId, "dan-1")

        // isMatched=false → null；fileName null 用 title；无 appId 无签名头
        val (src2, http2) = makeSrc()
        http2.pushOk("{\"isMatched\":false,\"matches\":[],\"x\":\"<1\"}")
        eq("match isMatched=false", src2.match(DanmakuMatchRequest(title = "x", episode = null)), null)
        val bt2 = bodyText(http2)
        check("match fileName 回退 title", bt2.contains("x"), bt2)
        check("match 无签名头", (http2.lastReq?.headers ?: emptyMap()).isEmpty(), http2.lastReq?.headers.orEmpty().toString())
        http2.pushOk("{\"x\":\"<1\"}")
        src2.match(DanmakuMatchRequest(title = "回退标题", episode = null))
        eq("match 回退 body", bodyText(http2).contains("回退标题"), true)

        // 坏响应
        val (srcB, httpB) = makeSrc()
        httpB.pushFail(500)
        eq("match HTTP 500 → null", srcB.match(DanmakuMatchRequest(title = "x", episode = null)), null)
        httpB.pushOk("not-a-json<1")
        eq("match 坏 JSON → null", srcB.match(DanmakuMatchRequest(title = "x", episode = null)), null)

        // 异常抛（模拟通道崩溃）
        val (srcE, httpE) = makeSrc()
        httpE.queue.add { throw IllegalStateException("boom") }
        eq("match 异常 → null", srcE.match(DanmakuMatchRequest(title = "x", episode = null)), null)
    }

    // ---------- searchEpisodes ----------
    runBlocking {
        val (src, http) = makeSrc()
        http.pushOk("{\"success\":true,\"animes\":[{\"animeTitle\":\"葬送之芙莉莲\",\"episodes\":[{\"episodeId\":11,\"episodeTitle\":\"第1话\",\"shift\":0}]},{\"animeTitle\":\"孤独摇滚\",\"episodes\":[{\"episodeId\":22,\"episodeTitle\":\"第1话\",\"shift\":5}]}],\"x\":\"<1\"}")
        val list = src.searchEpisodes("动漫", 3)
        eq("searchEpisodes URL 编码", http.lastReq?.url, "http://dm.lanyue.test/api/v2/search/episodes?anime=%E5%8A%A8%E6%BC%AB&episode=3")
        eq("searchEpisodes 解析数", list.size, 2)
        eq("searchEpisodes 集1 id", list[0].episodeId, 11L)
        eq("searchEpisodes 集1 animeTitle", list[0].animeTitle, "葬送之芙莉莲")
        eq("searchEpisodes 集1 title", list[0].episodeTitle, "第1话")
        eq("searchEpisodes 集2 shift", list[1].shift, 5L)
        eq("searchEpisodes origin", list[0].sourceId, "dan-1")

        http.pushOk("{\"success\":true,\"animes\":[],\"x\":\"<1\"}")
        eq("searchEpisodes 空 animes", src.searchEpisodes("empty", null).size, 0)
        eq("searchEpisodes 无 episode 参数", http.lastReq?.url, "http://dm.lanyue.test/api/v2/search/episodes?anime=empty")
        http.pushOk("broken<1")
        eq("searchEpisodes 坏 JSON", src.searchEpisodes("x", 1).size, 0)
        http.pushFail(404)
        eq("searchEpisodes 404", src.searchEpisodes("x", null).size, 0)
        val reqCount = http.count
        eq("search 空 key 返回空", src.search(""), emptyList<DanmakuMatch>())
        eq("search 空 key 不发请求", http.count, reqCount)
    }

    // ---------- getComments ----------
    runBlocking {
        val (src, http) = makeSrc()
        val match = DanmakuMatch(episodeId = 11L, animeTitle = "a", episodeTitle = "b", sourceId = "dan-1")
        http.pushOk("{\"comments\":[{\"p\":\"1.5,1,16777215,uid1\",\"m\":\"弹幕一\"},{\"p\":\"bad\",\"m\":\"坏p\"},{\"m\":\"缺p\"},{\"p\":\"2,2,255\",\"m\":\"两段p\"}],\"x\":\"<1\"}")
        val evts = src.getComments(match, 1200L, 3600L)
        eq("getComments URL", http.lastReq?.url, "http://dm.lanyue.test/api/v2/comment/11?withRelated=true&chConvert=1&from=1200&duration=3600")
        eq("弹幕条数（坏p/缺p 跳过）", evts.size, 2)
        eq("弹幕时间ms", evts[0].timeMs, 1500L)
        eq("弹幕模式1", evts[0].mode, 1)
        eq("弹幕颜色", evts[0].color, 16777215)
        eq("弹幕文本", evts[0].text, "弹幕一")
        eq("弹幕来源", evts[1].source, "dan-1")
        eq("弹幕模式2", evts[1].mode, 2)
        eq("缺m 弹幕跳过", evts.any { it.text == "缺p" }, false)
        http.pushOk("{\"comments\":[],\"x\":\"<1\"}")
        eq("弹幕空列表", src.getComments(match, 0, 0).size, 0)
        http.pushFail(500)
        eq("弹幕500", src.getComments(match, 0, 0).size, 0)
        http.pushOk("broken<1")
        eq("弹幕坏JSON", src.getComments(match, 0, 0).size, 0)
    }

    // ---------- pickBest ----------
    runBlocking {
        val src = makeSrc().first
        val j1 = JsonObject(); j1.addProperty("episodeId", 1); j1.addProperty("animeTitle", "葬送之芙莉莲"); j1.addProperty("episodeTitle", "第1话")
        val j2 = JsonObject(); j2.addProperty("episodeId", 2); j2.addProperty("animeTitle", "其他"); j2.addProperty("episodeTitle", "第2集")
        eq("pickBest 标题精确", src.pickBest(listOf(j1, j2), "葬送之芙莉莲", 1)?.episodeId, 1L)
        eq("pickBest 标题忽略大小写", src.pickBest(listOf(j1, j2), "葬送之芙莉莲", 1)?.episodeId, 1L)
        eq("pickBest 集数一致", src.pickBest(listOf(j1, j2), "别的", 2)?.episodeId, 2L)
        eq("pickBest 首个", src.pickBest(listOf(j1, j2), "别的", null)?.episodeId, 1L)
        eq("pickBest 空候选", src.pickBest(emptyList(), "x", null), null)
        val bad = JsonObject(); bad.addProperty("animeTitle", "无集数")
        eq("pickBest 无可解析", src.pickBest(listOf(bad), "x", null), null)
    }

    // ---------- probe ----------
    runBlocking {
        val (src, http) = makeSrc()
        http.pushOk("{\"animes\":[],\"x\":\"<1\"}")
        eq("probe 成功", src.probe(), true)
        eq("probe URL", http.lastReq?.url, "http://dm.lanyue.test/api/v2/search/episodes?anime=test")
        http.pushFail(500)
        eq("probe 500", src.probe(), false)
    }

    println("")
    println("弹幕源测试: $pass 通过, $fail 失败")
    if (fail > 0) kotlin.system.exitProcess(1)
}
