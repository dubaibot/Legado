package com.lanyue.reader.source.rule

/**
 * 1.4.3 searchUrl/exploreUrl 占位符替换。
 *
 * 明确约束：
 * - 支持 {{key}} / {{page}} / {{page±N}}（真实书源常用 {{page-1}} 让首请求从 0 开始，
 *   对齐 Legado AnalyzeUrl 中 {{page-1}} 经 JS 求值的行为，这里用正则覆盖该子集）
 * - 无占位符时默认原样请求，不自动追加 ?q={key}
 *   （Legado AnalyzeUrl 无此行为，自动追加属猜测，见 TODO-DEV-3）
 */
object UrlTemplate {

    /** {{page}}、{{ page - 1 }}、{{page+2}} 等页码表达式。 */
    private val PAGE_EXPR = Regex("\\{\\{\\s*page\\s*(?:([+-])\\s*(\\d+))?\\s*\\}\\}")

    fun fillKey(template: String, key: String): String {
        val encoded = java.net.URLEncoder.encode(key, "UTF-8")
        return template.replace("{{key}}", encoded)
            .replace("{{key_raw}}", key)
    }

    fun fillPage(template: String, page: Int): String =
        PAGE_EXPR.replace(template) { m ->
            val delta = m.groupValues[2].toIntOrNull() ?: 0
            val value = if (m.groupValues[1] == "-") page - delta else page + delta
            value.toString()
        }

    fun fill(template: String, key: String?, page: Int): String {
        var out = fillPage(template, page)
        if (key != null) out = fillKey(out, key)
        return out
    }

    /** 是否含占位符。无占位符的 searchUrl 原样请求。 */
    fun hasKeyPlaceholder(template: String): Boolean = template.contains("{{key}}")

    fun hasPagePlaceholder(template: String): Boolean = template.contains("{{page}}")
}
