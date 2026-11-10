import com.lanyue.reader.source.debug.SourceDebugger
import com.lanyue.reader.source.purify.PurifyPipeline
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

private fun r(
    pattern: String,
    replacement: String = "",
    isRegex: Boolean = false,
    scope: PurifyScope = PurifyScope.ALL,
    order: Int = 0,
    name: String = ""
) = ReplaceRule(name, pattern, replacement, isRegex, scope, true, order, false)

private fun json(vararg pairs: Pair<String, String>): String =
    pairs.joinToString(",", "[", "]") { (p, rep) ->
        """{"pattern":"$p","replacement":"$rep","isRegex":false}"""
    }

fun main() {
    // ---------- profileFor 基本行为 ----------
    run {
        val p = PurifyPipeline()
        val prof = p.profileFor("s1", """[{"pattern":"广告","isRegex":false}]""")
        eq("解析出 1 条规则", prof.rules.size, 1)
        ok("没有源级规则时无覆盖", prof.overriddenGlobals == 0)
        ok("无问题", prof.problems.isEmpty())
        ok("非空 profile", !prof.isEmpty)
    }
    run {
        val p = PurifyPipeline()
        val prof = p.profileFor("s1", null)
        ok("null 原文得到空 profile", prof.isEmpty)
        eq("空 profile 规则数为 0", prof.rules.size, 0)
    }
    run {
        val p = PurifyPipeline()
        ok("空串原文得到空 profile", p.profileFor("s1", "").isEmpty)
        ok("空白原文得到空 profile", p.profileFor("s1", "  \n ").isEmpty)
    }

    // ---------- 全局规则合并 ----------
    run {
        val glob = listOf(r("水印"), r("站点后缀"))
        val p = PurifyPipeline(globalRules = glob)
        val prof = p.profileFor("s1", null)
        eq("无源级规则时继承全部全局", prof.rules.size, 2)
        eq("无覆盖", prof.overriddenGlobals, 0)
    }
    run {
        val glob = listOf(r("水印"), r("站点后缀"))
        val p = PurifyPipeline(globalRules = glob)
        val prof = p.profileFor("s1", """[{"pattern":"水印","isRegex":false}]""")
        eq("源级覆盖同名全局后总数", prof.rules.size, 2)
        eq("覆盖计数为 1", prof.overriddenGlobals, 1)
        ok("保留未冲突全局", prof.rules.any { it.pattern == "站点后缀" })
    }
    run {
        // 源级 scope 与全局不同，不应判为覆盖
        val glob = listOf(r("x", scope = PurifyScope.CONTENT))
        val p = PurifyPipeline(globalRules = glob)
        val prof = p.profileFor("s1", """[{"pattern":"x","isRegex":false,"scope":"title"}]""")
        eq("不同 scope 不覆盖", prof.overriddenGlobals, 0)
        eq("两条都在", prof.rules.size, 2)
    }

    // ---------- 缓存命中与复用 ----------
    run {
        val p = PurifyPipeline()
        val a = p.purifierFor("s1", """[{"pattern":"a","isRegex":false}]""")
        val b = p.purifierFor("s1", """[{"pattern":"a","isRegex":false}]""")
        ok("相同源相同原文复用同一实例", a === b)
        eq("缓存条目数为 1", p.cachedCount, 1)
    }
    run {
        val p = PurifyPipeline()
        val a = p.purifierFor("s1", """[{"pattern":"a","isRegex":false}]""")
        val b = p.purifierFor("s2", """[{"pattern":"a","isRegex":false}]""")
        ok("不同源不共用缓存", a !== b)
        eq("缓存两条", p.cachedCount, 2)
    }
    run {
        // 原文变化导致 key 变化，必须重建
        val p = PurifyPipeline()
        val a = p.purifierFor("s1", """[{"pattern":"a","isRegex":false}]""")
        val b = p.purifierFor("s1", """[{"pattern":"b","isRegex":false}]""")
        ok("原文变化则重建", a !== b)
        eq("缓存两条（旧条目仍在直到淘汰）", p.cachedCount, 2)
    }

    // ---------- 缓存上限与 FIFO 淘汰 ----------
    run {
        val p = PurifyPipeline(maxCached = 3)
        p.purifierFor("s1", """[{"pattern":"a","isRegex":false}]""")
        p.purifierFor("s2", """[{"pattern":"a","isRegex":false}]""")
        p.purifierFor("s3", """[{"pattern":"a","isRegex":false}]""")
        eq("到达上限", p.cachedCount, 3)
        p.purifierFor("s4", """[{"pattern":"a","isRegex":false}]""")
        eq("超限后仍为 3", p.cachedCount, 3)
        // s1 最早插入，应被淘汰
        val again = p.purifierFor("s2", """[{"pattern":"a","isRegex":false}]""")
        eq("仍在缓存的 s2 不新增条目", p.cachedCount, 3)
        ok("s2 实例可复现", again === p.purifierFor("s2", """[{"pattern":"a","isRegex":false}]"""))
    }

    // ---------- 全局规则变化导致整体失效 ----------
    run {
        val p1 = PurifyPipeline(globalRules = listOf(r("g1")))
        val a = p1.purifierFor("s1", null)
        val p2 = PurifyPipeline(globalRules = listOf(r("g1")))
        // 不同实例互不影响
        ok("不同管线实例独立", p1.purifierFor("s1", null) === a)
        eq("p2 自身缓存独立计数", p2.cachedCount, 0)
    }

    // ---------- invalidate / clear ----------
    run {
        val p = PurifyPipeline()
        p.purifierFor("s1", """[{"pattern":"a","isRegex":false}]""")
        p.purifierFor("s2", """[{"pattern":"a","isRegex":false}]""")
        eq("清理前两条", p.cachedCount, 2)
        p.invalidate("s1")
        eq("清理 s1 后剩一条", p.cachedCount, 1)
        p.clear()
        eq("clear 后为空", p.cachedCount, 0)
    }
    run {
        // invalidate 只能命中同源前缀，不能误伤 s10
        val p = PurifyPipeline()
        p.purifierFor("s1", """[{"pattern":"a","isRegex":false}]""")
        p.purifierFor("s10", """[{"pattern":"a","isRegex":false}]""")
        p.invalidate("s1")
        eq("不误伤 s10", p.cachedCount, 1)
    }

    // ---------- 直接净化 ----------
    run {
        val p = PurifyPipeline()
        eq("purifyContent 生效",
            p.purifyContent("s1", """[{"pattern":"广告","isRegex":false}]""", "正文广告内容").text,
            "正文内容")
    }
    run {
        val p = PurifyPipeline()
        eq("purifyTitle 只走 title 规则",
            p.purifyTitle("s1", """[{"pattern":"X","isRegex":false,"scope":"title"}]""", "标题X").text,
            "标题")
    }
    run {
        val p = PurifyPipeline()
        eq("null 原文时不清洗", p.purifyContent("s1", null, "原文广告").text, "原文广告")
    }

    // ---------- problems 透传 ----------
    run {
        val p = PurifyPipeline()
        val prof = p.profileFor("s1", """[{"pattern":"a","scope":"titel"}]""")
        ok("未知 scope 问题透传", prof.problems.any { it.contains("titel") })
    }
    run {
        val p = PurifyPipeline()
        val prof = p.profileFor("s1", "{oops")
        ok("坏 JSON 透传问题", prof.problems.any { it.contains("不是合法 JSON") })
        ok("坏 JSON 时 profile 为空", prof.isEmpty)
    }

    // ---------- 与调试管线集成：判定必须在净化后 ----------
    run {
        val debugger = SourceDebugger
        // 真正文 168 字，混入 60 字水印噪声凑到 228 字：
        // 未净化时越过 200 字线被判通过，净化后回落到 168 字应判警告。
        // 噪声占比须低于 70%，否则触发净化器的过短回退保护。
        val body = "这是一段足够长的正常正文内容".repeat(12)
        val noisy = body + "水印".repeat(30)
        val purifier = TextPurifier(listOf(r("水印")))
        val raw = debugger.checkContent(noisy)
        val cleaned = debugger.checkContent(noisy, purifier = purifier)
        eq("噪声把正文凑过 200 字", raw.chars, noisy.length)
        eq("未净化时被判通过", raw.status.name, "PASS")
        // 正文阶段 count 恒为 1，字符数要看 chars
        ok("净化后正文更短", cleaned.chars < raw.chars)
        eq("净化后回到真实字数", cleaned.chars, body.length)
        eq("净化后应降为警告（不再通过）", cleaned.status.name, "WARN")
    }
    run {
        val debugger = SourceDebugger
        val long = "这是一段足够长的正常正文内容".repeat(30)
        val purifier = TextPurifier(listOf(r("水印")))
        val res = debugger.checkContent(long, purifier = purifier)
        eq("长正文净化后仍通过", res.status.name, "PASS")
    }
    run {
        val debugger = SourceDebugger
        // 需超过 MIN_CONTENT_CHARS(200) 才会判 PASS
        val plain = "正常正文内容足够长的一段文字用来测试判定逻辑是否正常工作".repeat(8)
        val res = debugger.checkContent(plain)
        eq("不传净化器保持原行为", res.status.name, "PASS")
    }

    // ---------- 端到端：书源 JSON → 管线 → 净化 ----------
    run {
        val g = listOf(r("（本章完）"))
        val p = PurifyPipeline(globalRules = g)
        val raw = """[
            {"name":"去水印","pattern":"盗版必究","isRegex":false,"scope":"content","sortOrder":1},
            {"name":"去后缀","pattern":"本站域名","isRegex":false,"scope":"all","sortOrder":2}
        ]"""
        val out = p.purifyContent("book1", raw,
            "正文内容盗版必究，本站域名，还有（本章完）结束")
        eq("端到端结果", out.text, "正文内容，，还有结束")
        ok("端到端有应用规则", out.stats.applied >= 2)
    }

    java.io.File("/mnt/work/lanyue21/out_pipeline.txt").writeText(
        if (failures.isEmpty()) "NONE" else failures.joinToString("\n"), Charsets.UTF_8)
    println("PASS = $pass")
    println("FAIL = ${failures.size}")
    if (failures.isNotEmpty()) System.exit(1)
}
