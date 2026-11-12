package com.lanyue.reader.core.tts

/**
 * 章节续读策略。
 *
 * - [ENABLED]：读完一章自动加载下一章继续朗读（默认）。
 * - [DISABLED]：读完一章停在 FINISHED，等用户手动翻章。
 *
 * 默认设为 ENABLED 是因为朗读场景下用户通常希望连续听完整本书，
 * 就像听有声书一样——读完一章还要手动点「下一章」会打断通勤、做家务时的
 * 连续收听体验。
 */
enum class AutoContinue { ENABLED, DISABLED }

/**
 * [TtsChapterCoordinator.onSegmentFinished] 的返回值，让上层知道发生了什么。
 *
 * 上层据此决定 UI 反馈（如显示「本章读完」「全书读完」提示）。
 */
enum class AdvanceOutcome {
    /** 章内正常推进到下一段。 */
    NEXT_SEGMENT,
    /** 本章读完，已自动续读下一章。 */
    CONTINUED,
    /** 本章读完，未续读（开关关闭）。 */
    STOPPED,
    /** 全书读完（后面没有可读内容了）。 */
    BOOK_DONE,
    /** 回调被忽略（非 SPEAKING 态，迟到回调）。 */
    IGNORED
}

/**
 * 章节内容提供者。
 *
 * 由上层注入实现——真实环境从数据库/网络取章节正文，
 * 测试环境用内存 Map 模拟。这样协调器的全部逻辑可在 JVM 单测里跑。
 */
interface ChapterProvider {
    /** 章节总数。 */
    fun chapterCount(): Int
    /** 取指定章节正文；空串或空白视为无内容。 */
    fun chapterText(index: Int): String
}

/**
 * 章节续读协调器：把单章 [TtsReadController] 扩展为多章连续朗读。
 *
 * 职责边界：
 * - **只管「读完了该干嘛」**：onSegmentFinished → 检查 FINISHED → 续读/停住/全书完。
 * - **不管引擎细节**：speak/stop 仍由 controller 的 lambda 承接，协调器不碰 TextToSpeech。
 * - **不管 UI 渲染**：只提供高亮区间数据（[highlightRange]），绘制是 Compose 的事。
 *
 * 守住的约束：
 * - **迟到回调安全**：只在 controller 处于 SPEAKING 时才推进，停止/暂停后的迟到
 *   onDone 不会触发续读（否则会出现「停了又自动念下一章」）。
 * - **空章跳过**：遇到无内容的章节自动跳到下一章（不论是否开启续读），
 *   否则用户点朗读却没声音，以为功能坏了。
 * - **续读从头开始**：自动续读下一章时 charOffset=0，不从上一章的偏移延续——
 *   章与章之间的正文是独立的，偏移不能跨章。
 */
class TtsChapterCoordinator(
    private val controller: TtsReadController,
    private val provider: ChapterProvider,
    var autoContinue: AutoContinue = AutoContinue.ENABLED
) {
    /** 当前朗读章节索引，未装载为 -1。 */
    val currentChapter: Int get() = controller.currentChapter

    /** 当前朗读状态。 */
    val state: TtsState get() = controller.state

    /** 当前朗读进度。 */
    val progress: TtsProgress get() = controller.progress

    /** 后面还有没有可读章节。 */
    fun hasNextChapter(): Boolean {
        var i = currentChapter + 1
        while (i < provider.chapterCount()) {
            if (provider.chapterText(i).isNotBlank()) return true
            i++
        }
        return false
    }

    /**
     * 当前朗读段在原文中的字符区间，供 UI 高亮。
     *
     * 返回 [charStart, charEnd) 的左闭右开区间；无段时返回 null。
     * UI 拿到区间后把它映射到 Compose 文本 layout 的像素范围绘制高亮背景。
     */
    fun highlightRange(): IntRange? {
        val seg = controller.currentSegment ?: return null
        return seg.charStart until seg.charEnd
    }

    /**
     * 开始朗读指定章节。
     *
     * 如果该章无内容，自动跳到下一章（跳过的章不算朗读过）。
     * 跳章时 charOffset 归零——偏移只对首个目标章有效。
     *
     * @return true 表示成功装载并开始朗读；false 表示从该章往后都没有可读内容。
     */
    fun startChapter(chapterIndex: Int, charOffset: Int = 0): Boolean {
        if (chapterIndex < 0) return false
        var idx = chapterIndex
        var offset = charOffset
        while (idx < provider.chapterCount()) {
            val text = provider.chapterText(idx)
            if (text.isNotBlank()) {
                controller.load(idx, text, offset, autoPlay = true)
                return true
            }
            idx++
            offset = 0
        }
        return false
    }

    /**
     * 点正文起读：从指定章节的指定字符偏移开始朗读。
     *
     * 对接阅读页「点正文任意位置 → 从该处起读」——上层把点击位置转换为
     * 章号与章内字符偏移传进来即可。
     */
    fun startFromPosition(chapterIndex: Int, charOffset: Int): Boolean =
        startChapter(chapterIndex, charOffset)

    /**
     * 段落推进，含章节续读决策。
     *
     * 必须由上层在 TTS 引擎的 onDone 回调里调用本方法（而非直接调
     * controller.onSegmentFinished），否则续读不会触发。
     *
     * 返回值让上层知道该做什么 UI 反馈：
     * - [NEXT_SEGMENT]：正常推进，更新高亮位置即可。
     * - [CONTINUED]：已跳到下一章，需要刷新正文显示与新章标题。
     * - [STOPPED]：本章读完但没续读，显示「本章读完」并保持当前位置。
     * - [BOOK_DONE]：全书读完，显示「全书读完」并停止。
     * - [IGNORED]：迟到回调，什么也不做。
     */
    fun onSegmentFinished(): AdvanceOutcome {
        // 只在 SPEAKING 态推进，迟到回调直接忽略
        if (controller.state != TtsState.SPEAKING) return AdvanceOutcome.IGNORED
        controller.onSegmentFinished()
        // 章内推进
        if (controller.state != TtsState.FINISHED) return AdvanceOutcome.NEXT_SEGMENT
        // 本章读完
        if (autoContinue == AutoContinue.DISABLED) return AdvanceOutcome.STOPPED
        // 续读下一章
        val next = controller.currentChapter + 1
        return if (startChapter(next)) AdvanceOutcome.CONTINUED else AdvanceOutcome.BOOK_DONE
    }

    /** 恢复播放。 */
    fun play() = controller.play()

    /** 暂停。 */
    fun pause() = controller.pause()

    /** 停止并清空当前章节。 */
    fun stop() = controller.stop()

    /** 跳到下一段。 */
    fun skipToNextSegment() = controller.skipToNext()

    /** 跳到上一段。 */
    fun skipToPreviousSegment() = controller.skipToPrevious()
}
