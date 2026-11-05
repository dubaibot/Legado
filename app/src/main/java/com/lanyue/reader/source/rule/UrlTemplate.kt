package com.lanyue.reader.source.rule

/**
 * 1.4.3 searchUrl 占位符替换。
 *
 * 明确约束：
 * - 支持 {{key}} / {{page}}
 * - 无占位符时默认原样请求，不自动追加 ?q={key}
 *   （Legado AnalyzeUrl 无此行为，自动追加属猜测，见 TODO-DEV-3）
 */
object UrlTemplate {

    fun fillKey(template: String, key: String): String {
        val encoded = java.net.URLEncoder.encode(key, "UTF-8")
        return template.replace("{{key}}", encoded)
            .replace("{{key_raw}}", key)
    }

    fun fillPage(template: String, page: Int): String =
        template.replace("{{page}}", page.toString())

    fun fill(template: String, key: String?, page: Int): String {
        var out = fillPage(template, page)
        if (key != null) out = fillKey(out, key)
        return out
    }

    /** 是否含占位符。无占位符的 searchUrl 原样请求。 */
    fun hasKeyPlaceholder(template: String): Boolean = template.contains("{{key}}")

    fun hasPagePlaceholder(template: String): Boolean = template.contains("{{page}}")
}
