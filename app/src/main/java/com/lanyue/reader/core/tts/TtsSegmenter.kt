package com.lanyue.reader.core.tts

/**
 * 朗读分段结果，[charStart, charEnd) 指向**章节原文**的字符区间。
 *
 * 保留偏移不是为了显示，而是为了进度可逆：用户朗读到第 7 段时切回阅读页，
 * 应当停在正文对应的那句话。只存段号还原不了位置——分段结果会随引擎长度上限
 * 和正文规范化而变，段号与字号的对应关系并不稳定。
 */
data class TtsSegment(
    val text: String,
    val charStart: Int,
    val charEnd: Int,
    /** 本章内的段序号，从 0 开始且连续。 */
    val index: Int
) {
    val length: Int get() = charEnd - charStart
}

/**
 * 把整章正文切成适合逐段送进 TTS 引擎的句子块。
 *
 * 为什么要分段，而不是把整章一次性交给 speak：
 * - `speak` 是异步排队，整章入队后「暂停」「跳句」的粒度会变成整章，
 *   用户想跳过一段水印也做不到；
 * - 多数引擎对超长入参有内部截断，表现是「读到一半突然没声了」，
 *   而且不报错，排查时极难定位；
 * - 逐句入队才能做跟随高亮与断点续读。
 *
 * 切分规则按中文正文的实际排版习惯定：
 * - **只在引号以外的句末标点切**。引号里的句号是台词的一部分，
 *   在「他说：」后面切开会把一句台词劈成两段，听感像换了个人在念。
 * - **段首不留标点**：右引号、右括号并入前一段，否则引擎会把「」」单独读成
 *   「右引号」，那是很明显的机械感来源。
 * - **单句超长先退到软断点**（逗号类），实在没有标点才硬切。硬切只是兜底，
 *   不能因为原文缺标点就把整句丢给引擎。
 */
class TtsSegmenter(
    private val maxChars: Int = DEFAULT_MAX_CHARS
) {
    init {
        require(maxChars > 0) { "maxChars 必须为正数，实际为 $maxChars" }
    }

    companion object {
        /**
         * 单段字符上限。系统 TTS 的 `getMaxSpeechInputLength()` 建议值是 4000，
         * 但取满会让「跳句」粒度太粗；部分国产引擎在 1000 字以上开始吞字，
         * 默认取 400 兼顾可控性与请求次数。
         */
        const val DEFAULT_MAX_CHARS = 400

        private const val TERMINATORS = "。！？；!?;…\n"
        private const val SOFT_BREAKS = "，、,：:"
        private const val OPEN_QUOTES = "「『“（(《【["
        private const val CLOSE_QUOTES = "」』”）)》】]"
        /** 只有引号类收尾能在句内切开；括号类不算，否则「（见注一）后续」会被腰斩。 */
        private const val SPEECH_CLOSES = "」』”"
        /** 软断点切出的段若短于这个长度，宁可硬切：碎成几个字的段听感更差。 */
        private const val MIN_SOFT_SEGMENT = 10

        /**
         * 长度不变的规范化，只做 1:1 字符替换，保证返回串与入参**等长**。
         *
         * 之所以不敢合并连续空格、不敢删零宽字符：分段偏移要能对回原文，
         * 一旦长度变化，所有段的 charStart 都会整体错位，朗读进度就和正文对不上了。
         * 真正会影响引擎朗读的只有全角空格（会被读成停顿很长）与 `\r`，
         * 这两个都能 1:1 替换掉。
         */
        fun normalize(text: String): String {
            if (text.isEmpty()) return text
            val sb = StringBuilder(text.length)
            for (c in text) {
                when (c) {
                    '\u3000', '\u00A0' -> sb.append(' ')
                    '\r' -> sb.append('\n')
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun isSpace(c: Char): Boolean =
            c.isWhitespace() || c == '\u200B' || c == '\uFEFF'
    }

    /** 切分全文。空文本返回空列表，调用方据此判断「本章无可朗读内容」。 */
    fun segment(text: String): List<TtsSegment> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<TtsSegment>()
        for (range in pack(sentenceRanges(text), text)) {
            val piece = text.substring(range.first, range.last + 1)
            if (piece.isBlank()) continue
            out.add(TtsSegment(piece, range.first, range.last + 1, out.size))
        }
        return out
    }

    /** 找出句子区间（闭区间），引号内的句末标点不切。 */
    private fun sentenceRanges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var depth = 0
        var start = -1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (start < 0 && !isSpace(c)) start = i
            if (OPEN_QUOTES.indexOf(c) >= 0) {
                depth++
            } else if (CLOSE_QUOTES.indexOf(c) >= 0 && depth > 0) {
                depth--
                // 引号收尾且里面已是完整一句（前一字符为句末标点）时，句子到此结束。
                // 不做这个判断的话，「他说：「你先走。」然后转身。」会整段连成一句，
                // 台词和叙述被捆在一起，超长时还会在句子中间硬切。
                if (depth == 0 && start >= 0 && SPEECH_CLOSES.indexOf(c) >= 0 &&
                    i > start && TERMINATORS.indexOf(text[i - 1]) >= 0
                ) {
                    var end = i
                    while (end + 1 < text.length && isTail(text[end + 1])) end++
                    out.add(start..end)
                    start = -1
                    i = end + 1
                    continue
                }
            }
            if (start >= 0 && depth == 0 && TERMINATORS.indexOf(c) >= 0) {
                // 句末标点后紧跟的收尾符号（右引号、右括号、重复感叹号）并入本句
                var end = i
                while (end + 1 < text.length && isTail(text[end + 1])) end++
                out.add(start..end)
                start = -1
                i = end + 1
                continue
            }
            i++
        }
        if (start >= 0) {
            var end = text.length - 1
            while (end > start && isSpace(text[end])) end--
            if (end >= start) out.add(start..end)
        }
        return out
    }

    private fun isTail(c: Char): Boolean =
        TERMINATORS.indexOf(c) >= 0 || CLOSE_QUOTES.indexOf(c) >= 0 || c == '"'

    /** 把句子贪心装进不超过 maxChars 的段里。 */
    private fun pack(ranges: List<IntRange>, text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var curStart = -1
        var curEnd = -1

        fun flush() {
            if (curStart >= 0) out.add(curStart..curEnd)
            curStart = -1
        }

        for (r in ranges) {
            for (piece in splitIfTooLong(r, text)) {
                if (curStart < 0) {
                    curStart = piece.first
                    curEnd = piece.last
                } else if (piece.last - curStart + 1 <= maxChars) {
                    curEnd = piece.last
                } else {
                    flush()
                    curStart = piece.first
                    curEnd = piece.last
                }
            }
        }
        flush()
        return out
    }

    private fun splitIfTooLong(range: IntRange, text: String): List<IntRange> {
        if (range.last - range.first + 1 <= maxChars) return listOf(range)
        val out = ArrayList<IntRange>()
        var start = range.first
        while (start <= range.last) {
            val limit = start + maxChars - 1
            if (limit >= range.last) {
                out.add(start..range.last)
                break
            }
            var cut = -1
            for (i in limit downTo start) {
                if (SOFT_BREAKS.indexOf(text[i]) >= 0) {
                    cut = i
                    break
                }
            }
            if (cut < 0 || cut - start + 1 < MIN_SOFT_SEGMENT) cut = limit
            out.add(start..cut)
            start = cut + 1
            while (start <= range.last && isSpace(text[start])) start++
        }
        return out
    }
}
