import com.lanyue.reader.core.engine.DomParser
import com.lanyue.reader.core.engine.JsExecMode
import com.lanyue.reader.core.engine.JsRuntime
import com.lanyue.reader.core.engine.PoolTier
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.core.engine.EngineException
import com.lanyue.reader.source.rule.RuleEngine
import com.lanyue.reader.source.rule.RuleEngine.RuleContext
import kotlinx.coroutines.runBlocking
import java.io.File

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

/** 假 JS 运行时：只回传固定值，用于验证引擎的 JS 分发路径不依赖真 WebView。 */
private class FakeJs(private val reply: String) : JsRuntime {
    var lastScript: String = ""
    override suspend fun eval(
        script: String,
        mode: JsExecMode,
        tier: PoolTier,
        traceId: String,
        dispatch: (String, String) -> Boolean
    ): String {
        lastScript = script
        dispatch("task-1", script)
        return reply
    }
    override fun onRenderProcessGone(error: Throwable) {}
    override suspend fun drainPending(): Int = 0
}

private const val HTML = """
<html><head><title>书城</title></head><body>
<div class="book-list">
  <div class="item"><a class="name" href="/book/1">三体</a><span class="author">刘慈欣</span><img src="//img.a.com/1.jpg"></div>
  <div class="item"><a class="name" href="/book/2">活着</a><span class="author">余华</span><img src="//img.a.com/2.jpg"></div>
  <div class="item"><a class="name" href="https://x.com/b3">围城</a></div>
</div>
<div id="meta" data-cid="42">元信息</div>
<a href="rel/path">相对链接</a>
</body></html>
"""

private const val JSON = """
{"code":0,"data":{"list":[
  {"name":"三体","type":"book","words":880000},
  {"name":"某漫画","type":"comic","words":0}
]}}
"""

fun main() = runBlocking {
    val metrics = LocalMetrics(File("/tmp/metrics"))
    val dom = DomParser(null, metrics) // realDom = null，走 jsoup 降级
    val js = FakeJs("js-ok")
    val engine = RuleEngine(js, dom, metrics)

    // ---------------- CSS 基础 ----------------
    eq("CSS 取文本", engine.evalList(HTML, "class.name@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("三体", "活着", "围城"))
    eq("CSS 标签前缀", engine.evalList(HTML, "tag.span@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("刘慈欣", "余华"))
    eq("CSS id 前缀", engine.evalList(HTML, "id.meta@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("元信息"))

    // ---------------- @href / @src（本轮修复的核心缺陷） ----------------
    eq("CSS 取 href", engine.evalList(HTML, "class.name@href", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("/book/1", "/book/2", "https://x.com/b3"))
    eq("CSS 取 img src", engine.evalList(HTML, "tag.img@src", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("//img.a.com/1.jpg", "//img.a.com/2.jpg"))
    eq("CSS 取 data-* 属性", engine.evalList(HTML, "id.meta@data-cid", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("42"))
    eq("attr: 前缀", engine.evalList(HTML, "class.name@attr:href", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("/book/1", "/book/2", "https://x.com/b3"))

    // ---------------- @abs: 绝对化 ----------------
    eq("abs: 站点根相对", engine.evalList(HTML, "class.name@abs:href", RuleContext(baseUrl = "https://a.com/list", sourceId = "src-1", traceId = "trace-1")),
        listOf("https://a.com/book/1", "https://a.com/book/2", "https://x.com/b3"))
    eq("abs: 协议相对", engine.evalList(HTML, "tag.img@abs:src", RuleContext(baseUrl = "https://a.com/list", sourceId = "src-1", traceId = "trace-1")),
        listOf("https://img.a.com/1.jpg", "https://img.a.com/2.jpg"))
    // tag.a 会选中全部 <a>，因此结果含 4 条；最后一条验证"目录相对"拼接
    val absAll = engine.evalList(HTML, "tag.a@abs:href",
        RuleContext(baseUrl = "https://a.com/dir/page", sourceId = "src-1", traceId = "trace-1"))
    eq("abs: 全量链接数", absAll.size, 4)
    eq("abs: 目录相对", absAll.last(), "https://a.com/dir/rel/path")

    // ---------------- 链式选择器 + 尾部索引 ----------------
    eq("链式嵌套", engine.evalList(HTML, "class.book-list@class.name@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("三体", "活着", "围城"))
    eq("尾部索引取第 2 个", engine.evalList(HTML, "class.name.1@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("活着"))
    eq("尾部负索引", engine.evalList(HTML, "class.name.-1@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("围城"))

    // ---------------- XPath ----------------
    eq("XPath 取文本", engine.evalList(HTML, "//div[@class='book-list']//a/text()", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("三体", "活着", "围城"))
    eq("XPath 取属性", engine.evalList(HTML, "//a[@class='name']/@href", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("/book/1", "/book/2", "https://x.com/b3"))
    eq("XPath 位置", engine.evalList(HTML, "//div[@class='item'][2]//span/text()", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("余华"))
    // 不支持的结构必须明确报错，不能静默空
    try {
        engine.evalList(HTML, "//div/ancestor::span", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1"))
        fail++; failures.add("XPath 轴应报错却成功")
    } catch (e: EngineException) {
        check("XPath 轴报错信息含 unsupported", e.message?.contains("unsupported") == true, "msg=${e.message}")
    }

    // ---------------- JSONPath ----------------
    eq("JSONPath 字段", engine.evalList(JSON, "$.data.list[*].name", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("三体", "某漫画"))
    eq("JSONPath 过滤", engine.evalList(JSON, "$.data.list[?(@.type=='book')].name", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("三体"))
    eq("JSONPath 比较", engine.evalList(JSON, "$.data.list[?(@.words>1000)].name", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("三体"))
    // JSON 响应套 CSS 规则 -> 取不到，不应崩
    eq("HTML 上跑 JSONPath 判空", engine.evalList(HTML, "$.data.list", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        emptyList<String>())

    // ---------------- JS 分发 ----------------
    eq("JS 规则走运行时", engine.evalList(HTML, "@js:1+1", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("js-ok"))
    check("JS 脚本注入了 result", js.lastScript.contains("var result ="))

    // ---------------- 正则替换 ----------------
    eq("## 替换", engine.evalList(HTML, "class.name@text##第(\\d+)章##$1", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("三体", "活着", "围城"))

    // ---------------- || 短路 ----------------
    eq("|| 第一备选命中", engine.evalList(HTML, "class.name@text||class.author@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("三体", "活着", "围城"))
    eq("|| 第一备选失败回落", engine.evalList(HTML, "class.nope@text||class.author@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("刘慈欣", "余华"))

    // ---------------- && 合并 ----------------
    eq("&& 合并两段", engine.evalList(HTML, "class.author@text&&id.meta@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")),
        listOf("刘慈欣", "余华", "元信息"))

    // ---------------- 空规则 ----------------
    eq("null 规则", engine.evalList(HTML, null, RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")), emptyList<String>())
    eq("空串规则", engine.evalList(HTML, "  ", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")), emptyList<String>())
    eq("选择器无匹配", engine.evalList(HTML, "class.none@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")), emptyList<String>())

    // ---------------- evalString ----------------
    eq("evalString 取首个", engine.evalString(HTML, "class.name@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")), "三体")
    eq("evalString 无匹配", engine.evalString(HTML, "class.none@text", RuleContext(baseUrl = "https://a.com", sourceId = "src-1", traceId = "trace-1")), null)

    println("================ RuleEngine 分发集成测试 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
