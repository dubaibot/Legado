import com.lanyue.reader.source.purify.PurifyScope
import com.lanyue.reader.source.purify.ReplaceRule
import com.lanyue.reader.source.purify.TextPurifier

private var pass = 0
private val failures = ArrayList<String>()

private fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++ else failures += "$name\n     期望: $expected\n     实际: $actual"
}

private fun ok(name: String, cond: Boolean) {
    if (cond) pass++ else failures += name
}

private fun rule(
    pattern: String,
    replacement: String = "",
    isRegex: Boolean = true,
    scope: PurifyScope = PurifyScope.ALL,
    enabled: Boolean = true,
    order: Int = 0,
    firstLineOnly: Boolean = false,
    name: String = ""
) = ReplaceRule(name, pattern, replacement, isRegex, scope, enabled, order, firstLineOnly)

fun main() {
    // ---------- 基础替换 ----------
    run {
        val p = TextPurifier(listOf(rule("广告", "")), normalizeWhitespace = false)
        eq("字面量删除广告", p.purifyContent("正文广告内容").text, "正文内容")
    }
    run {
        val p = TextPurifier(listOf(rule("(\\d+)\\.(\\d+)", "$1-$2")), normalizeWhitespace = false)
        eq("正则捕获组替换", p.purifyContent("版本 3.14 发布").text, "版本 3-14 发布")
    }
    run {
        val p = TextPurifier(listOf(rule("\\s+", " ")), normalizeWhitespace = false)
        eq("正则压缩空白", p.purifyContent("a   b    c").text, "a b c")
    }

    // ---------- 非正则不得当正则解释 ----------
    run {
        val p = TextPurifier(listOf(rule("(*广告*)", "", isRegex = false)), normalizeWhitespace = false)
        // 若误当正则，(*广告*) 会编译失败或匹配成别的东西
        // 文本需足够长，否则会触发 minKeepRatio 回退保护（那是另一条独立逻辑）
        eq("字面量含元字符", p.purifyContent("前(*广告*)后还有正常的正文内容在这里").text,
            "前后还有正常的正文内容在这里")
        eq("字面量含点号", TextPurifier(listOf(rule("a.b", "X", isRegex = false)), false)
            .purifyContent("a.b axb").text, "X axb")
    }
    run {
        val p = TextPurifier(listOf(rule("章节\\d+", "", isRegex = false)), normalizeWhitespace = false)
        eq("字面量不解释反斜杠", p.purifyContent("章节\\d+ 与 章节7").text, " 与 章节7")
    }

    // ---------- 空 pattern 必须跳过 ----------
    run {
        val p = TextPurifier(listOf(rule("", "X")), normalizeWhitespace = false)
        val r = p.purifyContent("abc")
        eq("空 pattern 不改正文", r.text, "abc")
        ok("空 pattern 计入 skipped", r.stats.skipped == 1)
        eq("空 pattern 未计 applied", r.stats.applied, 0)
    }
    run {
        // 空 pattern 夹在正常规则中间，正常规则仍要生效
        val p = TextPurifier(listOf(
            rule("坏", "X", order = 0),
            rule("", "Y", order = 1),
            rule("好", "Z", order = 2)
        ), normalizeWhitespace = false)
        eq("空 pattern 不阻断后续", p.purifyContent("坏好").text, "XZ")
    }

    // ---------- 非法正则只废这一条 ----------
    run {
        val p = TextPurifier(listOf(
            rule("[unclosed", "X", order = 0),
            rule("广告", "", order = 1)
        ), normalizeWhitespace = false)
        val r = p.purifyContent("正文广告")
        eq("非法正则后仍执行后续", r.text, "正文")
        eq("非法正则计 failed", r.stats.failed, 1)
        eq("后续规则计 applied", r.stats.applied, 1)
    }
    run {
        // 非法分组引用：正则合法但替换模板非法
        val p = TextPurifier(listOf(rule("(a)", "$9")), normalizeWhitespace = false)
        val r = p.purifyContent("aaa")
        eq("非法模板不改文本", r.text, "aaa")
        eq("非法模板计 failed", r.stats.failed, 1)
    }

    // ---------- 作用域分流 ----------
    run {
        val rules = listOf(
            rule("【正文】", "", scope = PurifyScope.CONTENT, order = 0),
            rule("【标题】", "", scope = PurifyScope.TITLE, order = 1)
        )
        val p = TextPurifier(rules, normalizeWhitespace = false)
        eq("正文只走 CONTENT 规则", p.purifyContent("a【正文】b【标题】").text, "ab【标题】")
        eq("标题只走 TITLE 规则", p.purifyTitle("a【正文】b【标题】").text, "a【正文】b")
    }
    run {
        val p = TextPurifier(listOf(rule("x", "y", scope = PurifyScope.ALL)), normalizeWhitespace = false)
        eq("ALL 规则对正文有效", p.purifyContent("xx").text, "yy")
        eq("ALL 规则对标题有效", p.purifyTitle("xx").text, "yy")
    }
    run {
        val p = TextPurifier(listOf(rule("x", "y", scope = PurifyScope.TITLE)))
        val r = p.purifyContent("xx")
        eq("不匹配作用域正文不变", r.text, "xx")
        eq("不匹配作用域计 skipped", r.stats.skipped, 1)
    }

    // ---------- enabled 开关 ----------
    run {
        val p = TextPurifier(listOf(
            rule("x", "y", enabled = false),
            rule("a", "b", enabled = true, order = 1)
        ), normalizeWhitespace = false)
        eq("禁用规则不生效", p.purifyContent("xa").text, "xb")
        eq("ruleCount 只算启用", p.ruleCount, 1)
    }

    // ---------- order 排序 ----------
    run {
        // 先删括号再删内容，顺序错了结果不同
        val p = TextPurifier(listOf(
            rule("【.*?】", "", order = 0),
            rule("广告", "", order = 1)
        ), normalizeWhitespace = false)
        eq("order 小者先执行", p.purifyContent("书【广告】评").text, "书评")
    }
    run {
        // 同 order 保持声明顺序
        val p = TextPurifier(listOf(
            rule("ab", "x", order = 5),
            rule("x", "z", order = 5)
        ), normalizeWhitespace = false)
        eq("同 order 按声明顺序", p.purifyContent("ab").text, "z")
    }

    // ---------- firstLineOnly ----------
    run {
        val p = TextPurifier(listOf(rule("^第.*?章", "", firstLineOnly = true)), normalizeWhitespace = false)
        eq("只处理首行", p.purifyContent("第一章 起\n第二章 承").text, " 起\n第二章 承")
    }
    run {
        val p = TextPurifier(listOf(rule("水印", "", firstLineOnly = true)), normalizeWhitespace = false)
        eq("首行替换其余行保留水印", p.purifyContent("水印A\n水印B\nC").text, "A\n水印B\nC")
    }
    run {
        val p = TextPurifier(listOf(rule("a", "b", firstLineOnly = true)), normalizeWhitespace = false)
        // 单行即整篇，首行路径退化为全文替换
        eq("单行文本也走首行路径", p.purifyContent("aaa").text, "bbb")
    }

    // ---------- 空白归一化 ----------
    run {
        val p = TextPurifier(emptyList(), normalizeWhitespace = true)
        eq("非断空格转普通空格", p.purifyContent("a\u00a0b").text, "a b")
        eq("全角空格转普通空格", p.purifyContent("a\u3000b").text, "a b")
        eq("连续空行折叠", p.purifyContent("a\n\n\n\nb").text, "a\n\nb")
        eq("行尾空白去掉", p.purifyContent("a   \nb").text, "a\nb")
        eq("首尾空白去掉", p.purifyContent("  a  ").text, "a")
        eq("CRLF 归一", p.purifyContent("a\r\nb").text, "a\nb")
    }
    run {
        val p = TextPurifier(emptyList(), normalizeWhitespace = false)
        eq("关闭归一化保留原样", p.purifyContent("a\u00a0b\n\n\n\nc").text, "a\u00a0b\n\n\n\nc")
    }

    // ---------- 异常结果回退 ----------
    run {
        // 把整篇正文替空，必须回退
        val p = TextPurifier(listOf(rule(".", "")))
        val r = p.purifyContent("这一整章正文内容")
        eq("正文被清空则回退原文", r.text, "这一整章正文内容")
        ok("回退标记为 true", r.stats.reverted)
    }
    run {
        // 标题短，相同阈值不应误回退
        val p = TextPurifier(listOf(rule("第.章", "")))
        val r = p.purifyTitle("第一章")
        eq("标题不因变短而回退", r.text, "")
        ok("标题未标记回退", !r.stats.reverted)
    }
    run {
        // 正常净化掉一小部分不触发回退
        val p = TextPurifier(listOf(rule("（本章完）", "")))
        val text = "这是一段足够长的正文内容，用来验证阈值判定不会误伤正常净化（本章完）"
        val r = p.purifyContent(text)
        eq("轻微缩短不回退", r.text.contains("（本章完）"), false)
        ok("正常净化未标记回退", !r.stats.reverted)
    }

    // ---------- 超长文本跳过 ----------
    run {
        val p = TextPurifier(listOf(rule("a", "b")), maxTextLength = 10)
        val r = p.purifyContent("aaaaaaaaaaaaaaa")
        eq("超长文本原样返回", r.text, "aaaaaaaaaaaaaaa")
        ok("超长标记 oversize", r.stats.oversize)
    }

    // ---------- 空输入 ----------
    run {
        val p = TextPurifier(listOf(rule("a", "b")))
        val r = p.purifyContent("")
        eq("空文本原样返回", r.text, "")
        eq("空文本无统计", r.stats.total, 0)
    }

    // ---------- 规则自检 ----------
    run {
        val problems = TextPurifier.validate(listOf(
            rule("", "X", name = "空匹配"),
            rule("[bad", "Y", name = "坏正则"),
            rule("-a", "Z", order = -1, name = "负序"),
            rule("ok", "W", name = "正常")
        ))
        ok("检出空匹配", problems.any { it.contains("空匹配") })
        ok("检出坏正则", problems.any { it.contains("坏正则") })
        ok("检出负 order", problems.any { it.contains("负序") })
        ok("正常规则不报警", problems.none { it.contains("正常") })
        eq("问题条数", problems.size, 3)
    }
    run {
        eq("全合法无问题", TextPurifier.validate(listOf(rule("a", "b"))), emptyList<String>())
    }

    // ---------- 规则串联 ----------
    run {
        val p = TextPurifier(listOf(
            rule("&nbsp;", " ", isRegex = false, order = 0),
            rule("<[^>]+>", "", order = 1),
            rule("\\s+", "\n", order = 2)
        ), normalizeWhitespace = true)
        // 标签整体删除后中间不含空白，故不会插入换行
        eq("多规则串联", p.purifyContent("<p>第一段</p><p>第二段</p>").text, "第一段第二段")
    }
    run {
        // 缓存复用：同 pattern 多次净化结果稳定
        val p = TextPurifier(listOf(rule("\\d+", "#")), normalizeWhitespace = false)
        eq("缓存后结果一致 1", p.purifyContent("a1b22").text, "a#b#")
        eq("缓存后结果一致 2", p.purifyContent("c333d").text, "c#d")
    }

    // ---------- 统计口径 ----------
    run {
        val p = TextPurifier(listOf(
            rule("hit", "", order = 0),        // 命中
            rule("miss", "", order = 1),       // 未命中
            rule("x", "y", scope = PurifyScope.TITLE, order = 2) // 作用域不符
        ), normalizeWhitespace = false)
        val r = p.purifyContent("has hit only")
        eq("统计 applied", r.stats.applied, 1)
        eq("统计 skipped", r.stats.skipped, 1)
        eq("统计 failed", r.stats.failed, 0)
        eq("统计 total", r.stats.total, 2)
    }

    java.io.File("/mnt/work/lanyue19/out_purify.txt").writeText(
        if (failures.isEmpty()) "NONE" else failures.joinToString("\n"), Charsets.UTF_8)
    println("PASS = $pass")
    println("FAIL = ${failures.size}")
    if (failures.isNotEmpty()) {
        java.io.File("/mnt/work/lanyue19/out_purify.txt").writeText(failures.joinToString("\n"), Charsets.UTF_8)
    }
    if (failures.isNotEmpty()) System.exit(1)
}
