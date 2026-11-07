import com.lanyue.reader.core.engine.JsExecMode
import com.lanyue.reader.core.engine.JsRuntime
import com.lanyue.reader.core.engine.PoolTier
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.source.model.SortRule
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

private class FakeJsRuntime(
    var result: String? = null,
    var thrown: Throwable? = null
) : JsRuntime {
    val seenScript = mutableListOf<String>()
    val seenTraces = mutableListOf<String>()
    val seenTiers = mutableListOf<PoolTier>()
    override suspend fun eval(
        script: String,
        mode: JsExecMode,
        tier: PoolTier,
        traceId: String,
        dispatch: (taskId: String, script: String) -> Boolean
    ): String {
        seenScript.add(script)
        seenTraces.add(traceId)
        seenTiers.add(tier)
        thrown?.let { throw it }
        return result ?: ""
    }
    override fun onRenderProcessGone(error: Throwable) {}
    override suspend fun drainPending(): Int = 0
}

private var metricsSeq = 0

private fun newParser(js: JsRuntime) =
    SortUrlParser(js, LocalMetrics(File("/tmp/opencode/metrics-sort-r18-${metricsSeq++}")))

fun main() {
    println("===== SortUrl 动态生成与静态分类解析 =====")

    // ---------------- parseStatic 基础 ----------------
    val P = newParser(FakeJsRuntime())
    val rules = P.parseStatic("热门::http://a/s?c=1\n最新::http://b/s")
    eq("换行分隔两条分类", rules, listOf(SortRule("热门", "http://a/s?c=1"), SortRule("最新", "http://b/s")))

    val andMerge = P.parseStatic("A类::http://p/s&&B类::http://q/s")
    eq("&& 串联两条", andMerge.size, 2)
    eq("&& 第一条名", andMerge[0].name, "A类")
    eq("&& 第二条url", andMerge[1].url, "http://q/s")

    eq("空行过滤", P.parseStatic("\n\n 热门::http://a \n\n").size, 1)
    eq("行内两侧 trim", P.parseStatic(" 热门 :: http://a "), listOf(SortRule("热门", "http://a")))

    eq("无 :: 忽略", P.parseStatic("只有名字\n名字::").size, 0)
    eq("url 含 ::", P.parseStatic("名::http://x::extra"), listOf(SortRule("名", "http://x::extra")))
    eq("空名忽略", P.parseStatic("::http://a").size, 0)
    eq("空url忽略", P.parseStatic("名::").size, 0)

    // ---------------- parse 空输入 ----------------
    val emptyNull = runBlocking { P.parse(null, "src", "tr-null") }
    check("null sortUrl 非降级", !emptyNull.degraded && !emptyNull.truncated)
    eq("null sortUrl 空分类", emptyNull.sorts, emptyList<SortRule>())
    val emptyBlank = runBlocking { P.parse("   \n  ", "src", "tr-blank") }
    eq("空白 sortUrl 空分类", emptyBlank.sorts.size, 0)

    // ---------------- parse 静态 ----------------
    val static = runBlocking { P.parse("热门::http://a\n最新::http://b", "src", "tr-static") }
    check("静态非降级", !static.degraded && !static.truncated)
    eq("静态两条", static.sorts.size, 2)

    // ---------------- parse 动态 @js: ----------------
    val jsFake = FakeJsRuntime(result = "热门::http://dy/s\n新番::http://dy/s2")
    val P2 = newParser(jsFake)
    val dy = runBlocking { P2.parse("@js:var x=1;'热门::http://dy/s/新番2'", "src", "tr-dy") }
    eq("@js: 走动态求值", jsFake.seenScript.size, 1)
    check("@js: IIFE 包装", jsFake.seenScript[0].startsWith("(function(){") &&
        jsFake.seenScript[0].endsWith("})()") && jsFake.seenScript[0].contains("var x=1;"))
    eq("@js: traceId 透传", jsFake.seenTraces[0], "tr-dy")
    eq("@js: NORMAL 池", jsFake.seenTiers[0], PoolTier.NORMAL)
    check("@js: 非降级", !dy.degraded)
    eq("@js: 结果并入分类", dy.sorts.size, 2)

    // ---------------- parse 动态 <js> ----------------
    val jsFake2 = FakeJsRuntime(result = "A::u1&&B::u2")
    val P3 = newParser(jsFake2)
    val dy2 = runBlocking { P3.parse("<js>return 'A::u1&&B::u2'</js>", "src", "tr-dy2") }
    eq("<js> 两条分类", dy2.sorts.size, 2)
    eq("<js> 提取标签内脚本", jsFake2.seenScript[0], "(function(){ return 'A::u1&&B::u2' })()")
    check("<js> 非降级", !dy2.degraded)

    // ---------------- 动态失败降级 ----------------
    val jsBlank = FakeJsRuntime(result = "   ")
    val P4 = newParser(jsBlank)
    val dy3 = runBlocking { P4.parse("@js:var a=1", "src", "tr-blank-js") }
    check("js 返回空白降级", dy3.degraded && dy3.sorts.isEmpty() && !dy3.truncated)

    val jsThrow = FakeJsRuntime(thrown = RuntimeException("eval boom"))
    val P5 = newParser(jsThrow)
    val dy4 = runBlocking { P5.parse("@js:boom()", "src", "tr-throw-js") }
    check("js 抛异常降级", dy4.degraded)

    // ---------------- 截断 ----------------
    val manyLines = List(110) { "分类$it::http://m/$it" }.joinToString("\n")
    val P6 = newParser(FakeJsRuntime())
    val dy5 = runBlocking { P6.parse(manyLines, "src", "tr-many") }
    check("超上限截断", dy5.truncated)
    eq("截断后数量上限100", dy5.sorts.size, 100)
    eq("截断保留首条", dy5.sorts[0].name, "分类0")
    eq("截断末条", dy5.sorts[99].name, "分类99")

    val exact100 = List(100) { "分类$it::http://m/$it" }.joinToString("\n")
    val dy6 = runBlocking { P6.parse(exact100, "src", "tr-exact") }
    check("恰好100不截断", !dy6.truncated && dy6.sorts.size == 100)

    val jsMany = FakeJsRuntime(result = List(150) { "分类$it::http://m/$it" }.joinToString("\n"))
    val P7 = newParser(jsMany)
    val dy7 = runBlocking { P7.parse("@js:x", "src", "tr-js-many") }
    check("动态结果截断", dy7.truncated && dy7.sorts.size == 100 && !dy7.degraded)

    println()
    println("PASS = $pass   FAIL = $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
