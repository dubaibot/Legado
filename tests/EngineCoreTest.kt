import com.lanyue.reader.core.engine.ChannelStickyStore
import com.lanyue.reader.core.engine.CookieBridge
import com.lanyue.reader.core.engine.DomParser
import com.lanyue.reader.core.engine.GlobalConcurrencyGate
import com.lanyue.reader.core.engine.InMemoryStickyStore
import com.lanyue.reader.core.engine.JsExtensions
import com.lanyue.reader.core.engine.NetChannel
import com.lanyue.reader.core.engine.RateLimit
import com.lanyue.reader.core.engine.RealDomSession
import com.lanyue.reader.core.engine.Sniffer
import com.lanyue.reader.core.engine.SnifferRenderPool
import com.lanyue.reader.core.engine.SnifferSession
import com.lanyue.reader.core.engine.SourceRateLimiter
import com.lanyue.reader.core.engine.TokenBucket
import com.lanyue.reader.core.observability.LocalMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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

private class FakeDom(private val doc: Document?) : RealDomSession {
    override suspend fun queryDocument(html: String, baseUrl: String?, timeoutMs: Long): Document? = doc
    override suspend fun select(sessionId: String, selector: String): List<String> = emptyList()
    override suspend fun close(sessionId: String) {}
}

private fun main() = runBlocking {
    val metrics = LocalMetrics(File("/tmp/opencode/metrics-engine"))

    // ---------------- InMemoryStickyStore ----------------
    val mem = InMemoryStickyStore()
    eq("store 初始空", mem.keys().toList(), emptyList<String>())
    eq("store 缺失键", mem.get("nope"), null)
    mem.put("k1", "v1")
    eq("store 写入", mem.get("k1"), "v1")
    mem.remove("k1")
    eq("store remove", mem.get("k1"), null)
    mem.put("k1", "v1")
    mem.put("k2", "v2")
    eq("store keys 多键", mem.keys().toList(), listOf("k1", "k2"))

    // ---------------- ChannelStickyStore：无抽查不写持久层 ----------------
    var clock = 1_000_000L
    val ck = ChannelStickyStore(mem, now = { clock })
    mem.keys().toList().forEach { mem.remove(it) }
    val host = "m.share.com"
    val sid = "src-a"
    val k1 = ck.stickyKey(host, sid)
    check("stickyKey 含主机", k1.startsWith("m.share.com#"))
    eq("stickyKey 后缀16位", k1.substringAfter("#").length, 16)
    eq("stickyKey 稳定", ck.stickyKey(host, sid), k1)
    check("不同源键不同", ck.stickyKey(host, "src-b") != k1)
    eq("channelFor 空", ck.channelFor(host, sid), null)

    // 错格式写入：脏值不得污染读取路径
    mem.put(k1, "garbage")
    eq("格式错误 null", ck.channelFor(host, sid), null)
    mem.keys().toList().forEach { mem.remove(it) }

    // 连续 2 次命中才触发
    eq("命中1次不切", ck.recordFingerprintHit(host, sid, NetChannel.CRONET), false)
    eq("命中2次才切", ck.recordFingerprintHit(host, sid, NetChannel.CRONET), true)
    eq("命中后可读通道", ck.channelFor(host, sid), NetChannel.CRONET)
    ck.resetHitCount(host, sid)
    eq("清计数后重来", ck.recordFingerprintHit(host, sid, NetChannel.CRONET), false)
    ck.recordFingerprintHit(host, sid, NetChannel.CRONET)
    eq("再次绑定", ck.channelFor(host, sid), NetChannel.CRONET)

    // TTL 7 天过期
    clock = 1_000_000L + 7 * 24 * 60 * 60 * 1000 + 1
    eq("7天过期 null", ck.channelFor(host, sid), null)

    // clearForSource 清理本源全部键
    val digest = k1.substringAfter("#")
    clock = 2_000_000L
    eq("h1第1次", ck.recordFingerprintHit("h1.a.com", sid, NetChannel.OKHTTP), false)
    ck.recordFingerprintHit("h1.a.com", sid, NetChannel.OKHTTP)
    eq("h2第1次", ck.recordFingerprintHit("h2.a.com", sid, NetChannel.CRONET), false)
    ck.recordFingerprintHit("h2.a.com", sid, NetChannel.CRONET)
    check("两主机键已写入", mem.keys().any { it.endsWith("#$digest") })
    ck.clearForSource(sid)
    eq("clearForSource 清空", mem.keys().isEmpty(), true)

    // onSourceEdited 只有变化才清理
    val k2 = ck.stickyKey("h3.a.com", sid)
    ck.recordFingerprintHit("h3.a.com", sid, NetChannel.OKHTTP)
    ck.recordFingerprintHit("h3.a.com", sid, NetChannel.OKHTTP)
    ck.onSourceEdited("h3.a.com", sid, baseUrlChanged = false, uaChanged = false)
    check("无变化保留", k2 in mem.keys())
    ck.onSourceEdited("h3.a.com", sid, baseUrlChanged = true, uaChanged = false)
    check("baseUrl 变化清理", k2 !in mem.keys())

    // ---------------- TokenBucket ----------------
    val tb = TokenBucket(2, 1000L)
    eq("桶初始可进", tb.tryAcquire(), true)
    eq("桶二次可进", tb.tryAcquire(), true)
    eq("桶三次拒绝", tb.tryAcquire(), false)
    check("等待期非零", tb.nextWaitMs() > 0L)

    val tb2 = TokenBucket(1, 10L)
    eq("小桶可取", tb2.tryAcquire(), true)
    eq("小桶取尽拒绝", tb2.tryAcquire(), false)
    delay(30L)
    eq("小桶补充后可取", tb2.tryAcquire(), true)
    delay(15L)
    eq("小桶补充后下主机可取", tb2.tryAcquire(), true)
    check("消耗后等待非零", tb2.nextWaitMs() > 0L)
    eq("消耗后不可取", tb2.tryAcquire(), false)
    delay(30L)
    eq("小桶再补充", tb2.tryAcquire(), true)

    // ---------------- SourceRateLimiter ----------------
    val sl = SourceRateLimiter()
    repeat(10) { i ->
        eq("源1第${i + 1}次", sl.tryAcquire("src-1", null), true)
    }
    eq("源1第11次拒绝", sl.tryAcquire("src-1", null), false)
    eq("源2独立桶", sl.tryAcquire("src-2", null), true)
    eq("自定义 limit", sl.tryAcquire("src-3", RateLimit(1, 1000L)), true)
    eq("自定义 limit 第2次拒绝", sl.tryAcquire("src-3", RateLimit(1, 1000L)), false)
    val decoded = runBlocking { sl.serialized("src-login") { "ok" } }
    eq("serial lock 返回值", decoded, "ok")
    sl.clear("src-1")
    eq("clear 后重置", sl.tryAcquire("src-1", null), true)

    // ---------------- GlobalConcurrencyGate ----------------
    val gate = GlobalConcurrencyGate(3)
    val counter = java.util.concurrent.atomic.AtomicInteger(0)
    var peak = 0
    runBlocking {
        kotlinx.coroutines.coroutineScope {
            repeat(6) { i ->
                launch(Dispatchers.Default) {
                    gate.withPermit {
                        val c = counter.incrementAndGet()
                        peak = maxOf(peak, c)
                        delay(25L)
                        counter.decrementAndGet()
                    }
                }
            }
        }
    }
    eq("并发峰值不超过闸", true, peak <= 3)
    eq("并发归零", counter.get(), 0)

    // ---------------- DomParser ----------------
    val dom = DomParser(null, metrics)
    val r1 = dom.parse("<html><head><title>书城</title></head><body><p>ok</p></body></html>", null, needsRealDom = false, traceId = "t1")
    check("jsoup 降级 Ok", r1 is DomParser.ParseResult.Ok)
    eq("jsoup 降级标志", (r1 as? DomParser.ParseResult.Ok)?.degraded, true)
    eq("jsoup 抽取标题", (r1 as DomParser.ParseResult.Ok).document.select("title").first().text(), "书城")

    val r2 = dom.parse("<html></html>", null, needsRealDom = true, traceId = "t2")
    check("真DOM失败降级", r2 is DomParser.ParseResult.Ok)
    eq("真DOM失败降级标志", (r2 as? DomParser.ParseResult.Ok)?.degraded, true)

    val realDoc = Jsoup.parse("<html><body>R</body></html>", "")
    val r3 = DomParser(FakeDom(realDoc), metrics).parse("<html></html>", "https://a.com", needsRealDom = true, traceId = "t3")
    check("真DOM成功", r3 is DomParser.ParseResult.Ok)
    eq("真DOM成功无降级", (r3 as? DomParser.ParseResult.Ok)?.degraded, false)
    eq("真DOM查询内容", (r3 as DomParser.ParseResult.Ok).document.body()?.text(), "R")

    val bigHtml = "x".repeat(2 * 1024 * 1024 + 1)
    val r4 = dom.parse(bigHtml, null, needsRealDom = false, traceId = "t4")
    check("超限失败", r4 is DomParser.ParseResult.Failed)
    eq("超限原因含长度", (r4 as? DomParser.ParseResult.Failed)?.reason?.contains("2MB"), true)

    // ---------------- Sniffer.isMedia ----------------
    val sn = Sniffer(
        object : SnifferRenderPool {
            override fun open(
                url: String,
                cookies: String?,
                onSubresource: (String, String?, Map<String, String>) -> String?
            ): SnifferSession? = null
        },
        object : CookieBridge {
            override fun read(url: String): String? = null
            override fun write(url: String, setCookie: String) {}
            override fun markInjected(url: String, cookie: String) {}
            override fun flush() {}
        }
    )
    eq("M3U8 后缀", sn.isMedia("https://m.share.com/v.m3u8", null, { true }), true)
    eq("MP4 后缀", sn.isMedia("https://m.share.com/x.mp4", "text/html", { true }), true)
    eq("MP4 MIME", sn.isMedia("https://m.share.com/page", "video/mp4", { true }), true)
    eq("MPD hint", sn.isMedia("https://m.share.com/manifest.mpd", null, { true }), true)
    eq("非白名单域拒绝", sn.isMedia("https://evil.com/x.mp4", null, { false }), false)
    eq("userinfo 非白名单拒绝", sn.isMedia("https://evil.com@m.share.com/x.mp4", null, { false }), false)
    eq("无 host 拒绝", sn.isMedia("no url here", "video/mp4", { true }), false)
    eq("空串拒绝", sn.isMedia("", "video/mp4", { true }), false)
    eq("普通页非媒体", sn.isMedia("https://m.share.com/list.html", "text/html", { true }), false)
    eq("ts 后缀圆角", sn.isMedia("https://m.share.com/123.ts", null, { true }), true)

    // ---------------- JsExtensions ----------------
    check("PRELUDE 含 Base64", JsExtensions.PRELUDE.contains("__b64Encode"))
    check("PRELUDE Seg64", JsExtensions.PRELUDE.contains("__vars = {}"))
    check("PRELUDE MD5", JsExtensions.PRELUDE.contains("__md5 ="))
    check("PRELUDE 桥接报错", JsExtensions.PRELUDE.contains("java.ajax 需要宿主桥接"))
    check("PRELUDE 变量表", JsExtensions.PRELUDE.contains("__vars"))
    eq("EXPORTED_NAMES 规模", JsExtensions.EXPORTED_NAMES.size, 23)
    check("所有扩展名入预置", JsExtensions.EXPORTED_NAMES.all { JsExtensions.PRELUDE.contains("$it:") })
    val wrapped = JsExtensions.wrap("var x = 1;")
    check("wrap 前缀预置", wrapped.startsWith(JsExtensions.PRELUDE))
    eq("wrap 包裹", wrapped.trimEnd().endsWith("})();"), true)
    check("wrap 内容保留", wrapped.contains("var x = 1;"))
    val kept = JsExtensions.wrap("a|b", hostBridge = false)
    check("wrap hostBridge=false 可用", kept.contains("a|b"))
    eq("wrap 重载", JsExtensions.wrap("a|b").length, JsExtensions.wrap("a|b", hostBridge = false).length)
    println("== EngineCoreTest ==")
    println("$pass 通过, $fail 失败")
    failures.forEach { println("  FAIL $it") }
    if (fail > 0) kotlin.system.exitProcess(1)
}
