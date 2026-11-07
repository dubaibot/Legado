import com.lanyue.reader.core.engine.ChannelStickyStore
import com.lanyue.reader.core.engine.CronetChannel
import com.lanyue.reader.core.engine.CookieBridge
import com.lanyue.reader.core.engine.EngineErrorCode
import com.lanyue.reader.core.engine.EngineException
import com.lanyue.reader.core.engine.InMemoryStickyStore
import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.engine.NetworkException
import com.lanyue.reader.core.engine.SourcePermissionGate
import com.lanyue.reader.core.engine.SourceRateLimiter
import com.lanyue.reader.core.engine.WebViewChannel
import com.lanyue.reader.core.net.HttpChannel
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.net.HttpResponse
import com.lanyue.reader.core.observability.LocalMetrics
import java.io.File
import kotlinx.coroutines.runBlocking

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

private class R13Http : HttpChannel {
    var count = 0
    var lastReq: HttpRequest? = null
    private val queue = ArrayDeque<() -> HttpResponse>()
    fun push(resp: HttpResponse) { queue.add { resp } }
    fun pushFail(msg: String) { queue.add { throw NetworkException(msg) } }
    override suspend fun execute(req: HttpRequest): HttpResponse {
        count++
        lastReq = req
        return queue.removeFirstOrNull()?.invoke()
            ?: HttpResponse(200, "<html>ok</html>", channel = "OKHTTP")
    }
}

private class R13Cronet : CronetChannel {
    var count = 0
    private val queue = ArrayDeque<() -> HttpResponse>()
    fun push(resp: HttpResponse) { queue.add { resp } }
    fun pushFail(msg: String) { queue.add { throw NetworkException(msg) } }
    override suspend fun execute(req: HttpRequest): HttpResponse {
        count++
        return queue.removeFirstOrNull()?.invoke()
            ?: HttpResponse(200, "<html>cronet</html>", channel = "CRONET")
    }
}

private class R13WebView : WebViewChannel {
    var count = 0
    override suspend fun execute(req: HttpRequest): HttpResponse {
        count++
        return HttpResponse(200, "<html>wv</html>", channel = "WEBVIEW")
    }
}

private class R13Cookies : CookieBridge {
    var readValue: String? = null
    var markUrl: String? = null
    var markCookie: String? = null
    var writtenUrl: String? = null
    var writtenCookie: String? = null
    override fun read(url: String): String? = readValue
    override fun write(url: String, setCookie: String) { writtenUrl = url; writtenCookie = setCookie }
    override fun markInjected(url: String, cookie: String) { markUrl = url; markCookie = cookie }
    override fun flush() {}
}

private class R13Env(
    val ok: R13Http = R13Http(),
    val cronet: R13Cronet? = null,
    val wv: R13WebView? = null,
    val cookies: R13Cookies = R13Cookies(),
    val stickyStore: InMemoryStickyStore = InMemoryStickyStore(),
    val rateLimiter: SourceRateLimiter = SourceRateLimiter(),
    gate: SourcePermissionGate = SourcePermissionGate.ALLOW_ALL
) {
    val metrics = LocalMetrics(File("/tmp/opencode/metrics-round13-${R13Env.seq++}"))
    val sticky = ChannelStickyStore(stickyStore)
    val dispatcher = NetworkDispatcher(ok, cronet, wv, sticky, rateLimiter, metrics, cookies, gate)

    companion object {
        var seq = 0
    }
}

private fun req(
    method: String = "GET",
    sourceId: String = "src-13",
    url: String = "http://a.com/list",
    headers: Map<String, String> = emptyMap()
) = HttpRequest(url = url, method = method, headers = headers, sourceId = sourceId)

private val OKHTTP200 = HttpResponse(200, "<html>ok</html>", channel = "OKHTTP")

fun main() = runBlocking {
    val env0 = R13Env()
    val r0 = env0.dispatcher.execute(req(), traceId = "t0")
    eq("默认门放行", r0.code, 200)
    eq("默认通道OKHTTP", r0.channel, "OKHTTP")
    eq("默认一次调用", env0.ok.count, 1)
    eq("默认crONET未涉", env0.cronet?.count, null)
    eq("默认wv未涉", env0.wv?.count, null)
    eq("默认sourceId透传", env0.ok.lastReq!!.sourceId, "src-13")

    // 限速拒绝：2/100s 窗口内第 3 次 acquire 必然 5s 超时为 RATE_LIMITED
    val envL = R13Env(rateLimiter = SourceRateLimiter(2, 100_000L))
    for (i in 1..2) envL.dispatcher.execute(req(sourceId = "src-13l"), traceId = "tl-$i")
    var thrownL: Throwable? = null
    try {
        envL.dispatcher.execute(req(sourceId = "src-13l"), traceId = "tl-3")
    } catch (e: Throwable) {
        thrownL = e
    }
    check("限速第3次拒绝", thrownL is EngineException && thrownL.code == EngineErrorCode.RATE_LIMITED, "thrown=$thrownL")

    // WAF 指纹拦截两击绑定
    val env1 = R13Env(cronet = R13Cronet())
    val block = HttpResponse(403, "Access Denied", channel = "OKHTTP")
    env1.ok.push(block)
    env1.ok.push(block)
    val r1 = env1.dispatcher.execute(req(sourceId = "src-waf"), traceId = "t1")
    eq("首击回退crONET", r1.channel, "CRONET")
    eq("首击crONET一次", env1.cronet!!.count, 1)
    val r1b = env1.dispatcher.execute(req(sourceId = "src-waf"), traceId = "t1b")
    eq("次击仍回退crONET", r1b.channel, "CRONET")
    eq("次击后http两次", env1.ok.count, 2)
    val r1c = env1.dispatcher.execute(req(sourceId = "src-waf"), traceId = "t1c")
    eq("绑定后直达crONET", r1c.channel, "CRONET")
    eq("绑定后不再ok", env1.ok.count, 2)
    eq("绑定后crONET三次", env1.cronet!!.count, 3)

    // croNET 抛异常回落 OKHTTP
    val env2 = R13Env(cronet = R13Cronet())
    env2.ok.push(HttpResponse(403, "Access Denied", channel = "OKHTTP"))
    env2.cronet!!.pushFail("crONET unavailable")
    val r2 = env2.dispatcher.execute(req(sourceId = "src-fb"), traceId = "t2")
    eq("crONET异常回落ok", r2.channel, "OKHTTP")
    eq("回落成功码200", r2.code, 200)
    eq("crONET共一次", env2.cronet!!.count, 1)
    eq("ok两次", env2.ok.count, 2)

    // OKHTTP 两击皆败
    val env3 = R13Env()
    env3.ok.pushFail("handshake fail")
    env3.ok.pushFail("handshake fail")
    var thrown3: Throwable? = null
    try {
        env3.dispatcher.execute(req(sourceId = "src-fail"), traceId = "t3")
    } catch (e: Throwable) {
        thrown3 = e
    }
    check("OKHTTP两败抛网络异常", thrown3 is NetworkException, "thrown=$thrown3")
    eq("两败调用两次", env3.ok.count, 2)

    // 空 HTML（无标签 <1KB）判为指纹拦截
    val env4 = R13Env(cronet = R13Cronet())
    env4.ok.push(HttpResponse(200, "xx", channel = "OKHTTP"))
    val r4 = env4.dispatcher.execute(req(sourceId = "src-empty", url = "http://a.com/item.html"), traceId = "t4")
    eq("空HTML回退crONET", r4.channel, "CRONET")
    eq("空HTML仅触ok一次", env4.ok.count, 1)
    eq("空HTML触crONET一次", env4.cronet!!.count, 1)

    // Cookie 注入 + Set-Cookie 回写
    val env5 = R13Env()
    env5.cookies.readValue = "a=1; b=2"
    val respCookie = HttpResponse(
        200, "<html>ok</html>",
        headers = mapOf("Set-Cookie" to "sid=9"),
        finalUrl = "http://a.com/list",
        channel = "OKHTTP"
    )
    env5.ok.push(respCookie)
    env5.dispatcher.execute(req(sourceId = "src-cookie"), traceId = "t5")
    eq("Cookie注入标记url", env5.cookies.markUrl, "http://a.com/list")
    eq("Cookie注入值", env5.cookies.markCookie, "a=1; b=2")
    eq("Set-Cookie回写url", env5.cookies.writtenUrl, "http://a.com/list")
    eq("Set-Cookie回写值", env5.cookies.writtenCookie, "sid=9")

    // 已有 Cookie 头则不加注入标记
    val env5b = R13Env()
    env5b.cookies.readValue = "a=1"
    env5b.dispatcher.execute(req(sourceId = "src-cookie2", headers = mapOf("Cookie" to "prev=1")), traceId = "t5b")
    eq("已有Cookie不标记", env5b.cookies.markCookie, null)

    // 通道绑定 WEBVIEW 直达
    val env6 = R13Env(wv = R13WebView())
    val key6 = env6.sticky.stickyKey("a.com", "src-wv")
    env6.stickyStore.put(key6, "${System.currentTimeMillis()}|WEBVIEW")
    val r6 = env6.dispatcher.execute(req(sourceId = "src-wv", url = "http://a.com/list"), traceId = "t6")
    eq("绑定直达webview", r6.channel, "WEBVIEW")
    eq("直达零ok", env6.ok.count, 0)
    eq("直达webview一次", env6.wv!!.count, 1)

    // SSRF 子资源转发
    val env7 = R13Env()
    val headers7 = mapOf(":method" to "GET", "Cookie" to "priv=1")
    eq("ssrf拒绝返回null", env7.dispatcher.forwardForSubresource("http://a.com/ff", headers7, false), null)
    eq("ssrf拒绝零请求", env7.ok.count, 0)
    val r7 = env7.dispatcher.forwardForSubresource("http://a.com/ff", headers7, true)!!
    eq("SSRF放行", r7.channel, "OKHTTP")
    eq("转发剥Cookie", env7.ok.lastReq!!.headers.containsKey("Cookie"), false)
    eq("转发method透传", env7.ok.lastReq!!.method, "GET")
    eq("转发10s超时", env7.ok.lastReq!!.timeoutMs, 10_000L)
    val r7head = env7.dispatcher.forwardForSubresource("http://a.com/ff2", mapOf(":method" to "HEAD"), true)!!
    eq("HEAD转发", r7head.code, 200)
    val r7post = env7.dispatcher.forwardForSubresource(
        "http://a.com/ff3", mapOf(":method" to "POST", "Range" to "bytes=0-9"), true
    )!!
    eq("POST+Range放行", r7post.code, 200)
    eq("POST+Range透传", env7.ok.lastReq!!.method, "POST")
    eq("POST无Range拒绝", env7.dispatcher.forwardForSubresource("http://a.com/ff4", mapOf(":method" to "POST"), true), null)

    val env7b = R13Env()
    env7b.ok.pushFail("timeout")
    eq("转发异常吞掉返回null", env7b.dispatcher.forwardForSubresource("http://a.com/ff", mapOf(":method" to "GET"), true), null)

    // 执行期权限闸
    val gate8 = SourcePermissionGate { _, method -> method == "GET" }
    val env8 = R13Env(gate = gate8)
    var thrown8: Throwable? = null
    try {
        env8.dispatcher.execute(req(method = "POST", sourceId = "src-video", url = "http://a.com/v"), traceId = "t8")
    } catch (e: Throwable) {
        thrown8 = e
    }
    check("POST未授权抛PERMISSION_DENIED",
        thrown8 is EngineException && thrown8.code == EngineErrorCode.PERMISSION_DENIED, "thrown=$thrown8")
    eq("拒绝零请求", env8.ok.count, 0)
    val r8 = env8.dispatcher.execute(req(sourceId = "src-video", url = "http://a.com/v"), traceId = "t8b")
    eq("GET放行200", r8.code, 200)
    eq("放行一次请求", env8.ok.count, 1)

    println("===== NetworkDispatcher 执行期权限闸与通道缝 =====")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
