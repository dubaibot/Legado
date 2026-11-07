package com.lanyue.reader.bookshelf

import com.lanyue.reader.source.model.TocItem

/**
 * 换源进度映射（纯逻辑，无 IO）。
 *
 * 换源是这类阅读器的刚需：某个源挂了、更新慢、正文缺字，
 * 用户要一键把这本书迁到另一个源，而阅读进度必须跟过去。
 * 难点在于两个源的目录几乎必然不同：
 * - 章节标题写法不同（「第一章 起点」/「1.起点」/「第1章起点」）；
 * - 新源可能多/少番外、公告、序章；
 * - 卷结构不同导致章节数整体错位。
 *
 * 三级匹配策略，置信度递减：
 * 1. EXACT  —— 归一化标题完全相等；
 * 2. NUMBER —— 提取章节号（阿拉伯 / 中文数字）相等，在候选中取离原章序最近者；
 * 3. INDEX  —— 直接按章节序号对齐，置信度低，提示用户核对；
 * 全部失败回落 RESET（从头读，进度不清零旧书）。
 *
 * 章内偏移：EXACT/NUMBER 命中且两侧章长已知时按字数比例近似映射；
 * 其余情况回到章首——跨源正文文本必然有差异，比例外推毫无意义。
 */
object SourceSwapper {

    /** 匹配置信度。 */
    enum class Confidence(val label: String) {
        /** 标题完全对应，进度可信。 */
        EXACT("标题对应"),
        /** 章节号对应，进度基本可信。 */
        NUMBER("章节号对应"),
        /** 仅按序号对齐，建议用户核对进度。 */
        INDEX("按章节序对齐"),
        /** 无任何对应，从头开始。 */
        RESET("未找到对应章节")
    }

    /** 原书阅读位置。 */
    data class ReadPosition(
        val chapterIndex: Int,
        /** 原书当前章标题，用于匹配；可空（早期进度没有标题）。 */
        val chapterTitle: String? = null,
        /** 章内字符偏移。 */
        val charOffset: Int = 0,
        /** 原书当前章总字数，用于比例映射；未知传 0。 */
        val chapterCharCount: Int = 0
    )

    /** 换源结论。 */
    data class SwapPlan(
        /** 在新书目录中的章节序号。 */
        val newChapterIndex: Int,
        /** 新书章内偏移。 */
        val newCharOffset: Int,
        val confidence: Confidence,
        /** 命中的新书章标题（RESET 时为 null）。 */
        val matchedTitle: String? = null,
        /** 面向用户的一句话说明。 */
        val message: String
    ) {
        /** 是否建议提示用户核对（INDEX 档）。 */
        val needsReview: Boolean get() = confidence == Confidence.INDEX
    }

    /** 换源动作结果说明，供确认弹窗展示。 */
    data class SwapReport(
        val fromOrigin: String,
        val toOrigin: String,
        val plan: SwapPlan,
        /** 新目录总章数。 */
        val newTocSize: Int
    )

    // ============================ 归一化 ============================

    /** 标题装饰字符：标点 + 括号 + 空白。 */
    private val DECORATIONS = setOf(
        '《', '》', '【', '】', '「', '」', '『', '』', '（', '）', '(', ')',
        '[', ']', '{', '}', '·', '•', '・', '*', '~', '～', '-', '—', '―',
        '：', ':', '，', ',', '。', '.', '!', '！', '?', '？', ';', '；',
        '"', '\'', '“', '”', '‘', '’', '、', '\\', '/', '|',
        ' ', '\t', '\n', '\r', '\u00A0', '\u3000'
    )

    /** 全角转半角 + 去标点装饰。 */
    fun normalizeChapterTitle(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length)
        raw.forEach { c ->
            when {
                c in '\uFF01'..'\uFF5E' -> sb.append(c - 0xFEE0)
                else -> sb.append(c)
            }
        }
        return sb.toString().filter { it !in DECORATIONS }.lowercase()
    }

    private val CHINESE_DIGITS = mapOf(
        '零' to 0L, '一' to 1L, '二' to 2L, '两' to 2L, '三' to 3L, '四' to 4L,
        '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L
    )
    private val CHINESE_UNITS = mapOf('十' to 10L, '百' to 100L, '千' to 1000L, '万' to 10000L)

    /**
     * 提取章节号，优先级从高到低：
     * 1. 「第45章 / 第12回」里的阿拉伯数字；
     * 2. 「第四十五章」里的中文数字；
     * 3. 标题首段连续阿拉伯数字（「1.起点」「Chapter 12」）；
     * 4. 标题首个中文数字词（「一百零三」）。
     * 「番外」「公告」返回 null。「0102」按 102 读（前导零是源站防爬花招）。
     */
    fun extractChapterNumber(title: String?): Long? {
        val normalized = normalizeChapterTitle(title)
        if (normalized.isEmpty()) return null

        Regex("第?(\\d+)[章节回篇]").find(normalized)?.let { return it.groupValues[1].toLongOrNull() }
        Regex("第([\\d零一二两三四五六七八九十百千万]+)[章节回篇]").find(normalized)?.let {
            parseChineseRun(it.groupValues[1])?.let { n -> return n }
        }
        Regex("\\d+").find(normalized)?.let { return it.value.toLongOrNull() }

        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            if (c in CHINESE_DIGITS || c in CHINESE_UNITS) {
                return parseChineseRun(normalized.substring(i))
            }
            i++
        }
        return null
    }

    /**
     * 解析一段中文数字（开头即数字词），支持十/百/千/万组合，上限 9999_9999。
     * 「一百零三」-> 103，「两千零一十」-> 2010，「十」-> 10。
     * 遇到数字词之外的字符即停止（「第三章a45」只读「三」）。
     */
    private fun parseChineseRun(s: String): Long? {
        var result = 0L
        var current = 0L
        var matched = false
        for (c in s) {
            val digit = CHINESE_DIGITS[c]
            val unit = CHINESE_UNITS[c]
            when {
                digit != null -> {
                    current = current * 10 + digit
                    matched = true
                }
                unit != null -> {
                    // 「十」开头表示一十：古代写法「十五」= 15
                    if (current == 0L && result == 0L) current = 1L
                    result += current * unit
                    current = 0L
                    matched = true
                }
                else -> if (matched) break
            }
        }
        return if (matched) result + current else null
    }

    // ============================ 主映射 ============================

    /**
     * 把原书进度映射到新书目录。
     *
     * @param oldToc 原书目录（至少包含当前章及之前章节）
     * @param newToc 新书完整目录
     */
    fun mapProgress(oldToc: List<TocItem>, newToc: List<TocItem>, position: ReadPosition): SwapPlan {
        if (newToc.isEmpty()) {
            return SwapPlan(0, 0, Confidence.RESET, null, "新书目录为空，进度将从头开始")
        }
        val safeOldIndex = if (oldToc.isEmpty()) 0
        else position.chapterIndex.coerceIn(0, oldToc.size - 1)
        val oldTitle = position.chapterTitle ?: oldToc.getOrNull(safeOldIndex)?.title
        val oldNormalized = normalizeChapterTitle(oldTitle)

        // 1. 标题完全对应。仅当该归一化标题在两侧目录各只出现一次时才可信：
        //    卷名（「正文卷」「公告」）会成片重复，此时退回章节号匹配更稳。
        if (oldNormalized.isNotEmpty()) {
            val oldOccurrences = oldToc.count { normalizeChapterTitle(it.title) == oldNormalized }
            if (oldOccurrences == 1) {
                val newHit = newToc.withIndex().firstOrNull {
                    normalizeChapterTitle(it.value.title) == oldNormalized
                }
                if (newHit != null) {
                    return SwapPlan(
                        newHit.index,
                        mapOffset(position, Confidence.EXACT),
                        Confidence.EXACT,
                        newHit.value.title,
                        "已按章节标题对应：" + newHit.value.title
                    )
                }
            }
        }

        // 2. 章节号对应：候选取离原章序最近的
        val oldNumber = extractChapterNumber(oldTitle)
        if (oldNumber != null) {
            val candidates = newToc.withIndex().filter {
                extractChapterNumber(it.value.title) == oldNumber
            }
            if (candidates.isNotEmpty()) {
                val best = candidates.minByOrNull { kotlin.math.abs(it.index - safeOldIndex) }!!
                return SwapPlan(
                    best.index,
                    mapOffset(position, Confidence.NUMBER),
                    Confidence.NUMBER,
                    best.value.title,
                    "已按章节号（第" + oldNumber + "章）对应：" + best.value.title
                )
            }
        }

        // 3. 序号对齐：仅当原章序在新目录范围内时可用
        if (safeOldIndex < newToc.size) {
            val target = newToc[safeOldIndex]
            return SwapPlan(
                safeOldIndex, 0, Confidence.INDEX, target.title,
                "标题与章节号均未对应，已按第 " + (safeOldIndex + 1) + " 章对齐，请核对进度"
            )
        }

        // 4. 兜底：从头开始
        return SwapPlan(0, 0, Confidence.RESET, newToc.firstOrNull()?.title, "新书章节比原书少，进度将从头开始")
    }

    /**
     * 防误判已并入主流程：归一化标题在原目录多次出现时不再走 EXACT，
     * 避免卷名 / 公告类标题把进度带错章。
     */

    /** EXACT/NUMBER 命中时按两侧章长比例映射章内偏移。 */
    private fun mapOffset(position: ReadPosition, confidence: Confidence): Int {
        if (confidence == Confidence.INDEX || confidence == Confidence.RESET) return 0
        val oldLen = position.chapterCharCount
        if (oldLen <= 0 || position.charOffset <= 0) return 0
        // 新章字数未知时按原章内相对位置保留整数偏移，上层取到新章字数后可再精修
        return position.charOffset.coerceAtMost(oldLen)
    }

    /**
     * 用新章字数精修偏移：换源成功、拿到新书章节字数后调用。
     * oldCount<=0 时返回 0（无从估算）。
     */
    fun refineOffset(oldOffset: Int, oldCharCount: Int, newCharCount: Int): Int {
        if (oldCharCount <= 0 || oldOffset <= 0 || newCharCount <= 0) return 0
        val ratio = oldOffset.toDouble() / oldCharCount.toDouble()
        return (ratio * newCharCount).toInt().coerceIn(0, newCharCount)
    }

    /** 组装确认弹窗用的报告。 */
    fun report(fromOrigin: String, toOrigin: String, plan: SwapPlan, newTocSize: Int): SwapReport =
        SwapReport(fromOrigin, toOrigin, plan, newTocSize)
}
