package com.lanyue.reader.core.tts

/** 朗读状态。IDLE 与 FINISHED 不同：前者是「没装载章节」，后者是「本章读完了」。 */
enum class TtsState { IDLE, SPEAKING, PAUSED, FINISHED }

/**
 * 朗读进度。[charOffset] 取自当前段的起点，可直接拿回阅读页定位。
 * [ratio] 对空内容返回 0，避免 UI 出现 NaN 宽度。
 */
data class TtsProgress(
    val chapterIndex: Int,
    val segmentIndex: Int,
    val totalSegments: Int,
    val charOffset: Int
) {
    val hasContent: Boolean get() = totalSegments > 0

    val ratio: Float
        get() = if (totalSegments <= 0) 0f
        else ((segmentIndex + 1).toFloat() / totalSegments).coerceIn(0f, 1f)
}

/**
 * 朗读调度器：把分段结果逐段送进引擎，并维护「当前读到哪一段」。
 *
 * 它不碰 `android.speech.tts.TextToSpeech`，只通过 [speak] / [stopSpeaking]
 * 两个函数与外部交互。这样做有两个实际好处：
 * - 单测可以直接跑，不需要真机与语音包；
 * - 换引擎（系统 TTS、在线 TTS、本地大模型）时这层逻辑不用改。
 *
 * 几个必须守住的约束：
 * - **引擎回调可能迟到**。用户按停止后，`onSegmentFinished` 仍可能到达一次。
 *   若无条件前进光标，会出现「已经停了却又自动念下一段」——所以只在 SPEAKING
 *   状态下接受回调。
 * - **空内容不进 SPEAKING**。否则底栏图标变成「朗读中」却永远没有声音，
 *   用户会以为朗读坏了，实际是本章没内容。
 * - **暂停后恢复从当前段继续**，不能从头开始：用户暂停是为了处理别的事，
 *   回来重听整章会直接关掉这个功能。
 * - **读完不循环**。章节读完停在 FINISHED 等上层决定是否自动跳下一章，
 *   调度器自己循环会导致「翻到下一章又被拉回来」。
 */
class TtsReadController(
    private val segmenter: TtsSegmenter = TtsSegmenter(),
    /** 交给引擎朗读一段，由调用方接 `TextToSpeech.speak`。 */
    private val speak: (TtsSegment) -> Unit = {},
    /** 打断引擎当前朗读，由调用方接 `TextToSpeech.stop`。 */
    private val stopSpeaking: () -> Unit = {}
) {
    var state: TtsState = TtsState.IDLE
        private set

    /** 语速。越界直接夹取而不抛异常：设置项来自滑块，越界是常态输入。 */
    var rate: Float = DEFAULT_RATE
        set(value) {
            field = value.coerceIn(MIN_RATE, MAX_RATE)
        }

    var pitch: Float = DEFAULT_PITCH
        set(value) {
            field = value.coerceIn(MIN_PITCH, MAX_PITCH)
        }

    private var chapter = -1
    private var segments: List<TtsSegment> = emptyList()
    private var cursor = -1

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 3.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
        const val DEFAULT_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
    }

    val currentSegment: TtsSegment? get() = segments.getOrNull(cursor)

    val progress: TtsProgress
        get() = TtsProgress(chapter, cursor, segments.size, currentSegment?.charStart ?: 0)

    /** 当前装载的章节索引，未装载为 -1。 */
    val currentChapter: Int get() = chapter

    /** 装载一章。[startCharOffset] 用于换章或从阅读位置起读，定位到包含该偏移的段。 */
    fun load(
        chapterIndex: Int,
        text: String,
        startCharOffset: Int = 0,
        autoPlay: Boolean = false
    ) {
        require(chapterIndex >= 0) { "chapterIndex 不能为负，实际为 $chapterIndex" }
        if (state == TtsState.SPEAKING) stopSpeaking()
        chapter = chapterIndex
        segments = segmenter.segment(TtsSegmenter.normalize(text))
        cursor = when {
            segments.isEmpty() -> -1
            startCharOffset <= 0 -> 0
            else -> segments.indexOfFirst { it.charEnd > startCharOffset }
                .let { if (it < 0) segments.lastIndex else it }
        }
        state = if (segments.isEmpty()) TtsState.IDLE else TtsState.PAUSED
        if (autoPlay && segments.isNotEmpty()) play()
    }

    fun play() {
        if (segments.isEmpty()) return
        if (state == TtsState.SPEAKING) return
        // 已读完再点播放，视为重听本章
        if (state == TtsState.FINISHED) cursor = 0
        if (cursor < 0) cursor = 0
        state = TtsState.SPEAKING
        segments.getOrNull(cursor)?.let(speak)
    }

    fun pause() {
        if (state != TtsState.SPEAKING) return
        stopSpeaking()
        state = TtsState.PAUSED
    }

    fun stop() {
        if (state == TtsState.SPEAKING) stopSpeaking()
        chapter = -1
        segments = emptyList()
        cursor = -1
        state = TtsState.IDLE
    }

    /** 引擎念完一段的回调。非 SPEAKING 状态下忽略，防止停止后又被推进。 */
    fun onSegmentFinished() {
        if (state != TtsState.SPEAKING) return
        if (cursor >= segments.lastIndex) {
            state = TtsState.FINISHED
            return
        }
        cursor++
        segments.getOrNull(cursor)?.let(speak)
    }

    /** 下一段。暂停状态下只移动光标，恢复播放时从新段开始。 */
    fun skipToNext() {
        if (segments.isEmpty()) return
        if (cursor >= segments.lastIndex) {
            if (state == TtsState.SPEAKING) stopSpeaking()
            state = TtsState.FINISHED
            return
        }
        cursor++
        if (state == TtsState.SPEAKING) {
            stopSpeaking()
            segments.getOrNull(cursor)?.let(speak)
        }
    }

    /**
     * 上一段。已在第一段时停在本段开头重听，而不是什么都不做——
     * 用户连按「上一段」通常是想重听刚过去的那句。
     */
    fun skipToPrevious() {
        if (segments.isEmpty()) return
        if (cursor > 0) cursor--
        if (state == TtsState.FINISHED) state = TtsState.PAUSED
        if (state == TtsState.SPEAKING) {
            stopSpeaking()
            segments.getOrNull(cursor)?.let(speak)
        }
    }
}
