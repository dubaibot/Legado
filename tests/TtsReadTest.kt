import com.lanyue.reader.core.tts.TtsReadController
import com.lanyue.reader.core.tts.TtsSegmenter
import com.lanyue.reader.core.tts.TtsState

private var pass = 0
private val failures = ArrayList<String>()
private val outFile = java.io.File("/mnt/work/lanyue22/out_tts.txt")

private fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++ else failures += "$name\n  期望: $expected\n  实际: $actual"
}

private fun ok(name: String, cond: Boolean) {
    if (cond) pass++ else failures += name
}

private fun report() {
    println("PASS = $pass")
    println("FAIL = ${failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEachIndexed { i, f -> println("${i + 1}. $f") }
        outFile.writeText(failures.joinToString("\n\n"))
        kotlin.system.exitProcess(1)
    }
}

/** 12 字一句，配合小上限即可验证逐句切分。 */
private val S1 = "第一句话内容比较长一些。"
private val S2 = "第二句话内容同样比较长。"
private val S3 = "第三句话内容也是比较长。"
private val S4 = "第四句话内容依然比较长。"

/** 默认上限（400）用于验证合并行为。 */
private val segDef = TtsSegmenter()
/** 小上限（20）用于验证逐句切分：12 字一句可独立成段，两句 24 字超限。 */
private val seg = TtsSegmenter(20)

fun main() {
    // ============ 1. 规范化必须等长（否则偏移全错位） ============
    run {
        val src = "第一段\u3000有全角空格\r\n第二段\u00A0有窄空格"
        val norm = TtsSegmenter.normalize(src)
        eq("规范化不改变长度", norm.length, src.length)
        ok("全角空格转半角", norm.contains("第一段 有全角"))
        ok("全角空格已替换", !norm.contains('\u3000'))
        ok("回车已转换行", !norm.contains('\r'))
        ok("窄空格已替换", !norm.contains('\u00A0'))
        eq("空串规范化仍为空", TtsSegmenter.normalize(""), "")
        val pure = "完全没有特殊字符的正文"
        eq("普通文本规范化不变", TtsSegmenter.normalize(pure), pure)
    }

    // ============ 2. 逐句切分与偏移 ============
    run {
        val t = S1 + S2 + S3
        val s = seg.segment(t)
        eq("三句切成三段", s.size, 3)
        eq("段0 文本", s[0].text, S1)
        eq("段1 文本", s[1].text, S2)
        eq("段2 文本", s[2].text, S3)
        eq("段索引从 0 连续", s.map { it.index }, listOf(0, 1, 2))
        eq("段0 起点", s[0].charStart, 0)
        eq("段0 终点", s[0].charEnd, S1.length)
        eq("段1 起点接续段0", s[1].charStart, S1.length)
        eq("段长等于文本长", s[0].length, s[0].text.length)
        eq("拼接还原原文", s.joinToString("") { it.text }, t)
        ok("偏移可回切原文", s.all { t.substring(it.charStart, it.charEnd) == it.text })
    }

    // ============ 3. 引号内的句号不切 ============
    run {
        val t = "他说：「你先走。我随后就来。」然后转身。"
        val s = TtsSegmenter(18).segment(t)
        eq("台词不被劈开", s.size, 2)
        eq("第一段完整保留台词", s[0].text, "他说：「你先走。我随后就来。」")
        eq("第二段是叙述", s[1].text, "然后转身。")
        ok("引号内未切开", s[0].text.contains("你先走。我随后就来"))
        eq("拼接还原", s.joinToString("") { it.text }, t)
    }

    // ============ 4. 右引号并入前段，不单独成段 ============
    run {
        val t = "「走吧。」他说。"
        val s = TtsSegmenter(6).segment(t)
        ok("不产生独立的右引号段", s.none { it.text.trim() == "」" })
        eq("台词段保留完整右引号", s[0].text, "「走吧。」")
        ok("右引号并入前段", s[0].text.endsWith("」"))
        eq("拼接还原", s.joinToString("") { it.text }, t)
    }

    // ============ 5. 空与纯空白不产生段 ============
    run {
        eq("空串无段", segDef.segment("").size, 0)
        eq("纯空白无段", segDef.segment("   \n\t  ").size, 0)
        eq("全角空格无段", segDef.segment("\u3000\u3000").size, 0)
    }

    // ============ 6. 无标点超长硬切 ============
    run {
        val long = "一".repeat(1000)
        val s = TtsSegmenter(100).segment(long)
        eq("切成 10 段", s.size, 10)
        ok("每段不超上限", s.all { it.length <= 100 })
        eq("拼接还原全文", s.joinToString("") { it.text }.length, 1000)
        eq("末段索引正确", s.last().index, s.size - 1)
        ok("偏移连续", s.zipWithNext().all { (a, b) -> a.charEnd == b.charStart })
    }

    // ============ 7. 超长优先在逗号处断 ============
    run {
        val body = "甲".repeat(45) + "，" + "乙".repeat(45) + "，" + "丙".repeat(45) + "。"
        val s = TtsSegmenter(60).segment(body)
        eq("切成三段", s.size, 3)
        ok("每段不超上限", s.all { it.length <= 60 })
        ok("非末段以逗号结尾", s.dropLast(1).all { it.text.endsWith("，") })
        eq("拼接还原", s.joinToString("") { it.text }, body)
    }

    // ============ 8. 短句合并以减少引擎调用 ============
    run {
        val t = "短句一。短句二。短句三。短句四。"
        val s = segDef.segment(t)
        eq("短句合并为一段", s.size, 1)
        eq("合并后文本完整", s[0].text, t)
        eq("合并段长度", s[0].length, 16)
    }

    // ============ 9. 段首不留空白 ============
    run {
        val t = "第一句。\n\n  第二句。"
        val s = segDef.segment(t)
        ok("段首无前导空白", s.none { it.text.firstOrNull()?.isWhitespace() == true })
        ok("仍有内容", s.isNotEmpty())
        eq("拼接还原（空白被包含在段内）", s.joinToString("") { it.text }, t)
    }

    // ============ 10. 控制器：装载与状态机 ============
    run {
        val spoken = ArrayList<String>()
        val c = TtsReadController(seg, speak = { spoken += it.text }, stopSpeaking = {})
        eq("初始 IDLE", c.state, TtsState.IDLE)
        eq("初始无章节", c.currentChapter, -1)
        eq("初始无当前段", c.currentSegment, null)
        c.load(0, S1 + S2)
        eq("装载后 PAUSED", c.state, TtsState.PAUSED)
        eq("章节号正确", c.currentChapter, 0)
        eq("定位第一段", c.currentSegment?.index, 0)
        eq("装载不自动发声", spoken.size, 0)
        c.play()
        eq("进入播放中", c.state, TtsState.SPEAKING)
        eq("已送第一段", spoken.size, 1)
        eq("读出第一句", spoken[0], S1)
    }

    // ============ 11. 逐段推进到读完 ============
    run {
        val spoken = ArrayList<String>()
        val c = TtsReadController(seg, speak = { spoken += it.text })
        c.load(0, S1 + S2 + S3, autoPlay = true)
        eq("自动进入播放", c.state, TtsState.SPEAKING)
        eq("首段已发声", spoken.size, 1)
        c.onSegmentFinished()
        eq("推进到第二段", spoken.size, 2)
        c.onSegmentFinished()
        c.onSegmentFinished()
        eq("三段全部读完", spoken.size, 3)
        eq("读完变 FINISHED", c.state, TtsState.FINISHED)
        eq("进度停在末段", c.progress.segmentIndex, 2)
    }

    // ============ 12. 停止/暂停后的迟到回调必须忽略 ============
    run {
        val spoken = ArrayList<String>()
        val c = TtsReadController(seg, speak = { spoken += it.text })
        c.load(0, S1 + S2 + S3, autoPlay = true)
        eq("播了第一段", spoken.size, 1)
        c.pause()
        eq("暂停态", c.state, TtsState.PAUSED)
        val before = spoken.size
        c.onSegmentFinished()
        eq("暂停后迟到回调不推进", spoken.size, before)
        eq("仍停在第一段", c.progress.segmentIndex, 0)
        c.stop()
        eq("停止回 IDLE", c.state, TtsState.IDLE)
        c.onSegmentFinished()
        eq("停止后回调被忽略", spoken.size, before)
        eq("停止后章节清空", c.currentChapter, -1)
        c.onSegmentFinished()
        c.play()
        eq("清空后播放无动作", c.state, TtsState.IDLE)
    }

    // ============ 13. 暂停后从当前段续读，不从头开始 ============
    run {
        val spoken = ArrayList<String>()
        val c = TtsReadController(seg, speak = { spoken += it.text })
        c.load(0, S1 + S2 + S3, autoPlay = true)
        c.onSegmentFinished()
        eq("已读两段", spoken.size, 2)
        c.pause()
        val cursorAtPause = c.progress.segmentIndex
        eq("暂停停在第二段", cursorAtPause, 1)
        c.play()
        eq("恢复仍是第二段（重播当前段）", c.progress.segmentIndex, cursorAtPause)
        eq("恢复后读的是第二段", spoken.last(), S2)
        eq("第一段未被重读", spoken.count { it == S1 }, 1)
    }

    // ============ 14. 空内容不进入 SPEAKING ============
    run {
        val c = TtsReadController(seg)
        c.load(3, "   ")
        eq("空章节保持 IDLE", c.state, TtsState.IDLE)
        eq("空章节无段", c.progress.totalSegments, 0)
        eq("空章节比例 0", c.progress.ratio, 0f)
        ok("hasContent 为假", !c.progress.hasContent)
        c.play()
        eq("空章节播放无效", c.state, TtsState.IDLE)
    }

    // ============ 15. 跳段边界 ============
    run {
        val spoken = ArrayList<String>()
        val c = TtsReadController(seg, speak = { spoken += it.text })
        c.load(0, S1 + S2 + S3 + S4, autoPlay = true)
        eq("共四段", c.progress.totalSegments, 4)
        c.skipToNext()
        eq("跳过到第二段", spoken.last(), S2)
        c.skipToPrevious()
        eq("回退到第一段", spoken.last(), S1)
        c.skipToPrevious()
        eq("首段再回退仍读首段", spoken.last(), S1)
        repeat(5) { c.skipToNext() }
        eq("一直跳过末段变 FINISHED", c.state, TtsState.FINISHED)
        eq("末段索引不越界", c.progress.segmentIndex, 3)
    }

    // ============ 16. 按字符偏移定位起读 ============
    run {
        val t = S1 + S2 + S3
        val c = TtsReadController(seg)
        c.load(0, t, startCharOffset = 0)
        eq("偏移 0 落在首段", c.currentSegment?.index, 0)
        c.load(0, t, startCharOffset = S1.length + 3)
        eq("段内偏移落在第二段", c.currentSegment?.index, 1)
        eq("定位段文本正确", c.currentSegment?.text, S2)
        c.load(0, t, startCharOffset = 99999)
        eq("越界落到末段", c.currentSegment?.index, 2)
    }

    // ============ 17. 语速音调夹取而非抛异常 ============
    run {
        val c = TtsReadController(seg)
        eq("默认语速", c.rate, 1.0f)
        eq("默认音调", c.pitch, 1.0f)
        c.rate = 5.0f
        eq("语速超上限被夹取", c.rate, TtsReadController.MAX_RATE)
        c.rate = 0.1f
        eq("语速超下限被夹取", c.rate, TtsReadController.MIN_RATE)
        c.pitch = 99.0f
        eq("音调超上限被夹取", c.pitch, TtsReadController.MAX_PITCH)
        c.pitch = 0.0f
        eq("音调超下限被夹取", c.pitch, TtsReadController.MIN_PITCH)
    }

    // ============ 18. 读完后播放为重听本章 ============
    run {
        val spoken = ArrayList<String>()
        val c = TtsReadController(seg, speak = { spoken += it.text })
        c.load(0, S1 + S2, autoPlay = true)
        c.onSegmentFinished()
        eq("读完两段前仍在播放", c.state, TtsState.SPEAKING)
        c.onSegmentFinished()
        eq("已读完", c.state, TtsState.FINISHED)
        c.play()
        eq("重听从头开始", spoken.last(), S1)
        eq("回到 SPEAKING", c.state, TtsState.SPEAKING)
    }

    // ============ 19. 进度比例 ============
    run {
        val c = TtsReadController(seg)
        c.load(0, S1 + S2 + S3 + S4)
        eq("起读比例 1/4", c.progress.ratio, 0.25f)
        c.skipToNext()
        eq("第二段比例 1/2", c.progress.ratio, 0.5f)
        c.skipToNext(); c.skipToNext()
        eq("末段比例 1.0", c.progress.ratio, 1.0f)
        ok("比例不越界", c.progress.ratio in 0f..1f)
        eq("偏移等于段起点", c.progress.charOffset, c.currentSegment?.charStart)
    }

    // ============ 20. 端到端：偏移回对原文 ============
    run {
        val t = S1 + S2 + S3
        val c = TtsReadController(seg)
        c.load(0, TtsSegmenter.normalize(t), autoPlay = true)
        var guard = 0
        while (c.state == TtsState.SPEAKING && guard++ < 10) c.onSegmentFinished()
        eq("循环推进后读完", c.state, TtsState.FINISHED)
        ok("循环有界未死锁", guard <= 10)
        val all = c.currentSegment
        eq("末段文本正确", all?.text, S3)
        ok("末段偏移可回切原文", all != null && t.substring(all.charStart, all.charEnd) == all.text)
    }

    report()
}
