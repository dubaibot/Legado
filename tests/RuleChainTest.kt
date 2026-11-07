import com.lanyue.reader.source.rule.RuleChain
import com.lanyue.reader.source.rule.RuleSyntax
import kotlinx.coroutines.runBlocking

private var pass = 0
private var fail = 0

private fun eq(label: String, actual: Any?, expected: Any?) {
    if (actual == expected) { pass++; println("[PASS] $label") }
    else { fail++; println("[FAIL] $label -> actual=$actual expected=$expected") }
}

private fun check(label: String, cond: Boolean, detail: String = "") {
    if (cond) { pass++; println("[PASS] $label") }
    else { fail++; println("[FAIL] $label -> cond=$cond $detail") }
}

fun main() = runBlocking {
    // ---------- parse：空规则 ----------
    eq("null规划为Empty", RuleChain.parse(null), RuleChain.Empty)
    eq("空串Empty", RuleChain.parse(""), RuleChain.Empty)
    eq("空白Empty", RuleChain.parse("   "), RuleChain.Empty)
    eq("全空段Empty", RuleChain.parse("  ||  "), RuleChain.Empty)
    eq("Empty船判自身", RuleChain.Empty.isEmpty, true)

    // ---------- || / && 切分 ----------
    val p1 = RuleChain.parse("$.a||$.b")
    eq("备选数2", p1.alternatives.size, 2)
    eq("备选0规则", p1.alternatives[0].segments[0].rule, "$.a")
    eq("备选1规则", p1.alternatives[1].segments[0].rule, "$.b")

    val p2 = RuleChain.parse("a&&b")
    eq("并列单备选", p2.alternatives.size, 1)
    eq("并列段数", p2.alternatives[0].segments.size, 2)
    eq("并列段0", p2.alternatives[0].segments[0].rule, "a")
    eq("并列段1", p2.alternatives[0].segments[1].rule, "b")

    val p3 = RuleChain.parse("a||b##c")
    eq("a无替换", p3.alternatives[0].segments[0].replacement, null)
    val r1 = p3.alternatives[1].segments[0].replacement!!
    eq("b替换正则", r1.pattern.pattern, "c")

    // 分隔符不能切进花括号
    val pB = RuleChain.parse("@put:{k:a||b}||2")
    eq("花括号内||不切", pB.alternatives.size, 2)
    eq("花括号内值保留", pB.alternatives[0].segments[0].puts, listOf("k" to "a||b"))

    // 尾部空备选被滤除
    eq("尾部空段滤除", RuleChain.parse("a||").alternatives.size, 1)

    // ---------- compileSegment：指令先摘、替换后解析 ----------
    val s1 = RuleChain.compileSegment("@put:{k:sel##a##b}x")
    eq("put值先摘", s1.puts, listOf("k" to "sel##a##b"))
    eq("主体替换后解析", s1.rule, "x")
    eq("主体无替换", s1.replacement, null)

    val s2 = RuleChain.compileSegment("sel##x##y")
    eq("基础段正则", s2.rule, "sel")
    eq("基础段替换", s2.replacement!!.replacement, "y")
    eq("基础段编译", s2.replacement!!.pattern.pattern, "x")

    val s3 = RuleChain.compileSegment("  x  ##a##$1")
    eq("段体trim", s3.rule, "x")
    eq("$1替换段", s3.replacement!!.replacement, "$1")

    val s4 = RuleChain.compileSegment("@put:{k:a} <@get:{k}> x")
    eq("双指令注册", s4.puts, listOf("k" to "a"))
    eq("Get读取", s4.gets, listOf("k"))
    eq("指令清理后主体", s4.rule, "<> x" .trim())
    val s5 = RuleChain.compileSegment("<> x".trim())
    eq("对照主体", s5.rule, s4.rule)

    // 替换段没有替换/无替换段：仅删匹配
    val s6 = RuleChain.compileSegment("x##1##$1")
    eq("两替换段切分", s6.rule, "x")
    eq("替换段内容", s6.replacement!!.pattern.pattern, "1")

    // 只有一个 ##：视为删除匹配内容
    val s7 = RuleChain.compileSegment("x##1")
    eq("首替换段", s7.rule, "x")
    eq("删除匹配内容", s7.replacement!!.replacement, "")

    // 坏正则不抛异常
    val s8 = RuleChain.compileSegment("sel##(")
    eq("坏正则不抛", s8.rule.trim(), "sel")
    eq("坏正则置空", s8.replacement, null)
    val s9 = RuleChain.compileSegment("##(")
    eq("全坏保留", s9.rule, "")
    eq("全坏置空", s9.replacement, null)

    // ---------- shouldAccept / finish / merge ----------
    val blank = List(3) { "" }
    eq("全空备选不采纳", RuleChain.shouldAccept(blank), false)
    eq("部分采纳", RuleChain.
 shouldAccept(listOf("", "x", "")), true)
    eq("非空值采纳", RuleChain.shouldAccept(listOf("abs")), true)

    val finNull = RuleChain.finish(listOf("", "x", "  "), listOf(null, null))
    eq("无替换段", finNull, listOf("x"))
    val finB = RuleChain.finish(listOf("  "), emptyList())
    eq("空结果清空", finB, emptyList<String>())
    val repl = RuleSyntax.RegexReplacement(Regex("\\d"), "-")
    val finR = RuleChain.finish(listOf("a1b2", "  c  "), listOf(repl))
    eq("替换后trim", finR, listOf("a-b-", "c"))
    val finFirst = RuleChain.finish(listOf("a1"), listOf(repl, repl))
    eq("首个非空替换生效", finFirst, listOf("a-"))

    val mg = RuleChain.merge(listOf(listOf("a", ""), listOf("b", "c")))
    eq("段间合并顺序", mg, listOf("a", "b", "c"))
    eq("全空合并结果", RuleChain.merge(listOf(emptyList(), listOf(""))), emptyList<String>())

    // ---------- allPuts / allGets / isEmpty ----------
    val pa = RuleChain.parse("@put:{a:1}||@put:{b:2}")
    eq("跨备选allPuts", pa.allPuts(), listOf("a" to "1", "b" to "2"))
    val pg = RuleChain.parse("@get:{a}||@get:{a,b}")
    eq("allGets去重", pg.allGets(), listOf("a", "b"))

    val pe = RuleChain.parse("@put:{k:v}")
    eq("仅指令段仍判空", pe.isEmpty, true)
    val pe2 = RuleChain.parse("@put:{k:${' '}}")
    eq("空号段判空", pe2.isEmpty, true)

    // ---------- 常量契约 ----------
    eq("备选分隔", RuleChain.SEP_OR, "||")
    eq("并列分隔", RuleChain.SEP_AND, "&&")

    println("===== 规则编排层 RuleChain =====")
    println("PASS = $pass   FAIL = $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
