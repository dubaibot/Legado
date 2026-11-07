import com.lanyue.reader.core.engine.DomParser
import com.lanyue.reader.core.engine.JsExecMode
import com.lanyue.reader.core.engine.JsRuntime
import com.lanyue.reader.core.engine.PoolTier
import com.lanyue.reader.core.observability.LocalMetrics
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

/** 假 JS 运行时（类名带 R12 前缀，避免与既有测试文件的同名类冲突）。 */
private class R12FakeJs(private val reply: String) : JsRuntime {
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
<div class="books">
  <div class="book" data-cover="cover-1">书1</div>
  <div class="book" data-cover="cover-2">书2</div>
</div>
<div class="items">
  <div class="item"><a class="name" href="/book/1">三体</a><span class="author">刘慈欣</span><img src="//img.a.com/1.jpg"></div>
  <div class="item"><a class="name" href="/book/2">活着</a><span class="author">余华</span><img src="//img.a.com/2.jpg"></div>
  <div class="item col-2"><a class="name" href="/book/3">变更</a><span class="author">作者B</span><img src="//img.a.com/3.jpg"></div>
  <div class="item"><a class="name" href="https://x.com/b3">围城</a></div>
</div>
<div class="tags"><span class="tag">标签一</span></div>
<img src=" /book/9 "/>
</body></html>
"""

private fun ctx(base: String = "https://a.com", variables: Map<String, String> = emptyMap()) =
    RuleContext(baseUrl = base, sourceId = "src-12", traceId = "trace-12", variables = variables)

fun main() = runBlocking {
    val metrics = LocalMetrics(File("/tmp/opencode/metrics-round12"))
    val dom = DomParser(null, metrics)
    val js = R12FakeJs("js-ok")
    val engine = RuleEngine(js, dom, metrics)

    // ---------------- CSS 结果尾接 JS 转换 ----------------
    eq("CSS尾@js:逐个转换", engine.evalList(HTML, "class.name@text@js:1+1", ctx()),
        listOf("js-ok", "js-ok", "js-ok", "js-ok"))
    check("JS尾巴注入result", js.lastScript.contains("var result ="))
    check("JS尾巴传入选择结果", js.lastScript.contains("围城"))

    // ---------------- <js> 整段规则 ----------------
    eq("<js>整段分发", engine.evalList(HTML, "<js>1+1</js>", ctx()), listOf("js-ok"))
    check("<js>包裹脚本入预置", js.lastScript.contains("var result ="))

    // ---------------- XPath 属性指令透传 ----------------
    eq("XPath /@attr透传", engine.evalList(HTML, "//a[@class='name']/@href", ctx()),
        listOf("/book/1", "/book/2", "/book/3", "https://x.com/b3"))
    eq("XPath /@src无绝对化", engine.evalList(HTML, "//img/@src", ctx()),
        listOf("//img.a.com/1.jpg", "//img.a.com/2.jpg", "//img.a.com/3.jpg", "/book/9"))
    eq("XPath 位置+后代", engine.evalList(HTML, "//div[@class='item'][2]//span/text()", ctx()),
        listOf("余华"))

    // ---------------- 尾部索引边界 ----------------
    eq("索引越界判空", engine.evalList(HTML, "class.name.9@text", ctx()), emptyList<String>())
    eq("负索引越界判空", engine.evalList(HTML, "class.name.-5@text", ctx()), emptyList<String>())
    eq("负索引取倒数第3", engine.evalList(HTML, "class.name.-3@text", ctx()), listOf("活着"))
    eq("col-2类名不误判索引", engine.evalList(HTML, "class.item.col-2@tag.a@text", ctx()), listOf("变更"))
    eq("col-2子级无匹配判空", engine.evalList(HTML, "class.item.col-2@tag.nope@text", ctx()), emptyList<String>())

    // ---------------- absolutize 细节 ----------------
    eq("http scheme保留", engine.evalList(HTML, "tag.img@abs:src", ctx("http://a.com/dir/page")),
        listOf("http://img.a.com/1.jpg", "http://img.a.com/2.jpg", "http://img.a.com/3.jpg", "http://a.com/book/9"))
    eq("base无scheme补https", engine.evalList(HTML, "class.name@abs:href", ctx("example.com/list")),
        listOf("https://example.com/book/1", "https://example.com/book/2", "https://example.com/book/3", "https://x.com/b3"))
    eq("空base相对保持", engine.evalList(HTML, "class.name@abs:href", ctx("")),
        listOf("/book/1", "/book/2", "/book/3", "https://x.com/b3"))
    eq("缺失attr得空串过滤", engine.evalList(HTML, "class.tags@abs:href", ctx()), emptyList<String>())

    // ---------------- @put / @get / {{变量}} ----------------
    eq("put写入后模板引用", engine.evalList(
        HTML,
        "class.book@text@put:{cid:class.book.0@attr:data-cover}&&div.book[data-cover='{{cid}}']@text",
        ctx()), listOf("书1", "书2", "书1"))
    eq("@get注入ctx变量", engine.evalList(
        HTML, "class.tag@text@get:{sel}&&{{sel}}@text",
        ctx(variables = mapOf("sel" to "class.book"))), listOf("标签一", "书1", "书2"))
    eq("put简写回落本段值", engine.evalList(
        HTML, "class.tag@text@put:{who:}&&div.book[data-cover='{{who}}']@text",
        ctx()), listOf("标签一"))
    eq("put值解析为空不写变量", engine.evalList(
        HTML, "class.tag@text@put:{cid:id.none@text}&&div.book[data-cover='{{cid}}']@text",
        ctx()), listOf("标签一"))

    println("================ RuleEngine 派发第 2 批测试 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
