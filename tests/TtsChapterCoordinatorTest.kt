import com.lanyue.reader.core.tts.TtsChapterCoordinator
import com.lanyue.reader.core.tts.AutoContinue
import com.lanyue.reader.core.tts.AdvanceOutcome
import com.lanyue.reader.core.tts.ChapterProvider
import com.lanyue.reader.core.tts.TtsReadController
import com.lanyue.reader.core.tts.TtsSegment
import com.lanyue.reader.core.tts.TtsSegmenter
import com.lanyue.reader.core.tts.TtsState

private var pass = 0
private val failures = ArrayList<String>()
private val outFile = java.io.File("/mnt/work/lanyue31/out_tts_coord.txt")

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

/** 12 字一句，配合小上限（20）即可验证逐句切分。 */
private val S1 = "第一句话内容比较长一些。"
private val S2 = "第二句话内容同样比较长。"
private val S3 = "第三句话内容也是比较长。"
private val S4 = "第四句话内容依然比较长。"

private val seg = TtsSegmenter(20)

/** 内存章节提供者 */
private class ListProvider(private val texts: List<String>) : ChapterProvider {
    override fun chapterCount(): Int = texts.size
    override fun chapterText(index: Int): String = texts.getOrElse(index) { "" }
}

/** 记录送进引擎的文本 */
private class SpeakRecorder : (TtsSegment) -> Unit {
    val spoken = ArrayList<String>()
    override fun invoke(s: TtsSegment) { spoken += s.text }
}

fun main() {

    // ============ 1. 基本装载与续读（auto-continue ENABLED，默认） ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1 + S2, S3 + S4)))
        eq("初始 IDLE", coord.state, TtsState.IDLE)
        eq("初始章号 -1", coord.currentChapter, -1)
        ok("装载第一章", coord.startChapter(0))
        eq("进入 SPEAKING", coord.state, TtsState.SPEAKING)
        eq("当前章号", coord.currentChapter, 0)
        eq("已送第一段", rec.spoken.size, 1)
        eq("送的是 S1", rec.spoken[0], S1)
        // 推进到第二段
        val r1 = coord.onSegmentFinished()
        eq("章内推进", r1, AdvanceOutcome.NEXT_SEGMENT)
        eq("已送第二段", rec.spoken.size, 2)
        eq("送的是 S2", rec.spoken[1], S2)
        // 读完第一章 → 自动续读第二章
        val r2 = coord.onSegmentFinished()
        eq("续读下一章", r2, AdvanceOutcome.CONTINUED)
        eq("跳到第二章", coord.currentChapter, 1)
        eq("续读后仍在 SPEAKING", coord.state, TtsState.SPEAKING)
        eq("续读送了第三章第一段", rec.spoken.size, 3)
        eq("送的是 S3", rec.spoken[2], S3)
    }

    // ============ 2. 续读关闭（读完停 FINISHED） ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(
            c, ListProvider(listOf(S1 + S2, S3)),
            autoContinue = AutoContinue.DISABLED
        )
        coord.startChapter(0)
        coord.onSegmentFinished() // 推进到第二段
        val r = coord.onSegmentFinished() // 读完
        eq("续读关闭返回 STOPPED", r, AdvanceOutcome.STOPPED)
        eq("停在 FINISHED", coord.state, TtsState.FINISHED)
        eq("未续读仍在一章", coord.currentChapter, 0)
        ok("后面有章可读", coord.hasNextChapter())
    }

    // ============ 3. 全书读完（最后一章读完 → BOOK_DONE） ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1 + S2)))
        coord.startChapter(0)
        coord.onSegmentFinished()
        val r = coord.onSegmentFinished()
        eq("全书读完返回 BOOK_DONE", r, AdvanceOutcome.BOOK_DONE)
        eq("停在 FINISHED", coord.state, TtsState.FINISHED)
        ok("后面无章", !coord.hasNextChapter())
    }

    // ============ 4. 空章自动跳过（startChapter 跳过空章） ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        // 第 0 章空，第 1 章空白，第 2 章有内容
        val coord = TtsChapterCoordinator(c, ListProvider(listOf("", "   ", S1 + S2)))
        ok("startChapter 成功", coord.startChapter(0))
        eq("跳到第二章", coord.currentChapter, 2)
        eq("进入 SPEAKING", coord.state, TtsState.SPEAKING)
        eq("读了第一段", rec.spoken.size, 1)
    }

    // ============ 5. 续读时遇到空章自动跳过 ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        // 第 0 章有内容，第 1 章空，第 2 章有内容
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1, "", S2)))
        coord.startChapter(0)
        val r = coord.onSegmentFinished() // 读完第 0 章 → 续读
        eq("续读跳过空章", r, AdvanceOutcome.CONTINUED)
        eq("跳到第二章", coord.currentChapter, 2)
        eq("续读送了第二章的段", rec.spoken.last(), S2)
    }

    // ============ 6. 点正文起读（startFromPosition with charOffset） ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val t = S1 + S2 + S3
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(t)))
        ok("从偏移起读", coord.startFromPosition(0, S1.length + 3))
        eq("定位到第二段", coord.progress.segmentIndex, 1)
        eq("读了第二段", rec.spoken.last(), S2)
    }

    // ============ 7. 高亮区间数据 ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val t = S1 + S2 + S3
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(t)))
        coord.startChapter(0)
        val h0 = coord.highlightRange()
        ok("高亮区间非空", h0 != null)
        eq("首段起点", h0!!.first, 0)
        eq("首段末位", h0.last, S1.length - 1)
        ok("区间可回切原文", t.substring(h0.first, h0.last + 1) == S1)
        // 推进后高亮跟着移动
        coord.onSegmentFinished()
        val h1 = coord.highlightRange()
        ok("第二段高亮非空", h1 != null)
        eq("第二段起点", h1!!.first, S1.length)
        ok("高亮区间移动了", h1.first != h0.first)
        ok("第二段可回切原文", t.substring(h1.first, h1.last + 1) == S2)
    }

    // ============ 8. 迟到回调安全（停止后返回 IGNORED） ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1 + S2, S3)))
        coord.startChapter(0)
        coord.stop()
        eq("停止后 IDLE", coord.state, TtsState.IDLE)
        val r = coord.onSegmentFinished()
        eq("停止后回调 IGNORED", r, AdvanceOutcome.IGNORED)
        eq("没有续读", coord.currentChapter, -1)
    }

    // ============ 9. 暂停后不触发续读 ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1 + S2, S3)))
        coord.startChapter(0)
        coord.onSegmentFinished() // 推进到第二段
        coord.pause()
        eq("暂停态", coord.state, TtsState.PAUSED)
        val r = coord.onSegmentFinished()
        eq("暂停后回调 IGNORED", r, AdvanceOutcome.IGNORED)
        eq("未续读仍在一章", coord.currentChapter, 0)
    }

    // ============ 10. 多章连续续读（3 章逐章读完） ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1, S2, S3)))
        coord.startChapter(0)
        // 第 0 章 1 段
        var r = coord.onSegmentFinished()
        eq("第 0 章读完续读", r, AdvanceOutcome.CONTINUED)
        eq("跳到第 1 章", coord.currentChapter, 1)
        // 第 1 章 1 段
        r = coord.onSegmentFinished()
        eq("第 1 章读完续读", r, AdvanceOutcome.CONTINUED)
        eq("跳到第 2 章", coord.currentChapter, 2)
        // 第 2 章 1 段 → 全书完
        r = coord.onSegmentFinished()
        eq("第 2 章全书完", r, AdvanceOutcome.BOOK_DONE)
        eq("停在 FINISHED", coord.state, TtsState.FINISHED)
    }

    // ============ 11. hasNextChapter 判断 ============
    run {
        val c = TtsReadController(seg)
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1, S2, S3)))
        // 未装载时 currentChapter = -1
        ok("未装载时有下一章", coord.hasNextChapter())
        coord.startChapter(0)
        ok("第一章后有下一章", coord.hasNextChapter())
        // 跳到最后一章
        coord.startChapter(2)
        ok("最后一章后无下一章", !coord.hasNextChapter())
    }

    // ============ 12. hasNextChapter 跳过尾部空章 ============
    run {
        val c = TtsReadController(seg)
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1, "", "")))
        coord.startChapter(0)
        ok("后面只有空章时无下一章", !coord.hasNextChapter())
    }

    // ============ 13. 空书（chapterCount=0） ============
    run {
        val c = TtsReadController(seg)
        val coord = TtsChapterCoordinator(c, ListProvider(emptyList()))
        ok("空书 startChapter 失败", !coord.startChapter(0))
        eq("空书仍 IDLE", coord.state, TtsState.IDLE)
        ok("空书无下一章", !coord.hasNextChapter())
    }

    // ============ 14. 单章书（读完即全书完） ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1)))
        coord.startChapter(0)
        val r = coord.onSegmentFinished()
        eq("单章书全书完", r, AdvanceOutcome.BOOK_DONE)
        ok("单章书无下一章", !coord.hasNextChapter())
    }

    // ============ 15. startChapter 越界 ============
    run {
        val c = TtsReadController(seg)
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1, S2)))
        ok("越界 startChapter 失败", !coord.startChapter(5))
        eq("越界后仍 IDLE", coord.state, TtsState.IDLE)
    }

    // ============ 16. 续读从头开始（charOffset 不跨章） ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        // 第 0 章 2 段，第 1 章 2 段
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1 + S2, S3 + S4)))
        // 从第 0 章的第二段起读
        coord.startFromPosition(0, S1.length)
        eq("起读在第二段", coord.progress.segmentIndex, 1)
        // 读完第 0 章第二段 → FINISHED → 自动续读第 1 章
        val r = coord.onSegmentFinished()
        eq("续读第 1 章", r, AdvanceOutcome.CONTINUED)
        eq("续读从头开始（第一段）", coord.progress.segmentIndex, 0)
        eq("续读读的是第 1 章第一段", rec.spoken.last(), S3)
    }

    // ============ 17. 空白章节视为空章 ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf("  \n\t ", S1)))
        ok("空白章被跳过", coord.startChapter(0))
        eq("跳到第一章", coord.currentChapter, 1)
        eq("进入 SPEAKING", coord.state, TtsState.SPEAKING)
    }

    // ============ 18. 续读时后面全是空章 → BOOK_DONE ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1, "", "")))
        coord.startChapter(0)
        val r = coord.onSegmentFinished()
        eq("后面全空返回 BOOK_DONE", r, AdvanceOutcome.BOOK_DONE)
    }

    // ============ 19. play/pause/stop 透传 ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1 + S2)))
        coord.startChapter(0)
        eq("SPEAKING", coord.state, TtsState.SPEAKING)
        coord.pause()
        eq("PAUSED", coord.state, TtsState.PAUSED)
        coord.play()
        eq("恢复 SPEAKING", coord.state, TtsState.SPEAKING)
        coord.stop()
        eq("STOP -> IDLE", coord.state, TtsState.IDLE)
    }

    // ============ 20. skipToNext/PreviousSegment 透传 ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1 + S2 + S3 + S4)))
        coord.startChapter(0)
        eq("四段", coord.progress.totalSegments, 4)
        coord.skipToNextSegment()
        eq("跳到第二段", coord.progress.segmentIndex, 1)
        coord.skipToPreviousSegment()
        eq("跳回第一段", coord.progress.segmentIndex, 0)
    }

    // ============ 21. 高亮在无段时返回 null ============
    run {
        val c = TtsReadController(seg)
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1)))
        eq("未装载无高亮", coord.highlightRange(), null)
    }

    // ============ 22. 续读后高亮正确映射新章 ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1, S2)))
        coord.startChapter(0)
        coord.onSegmentFinished() // 读完第 0 章 → 续读第 1 章
        val h = coord.highlightRange()
        ok("续读后高亮非空", h != null)
        eq("新章高亮从头开始", h!!.first, 0)
        eq("新章高亮末位", h.last, S2.length - 1)
        ok("高亮区间可回切新章原文", S2.substring(h.first, h.last + 1) == S2)
    }

    // ============ 23. 从全书末尾的空章 startChapter ============
    run {
        val c = TtsReadController(seg)
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1, "")))
        ok("从空章 startChapter 失败", !coord.startChapter(1))
        eq("仍 IDLE", coord.state, TtsState.IDLE)
    }

    // ============ 24. 进度章节号与协调器一致 ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1 + S2, S3 + S4, S1)))
        coord.startChapter(1)
        eq("进度章号 = 协调器章号", coord.progress.chapterIndex, coord.currentChapter)
        coord.onSegmentFinished() // 读完第 1 章 → 续读第 2 章
        eq("续读后进度章号 = 协调器章号", coord.progress.chapterIndex, coord.currentChapter)
    }

    // ============ 25. 续读默认为 ENABLED ============
    run {
        val c = TtsReadController(seg)
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1, S2)))
        eq("默认续读开启", coord.autoContinue, AutoContinue.ENABLED)
    }

    // ============ 26. 负数章节索引 ============
    run {
        val c = TtsReadController(seg)
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1)))
        ok("负索引 startChapter 失败", !coord.startChapter(-1))
        eq("仍 IDLE", coord.state, TtsState.IDLE)
    }

    // ============ 27. 长章多段续读后章号正确 ============
    run {
        val rec = SpeakRecorder()
        val c = TtsReadController(seg, speak = rec, stopSpeaking = {})
        // 第 0 章 4 段，第 1 章 2 段
        val coord = TtsChapterCoordinator(c, ListProvider(listOf(S1 + S2 + S3 + S4, S1 + S2)))
        coord.startChapter(0)
        eq("第 0 章四段", coord.progress.totalSegments, 4)
        // 逐段推进 3 次（段 0→1→2→3）
        repeat(3) { coord.onSegmentFinished() }
        eq("推进到末段", coord.progress.segmentIndex, 3)
        eq("仍在第 0 章", coord.currentChapter, 0)
        // 读完末段 → 续读
        val r = coord.onSegmentFinished()
        eq("续读第 1 章", r, AdvanceOutcome.CONTINUED)
        eq("跳到第 1 章", coord.currentChapter, 1)
        eq("第 1 章两段", coord.progress.totalSegments, 2)
        eq("从头开始", coord.progress.segmentIndex, 0)
    }

    report()
}
