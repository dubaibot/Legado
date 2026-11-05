package com.lanyue.reader.source.rule

import com.lanyue.reader.core.engine.DomParser
import com.lanyue.reader.core.engine.EngineErrorCode
import com.lanyue.reader.core.engine.EngineException
import com.lanyue.reader.core.engine.JsExecMode
import com.lanyue.reader.core.engine.JsRuntime
import com.lanyue.reader.core.engine.PoolTier
import com.lanyue.reader.core.observability.LocalMetrics
import com.google.gson.JsonParser
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * 7.1 规则预处理 + 4.2 数据流分发。
 *
 * 规则形态（Legado 惯例）：
 *   class.xxx@tag.a@text            -> CSS 链式
 *   //div[@class="x"]/a/text()      -> XPath（降级为 CSS 近似）
 *   $.data.list[*]                  -> JSONPath（简化实现）
 *   <js>...</js> 或 @js:...         -> 交 JsRuntime
 *   ## 正则替换                      -> replaceRegex
 *
 * 统一收敛在这里，源实现只声明规则字符串，不自己发明语法。
 */
class RuleEngine(
    private val js: JsRuntime,
    private val dom: DomParser,
    private val metrics: LocalMetrics
) {

    /** 一条规则的解析上下文。 */
    data class RuleContext(
        val baseUrl: String,
        val sourceId: String,
        val traceId: String,
        val variables: Map<String, String> = emptyMap()
    )

    /**
     * 在一段 HTML 上执行规则，返回字符串列表。
     *
     * 整条规则交给 [RuleChain] 拆解：`||` 备选短路、`&&` 并列合并、
     * `##` 正则替换、`@put/@get` 变量、`{{ }}` 模板。
     * [rule] 为 null/空时返回空列表，不抛异常（源字段常为空）。
     */
    suspend fun evalList(
        html: String,
        rule: String?,
        ctx: RuleContext,
        needsRealDom: Boolean = false
    ): List<String> {
        val plan = RuleChain.parse(rule)
        if (plan.isEmpty) return emptyList()

        val vars = HashMap<String, String>()
        plan.allGets().forEach { key -> ctx.variables[key]?.let { vars[key] = it } }

        for (alt in plan.alternatives) {
            if (alt.isEmpty) continue
            val perSegment = ArrayList<List<String>>(alt.segments.size)
            for (seg in alt.segments) {
                val scope = ctx.copy(variables = vars.toMap())
                val values = evalSegment(html, seg.rule, seg.replacement, scope, needsRealDom)
                perSegment.add(values)
                applyPuts(html, seg, vars, values, scope, needsRealDom)
            }
            val merged = RuleChain.merge(perSegment)
            // 短路：第一个有结果的备选胜出，后面的不再请求（省远端流量）
            if (RuleChain.shouldAccept(merged)) return merged
        }
        return emptyList()
    }

    /** 单值规则：取第一个。 */
    suspend fun evalString(
        html: String,
        rule: String?,
        ctx: RuleContext,
        needsRealDom: Boolean = false
    ): String? = evalList(html, rule, ctx, needsRealDom).firstOrNull()

    /**
     * 执行 `@put` 写入。
     *
     * value 自身是一条独立规则；为空或与主体规则相同时回落到本段结果，
     * 这样 `sel@text@put:{title:}` 这种简写也能用。
     */
    private suspend fun applyPuts(
        html: String,
        seg: RuleChain.Segment,
        vars: HashMap<String, String>,
        values: List<String>,
        ctx: RuleContext,
        needsRealDom: Boolean
    ) {
        if (seg.puts.isEmpty()) return
        seg.puts.forEach { (key, valueRule) ->
            val trimmed = valueRule.trim()
            val value = if (trimmed.isEmpty() || trimmed == seg.rule.trim()) {
                values.firstOrNull()
            } else {
                val sub = RuleChain.compileSegment(trimmed)
                evalSegment(html, sub.rule, sub.replacement, ctx, needsRealDom).firstOrNull()
            }
            if (!value.isNullOrEmpty()) vars[key] = value
        }
    }

    /** 执行一个已拆解好的取数单元（变量指令已剥离）。 */
    private suspend fun evalSegment(
        html: String,
        rawRule: String,
        replacement: RuleSyntax.RegexReplacement?,
        ctx: RuleContext,
        needsRealDom: Boolean
    ): List<String> {
        val rule = resolveTemplate(rawRule, ctx)
        // 纯 @put 段没有主体规则：返回空，puts 走「回落到本段结果」分支
        if (rule.isBlank()) return emptyList()

        val values: List<String> = if (isJsRule(rule)) {
            runJsList(unwrapJs(rule), html, ctx)
        } else {
            val parseResult = dom.parse(html, ctx.baseUrl, needsRealDom, ctx.traceId)
            val doc = when (parseResult) {
                is DomParser.ParseResult.Ok -> parseResult.document
                is DomParser.ParseResult.Failed -> throw EngineException(
                    EngineErrorCode.SCRIPT_EXCEPTION,
                    "dom parse failed: " + parseResult.reason
                )
            }
            val split = splitJsTail(rule)
            var selected = selectCss(doc, split.first)
            val jsTail = split.second
            if (jsTail != null) {
                selected = selected.map { v -> runJsString(jsTail, v, ctx) }
            }
            selected
        }
        return RuleChain.finish(values, listOf(replacement))
    }

    /**
     * 替换 `{{ }}`：先查变量，再查内置名。无法求值时保留原样，
     * 避免把整条规则打穿成空串而静默失败。
     */
    private fun resolveTemplate(rule: String, ctx: RuleContext): String {
        if (!rule.contains("{{")) return rule
        return RuleSyntax.resolveTemplate(rule) { expr ->
            when (expr) {
                "baseUrl" -> ctx.baseUrl
                "sourceId" -> ctx.sourceId
                else -> ctx.variables[expr]
            }
        }
    }

    /**
     * CSS 选择器求值。支持 class./tag./id. 前缀与 @attr、@text、@html 后缀。
     */
    private fun selectCss(doc: Document, rawRule: String): List<String> {
        val parts = rawRule.split("@").filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()

        val lastIsDirective = isExtractDirective(parts.last())
        val selectorParts = if (lastIsDirective) parts.dropLast(1) else parts
        val directive = if (lastIsDirective) parts.last().lowercase() else null

        if (selectorParts.isEmpty()) return emptyList()

        var els: Elements = Elements()
        selectorParts.forEachIndexed { index, raw ->
            val (sel, pickAt) = splitIndexSuffix(raw)
            els = if (index == 0) {
                doc.select(sel)
            } else {
                val nested = Elements()
                els.forEach { el -> nested.addAll(el.select(sel)) }
                nested
            }
            // 尾部 .N 表示只取第 N 个（负数代表倒数），越界则判空，不静默取到错的元素
            if (pickAt != null) {
                val idx = if (pickAt < 0) els.size + pickAt else pickAt
                val picked = els.getOrNull(idx)
                els = if (picked == null) Elements() else Elements(picked)
            }
        }

        return els.map { extract(it, directive) }.filter { it.isNotEmpty() }
    }

    private fun isExtractDirective(part: String): Boolean {
        val lower = part.lowercase()
        return lower in setOf("text", "textnodes", "ownhtml", "html", "all", "content") ||
            lower.startsWith("attr:")
    }

    private fun extract(el: Element, directive: String?): String = when {
        directive == null || directive == "text" || directive == "textnodes" -> el.text()
        directive == "html" || directive == "ownhtml" || directive == "all" -> el.html()
        directive.startsWith("attr:") -> el.attr(directive.removePrefix("attr:"))
        else -> el.text()
    }

    /**
     * 拆出 `class.item.1` 这类尾部索引。
     *
     * 返回 (去索引后的选择器, 索引或 null)。
     * 只认最后一段是纯整数的情况，避免把 `.col-2` 之类的类名误当索引。
     */
    private fun splitIndexSuffix(raw: String): Pair<String, Int?> {
        val t = raw.trim()
        val dot = t.lastIndexOf('.')
        if (dot <= 0 || dot == t.lastIndex) return normalizeSelector(t) to null
        val tail = t.substring(dot + 1)
        val num = tail.toIntOrNull() ?: return normalizeSelector(t) to null
        val head = t.substring(0, dot)
        // head 为空（如 ".1"）时无法构成选择器，按索引无选择器处理
        if (head.isBlank()) return normalizeSelector(t) to null
        return normalizeSelector(head) to num
    }

    /** class.xxx -> .xxx；tag.a -> a；id.x -> #x。 */
    private fun normalizeSelector(raw: String): String {
        val t = raw.trim()
        return when {
            t.startsWith("class.") -> "." + t.removePrefix("class.")
            t.startsWith("id.") -> "#" + t.removePrefix("id.")
            t.startsWith("tag.") -> t.removePrefix("tag.")
            else -> t
        }
    }

    /** 规则尾部 @js: / <js> 拆出。 */
    private fun splitJsTail(rule: String): Pair<String, String?> {
        val jsIndex = rule.indexOf("@js:")
        if (jsIndex >= 0) {
            return rule.substring(0, jsIndex) to rule.substring(jsIndex + 4)
        }
        return rule to null
    }

    fun isJsRule(rule: String): Boolean {
        val t = rule.trimStart()
        if (t.startsWith("<js>")) return true
        if (t.startsWith("@js:")) return true
        return false
    }

    private fun unwrapJs(rule: String): String {
        val t = rule.trim()
        return when {
            t.startsWith("<js>") -> t.removePrefix("<js>").substringBefore("</js>")
            t.startsWith("@js:") -> t.removePrefix("@js:")
            else -> t
        }
    }

    private suspend fun runJsList(script: String, html: String, ctx: RuleContext): List<String> {
        val wrapped = buildJsWithContext(script, html, ctx)
        val raw = js.eval(
            script = wrapped,
            mode = JsExecMode.MAIN,
            tier = PoolTier.NORMAL,
            traceId = ctx.traceId
        ) { _, _ -> true }
        // JS 结果可能是 JSON 数组，也可能是单值
        val asArray = runCatching {
            JsonParser.parseString(raw).asJsonArray.map { it.asString }
        }.getOrNull()
        return (asArray ?: listOf(raw)).filter { it.isNotEmpty() }
    }

    private suspend fun runJsString(script: String, input: String, ctx: RuleContext): String {
        val wrapped = buildJsWithContext(script, input, ctx)
        return js.eval(wrapped, JsExecMode.MAIN, PoolTier.NORMAL, ctx.traceId) { _, _ -> true }
    }

    /** 注入 result / baseUrl / variables（Legado 惯例变量名）。 */
    private fun buildJsWithContext(script: String, result: String, ctx: RuleContext): String {
        val vars = ctx.variables.entries.joinToString(",") { (k, v) ->
            quoteLiteral(k) + ":" + quoteLiteral(v)
        }
        val sb = StringBuilder()
        sb.append("(function(){\n")
        sb.append("  var result = ").append(quoteLiteral(result)).append(";\n")
        sb.append("  var baseUrl = ").append(quoteLiteral(ctx.baseUrl)).append(";\n")
        sb.append("  var sourceId = ").append(quoteLiteral(ctx.sourceId)).append(";\n")
        sb.append("  var variables = {").append(vars).append("};\n")
        sb.append("  try {\n")
        sb.append("    var out = (function(){ ").append(script).append(" })();\n")
        sb.append("    if (out === undefined || out === null) return '';\n")
        sb.append("    return typeof out === 'string' ? out : JSON.stringify(out);\n")
        sb.append("  } catch (e) {\n")
        sb.append("    return '__JS_ERROR__' + e;\n")
        sb.append("  }\n")
        sb.append("})()")
        return sb.toString()
    }

    /** JSON 安全字符串字面量。 */
    private fun quoteLiteral(raw: String): String {
        val sb = StringBuilder("\"")
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
