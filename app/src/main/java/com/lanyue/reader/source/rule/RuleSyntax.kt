package com.lanyue.reader.source.rule

/**
 * 7.1 规则语法层（纯函数，无 Android / 无 IO，可直接单测）。
 *
 * 只做「文本层面」的拆解，不做任何取数据动作：
 * - 顶层分隔符切分（`||` 备选、`&&` 合并）
 * - `##正则##替换` 提取
 * - `@put:{k:v}` / `@get:{k}` 变量指令提取
 * - `{{ 表达式 }}` 模板定位与替换
 *
 * 这样做的好处：解析规则的正确性可以用 JVM 单测覆盖，
 * 不必依赖 WebView / jsoup，避免「语法错了但看起来能跑」。
 */
object RuleSyntax {

    private const val REGEX_SEP = "##"

    // ---------------- 顶层切分 ----------------

    /**
     * 按分隔符切分，但忽略 `{}` 内部与引号内部的同名字符。
     *
     * 例：`$.a||$.b`          -> ["$.a", "$.b"]
     *     `@js:a||b == 1`     -> 不切（引号内）
     *     `@put:{k:a||b}`     -> 不切（花括号内）
     */
    fun splitTopLevel(rule: String, sep: String): List<String> {
        require(sep.isNotEmpty()) { "separator must not be empty" }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var brace = 0
        var quote = 0.toChar()
        var i = 0
        while (i < rule.length) {
            val c = rule[i]
            when {
                quote != 0.toChar() -> {
                    sb.append(c)
                    if (c == quote && (i == 0 || rule[i - 1] != '\\')) quote = 0.toChar()
                    i++
                }
                c == '"' || c == '\'' || c == '`' -> {
                    quote = c
                    sb.append(c)
                    i++
                }
                c == '{' -> {
                    brace++
                    sb.append(c)
                    i++
                }
                c == '}' -> {
                    if (brace > 0) brace--
                    sb.append(c)
                    i++
                }
                brace == 0 && rule.startsWith(sep, i) -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                    i += sep.length
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        out.add(sb.toString())
        return out
    }

    // ---------------- ## 正则替换 ----------------

    /** 一条已编译的正则替换。 */
    data class RegexReplacement(val pattern: Regex, val replacement: String)

    /**
     * 拆出 `##正则##替换`。
     *
     * - 返回 first 为去掉替换段后的规则，second 为替换描述（无则 null）
     * - 正则编译失败时整体按原规则返回，不抛异常（坏规则不能拖垮整条链）
     */
    fun parseRegexReplacement(rule: String): Pair<String, RegexReplacement?> {
        val first = rule.indexOf(REGEX_SEP)
        if (first < 0) return rule to null

        val patternStart = first + REGEX_SEP.length
        val second = rule.indexOf(REGEX_SEP, patternStart)
        if (second < 0) {
            // 只有 ##正则，没有替换段：视为删除匹配内容
            val pattern = rule.substring(patternStart)
            return rule.substring(0, first) to compileOrNull(pattern, "")
        }

        val base = rule.substring(0, first)
        val pattern = rule.substring(patternStart, second)
        val replacement = rule.substring(second + REGEX_SEP.length)
        return base to compileOrNull(pattern, replacement)
    }

    private fun compileOrNull(pattern: String, replacement: String): RegexReplacement? {
        if (pattern.isEmpty()) return null
        return runCatching { RegexReplacement(Regex(pattern), replacement) }.getOrNull()
    }

    /**
     * 应用替换。replacement 支持 `$1`、`${name}` 反向引用。
     * 正则无效（构造时已置空）时调用方不会拿到本对象，故这里可安全执行。
     */
    fun applyReplacement(value: String, repl: RegexReplacement): String =
        runCatching { repl.pattern.replace(value, repl.replacement) }.getOrDefault(value)

    // ---------------- put / get 变量指令 ----------------

    data class VarDirectives(
        val rule: String,
        val puts: List<Pair<String, String>>,
        val gets: List<String>
    )

    private val PUT_REGEX = Regex("""@put:\{([^}]*)}""")
    private val GET_REGEX = Regex("""@get:\{([^}]*)}""")

    /**
     * 提取并移除 `@put:{key:rule}`、`@get:{key}`。
     *
     * put 的 value 本身可能含 `:`（如 `@css:xxx`），因此只按第一个 `:` 切分。
     */
    fun parseVarDirectives(rule: String): VarDirectives {
        val puts = ArrayList<Pair<String, String>>()
        PUT_REGEX.findAll(rule).forEach { m ->
            val body = m.groupValues[1]
            val idx = body.indexOf(':')
            if (idx > 0) {
                val key = body.substring(0, idx).trim()
                val value = body.substring(idx + 1).trim()
                if (key.isNotEmpty()) puts.add(key to value)
            }
        }

        val gets = ArrayList<String>()
        GET_REGEX.findAll(rule).forEach { m ->
            m.groupValues[1].split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { gets.add(it) }
        }

        val stripped = PUT_REGEX.replace(rule, "").let { GET_REGEX.replace(it, "") }.trim()
        return VarDirectives(stripped, puts, gets)
    }

    // ---------------- {{ }} 模板 ----------------

    private val TEMPLATE_REGEX = Regex("""\{\{([^}]*)\}\}""")

    /** 列出所有模板表达式内容（去空白），供调用方决定如何求值。 */
    fun findTemplateExpressions(rule: String): List<String> =
        TEMPLATE_REGEX.findAll(rule).map { it.groupValues[1].trim() }.toList()

    /**
     * 替换 `{{ }}`。求值失败时保留原样，避免把规则打穿成空串。
     */
    fun resolveTemplate(template: String, resolve: (String) -> String?): String =
        TEMPLATE_REGEX.replace(template) { m ->
            val expr = m.groupValues[1].trim()
            resolve(expr) ?: m.value
        }

    // ---------------- @ 链索引 ----------------

    /**
     * Legado 的 `class.item.1@tag.a` 中 `.1` 表示取第 2 个元素。
     * 独立成函数便于单测覆盖负数 / 越界。
     */
    fun isIndexSegment(segment: String): Boolean {
        val t = segment.trim()
        if (t.length < 2 || t[0] != '.') return false
        return t.substring(1).toIntOrNull() != null
    }

    fun indexOfSegment(segment: String): Int? =
        if (!isIndexSegment(segment)) null else segment.trim().substring(1).toIntOrNull()

    /** 安全取第 index 个（支持负数代表倒数）。越界返回 null。 */
    fun pickIndex(values: List<String>, index: Int): String? {
        if (values.isEmpty()) return null
        val real = if (index < 0) values.size + index else index
        return values.getOrNull(real)
    }

    // ---------------- 规则类型判定 ----------------

    fun isJsRule(rule: String): Boolean {
        val t = rule.trimStart()
        return t.startsWith("<js>") || t.startsWith("@js:")
    }

    fun isJsonPathRule(rule: String): Boolean = rule.trimStart().startsWith("$")

    fun isXPathRule(rule: String): Boolean {
        val t = rule.trimStart()
        return t.startsWith("//") || t.startsWith("/html")
    }
}
