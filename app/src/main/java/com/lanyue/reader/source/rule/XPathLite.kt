package com.lanyue.reader.source.rule

/**
 * 7.1 XPath → CSS 转换（纯 Kotlin，无 Android）。
 *
 * Legado 书源大量使用 XPath 风格规则，早期实现把 `//div/a` 直接塞给 jsoup，
 * 必然匹配不到。这里把常见子集翻译成 jsoup 能执行的 CSS：
 *
 *   //div[@class='x']/a/text()      -> div.x > a  + 取文本
 *   //div[@id='y']//span            -> div#y span
 *   //ul/li[2]                      -> ul > li:nth-child(2)
 *   //a[@href]/@href                -> a[href]   + 取属性 href
 *   //div[contains(@class,'box')]   -> div[class*=box]
 *   //p[last()]                     -> p:last-child
 *   /html/body/div[1]              -> html > body > div:nth-child(1)
 *
 * 明确不支持的（返回 null，由调用方降级）：
 *   - 轴（ancestor:: / following-sibling::）
 *   - 函数（text() 之外的 normalize-space 等）
 *   - 复杂布尔表达式（and / or）
 *
 * 取舍理由：宁可在不支持时明确降级，也不要生成一个语义错误的 CSS
 * 把无关节点取回来——后者会造成"看起来有数据但全是错的"。
 */
object XPathLite {

    /** 转换结果：可执行的 CSS 选择器 + 取值方式。 */
    data class Converted(val css: String, val directive: String?)

    /**
     * 判断是否像 XPath。以 `/` 或 `//` 开头，且不是协议相对 URL（`//host/path`）。
     */
    fun looksLikeXPath(rule: String): Boolean {
        val t = rule.trim()
        if (!t.startsWith("/") && !t.startsWith("(//") && !t.startsWith("(./")) return false
        // //example.com/a 这种更像 URL 尾巴，不当 XPath
        if (t.startsWith("//")) {
            val firstSeg = t.trimStart('/').substringBefore('/')
            if (firstSeg.contains('.') && !firstSeg.contains('[') && !firstSeg.contains('@')) {
                return false
            }
        }
        return true
    }

    /**
     * 执行转换。无法转换返回 null。
     *
     * [xpath] 允许外层带括号，如 `(//div/a)[1]` 会剥离外层括号后处理。
     */
    fun toCss(xpath: String): Converted? {
        var expr = xpath.trim()
        if (expr.startsWith("(") && expr.endsWith(")")) {
            expr = expr.substring(1, expr.length - 1).trim()
        }
        if (!expr.startsWith("/")) return null
        if (expr.contains("::")) return null
        // 不支持布尔组合，避免把 a and b 翻成错误的选择器
        if (Regex("\\b(and|or)\\b").containsMatchIn(expr)) return null

        var directive: String? = null

        // 尾部 /text() 或 /@attr
        val textIdx = expr.lastIndexOf("/text()")
        if (textIdx >= 0 && textIdx == expr.length - "/text()".length) {
            directive = "text"
            expr = expr.substring(0, textIdx)
        } else {
            val attrMatch = Regex("/@([A-Za-z_:][-A-Za-z0-9_:.]*)$").find(expr)
            if (attrMatch != null) {
                directive = "attr:" + attrMatch.groupValues[1].lowercase()
                expr = expr.substring(0, attrMatch.range.first)
            }
        }

        if (!expr.startsWith("/")) return null

        // 切段：'//' 是后代组合，'/' 是直接子元素
        val tokens = ArrayList<Pair<Boolean, String>>() // (是否后代, 段文本)
        var i = 0
        var pendingDescendant = true // 以 '//' 开头时第一段是后代
        if (expr.startsWith("//")) {
            i = 2
            pendingDescendant = true
        } else {
            i = 1
            pendingDescendant = false
        }

        val sb = StringBuilder()
        while (i <= expr.length) {
            val segStart = i
            var depth = 0
            var inSingle = false
            var inDouble = false
            while (i < expr.length) {
                val c = expr[i]
                when {
                    inSingle -> if (c == '\'') inSingle = false
                    inDouble -> if (c == '"') inDouble = false
                    c == '\'' -> inSingle = true
                    c == '"' -> inDouble = true
                    c == '[' -> depth++
                    c == ']' -> depth--
                    c == '/' && depth == 0 -> break
                }
                i++
            }
            val raw = expr.substring(segStart, i).trim()
            if (raw.isNotEmpty()) tokens.add(pendingDescendant to raw)

            // 看下一个分隔符是 '/' 还是 '//'
            if (i < expr.length && expr[i] == '/') {
                if (i + 1 < expr.length && expr[i + 1] == '/') {
                    pendingDescendant = true
                    i += 2
                } else {
                    pendingDescendant = false
                    i += 1
                }
            } else {
                break
            }
        }

        if (tokens.isEmpty()) return null

        tokens.forEachIndexed { index, (descendant, raw) ->
            val part = convertSegment(raw) ?: return null
            if (index == 0) {
                sb.append(part)
            } else {
                sb.append(if (descendant) " " else " > ").append(part)
            }
        }

        val css = sb.toString().trim()
        if (css.isEmpty()) return null
        return Converted(css, directive)
    }

    /**
     * 转换单个路径段，如 `div[@class='x'][2]`。
     * 返回 css 片段；无法转换返回 null。
     */
    private fun convertSegment(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null

        // 纯通配
        if (t == "*") return "*"

        val nameEnd = t.indexOfFirst { it == '[' || it == '*' }
        var name = if (nameEnd < 0) t else t.substring(0, nameEnd).trim()
        if (name.isEmpty()) name = "*"
        if (name == "." || name == "..") return null
        if (!Regex("^[A-Za-z_][-A-Za-z0-9_.:]*$|^\\*$").matches(name)) return null

        val css = StringBuilder(name)
        var i = if (nameEnd < 0) t.length else nameEnd
        // 跳过可能紧跟的名字后缀 '*'，如 div*（极少见，直接拒绝更安全）
        if (nameEnd >= 0 && nameEnd < t.length && t[nameEnd] == '*') return null

        while (i < t.length) {
            if (t[i] != '[') return null
            var depth = 0
            var inSingle = false
            var inDouble = false
            val start = i
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
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                }
                i++
            }
            if (depth != 0) return null
            val pred = t.substring(start + 1, i - 1).trim()
            val converted = convertPredicate(pred) ?: return null
            css.append(converted)
        }

        return css.toString()
    }

    /**
     * 转换谓词，如 `@class='x'`、`2`、`last()`、`contains(@class,'box')`。
     */
    private fun convertPredicate(pred: String): String? {
        if (pred.isEmpty()) return null

        // 位置：[2] -> :nth-child(2)
        val pos = pred.toIntOrNull()
        if (pos != null) {
            if (pos == 0) return null // XPath 位置从 1 开始，0 非法
            if (pos < 0) return ":nth-last-child(${-pos})"
            return ":nth-child($pos)"
        }
        if (pred == "last()") return ":last-child"

        // contains(@attr,'v') -> [attr*=v]
        val containsCall = Regex("^contains\\s*\\(").find(pred)
        if (containsCall != null) {
            val inner = pred.substring(containsCall.range.last + 1).trimEnd(')').trim()
            val comma = findTopLevelComma(inner) ?: return null
            val attrPart = inner.substring(0, comma).trim()
            val valuePart = inner.substring(comma + 1).trim()
            val attr = attrPart.removePrefix("@").trim().lowercase()
            if (!isValidAttr(attr)) return null
            val value = unquoteValue(valuePart) ?: return null
            return "[$attr*=" + quoteCss(value) + "]"
        }

        // 存在性：[@href] -> [href]
        val exists = Regex("^@([-A-Za-z0-9_:]+)$").find(pred)
        if (exists != null) return "[" + exists.groupValues[1].lowercase() + "]"

        // 相等 / 不等：手工解析，允许值里出现另一种引号（如 'a"b'）
        val opIndex = findCompareOp(pred) ?: return null
        val op = opIndex.second
        val attr = pred.substring(0, opIndex.first).trim().removePrefix("@").trim().lowercase()
        if (!isValidAttr(attr)) return null
        val value = unquoteValue(pred.substring(opIndex.first + op.length).trim()) ?: return null
        return when (op) {
            "=" -> "[" + attr + "=" + quoteCss(value) + "]"
            "!=" -> ":not([" + attr + "=" + quoteCss(value) + "])"
            else -> null
        }
    }

    /** 校验属性名合法，避免把奇怪字符拼进选择器。 */
    private fun isValidAttr(attr: String): Boolean =
        attr.isNotEmpty() && Regex("^[a-z_][-a-z0-9_:.]*$").matches(attr)

    /** 找到谓词里的比较运算符（先长后短），返回 (下标, 运算符)。 */
    private fun findCompareOp(pred: String): Pair<Int, String>? {
        for (op in listOf("!=", "=")) {
            val at = pred.indexOf(op)
            if (at > 0) return at to op
        }
        return null
    }

    /** 去掉值的引号；单引号包裹时内部允许双引号，反之亦然。 */
    private fun unquoteValue(raw: String): String? {
        if (raw.length < 2) return null
        val first = raw.first()
        val last = raw.last()
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            val body = raw.substring(1, raw.length - 1)
            // 内部不能出现同种引号（出现说明表达式畸形）
            if (body.contains(first)) return null
            return body
        }
        return null
    }

    /** 找参数里的顶层逗号（不位于引号内）。 */
    private fun findTopLevelComma(s: String): Int? {
        var inSingle = false
        var inDouble = false
        for (i in s.indices) {
            val c = s[i]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> if (c == '"') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == ',' -> return i
            }
        }
        return null
    }

    /** CSS 属性值引号：统一双引号并转义内部双引号与反斜杠。 */
    private fun quoteCss(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"" + escaped + "\""
    }
}
