import com.lanyue.reader.source.purify.PurifyRuleCodec
import com.lanyue.reader.source.purify.PurifyScope
import com.lanyue.reader.source.purify.ReplaceRule

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
    enabled: Boolean = true,
    order: Int = 0,
    firstLineOnly: Boolean = false,
    name: String = ""
) = ReplaceRule(name, pattern, replacement, isRegex, scope, enabled, order, firstLineOnly)

fun main() {
    // ---------- 空输入 ----------
    run {
        eq("null 解出空", PurifyRuleCodec.decode(null).rules, emptyList<ReplaceRule>())
        eq("空串解出空", PurifyRuleCodec.decode("").rules, emptyList<ReplaceRule>())
        eq("空白解出空", PurifyRuleCodec.decode("   \n ").rules, emptyList<ReplaceRule>())
        ok("空输入不算 broken", !PurifyRuleCodec.decode("").broken)
    }

    // ---------- 关键：isRegex 默认 false（跟随 Legado） ----------
    run {
        val d = PurifyRuleCodec.decode("""[{"pattern":"(*广告*)"}]""")
        eq("缺省 isRegex 为字面量", d.rules.size, 1)
        eq("isRegex 默认 false", d.rules[0].isRegex, false)
        ok("默认字面量不报警", d.problems.isEmpty())
    }
    run {
        val d = PurifyRuleCodec.decode("""[{"pattern":"\\d+","isRegex":true}]""")
        eq("显式 isRegex=true", d.rules[0].isRegex, true)
    }
    run {
        // 字符串形态 true/false 与 1/0 都要认
        eq("字符串 true", PurifyRuleCodec.decode("""[{"pattern":"a","isRegex":"true"}]""").rules[0].isRegex, true)
        eq("字符串 false", PurifyRuleCodec.decode("""[{"pattern":"a","isRegex":"false"}]""").rules[0].isRegex, false)
        eq("数字 1", PurifyRuleCodec.decode("""[{"pattern":"a","isRegex":1}]""").rules[0].isRegex, true)
        eq("数字 0", PurifyRuleCodec.decode("""[{"pattern":"a","isRegex":0}]""").rules[0].isRegex, false)
    }

    // ---------- 作用域 ----------
    run {
        eq("缺省 scope 为 ALL", PurifyRuleCodec.decode("""[{"pattern":"a"}]""").rules[0].scope, PurifyScope.ALL)
        eq("空串 scope 为 ALL", PurifyRuleCodec.decode("""[{"pattern":"a","scope":""}]""").rules[0].scope, PurifyScope.ALL)
        eq("title", PurifyRuleCodec.decode("""[{"pattern":"a","scope":"title"}]""").rules[0].scope, PurifyScope.TITLE)
        eq("content", PurifyRuleCodec.decode("""[{"pattern":"a","scope":"content"}]""").rules[0].scope, PurifyScope.CONTENT)
        eq("大写 TITLE 也认", PurifyRuleCodec.decode("""[{"pattern":"a","scope":"TITLE"}]""").rules[0].scope, PurifyScope.TITLE)
        eq("all", PurifyRuleCodec.decode("""[{"pattern":"a","scope":"all"}]""").rules[0].scope, PurifyScope.ALL)
    }
    run {
        // 拼错作用域是最容易踩的坑，必须给出可见提示
        val d = PurifyRuleCodec.decode("""[{"pattern":"a","scope":"titel"}]""")
        eq("未知 scope 退回 ALL", d.rules[0].scope, PurifyScope.ALL)
        ok("未知 scope 有提示", d.problems.any { it.contains("titel") })
        ok("提示含可选值", d.problems.any { it.contains("title") && it.contains("content") })
    }

    // ---------- 字段映射 ----------
    run {
        val d = PurifyRuleCodec.decode(
            """[{"name":"去水印","pattern":"水印","replacement":"","isRegex":false,
                "isEnabled":true,"scope":"content","sortOrder":7,"firstLineOnly":true}]"""
        )
        val x = d.rules[0]
        eq("name", x.name, "去水印")
        eq("pattern", x.pattern, "水印")
        eq("replacement", x.replacement, "")
        eq("order 取 sortOrder", x.order, 7)
        eq("firstLineOnly", x.firstLineOnly, true)
        eq("enabled", x.enabled, true)
    }
    run {
        eq("isEnabled=false", PurifyRuleCodec.decode("""[{"pattern":"a","isEnabled":false}]""").rules[0].enabled, false)
        eq("isEnabled 缺省 true", PurifyRuleCodec.decode("""[{"pattern":"a"}]""").rules[0].enabled, true)
        eq("firstLineOnly 缺省 false", PurifyRuleCodec.decode("""[{"pattern":"a"}]""").rules[0].firstLineOnly, false)
        eq("sortOrder 缺省 0", PurifyRuleCodec.decode("""[{"pattern":"a"}]""").rules[0].order, 0)
    }
    run {
        // 数字形态的 pattern 在社区书源里真实存在
        eq("数字 pattern 当字符串", PurifyRuleCodec.decode("""[{"pattern":123}]""").rules[0].pattern, "123")
        eq("数字 replacement", PurifyRuleCodec.decode("""[{"pattern":"a","replacement":456}]""").rules[0].replacement, "456")
    }
    run {
        eq("sortOrder 字符串", PurifyRuleCodec.decode("""[{"pattern":"a","sortOrder":"3"}]""").rules[0].order, 3)
    }

    // ---------- 坏条目容错 ----------
    run {
        val d = PurifyRuleCodec.decode("""[{"pattern":"ok"},{"noPattern":1},{"pattern":"ok2"}]""")
        eq("缺 pattern 的条目被跳过", d.rules.size, 2)
        ok("提示缺 pattern", d.problems.any { it.contains("缺少 pattern") })
        eq("其余条目仍保留", d.rules.map { it.pattern }, listOf("ok", "ok2"))
    }
    run {
        val d = PurifyRuleCodec.decode("""[1,"x",{"pattern":"a"}]""")
        eq("非对象条目被跳过", d.rules.size, 1)
        ok("提示非对象", d.problems.any { it.contains("不是对象") })
    }
    run {
        val d = PurifyRuleCodec.decode("""[{"pattern":""}]""")
        eq("空 pattern 仍保留供编辑", d.rules.size, 1)
        ok("空 pattern 有提示", d.problems.any { it.contains("pattern 为空") })
    }
    run {
        val d = PurifyRuleCodec.decode("""[{"pattern":"a","sortOrder":-5}]""")
        ok("负 order 有提示", d.problems.any { it.contains("负数") })
        eq("负 order 仍保留原值", d.rules[0].order, -5)
    }

    // ---------- 整体坏 JSON 不抛异常 ----------
    run {
        val d = PurifyRuleCodec.decode("{oops")
        eq("坏 JSON 返回空规则", d.rules, emptyList<ReplaceRule>())
        ok("坏 JSON 标记 broken", d.broken)
        ok("坏 JSON 有提示", d.problems.any { it.contains("不是合法 JSON") })
    }
    run {
        eq("纯数字不算数组", PurifyRuleCodec.decode("42").broken, true)
    }

    // ---------- 单个对象也接受 ----------
    run {
        val d = PurifyRuleCodec.decode("""{"pattern":"单条"}""")
        eq("单对象解出一条", d.rules.size, 1)
        eq("单对象 pattern", d.rules[0].pattern, "单条")
    }

    // ---------- 编解码往返 ----------
    run {
        val src = listOf(
            r("水印", "", false, PurifyScope.CONTENT, true, 1, false, "去水印"),
            r("\\d+", "#", true, PurifyScope.TITLE, false, 2, true, "数字")
        )
        val json = PurifyRuleCodec.encode(src)
        val back = PurifyRuleCodec.decode(json).rules
        eq("往返条数", back.size, 2)
        eq("往返 name", back[0].name, "去水印")
        eq("往返 pattern", back[0].pattern, "水印")
        eq("往返 isRegex", back[0].isRegex, false)
        eq("往返 scope", back[0].scope, PurifyScope.CONTENT)
        eq("往返 order", back[0].order, 1)
        eq("往返 enabled", back[1].enabled, false)
        eq("往返 firstLineOnly", back[1].firstLineOnly, true)
        eq("往返 scope2", back[1].scope, PurifyScope.TITLE)
    }
    run {
        eq("空列表编码为 []", PurifyRuleCodec.encode(emptyList()), "[]")
    }
    run {
        // 编码出的 scope 必须是 Legado 认的字符串
        val json = PurifyRuleCodec.encode(listOf(r("a", scope = PurifyScope.CONTENT)))
        ok("编码 scope=content", json.contains("\"scope\":\"content\""))
    }

    // ---------- 合并语义 ----------
    run {
        val global = listOf(r("水印"), r("通用后缀"))
        val source = listOf(r("水印"))
        val m = PurifyRuleCodec.merge(global, source)
        eq("源级覆盖全局同键", m.rules.size, 2)
        eq("被覆盖计数", m.overridden, 1)
        ok("保留未冲突的全局规则", m.rules.any { it.pattern == "通用后缀" })
    }
    run {
        // 同 pattern 不同 scope 不算同一条
        val global = listOf(r("x", scope = PurifyScope.CONTENT))
        val source = listOf(r("x", scope = PurifyScope.TITLE))
        val m = PurifyRuleCodec.merge(global, source)
        eq("scope 不同不覆盖", m.rules.size, 2)
        eq("未覆盖计数", m.overridden, 0)
    }
    run {
        val m = PurifyRuleCodec.merge(emptyList(), listOf(r("a"), r("b")))
        eq("无全局则全为源级", m.rules.size, 2)
        eq("无全局无覆盖", m.overridden, 0)
    }
    run {
        val m = PurifyRuleCodec.merge(listOf(r("a")), emptyList())
        eq("无源级保留全局", m.rules.size, 1)
        eq("无源级不覆盖", m.overridden, 0)
    }
    run {
        // 全局在前、源级在后，顺序可预测
        val m = PurifyRuleCodec.merge(listOf(r("g1"), r("g2")), listOf(r("s1")))
        eq("全局在前源级在后", m.rules.map { it.pattern }, listOf("g1", "g2", "s1"))
    }
    run {
        // 源级内部重复不应影响全局判定
        val m = PurifyRuleCodec.merge(listOf(r("dup")), listOf(r("dup"), r("dup")))
        eq("源级重复都保留", m.rules.count { it.pattern == "dup" }, 2)
        eq("全局被覆盖 1 条", m.overridden, 1)
    }

    // ---------- 解码结果与净化器协同 ----------
    run {
        val d = PurifyRuleCodec.decode("""[{"pattern":"广告","isRegex":false,"scope":"content"}]""")
        val p = com.lanyue.reader.source.purify.TextPurifier(d.rules, normalizeWhitespace = false)
        eq("解码后可直接净化", p.purifyContent("正文广告已清").text, "正文已清")
        eq("解码规则不作用于标题", p.purifyTitle("标题广告").text, "标题广告")
    }

    java.io.File("/mnt/work/lanyue20/out_codec.txt").writeText(
        if (failures.isEmpty()) "NONE" else failures.joinToString("\n"), Charsets.UTF_8)
    println("PASS = $pass")
    println("FAIL = ${failures.size}")
    if (failures.isNotEmpty()) System.exit(1)
}
