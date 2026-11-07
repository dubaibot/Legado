import com.lanyue.reader.danmaku.render.DanmakuFilter
import com.lanyue.reader.danmaku.render.DanmakuRenderParams
import com.lanyue.reader.source.model.DanmakuEvent
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0

private fun pass(label: String) {
    passed++
    println("[PASS] " + label)
}

private fun fail(label: String, detail: String) {
    failed++
    println("[FAIL] " + label + " (" + detail + ")")
}

private fun eq(actual: Any?, expected: Any?, label: String) {
    if (actual == expected) pass(label)
    else fail(label, "expected=" + expected + " actual=" + actual)
}

private fun check(label: String, cond: Boolean) {
    if (cond) pass(label)
    else fail(label, "条件不满足")
}

private fun ev(timeMs: Long = 0, mode: Int = 1, text: String = "hello"): DanmakuEvent =
    DanmakuEvent(timeMs, mode, 0xFFFFFF, text, null)

private fun main() {
    println("================ 弹幕过滤层测试 ================")

    // ── 默认渲染参数 ──
    val def = DanmakuRenderParams()
    eq(def.scrollSpeedPxPerSec, 240f, "默认滚动速度")
    eq(def.opacity, 0.9f, "默认不透明度")
    eq(def.fontSizeSp, 15f, "默认字号")
    eq(def.fontScale, 1f, "默认字号缩放")
    eq(def.displayAreaRatio, 1f, "默认显示区高度比")
    eq(def.topReservedDp, 0, "默认顶部保留区")
    eq(def.bottomReservedDp, 40, "默认底部保留区")
    eq(def.maxOnScreen, 200, "默认同屏条数")
    check("默认启用合并重复", def.mergeDuplicates)
    check("默认不屏蔽滚动", !def.blockScroll)
    check("默认不屏蔽顶部", !def.blockTop)
    check("默认不屏蔽底部", !def.blockBottom)
    eq(def.lineSpacing, 1.2f, "默认行高倍数")
    eq(DanmakuRenderParams.DEFAULT, def, "DEFAULT 与无参构造等值")

    // ── accept 基础：默认放行 ──
    val f0 = DanmakuFilter(DanmakuRenderParams())
    check("默认放行滚动弹幕", f0.accept(ev()))
    check("默认放行底部弹幕", f0.accept(ev(mode = 2)))
    check("默认放行顶部弹幕", f0.accept(ev(mode = 3)))

    // ── 空白文本 ──
    check("空白文本被拒", !f0.accept(ev(text = "   ")))
    check("空字符串被拒", !f0.accept(ev(text = "")))
    check("只有空白的换行文本被拒", !f0.accept(ev(text = "\n\t ")))

    // ── 模式挡板 ──
    val fScroll = DanmakuFilter(DanmakuRenderParams(blockScroll = true))
    check("blockScroll 拒滚动", !fScroll.accept(ev()))
    check("blockScroll 不拒底部", fScroll.accept(ev(mode = 2)))
    check("blockScroll 不拒顶部", fScroll.accept(ev(mode = 3)))
    val fTop = DanmakuFilter(DanmakuRenderParams(blockTop = true))
    check("blockTop 拒顶部", !fTop.accept(ev(mode = 3)))
    check("blockTop 不拒滚动", fTop.accept(ev()))
    val fBottom = DanmakuFilter(DanmakuRenderParams(blockBottom = true))
    check("blockBottom 拒底部", !fBottom.accept(ev(mode = 2)))
    check("blockBottom 不拒滚动", fBottom.accept(ev()))

    // ── 关键词挡板 ──
    val fKw = DanmakuFilter(DanmakuRenderParams(blockKeywords = listOf("广告")))
    check("关键词命中被拒", !fKw.accept(ev(text = "点击领广告")))
    check("关键词不命中放行", fKw.accept(ev(text = "这个版本不错")))
    check("关键词忽略大小写", !DanmakuFilter(
        DanmakuRenderParams(blockKeywords = listOf("AD"))
    ).accept(ev(text = "clicked ad")))
    val fMeta = DanmakuFilter(DanmakuRenderParams(blockKeywords = listOf("a.b")))
    check("关键词转义后不误匹配 aXb", fMeta.accept(ev(text = "aXb")))
    check("关键词转义后匹配字面 a.b", !fMeta.accept(ev(text = "a.b")))

    // ── 正则挡板 ──
    val fRegex = DanmakuFilter(DanmakuRenderParams(blockRegexes = listOf("^[A-Z]+\\d+$")))
    check("正则命中被拒", !fRegex.accept(ev(text = "AB123")))
    check("正则不命中放行", fRegex.accept(ev(text = "ab 123")))
    check("非法正则被忽略不崩", DanmakuFilter(
        DanmakuRenderParams(blockRegexes = listOf("[abc"))
    ).accept(ev(text = "x").copy(timeMs = 1)))
    check("非法正则被忽略不崩 #2", DanmakuFilter(
        DanmakuRenderParams(blockRegexes = listOf("x{1,2", "ok"))
    ).accept(ev(text = "hello world")))
    check("合法正则仍生效", !DanmakuFilter(
        DanmakuRenderParams(blockRegexes = listOf("x{1,2", "ok"))
    ).accept(ev(text = "hello ok")))

    // ── 空关键词防御（可选校验）──
    val fEmptyKeyword = DanmakuFilter(DanmakuRenderParams(blockKeywords = listOf("")))
    // 空关键词正则会匹配一切，接受空列表是更合理的行为
    check("空关键词不放行全部", !fEmptyKeyword.accept(ev()))

    // ── 合并去重 ──
    val dup = DanmakuEvent(1000, 1, 0xFFFFFF, "hello")
    val dup2 = DanmakuEvent(1000, 1, 0xFFFFFF, "hello")
    val diff = DanmakuEvent(1000, 1, 0xFFFFFF, "world")
    val fDupOn = DanmakuFilter(DanmakuRenderParams(mergeDuplicates = true))
    eq(fDupOn.dedupe(listOf(dup, dup2)).size, 1, "合并开启：同时间同文本去重")
    eq(fDupOn.dedupe(listOf(dup, diff)).size, 2, "合并开启：不同文本保留")
    eq(fDupOn.dedupe(listOf(dup, dup.copy(text = "hello"))).size, 1, "合并重复直接丢弃")
    val fDupOff = DanmakuFilter(DanmakuRenderParams(mergeDuplicates = false))
    eq(fDupOff.dedupe(listOf(dup, dup2)).size, 2, "合并关闭：重复保留")

    // ── apply：过滤 + 去重 + 排序 ──
    val raw = listOf(
        ev(3000, text = "广告"),
        ev(3000, text = "   "),
        ev(1000, text = "hello"),
        ev(3000, text = "hello")
    )
    val out = DanmakuFilter(DanmakuRenderParams(blockKeywords = listOf("广告"))).apply(raw)
    eq(out.size, 2, "apply 过滤空白与关键词后剩 2 条")
    eq(out[0].timeMs, 1000L, "apply 按时间升序")
    eq(out[1].timeMs, 3000L, "apply 第二条为 3000")
    check("apply 保文本原文", out[1].text == "hello")
    eq(DanmakuFilter(DanmakuRenderParams()).apply(emptyList()).size, 0, "空输入输出空列表")

    // ── 无关键词白名单保留有效弹幕（广告不拦、空白仍滤）──
    val passThrough = DanmakuFilter(DanmakuRenderParams()).apply(raw)
    eq(passThrough.size, 3, "无屏蔽时广告保留、空白过滤剩 3 条")
    check("广告文本被保留", passThrough.any { it.text == "广告" })

    // ── 同屏密度截断（保帧）：保留最早进入的 maxOnScreen 条 ──
    val many = List(10) { i -> ev(i * 1000L, text = "dm$i") }
    val capped = DanmakuFilter(DanmakuRenderParams(maxOnScreen = 3)).apply(many)
    eq(capped.size, 3, "超过同屏上限时截断")
    eq(capped[0].timeMs, 0L, "截断保留最早弹幕")
    eq(capped[2].timeMs, 2000L, "截断保留第 3 条")
    eq(capped.last().text, "dm2", "截断丢弃最新弹幕")
    val below = DanmakuFilter(DanmakuRenderParams(maxOnScreen = 15)).apply(many)
    eq(below.size, 10, "不超过上限时不截断")
    val noCap = DanmakuFilter(DanmakuRenderParams(maxOnScreen = 0)).apply(many)
    eq(noCap.size, 10, "maxOnScreen=0 视为不限制")

    println("================ 弹幕过滤层测试结束 ================")
    println("Pass: $passed, Fail: $failed")
    if (failed > 0) exitProcess(1)
    exitProcess(0)
}
