package com.lanyue.reader.source.rule

/**
 * 7.1 规则编排层（纯函数，无 Android / 无 IO / 无 jsoup）。
 *
 * 把一条规则字符串拆成"可执行步骤树"，真正取数据的动作留给 RuleEngine。
 * 这样拆解顺序、优先级、空值语义都能在 JVM 单测里锁死。
 *
 * 支持的语法（自外向内依次生效）：
 *   A || B            备选：A 有结果就用 A，否则用 B
 *   A && B            并列：两段结果合并成一个列表
 *   sel##正则##替换     取数后做正则替换（替换段可含 $1）
 *   @put:{k:v} @get:{k} 变量写入 / 读取
 *   {{ 表达式 }}        结果模板拼接
 */
object RuleChain {

    /** 一个最小可执行单元。 */
    data class Segment(
        /** 去掉变量指令与替换段之后的纯净规则。 */
        val rule: String,
        /** 待应用的正则替换（可能为 null）。 */
        val replacement: RuleSyntax.RegexReplacement?,
        /** 本段要写入的变量：key -> 规则。 */
        val puts: List<Pair<String, String>>,
        /** 本段依赖读取的变量名。 */
        val gets: List<String>
    )

    /** 一组并列取数单元（`&&` 连接），结果按顺序合并。 */
    data class Alternative(val segments: List<Segment>) {
        val isEmpty: Boolean get() = segments.isEmpty() || segments.all { it.rule.isBlank() }
    }

    /** 完整规则计划：[alternatives] 之间是 `||` 备选关系。 */
    data class Plan(val alternatives: List<Alternative>) {
        val isEmpty: Boolean get() = alternatives.isEmpty() || alternatives.all { it.isEmpty }

        /** 本计划声明的全部变量写入。 */
        fun allPuts(): List<Pair<String, String>> = alternatives.flatMap { alt ->
            alt.segments.flatMap { it.puts }
        }

        /** 本计划声明的全部变量读取。 */
        fun allGets(): List<String> = alternatives.flatMap { alt ->
            alt.segments.flatMap { it.gets }
        }.distinct()
    }

    val Empty = Plan(emptyList())

    /**
     * 解析整条规则。
     *
     * 空规则返回 [Empty]，不抛异常——源配置里字段缺失是常态。
     */
    fun parse(rule: String?): Plan {
        if (rule.isNullOrBlank()) return Empty

        // 1. 先按 || 切备选。必须在最外层，保证 "a||b##c" 是 (a) || (b##c)
        val altTexts = RuleSyntax.splitTopLevel(rule, SEP_OR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (altTexts.isEmpty()) return Empty

        val alternatives = altTexts.map { altText ->
            // 2. 再按 && 切并列段
            val segTexts = RuleSyntax.splitTopLevel(altText, SEP_AND)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            Alternative(segTexts.map { compileSegment(it) })
        }
        return Plan(alternatives)
    }

    /**
     * 单段编译：变量指令 -> 正则替换 -> 剩余规则。
     *
     * 顺序不能反：`@put:{k:sel##a##b}` 里的 `##` 属于 put 的 value，
     * 必须先把指令整块摘掉，再对剩下的主体做替换解析。
     */
    fun compileSegment(raw: String): Segment {
        val vars = RuleSyntax.parseVarDirectives(raw)
        val (base, replacement) = RuleSyntax.parseRegexReplacement(vars.rule)
        return Segment(
            rule = base.trim(),
            replacement = replacement,
            puts = vars.puts,
            gets = vars.gets
        )
    }

    /**
     * 判断一个备选是否"应该被采用"：有任意非空结果。
     *
     * 单列抽出来是为了让 `||` 的短路语义可测，
     * 也避免实现里出现 `result.isNotEmpty()` 与 `result.any{}` 两套标准。
     */
    fun shouldAccept(values: List<String>): Boolean = values.any { it.isNotEmpty() }

    /**
     * 应用替换并清理：替换后可能产生空串或纯空白，需要再过滤。
     */
    fun finish(values: List<String>, replacements: List<RuleSyntax.RegexReplacement?>): List<String> {
        val repl = replacements.firstOrNull { it != null }
        val mapped = if (repl == null) {
            values
        } else {
            values.map { RuleSyntax.applyReplacement(it, repl) }
        }
        return mapped.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 合并 `&&` 各段结果，保持段间顺序，段内顺序也保持。 */
    fun merge(valuesPerSegment: List<List<String>>): List<String> =
        valuesPerSegment.flatMap { seg -> seg.filter { it.isNotEmpty() } }

    const val SEP_OR = "||"
    const val SEP_AND = "&&"
}
