import com.lanyue.reader.core.engine.JsExecMode
import com.lanyue.reader.core.engine.JsRuntime
import com.lanyue.reader.core.engine.PoolTier
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.source.rule.RuleSyntax
import com.lanyue.reader.source.rule.SortUrlParser
import com.lanyue.reader.source.rule.UrlTemplate
import com.lanyue.reader.source.model.SortRule
import kotlinx.coroutines.runBlocking
import java.io.File

private var syntPass = 0
private var syntFail = 0
private val syntFailures = ArrayList<String>()

private fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) syntPass++ else {
        syntFail++
        syntFailures.add("$name -> actual=$actual expected=$expected")
    }
}

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) syntPass++ else {
        syntFail++
        syntFailures.add("$name -> $detail")
    }
}

private class FakeSortJs(private val reply: String) : JsRuntime {
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

private fun main() = runBlocking {
    val metrics = LocalMetrics(File("/tmp/opencode/metrics-syntax"))

    // ---------------- RuleSyntax：顶层切分 ----------------
    eq("顶层切分2段", RuleSyntax.splitTopLevel("$.a||$.b", "||"), listOf("$.a", "$.b"))
    eq("花括号保护", RuleSyntax.splitTopLevel("@put:{k:a||b}", "||").size, 1)
    eq("引号保护", RuleSyntax.splitTopLevel("@js:\"x||y\"||c", "||").size, 2)
    check("空分隔符报错", try {
        RuleSyntax.splitTopLevel("a", ""); false
    } catch (e: IllegalArgumentException) { true })
    eq("无分隔符原样", RuleSyntax.splitTopLevel("abc", "||"), listOf("abc"))

    // ---------------- RuleSyntax：## 正则替换 ----------------
    val plain = RuleSyntax.parseRegexReplacement("plain rule")
    eq("无##原样返回", plain.first, "plain rule")
    eq("无##无替换", plain.second, null)
    val full = RuleSyntax.parseRegexReplacement("a##\\d##N")
    eq("替换第1段", full.first, "a")
    check("替换第2段", full.second != null)
    eq("applyReplacement", RuleSyntax.applyReplacement("x1y2", full.second!!), "xNyN")
    val del = RuleSyntax.parseRegexReplacement("a##[0-9]+##")
    eq("删除正则删除匹配", del.second?.replacement, "")
    val bad = RuleSyntax.parseRegexReplacement("a##[##z")
    eq("坏正则保留规则", bad.first, "a")
    eq("坏正则无替换", bad.second, null)

    // ---------------- RuleSyntax：@put/@get ----------------
    val vd = RuleSyntax.parseVarDirectives("@put:{title:div.title@text} middle @get:{title,author}")
    eq("put 键", vd.puts.single().first, "title")
    eq("put 值取第一个冒号后", vd.puts.single().second, "div.title@text")
    eq("get 列表", vd.gets, listOf("title", "author"))
    eq("剥离指令", vd.rule, "middle")
    val vd2 = RuleSyntax.parseVarDirectives("@put:{t:a:b}")
    eq("put 值含冒号", vd2.puts.single().second, "a:b")

    // ---------------- RuleSyntax：模板与索引 ----------------
    eq("模板定位", RuleSyntax.findTemplateExpressions("{{ name }} {{expr}}"), listOf("name", "expr"))
    eq("模板求值成功", RuleSyntax.resolveTemplate("{{a}}-{{b}}", { k -> if (k == "a") "X" else null }), "X-{{b}}")
    eq("pickIndex 正数", RuleSyntax.pickIndex(listOf("a", "b", "c"), 1), "b")
    eq("pickIndex 负数", RuleSyntax.pickIndex(listOf("a", "b"), -1), "b")
    eq("pickIndex 越界", RuleSyntax.pickIndex(listOf("a"), 3), null)
    eq("pickIndex 空表", RuleSyntax.pickIndex(emptyList(), 0), null)
    eq("isIndexSegment .1", RuleSyntax.isIndexSegment(".1"), true)
    eq("isIndexSegment .-1", RuleSyntax.isIndexSegment(".-1"), true)
    eq("isIndexSegment .x", RuleSyntax.isIndexSegment(".x"), false)
    eq("isIndexSegment 2", RuleSyntax.isIndexSegment("2"), false)
    eq("indexOfSegment -1", RuleSyntax.indexOfSegment(".-1"), -1)

    // ---------------- RuleSyntax：规则类型判定 ----------------
    eq("isJsRule @js", RuleSyntax.isJsRule("@js: x"), true)
    eq("isJsRule <js>", RuleSyntax.isJsRule("<js>x</js>"), true)
    eq("isJsRule 普通CSS", RuleSyntax.isJsRule("div.x@text"), false)
    eq("isJsonPathRule $ 开头", RuleSyntax.isJsonPathRule("$.a.b"), true)
    eq("isJsonPathRule 非 $ 开头", RuleSyntax.isJsonPathRule("div.x@text"), false)
    eq("isXPathRule", RuleSyntax.isXPathRule("//div[1]"), true)
    eq("isXPathRule /html", RuleSyntax.isXPathRule("/html/body"), true)
    eq("isXPathRule 非 XPath", RuleSyntax.isXPathRule("class.a"), false)

    // ---------------- UrlTemplate ----------------
    eq("fillKey URL 编码", UrlTemplate.fillKey("h={{key}}", "a b"), "h=a+b")
    eq("fillKey raw", UrlTemplate.fillKey("h={{key_raw}}", "a b"), "h=a b")
    eq("fillPage", UrlTemplate.fillPage("p={{page}}", 7), "p=7")
    eq("fill 组合", UrlTemplate.fill("u?q={{key}}&p={{page}}", "x", 3), "u?q=x&p=3")
    eq("fill 无 key", UrlTemplate.fill("u?p={{page}}", null, 9), "u?p=9")
    eq("无占位符原样", UrlTemplate.fill("https://a.com/s", "x", 1), "https://a.com/s")
    eq("hasKeyPlaceholder", UrlTemplate.hasKeyPlaceholder("{{key}}"), true)
    eq("hasKeyPlaceholder key_raw", UrlTemplate.hasKeyPlaceholder("{{key_raw}}"), false)
    eq("hasPagePlaceholder", UrlTemplate.hasPagePlaceholder("{{page}}"), true)

    // ---------------- SortUrlParser ----------------
    val jsFake = FakeSortJs("s1::http://s1\ns2::http://s2")
    val parser = SortUrlParser(jsFake, metrics)
    val dr = parser.parse("@js: list", "src-1", "trace-dyn")
    eq("动态生成数量", dr.sorts.size, 2)
    eq("动态生成内容0", dr.sorts[0], SortRule("s1", "http://s1"))
    check("动态脚本透传", jsFake.lastScript.contains("list"))
    eq("动态未截断", dr.truncated, false)
    eq("动态未降级", dr.degraded, false)
    val db = parser.parse("<js>gen()</js>", "src-1", "trace-dyn2")
    check("js 块脚本透传", jsFake.lastScript.contains("gen()"))
    eq("js 块降级空", db.sorts.size, 2)
    val failed = SortUrlParser(FakeSortJs(""), metrics).parse("@js: nope", "src-1", "trace-bad")
    eq("js 失败降级 size0", failed.sorts.size, 0)
    eq("js 失败降级degraded", failed.degraded, true)
    eq("js 失败未截断", failed.truncated, false)
    val st = parser.parse("a::http://a\nb::http://b", "src-1", "trace-st")
    eq("静态两行", st.sorts.size, 2)
    val sc = parser.parse("x::1&&y::2", "src-1", "trace-sc")
    eq("&& 串联", sc.sorts.size, 2)
    val skip = parser.parse("::u\nn::\nok::http://o", "src-1", "trace-skip")
    eq("坏行跳过", skip.sorts.size, 1)
    eq("坏行保留", skip.sorts[0].name, "ok")
    val blank = parser.parse("   ", "src-1", "trace-blank")
    eq("空白空", blank.sorts.size, 0)
    eq("空白未降级", blank.degraded, false)
    val nullResult = parser.parse(null, "src-1", "trace-null")
    eq("null 空", nullResult.sorts.size, 0)
    eq("null 未降级", nullResult.degraded, false)
    val big = (1..SortUrlParser.MAX_SORTS + 1).joinToString("\n") { "s$it::http://s$it" }
    val tr = parser.parse(big, "src-1", "trace-trunc")
    eq("截断 size100", tr.sorts.size, 100)
    eq("截断标志", tr.truncated, true)
    eq("截断 degraded", tr.degraded, false)
    check("parseStatic 无::", parser.parseStatic("no sep").isEmpty())
    eq("parseStatic &&", parser.parseStatic("a::x&&b::y").size, 2)

    println("== EngineRuleSyntaxTest ==")
    println("$syntPass 通过, $syntFail 失败")
    syntFailures.forEach { println("  FAIL $it") }
    if (syntFail > 0) kotlin.system.exitProcess(1)
}
