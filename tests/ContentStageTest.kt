import com.lanyue.reader.source.content.ContentStage
import com.lanyue.reader.source.purify.PurifyPipeline
import com.lanyue.reader.source.purify.PurifyScope
import com.lanyue.reader.source.purify.ReplaceRule
import com.lanyue.reader.core.render.ReadingProgress
import com.lanyue.reader.core.render.ReadingSession
import com.lanyue.reader.core.render.ReadingSettings

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

private fun r(
    pattern: String,
    replacement: String = "",
    isRegex: Boolean = false,
    scope: PurifyScope = PurifyScope.ALL,
    order: Int = 0
) = ReplaceRule(pattern = pattern, replacement = replacement, isRegex = isRegex,
    scope = scope, enabled = true, order = order, firstLineOnly = false)

/** 书源 replaceRule 原文（字面量规则，isRegex 缺省为 false，对齐 Legado 约定） */
private fun rules(vararg ps: Pair<String, String>): String =
    ps.joinToString(",", "[", "]") { (p, rep) ->
        """{"pattern":"$p","replacement":"$rep","isRegex":false}"""
    }

fun main() {
    // ================= 一、净化串联：水印 / 站点后缀去除 =================
    run {
        val stage = ContentStage(PurifyPipeline())
        val raw = "正文内容盗版必究，本站域名结束"
        val out = stage.prepare("s1", rules("盗版必究" to "", "本站域名" to ""), "ch1", raw)
        eq("正文水印被净化", out.purified, "正文内容，结束")
        check("首次未命中缓存", !out.fromCache)
        check("净化有应用规则", out.stats.applied >= 2)
        eq("原始正文保留", out.raw, raw)
        eq("sourceId 透传", out.sourceId, "s1")
        eq("chapterKey 透传", out.chapterKey, "ch1")
    }
    run {
        // 正则规则：去 HTML 残留标签
        val stage = ContentStage(PurifyPipeline())
        val raw = "第一段<br>内容</br>结束"
        val out = stage.prepare("s1", """[{"pattern":"<[^>]+>","replacement":"","isRegex":true}]""", "ch1", raw)
        eq("正则去标签", out.purified, "第一段内容结束")
    }
    run {
        // 全局规则 + 源级规则合成（正文须足够长，避免触发净化器的比例回退保护）
        val glob = listOf(r("（本章完）"))
        val stage = ContentStage(PurifyPipeline(globalRules = glob))
        val raw = "这是一段足够长的正常正文内容盗版必究（本章完）"
        val out = stage.prepare("s1", rules("盗版必究" to ""), "ch1", raw)
        eq("全局+源级合成净化", out.purified, "这是一段足够长的正常正文内容")
    }

    // ================= 二、段落拆分 =================
    run {
        val stage = ContentStage(PurifyPipeline())
        val raw = "第一段内容\n\n第二段内容\n第三段内容"
        val out = stage.prepare("s1", null, "ch1", raw)
        eq("三段拆分", out.paragraphs.size, 3)
        eq("首段内容", out.paragraphs[0], "第一段内容")
        eq("末段内容", out.paragraphs[2], "第三段内容")
        check("空行被滤除", out.paragraphs.none { it.isEmpty() })
    }
    run {
        // 净化归一化后的空白：全角空格、行尾空白、连续空行
        val stage = ContentStage(PurifyPipeline())
        val raw = "段一\u3000\n段二   \n\n\n段三"
        val out = stage.prepare("s1", null, "ch1", raw)
        eq("归一化后三段", out.paragraphs.size, 3)
        eq("全角空格已归一", out.paragraphs[0], "段一")
        eq("行尾空白已去", out.paragraphs[1], "段二")
    }

    // ================= 三、结果缓存：二次打开不重复净化 =================
    run {
        val stage = ContentStage(PurifyPipeline())
        val raw = "正文广告内容"
        val first = stage.prepare("s1", rules("广告" to ""), "ch1", raw)
        val second = stage.prepare("s1", rules("广告" to ""), "ch1", raw)
        check("首次未命中", !first.fromCache)
        check("二次命中缓存", second.fromCache)
        eq("二次结果一致", second.purified, first.purified)
        eq("二次段落一致", second.paragraphs, first.paragraphs)
        eq("二次统计一致", second.stats, first.stats)
        eq("缓存条目为 1", stage.cachedCount, 1)
    }
    run {
        // 同源不同章：各自缓存
        val stage = ContentStage(PurifyPipeline())
        stage.prepare("s1", rules("广告" to ""), "ch1", "正文广告一")
        stage.prepare("s1", rules("广告" to ""), "ch2", "正文广告二")
        val again1 = stage.prepare("s1", rules("广告" to ""), "ch1", "正文广告一")
        val again2 = stage.prepare("s1", rules("广告" to ""), "ch2", "正文广告二")
        check("ch1 命中", again1.fromCache)
        check("ch2 命中", again2.fromCache)
        eq("两条缓存", stage.cachedCount, 2)
        eq("ch1 内容正确", again1.purified, "正文一")
        eq("ch2 内容正确", again2.purified, "正文二")
    }

    // ================= 四、缓存失效：规则变更后重跑 =================
    run {
        val stage = ContentStage(PurifyPipeline())
        val raw = "正文广告内容"
        val a = stage.prepare("s1", rules("广告" to ""), "ch1", raw)
        // 改规则：把"广告"换成"内容"——replaceRuleRaw 变了，指纹变，必须重跑
        val b = stage.prepare("s1", rules("内容" to ""), "ch1", raw)
        check("规则变更后未命中", !b.fromCache)
        eq("新规则生效", b.purified, "正文广告")
        check("规则变更前后结果不同", a.purified != b.purified)
        eq("仍只一条缓存（同 key 覆盖）", stage.cachedCount, 1)
    }
    run {
        // 全局规则不同 → 指纹不同 → 不命中
        val s1 = ContentStage(PurifyPipeline(globalRules = listOf(r("水印"))))
        val s2 = ContentStage(PurifyPipeline(globalRules = listOf(r("后缀"))))
        val raw = "正文水印后缀"
        val a = s1.prepare("src", null, "ch", raw)
        val b = s2.prepare("src", null, "ch", raw)
        eq("全局去水印", a.purified, "正文后缀")
        eq("全局去后缀", b.purified, "正文水印")
        check("两条结果不同（全局规则参与）", a.purified != b.purified)
    }

    // ================= 五、invalidate / clear =================
    run {
        val stage = ContentStage(PurifyPipeline())
        stage.prepare("s1", rules("广告" to ""), "ch1", "正文广告一")
        stage.prepare("s1", rules("广告" to ""), "ch2", "正文广告二")
        eq("清理前两条", stage.cachedCount, 2)
        stage.invalidate("ch1")
        eq("invalidate 后剩一条", stage.cachedCount, 1)
        val again = stage.prepare("s1", rules("广告" to ""), "ch1", "正文广告一")
        check("invalidate 后重跑", !again.fromCache)
        check("ch2 仍在缓存",
            stage.prepare("s1", rules("广告" to ""), "ch2", "正文广告二").fromCache)
    }
    run {
        val stage = ContentStage(PurifyPipeline())
        stage.prepare("s1", null, "a", "x")
        stage.prepare("s1", null, "b", "y")
        stage.clear()
        eq("clear 后为空", stage.cachedCount, 0)
    }

    // ================= 六、FIFO 淘汰 =================
    run {
        val stage = ContentStage(PurifyPipeline(), maxCachedChapters = 2)
        stage.prepare("s", null, "a", "内容A")
        stage.prepare("s", null, "b", "内容B")
        eq("到达上限", stage.cachedCount, 2)
        stage.prepare("s", null, "c", "内容C")
        eq("超限后仍为 2", stage.cachedCount, 2)
        // a 最早插入，应被淘汰
        val againA = stage.prepare("s", null, "a", "内容A")
        check("a 被淘汰后重跑", !againA.fromCache)
        // 重插 a 时淘汰了当时的最旧条目 b，c 仍在缓存
        val againC = stage.prepare("s", null, "c", "内容C")
        check("c 仍在缓存", againC.fromCache)
    }

    // ================= 七、边界：空内容 / null 规则 =================
    run {
        val stage = ContentStage(PurifyPipeline())
        val out = stage.prepare("s1", null, "ch1", "")
        eq("空正文净化为空", out.purified, "")
        check("空正文无段落", out.paragraphs.isEmpty())
        check("空正文首次未命中", !out.fromCache)
        eq("空正文 stats 默认", out.stats.applied, 0)
        // 二次打开空内容也命中缓存
        val again = stage.prepare("s1", null, "ch1", "")
        check("空正文二次命中", again.fromCache)
    }
    run {
        val stage = ContentStage(PurifyPipeline())
        val raw = "纯正文无规则"
        val out = stage.prepare("s1", null, "ch1", raw)
        // 无规则时只做空白归一，正文不变
        eq("null 规则不改正文", out.purified, raw)
        check("null 规则无应用", out.stats.applied == 0)
    }
    run {
        val stage = ContentStage(PurifyPipeline())
        val out = stage.prepare("s1", rules("广告" to ""), "ch1", "   ")
        eq("纯空白净化为空", out.purified, "")
        check("纯空白无段落", out.paragraphs.isEmpty())
    }

    // ================= 八、stats 透明性 =================
    run {
        val stage = ContentStage(PurifyPipeline())
        val raw = "正文广告一广告二结尾"
        val out = stage.prepare("s1", rules("广告一" to "", "广告二" to ""), "ch1", raw)
        eq("应用规则数", out.stats.applied, 2)
        eq("净化结果", out.purified, "正文结尾")
        check("无失败规则", out.stats.failed == 0)
    }

    // ================= 九、全链路：prepare → 交排版 =================
    run {
        val stage = ContentStage(PurifyPipeline())
        val raw = "甲".repeat(120) + "水印" + "乙".repeat(80)
        val out = stage.prepare("s1", rules("水印" to ""), "ch1", raw)
        check("水印已净化", !out.purified.contains("水印"))
        // 把净化后的段落交给排版
        val contentLen = out.paragraphs.sumOf { it.length }
        val progress = ReadingProgress.initial(listOf(contentLen))
        val settings = ReadingSettings.Default.withFontSize(16f)
        val view = ReadingSession.repaginate(progress, out.paragraphs, settings, 360f, 640f)
        check("分页非空", view.pageCount > 0)
        eq("排版总字数 == 净化后字数", view.doc.totalChars, contentLen)
        // 净化后的偏移落在合法区间
        check("页偏移在净化文本内",
            view.doc.pages.all { it.endOffset <= contentLen })
    }

    // ================= 十、「净化必须在排版前」的证明 =================
    run {
        // 同一份原始正文，两条路径对比：
        // A. 先排版原文（含水印）→ totalChars 含水印字数，偏移被水印撑偏
        // B. 先净化再排版 → totalChars 不含水印，偏移对应真实正文
        val stage = ContentStage(PurifyPipeline())
        val body = "正".repeat(100)
        val raw = body + "水印".repeat(30)
        val settings = ReadingSettings.Default.withFontSize(16f)

        // 路径 A：直接排版原文段落
        val rawParas = listOf(raw)
        val rawProgress = ReadingProgress.initial(listOf(raw.length))
        val rawView = ReadingSession.repaginate(rawProgress, rawParas, settings, 360f, 640f)

        // 路径 B：先净化再排版
        val prepared = stage.prepare("s1", rules("水印" to ""), "ch1", raw)
        val purProgress = ReadingProgress.initial(listOf(prepared.paragraphs.sumOf { it.length }))
        val purView = ReadingSession.repaginate(purProgress, prepared.paragraphs, settings, 360f, 640f)

        eq("原文排版含水印字数", rawView.doc.totalChars, 160)
        eq("净化排版不含水印", purView.doc.totalChars, 100)
        check("净化后排版更短（水印未混入分页）",
            purView.doc.totalChars < rawView.doc.totalChars)
        // 净化后第 100 个字符偏移应是合法的末尾，而原文第 100 个字符落在水印里
        check("净化文本无水印残留", !prepared.purified.contains("水印"))
    }

    // ================= 十一、缓存命中后分页一致 =================
    run {
        val stage = ContentStage(PurifyPipeline())
        val raw = "甲".repeat(200) + "广告" + "乙".repeat(100)
        val rule = rules("广告" to "")
        val settings = ReadingSettings.Default.withFontSize(16f)

        val first = stage.prepare("s1", rule, "ch1", raw)
        val progress = ReadingProgress.initial(listOf(first.paragraphs.sumOf { it.length }))
        val view1 = ReadingSession.repaginate(progress, first.paragraphs, settings, 360f, 640f)

        // 二次打开（命中缓存），用同样的段落重新分页，结果应一致
        val second = stage.prepare("s1", rule, "ch1", raw)
        check("二次命中缓存", second.fromCache)
        val view2 = ReadingSession.repaginate(progress, second.paragraphs, settings, 360f, 640f)

        eq("缓存命中后页数一致", view1.pageCount, view2.pageCount)
        eq("缓存命中后总字数一致", view1.doc.totalChars, view2.doc.totalChars)
        eq("缓存命中后段落一致", first.paragraphs, second.paragraphs)
    }

    // ================= 十二、多段正文端到端 =================
    run {
        val stage = ContentStage(PurifyPipeline(globalRules = listOf(r("（本章完）"))))
        val raw = buildString {
            append("第一章开篇内容写了很多字") // 14
            append("\n\n")
            append("第二章承接上文继续展开本章内容") // 15
            append("\n")
            append("（本章完）")
        }
        val out = stage.prepare("book1", null, "ch-url-1", raw)
        check("全局规则去掉本章完", !out.purified.contains("（本章完）"))
        eq("两段正文", out.paragraphs.size, 2)
        check("段落非空", out.paragraphs.all { it.isNotEmpty() })
        // 排版
        val contentLen = out.paragraphs.sumOf { it.length }
        val progress = ReadingProgress.initial(listOf(contentLen))
        val view = ReadingSession.repaginate(progress, out.paragraphs,
            ReadingSettings.Default.withFontSize(18f), 360f, 640f)
        check("多段排版有页", view.pageCount > 0)
        eq("多段排版总字数", view.doc.totalChars, contentLen)
        // 二次打开命中
        val again = stage.prepare("book1", null, "ch-url-1", raw)
        check("多段二次命中", again.fromCache)
        eq("多段二次段落一致", again.paragraphs, out.paragraphs)
    }

    // ================= 输出 =================
    val outDir = java.io.File("/mnt/work/lanyue30")
    outDir.mkdirs()
    java.io.File(outDir, "out_content.txt").writeText(
        if (failures.isEmpty()) "NONE" else failures.joinToString("\n"), Charsets.UTF_8)
    println("================ T15 正文获取与净化串联 单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
