package com.lanyue.reader.source.purify

/**
 * 替换净化规则（对齐 Legado ReplaceRule 语义）。
 *
 * 与 Legado 的差异都写在字段注释里，避免后来人按 Legado 文档误用：
 * - [scope] 是显式枚举。Legado 用 null / "title" / "content" / "all" 区分，
 *   字符串拼错不报错、整条规则静默失效；这里编译期就能挡住。
 * - [order] 相同则按声明顺序执行，保证结果可复现（见 [TextPurifier]）。
 */
data class ReplaceRule(
    val name: String = "",
    /** 匹配串：isRegex 时为正则源，否则按字面量替换 */
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
    val scope: PurifyScope = PurifyScope.ALL,
    val enabled: Boolean = true,
    /** 执行顺序，小的先执行 */
    val order: Int = 0,
    /**
     * 只处理第一行。
     * 真实场景：章节标题被混进正文首行（"第一章 xxx"），
     * 整篇替换会误伤正文里的同名字符串。
     */
    val firstLineOnly: Boolean = false
)

/** 规则作用域。正文与标题共用一份规则表时靠它分流。 */
enum class PurifyScope { CONTENT, TITLE, ALL }

/** 单次净化的执行统计，供调试面板展示，不参与结果判定。 */
data class PurifyStats(
    val applied: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    /** 净化结果异常（过短）已回退原文 */
    val reverted: Boolean = false,
    /** 命中长度上限，未执行任何规则 */
    val oversize: Boolean = false
) {
    val total: Int get() = applied + failed + skipped
}

data class PurifyResult(val text: String, val stats: PurifyStats)

/**
 * 正文/标题替换净化器。
 *
 * 设计约束来自线上真实故障：
 * 1. 单条坏规则不能废掉整章。正则编译失败只计入 failed 并跳过，继续执行后续规则。
 * 2. 非正则规则绝不走 Regex。书源作者写 `(*广告*)` 这类字面量时，
 *    当成正则会直接抛异常或被元字符改写成别的东西。
 * 3. 空 pattern 跳过。`String.replace("", x)` 会把 x 塞满每个字符间隙，
 *    一次就把整章正文毁掉。
 * 4. 净化后长度骤降则回退原文。错误规则（例如把 `.` 当正文全替）会把正文清空，
 *    这种结果比不净化更糟，必须有一道闸。
 */
class TextPurifier(
    rules: List<ReplaceRule>,
    /** 是否做空白归一化（全角空格、非断空格、连续空行） */
    private val normalizeWhitespace: Boolean = true,
    /**
     * 正文净化后保留比例下限。低于此比例判定为异常并回退原文。
     * 只对 CONTENT 生效——标题天然会被大幅缩短，用同一阈值会误回退。
     */
    private val minKeepRatio: Double = 0.3,
    /** 超过此长度的文本跳过净化，避免超长回溯把主线程拖死 */
    private val maxTextLength: Int = 2_000_000
) {
    /** 已启用规则，按 order 升序 + 声明顺序稳定排序 */
    private val ordered: List<ReplaceRule> = rules
        .withIndex()
        .filter { it.value.enabled }
        .sortedWith(compareBy({ it.value.order }, { it.index }))
        .map { it.value }

    /** 已编译正则缓存：同一 pattern 只编译一次 */
    private val regexCache = HashMap<String, Regex>()

    val ruleCount: Int get() = ordered.size

    fun purifyContent(text: String): PurifyResult = purify(text, PurifyScope.CONTENT)

    fun purifyTitle(text: String): PurifyResult = purify(text, PurifyScope.TITLE)

    fun purify(text: String, scope: PurifyScope): PurifyResult {
        if (text.isEmpty()) return PurifyResult(text, PurifyStats())

        // 超长文本整篇放行：宁可不净化，也不要在 UI 线程上跑灾难性回溯
        if (text.length > maxTextLength) {
            return PurifyResult(text, PurifyStats(oversize = true))
        }

        var applied = 0
        var failed = 0
        var skipped = 0
        var current = text

        for (rule in ordered) {
            if (!ruleApplies(rule, scope)) {
                skipped++
                continue
            }
            if (rule.pattern.isEmpty()) {
                // 空 pattern 会毁掉整篇正文，直接跳过并计数
                skipped++
                continue
            }
            val next = applyRule(rule, current)
            if (next == null) {
                failed++
                continue
            }
            if (next != current) applied++
            current = next
        }

        if (normalizeWhitespace) current = normalize(current)

        // 异常结果回退：只在正文净化时判定
        if (scope == PurifyScope.CONTENT && text.isNotEmpty() &&
            current.length < text.length * minKeepRatio
        ) {
            return PurifyResult(
                text = if (normalizeWhitespace) normalize(text) else text,
                stats = PurifyStats(applied, failed, skipped, reverted = true)
            )
        }

        return PurifyResult(current, PurifyStats(applied, failed, skipped))
    }

    private fun ruleApplies(rule: ReplaceRule, scope: PurifyScope): Boolean =
        rule.scope == PurifyScope.ALL || rule.scope == scope

    /** 返回 null 表示该规则执行失败（正则非法），调用方计入 failed 并继续 */
    private fun applyRule(rule: ReplaceRule, text: String): String? {
        if (!rule.isRegex) {
            // 字面量路径：不做任何正则解释，括号/方括号/点号都按原样匹配
            return if (rule.firstLineOnly) {
                replaceFirstLine(text) { it.replace(rule.pattern, rule.replacement) }
            } else {
                text.replace(rule.pattern, rule.replacement)
            }
        }

        val regex = compile(rule.pattern) ?: return null
        return try {
            if (rule.firstLineOnly) {
                replaceFirstLine(text) { regex.replace(it, rule.replacement) }
            } else {
                regex.replace(text, rule.replacement)
            }
        } catch (e: Throwable) {
            // 替换模板里出现非法分组引用（如 $9 但只有 2 组）也会抛，同样只废这一条
            null
        }
    }

    private fun compile(pattern: String): Regex? {
        regexCache[pattern]?.let { return it }
        return try {
            val r = Regex(pattern)
            regexCache[pattern] = r
            r
        } catch (e: Throwable) {
            null
        }
    }

    /** 只对首行应用变换，保留其余行原样（含原有换行符） */
    private fun replaceFirstLine(text: String, transform: (String) -> String): String {
        val idx = text.indexOf('\n')
        return if (idx < 0) {
            transform(text)
        } else {
            transform(text.substring(0, idx)) + text.substring(idx)
        }
    }

    /**
     * 空白归一化：
     * - 非断空格 / 全角空格 → 普通空格（爬来的正文里极常见，会让去重和搜索全部失效）
     * - 行尾空白去掉
     * - 连续空行折叠成一个空行
     * - 整体去首尾空白
     */
    private fun normalize(text: String): String {
        var s = text
            .replace('\u00a0', ' ')
            .replace('\u2007', ' ')
            .replace('\u202f', ' ')
            .replace('\u3000', ' ')
        s = s.replace("\r\n", "\n").replace('\r', '\n')
        val lines = s.split('\n').map { it.trimEnd() }
        val sb = StringBuilder()
        var blankRun = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blankRun++
                // 最多保留一个空行
                if (blankRun > 1) continue
            } else {
                blankRun = 0
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return sb.toString().trim()
    }

    companion object {
        /** 规则自检：给书源编辑器用，返回人类可读的问题列表 */
        fun validate(rules: List<ReplaceRule>): List<String> {
            val problems = ArrayList<String>()
            rules.forEachIndexed { i, r ->
                val label = if (r.name.isNotBlank()) r.name else "第 ${i + 1} 条"
                if (r.pattern.isEmpty()) {
                    problems += "$label：匹配串为空，执行时会清空正文"
                } else if (r.isRegex) {
                    try {
                        Regex(r.pattern)
                    } catch (e: Throwable) {
                        problems += "$label：正则无法编译（${e.message ?: "语法错误"}）"
                    }
                }
                if (r.order < 0) {
                    problems += "$label：order 为负数，排序会排在所有规则之前"
                }
            }
            return problems
        }
    }
}
