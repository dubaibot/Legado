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

        val values: List<String> = when {
            isJsRule(rule) -> runJsList(unwrapJs(rule), html, ctx)

            // JSONPath：$.a.b / $..name / [?(@.x=='y')]
            JsonPath.looksLikeJsonPath(rule) -> runJsonPath(html, rule)

            // XPath：//div[@class='x']/a/text()  -> 翻译成 CSS 后复用选择器逻辑
            XPathLite.looksLikeXPath(rule) -> runXPath(html, rule, ctx, needsRealDom)

            else -> {
                val doc = parseDomOrThrow(html, ctx, needsRealDom)
                val split = splitJsTail(rule)
                var selected = selectCss(doc, split.first, ctx.baseUrl)
                val jsTail = split.second
                if (jsTail != null) {
                    selected = selected.map { v -> runJsString(jsTail, v, ctx) }
                }
                selected
            }
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

    /** 显式取值指令。 */
    private val EXPLICIT_DIRECTIVES = setOf("text", "textnodes", "ownhtml", "html", "all", "content")

    /** 可按属性名直接取值的高频 HTML 属性。 */
    private val COMMON_ATTRS = setOf(
        "href", "src", "value", "alt", "title", "datetime", "poster",
        "srcset", "id", "class", "style", "name", "type", "placeholder"
    )

    /** 解析 DOM；失败统一转成带错误码的引擎异常。 */
    private suspend fun parseDomOrThrow(html: String, ctx: RuleContext, needsRealDom: Boolean): Document {
        val parseResult = dom.parse(html, ctx.baseUrl, needsRealDom, ctx.traceId)
        return when (parseResult) {
            is DomParser.ParseResult.Ok -> parseResult.document
            is DomParser.ParseResult.Failed -> throw EngineException(
                EngineErrorCode.SCRIPT_EXCEPTION,
                "dom parse failed: " + parseResult.reason
            )
        }
    }

    /**
     * JSONPath 求值。
     *
     * 非 JSON 响应直接判空而不是抛异常：同一字段在个别源上可能返回 HTML，
     * 这属于"取不到"而不是"程序错误"，抛异常会让整章失败。
     */
    private fun runJsonPath(html: String, rule: String): List<String> {
        val elements = JsonPath.selectText(html, rule)
        if (elements.isEmpty()) return emptyList()
        return JsonPath.toStrings(elements).filter { it.isNotEmpty() }
    }

    /**
     * XPath 求值：先翻译成 CSS，再复用选择器取值。
     *
     * 不支持的结构（轴 / and / or / 未知函数）会抛明确的规则错误，
     * 让用户知道是规则写法问题，而不是静默返回空。
     */
    private suspend fun runXPath(
        html: String,
        rule: String,
        ctx: RuleContext,
        needsRealDom: Boolean
    ): List<String> {
        val converted = XPathLite.toCss(rule) ?: throw EngineException(
            EngineErrorCode.SCRIPT_EXCEPTION,
            "unsupported xpath: " + rule
        )
        val doc = parseDomOrThrow(html, ctx, needsRealDom)
        val directive = converted.directive
        val composed = if (directive == null) converted.css else converted.css + "@" + directive
        return selectCss(doc, composed, ctx.baseUrl)
    }

    /**
     * CSS 选择器求值。支持 class./tag./id. 前缀与 @attr、@text、@html 后缀。
     */
    private fun selectCss(doc: Document, rawRule: String, baseUrl: String? = null): List<String> {
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

        return els.map { extract(it, directive, baseUrl) }.filter { it.isNotEmpty() }
    }

    /**
     * 判定 `@` 之后那一段是不是取值指令。
     *
     * Legado 里 `class.a@href` 是最常见写法，早期实现只认 text/html/attr:，
     * 于是 "href" 被当成第二段 CSS 选择器（找 <href> 标签），永远取不到值。
     * 这里补齐常见属性名，并支持 @attr: 与 @abs: 两种前缀。
     */
    private fun isExtractDirective(part: String): Boolean {
        val lower = part.lowercase()
        if (lower in EXPLICIT_DIRECTIVES) return true
        if (lower.startsWith("attr:") || lower.startsWith("abs:")) return true
        if (lower in COMMON_ATTRS) return true
        // data-* / aria-* 是自定义属性，按指令处理
        if (lower.startsWith("data-") || lower.startsWith("aria-")) return true
        return false
    }

    /**
     * 从元素取值。
     *
     * 属性不存在时返回空串（由上层过滤掉），不回落到文本——
     * 回落会把"取不到 href"变成"取到一段标题文字"，产生看起来对但实际错的数据。
     */
    private fun extract(el: Element, directive: String?, baseUrl: String?): String {
        if (directive == null) return el.text()
        return when {
            directive == "text" || directive == "textnodes" ->
                el.text()
            directive == "html" || directive == "ownhtml" || directive == "all" ->
                el.html()
            directive == "content" ->
                if (el.hasAttr("content")) el.attr("content") else el.text()
            directive.startsWith("abs:") ->
                absolutize(el.attr(directive.removePrefix("abs:")), baseUrl)
            directive.startsWith("attr:") ->
                el.attr(directive.removePrefix("attr:"))
            else ->
                el.attr(directive)
        }
    }

    /**
     * 把相对地址补成绝对地址（@abs:href 用）。
     *
     * 只做常见形态，不引入 URI 解析开销：
     *   //host/a   -> 继承 baseUrl 的 scheme
     *   /a         -> 继承 origin
     *   a/b        -> 拼在 baseUrl 目录后
     *   已是绝对地址则原样返回
     */
    private fun absolutize(value: String, baseUrl: String?): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        if (v.startsWith("http://") || v.startsWith("https://")) return v
        val base = baseUrl?.trim().orEmpty()
        if (base.isEmpty()) return v
        val schemeEnd = base.indexOf("://")
        val scheme = if (schemeEnd > 0) base.substring(0, schemeEnd) else "https"
        if (v.startsWith("//")) return scheme + ":" + v
        val originEnd = base.indexOf('/', schemeEnd + 3)
        val origin = if (originEnd > 0) base.substring(0, originEnd) else base
        if (v.startsWith("/")) return origin + v
        val dir = if (originEnd > 0) base.substring(0, base.lastIndexOf('/') + 1) else base + "/"
        return dir + v
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
