package com.lanyue.reader.source.rule

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 7.1 JSONPath 求值器（纯 Kotlin，无 Android / 无网络）。
 *
 * Legado 书源里 `$.` 开头的规则很常见，早期实现把它当 CSS 处理，必然取不到值。
 * 这里实现一个够用的子集：
 *
 *   $.data.list[*].name         字段 + 通配
 *   $.data.list[0]              下标（负数表示倒数）
 *   $..name                     递归下降
 *   $.list[1:4]                 切片（含头不含尾，支持负数与省略端点）
 *   $.list[?(@.type=='book')]   过滤：字符串 / 数字 / 布尔 / 存在性
 *   $.list[?(@.wordCount>1000)] 比较：> >= < <= != ==
 *   $['weird name']             方括号字符串键
 *
 * 设计取舍：
 * - 不引入第三方 JSONPath 库（体积、反射、不可测），保持纯函数可单测。
 * - 解析失败一律返回空列表而不是抛异常：源配置写错表现为"取不到"，不应让整章崩掉。
 */
object JsonPath {

    /** 在 JSON 文本上求值；文本非法返回空列表。 */
    fun selectText(json: String, path: String): List<JsonElement> {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        return select(root, path)
    }

    /** 在已解析的 JsonElement 上求值。 */
    fun select(root: JsonElement, path: String): List<JsonElement> {
        val steps = parse(path) ?: return emptyList()
        var current = listOf(root)
        for (step in steps) {
            if (current.isEmpty()) return emptyList()
            current = current.flatMap { apply(it, step) }
        }
        return current.filter { !it.isJsonNull }
    }

    /** 转字符串：标量取文本，对象/数组保留 JSON 文本。 */
    fun toStrings(elements: List<JsonElement>): List<String> =
        elements.map { el -> if (el.isJsonPrimitive) el.asString else el.toString() }
            .filter { it.isNotEmpty() }

    /** 取第一个非空结果。 */
    fun firstString(json: String, path: String): String? =
        toStrings(selectText(json, path)).firstOrNull { it.isNotEmpty() }

    /** 是否像 JSONPath（`$` 开头且后接 `.` 或 `[`）。 */
    fun looksLikeJsonPath(rule: String): Boolean {
        val t = rule.trim()
        return t.startsWith("$") && (t.length == 1 || t[1] == '.' || t[1] == '[')
    }

    // ---------------- 步骤 / 谓词 ----------------

    sealed interface Step {
        data class Field(val name: String) : Step
        data class Index(val index: Int) : Step
        data object Wildcard : Step
        data class Slice(val start: Int?, val end: Int?) : Step
        data class Recursive(val name: String) : Step
        data class Filter(val predicate: Predicate) : Step
    }

    sealed interface Predicate {
        data class Exists(val field: String) : Predicate
        data class Equals(val field: String, val value: String, val numeric: Boolean) : Predicate
        data class NotEquals(val field: String, val value: String, val numeric: Boolean) : Predicate
        data class Compare(val field: String, val op: String, val value: Double) : Predicate
    }

    // ---------------- 解析 ----------------

    /** 解析成步骤序列；语法非法返回 null。 */
    fun parse(path: String): List<Step>? {
        val t = path.trim()
        if (t.isEmpty()) return null
        var i = if (t.startsWith("$")) 1 else 0
        val steps = ArrayList<Step>()

        while (i < t.length) {
            when {
                t.startsWith("..", i) -> {
                    i += 2
                    val name = readName(t, i) ?: return null
                    if (name.isEmpty()) return null
                    steps.add(Step.Recursive(name))
                    i += name.length
                }
                t[i] == '.' -> {
                    i++
                    if (i < t.length && t[i] == '*') {
                        steps.add(Step.Wildcard)
                        i++
                    } else {
                        val name = readName(t, i) ?: return null
                        if (name.isEmpty()) return null
                        steps.add(Step.Field(name))
                        i += name.length
                    }
                }
                t[i] == '[' -> {
                    val close = matchBracket(t, i)
                    if (close < 0) return null
                    val step = parseBracket(t.substring(i + 1, close).trim()) ?: return null
                    steps.add(step)
                    i = close + 1
                }
                else -> return null
            }
        }
        return steps
    }

    private fun readName(t: String, from: Int): String? {
        var i = from
        while (i < t.length && t[i] != '.' && t[i] != '[') i++
        val name = t.substring(from, i)
        // 名字含空白说明路径畸形（避免把 "a b" 当字段名静默取空）
        if (name.any { it.isWhitespace() }) return null
        return name
    }

    /** 返回与 [open] 处 `[` 配对的 `]` 下标；不配对返回 -1。 */
    private fun matchBracket(t: String, open: Int): Int {
        var depth = 0
        var inSingle = false
        var inDouble = false
        var i = open
        while (i < t.length) {
            val c = t[i]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> if (c == '"') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '[' -> depth++
                c == ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun parseBracket(inner: String): Step? {
        if (inner.isEmpty()) return null
        if (inner == "*") return Step.Wildcard
        if (inner.startsWith("?")) return parseFilter(inner)

        if ((inner.startsWith("'") && inner.endsWith("'")) ||
            (inner.startsWith("\"") && inner.endsWith("\""))
        ) {
            return Step.Field(inner.substring(1, inner.length - 1))
        }

        if (inner.contains(":")) {
            val parts = inner.split(":")
            if (parts.size > 2) return null
            val rawStart = parts.getOrNull(0)?.trim().orEmpty()
            val rawEnd = parts.getOrNull(1)?.trim().orEmpty()
            val start = if (rawStart.isEmpty()) null else rawStart.toIntOrNull()
            val end = if (rawEnd.isEmpty()) null else rawEnd.toIntOrNull()
            // 写了内容却不是合法整数 -> 语法错误，不当作端点省略
            if (rawStart.isNotEmpty() && start == null) return null
            if (rawEnd.isNotEmpty() && end == null) return null
            return Step.Slice(start, end)
        }

        val idx = inner.toIntOrNull() ?: return null
        return Step.Index(idx)
    }

    private fun parseFilter(inner: String): Step? {
        var body = inner.removePrefix("?").trim()
        if (body.startsWith("(") && body.endsWith(")")) {
            body = body.substring(1, body.length - 1).trim()
        }
        if (!body.startsWith("@")) return null
        var expr = body.removePrefix("@").trim()
        if (expr.startsWith(".")) expr = expr.substring(1)

        // 纯存在性：?(@.field)
        if (expr.none { it in "=<>!" }) {
            return if (expr.isEmpty()) null else Step.Filter(Predicate.Exists(expr))
        }

        // 先长后短匹配运算符，避免 ">=" 被 ">" 抢先
        val op = listOf("==", "!=", ">=", "<=", ">", "<").firstOrNull { expr.contains(it) }
            ?: return null
        val at = expr.indexOf(op)
        val field = expr.substring(0, at).trim()
        if (field.isEmpty()) return null

        val rawRight = expr.substring(at + op.length).trim()
        val quoted = rawRight.length >= 2 &&
            ((rawRight.startsWith("'") && rawRight.endsWith("'")) ||
                (rawRight.startsWith("\"") && rawRight.endsWith("\"")))
        val value = if (quoted) rawRight.substring(1, rawRight.length - 1) else rawRight
        if (value.isEmpty()) return null

        val numeric = !quoted && value.toDoubleOrNull() != null
        return when (op) {
            "==" -> Step.Filter(Predicate.Equals(field, value, numeric))
            "!=" -> Step.Filter(Predicate.NotEquals(field, value, numeric))
            else -> {
                val num = value.toDoubleOrNull() ?: return null
                Step.Filter(Predicate.Compare(field, op, num))
            }
        }
    }

    // ---------------- 求值 ----------------

    private fun apply(element: JsonElement, step: Step): List<JsonElement> = when (step) {
        is Step.Field -> {
            val obj = if (element.isJsonObject) element.asJsonObject else return emptyList()
            val v = obj.get(step.name) ?: return emptyList()
            listOf(v)
        }
        is Step.Index -> {
            val arr = if (element.isJsonArray) element.asJsonArray else return emptyList()
            val idx = if (step.index < 0) arr.size() + step.index else step.index
            if (idx !in 0 until arr.size()) emptyList() else listOf(arr.get(idx))
        }
        Step.Wildcard -> when {
            element.isJsonArray -> toList(element.asJsonArray)
            element.isJsonObject -> element.asJsonObject.entrySet().map { it.value }
            else -> emptyList()
        }
        is Step.Slice -> {
            val arr = if (element.isJsonArray) element.asJsonArray else return emptyList()
            val size = arr.size()
            var s = step.start ?: 0
            var e = step.end ?: size
            if (s < 0) s += size
            if (e < 0) e += size
            s = s.coerceIn(0, size)
            e = e.coerceIn(0, size)
            if (e <= s) emptyList() else toList(arr).subList(s, e)
        }
        is Step.Recursive -> {
            val out = ArrayList<JsonElement>()
            collectByName(element, step.name, out, 0)
            out
        }
        is Step.Filter -> {
            val arr = if (element.isJsonArray) element.asJsonArray else return emptyList()
            toList(arr).filter { matches(it, step.predicate) }
        }
    }

    private fun toList(arr: JsonArray): List<JsonElement> = (0 until arr.size()).map { arr.get(it) }

    /** 递归下降；深度设上限，防止畸形深层 JSON 栈溢出。 */
    private fun collectByName(
        element: JsonElement,
        name: String,
        out: MutableList<JsonElement>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        when {
            element.isJsonObject -> {
                for ((k, v) in element.asJsonObject.entrySet()) {
                    if (k == name) out.add(v)
                    collectByName(v, name, out, depth + 1)
                }
            }
            element.isJsonArray -> {
                for (child in toList(element.asJsonArray)) collectByName(child, name, out, depth + 1)
            }
        }
    }

    private fun matches(candidate: JsonElement, predicate: Predicate): Boolean {
        if (!candidate.isJsonObject) return false
        val obj = candidate.asJsonObject
        return when (predicate) {
            is Predicate.Exists -> {
                val v = obj.get(predicate.field)
                v != null && !v.isJsonNull
            }
            is Predicate.Equals ->
                equality(obj, predicate.field, predicate.value, predicate.numeric, wantEqual = true)
            is Predicate.NotEquals ->
                equality(obj, predicate.field, predicate.value, predicate.numeric, wantEqual = false)
            is Predicate.Compare -> {
                val v = obj.get(predicate.field) ?: return false
                if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) return false
                val actual = v.asDouble
                when (predicate.op) {
                    ">" -> actual > predicate.value
                    ">=" -> actual >= predicate.value
                    "<" -> actual < predicate.value
                    "<=" -> actual <= predicate.value
                    else -> false
                }
            }
        }
    }

    private fun equality(
        obj: JsonObject,
        field: String,
        expected: String,
        numeric: Boolean,
        wantEqual: Boolean
    ): Boolean {
        val v = obj.get(field)
        // 字段缺失时：!= 视为满足，== 视为不满足
        if (v == null || v.isJsonNull) return !wantEqual
        val hit = when {
            numeric -> {
                val isNum = v.isJsonPrimitive && v.asJsonPrimitive.isNumber
                isNum && expected.toDoubleOrNull() != null && v.asDouble == expected.toDouble()
            }
            v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean == expected.toBoolean()
            v.isJsonPrimitive -> v.asString == expected
            else -> false
        }
        return if (wantEqual) hit else !hit
    }

    private const val MAX_DEPTH = 64
}
