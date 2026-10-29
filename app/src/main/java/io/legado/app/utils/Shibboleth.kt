package io.legado.app.utils

import java.net.URI
import kotlin.random.Random

enum class ShibbolethType(val code: String) {
    BOOK_SOURCE("sy"),
    RSS_SOURCE("dy"),
    DICT_RULE("zd"),
    REPLACE_RULE("jh"),
    TOC_RULE("ml"),
    TTS_RULE("ld"),
    AUTO_TASK("rw");

    companion object {
        fun fromCode(code: String): ShibbolethType? = entries.find { it.code == code }
    }
}

data class ShibbolethToken(
    val url: String,
    val type: ShibbolethType,
    val expiresAtMillis: Long,
)

sealed interface ShibbolethParseResult {
    data object NotShibboleth : ShibbolethParseResult
    data class Invalid(val reason: Reason) : ShibbolethParseResult
    data class Expired(val expiresAtMillis: Long) : ShibbolethParseResult
    data class Valid(val token: ShibbolethToken) : ShibbolethParseResult
    enum class Reason { MALFORMED, UNKNOWN_TYPE, INVALID_URL }
}

object Shibboleth {

    private const val URL_MARKER = "#L:"
    private const val TYPE_MARKER = "！"
    private const val EXPIRY_MARKER = "©"
    private const val SUFFIX_MARKER = "¥"
    private const val END_MARKER = "^"
    private const val PREFIX = "复制口令到阅读导入"
    private const val OUTPUT_CUSTOM_WORD = "LegadoT"

    private val mappings = linkedMapOf(
        "https://" to listOf("#L:"),
        "." to listOf("电", "店", "垫", "殿", "。"),
        "%" to listOf("白", "百", "拜", "摆", "💯"),
        "/" to listOf("杠", "刚", "钢", "岗", "🎹"),
        "zip" to listOf("压", "亚", "呀", "牙", "🦆"),
        "json" to listOf("串", "穿", "船", "传", "🚢"),
        "4" to listOf("四", "是", "时", "丝", "🕓"),
        "5" to listOf("五", "武", "误", "勿", "🕔"),
        "6" to listOf("六", "刘", "留", "陆", "🕕"),
        "0" to listOf("零", "另", "玲", "灵", "⏰"),
        "com" to listOf("🛜1", "🌐1", "🌏1"),
        "cn" to listOf("🛜2", "🌐2", "🌏2"),
        "net" to listOf("🛜3", "🌐3", "🌏3"),
        "org" to listOf("🛜7", "🌐7", "🌏7"),
        "xyz" to listOf("🛜8", "🌐8", "🌏8"),
        "me" to listOf("🛜9", "🌐9", "🌏9"),
    )
    private val sortedMappingKeys = mappings.keys.sortedByDescending { it.length }
    private val reverseMappings = mappings.flatMap { (original, replacements) ->
        replacements.map { replacement -> replacement to original }
    }
    private val unsafeUrlTokens = mappings.values.flatten() +
        listOf(TYPE_MARKER, EXPIRY_MARKER, SUFFIX_MARKER, END_MARKER)

    fun canEncode(url: String): Boolean =
        url.startsWith("https://") &&
            isValidUrl(url, httpsOnly = true) &&
            unsafeUrlTokens.none(url::contains)

    fun encode(
        url: String,
        type: ShibbolethType,
        expiresAtMillis: Long,
        randomSeed: Long = System.currentTimeMillis(),
    ): String? {
        if (!canEncode(url) ||
            expiresAtMillis < 0 ||
            (expiresAtMillis > 0 && expiresAtMillis.toString().length < 7)
        ) {
            return null
        }

        val random = Random(randomSeed)
        val encodedUrl = buildString {
            var index = 0
            while (index < url.length) {
                val key = sortedMappingKeys.firstOrNull { url.startsWith(it, index) }
                if (key == null) {
                    append(url[index])
                    index++
                } else {
                    val replacements = mappings.getValue(key)
                    append(replacements[random.nextInt(replacements.size)])
                    index += key.length
                }
            }
        }
        val expiry = if (expiresAtMillis == 0L) "0" else expiresAtMillis.toString().take(7)
        return "$PREFIX$encodedUrl$TYPE_MARKER${type.code}$EXPIRY_MARKER$expiry" +
            "$SUFFIX_MARKER$OUTPUT_CUSTOM_WORD$END_MARKER"
    }

    fun parse(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ShibbolethParseResult {
        // 识别锚定 #L: 标记本身,前缀词各分支可自定;无标记时按前缀定位未编码链接
        val markerStart = text.indexOf(URL_MARKER)
        val prefixStart = text.indexOf(PREFIX)
        val urlStart = when {
            markerStart >= 0 -> markerStart
            prefixStart >= 0 -> prefixStart + PREFIX.length
            else -> return ShibbolethParseResult.NotShibboleth
        }

        val typeStart = text.indexOf(TYPE_MARKER, urlStart)
        val expiryStart = if (typeStart >= 0) text.indexOf(EXPIRY_MARKER, typeStart + 1) else -1
        val suffixStart = if (expiryStart >= 0) text.indexOf(SUFFIX_MARKER, expiryStart + 1) else -1
        val end = text.indexOf(END_MARKER, suffixStart + 1)
        if (typeStart <= urlStart ||
            expiryStart <= typeStart + TYPE_MARKER.length ||
            suffixStart <= expiryStart + EXPIRY_MARKER.length ||
            end < suffixStart + SUFFIX_MARKER.length
        ) {
            // 分隔符链不全:带前缀视为残缺口令,裸 #L: 视为普通文本防误报
            return if (prefixStart >= 0) {
                ShibbolethParseResult.Invalid(ShibbolethParseResult.Reason.MALFORMED)
            } else {
                ShibbolethParseResult.NotShibboleth
            }
        }

        val typeCode = text.substring(typeStart + TYPE_MARKER.length, expiryStart)
        val type = ShibbolethType.fromCode(typeCode)
            ?: return ShibbolethParseResult.Invalid(ShibbolethParseResult.Reason.UNKNOWN_TYPE)
        val expiryText = text.substring(expiryStart + EXPIRY_MARKER.length, suffixStart)
        if (expiryText.any { it !in '0'..'9' }) {
            return ShibbolethParseResult.Invalid(ShibbolethParseResult.Reason.MALFORMED)
        }
        // 7位=毫秒前7位截断约定,10/13位=秒/毫秒完整时间戳,其余宽度不强制有效期
        val expiresAtMillis = when (expiryText.length) {
            7 -> expiryText.toLong() * 1_000_000L
            10 -> expiryText.toLong() * 1_000L
            13 -> expiryText.toLong()
            else -> 0L
        }

        var url = text.substring(urlStart, typeStart).trim()
        if (url.startsWith(URL_MARKER)) {
            reverseMappings.forEach { (replacement, original) ->
                url = url.replace(replacement, original)
            }
        }
        if (!isValidUrl(url, httpsOnly = false)) {
            return ShibbolethParseResult.Invalid(ShibbolethParseResult.Reason.INVALID_URL)
        }
        if (expiresAtMillis > 0 && expiresAtMillis < nowMillis) {
            return ShibbolethParseResult.Expired(expiresAtMillis)
        }
        return ShibbolethParseResult.Valid(ShibbolethToken(url, type, expiresAtMillis))
    }

    private fun isValidUrl(url: String, httpsOnly: Boolean): Boolean = runCatching {
        val uri = URI(url)
        val validScheme = uri.scheme.equals("https", ignoreCase = true) ||
            (!httpsOnly && uri.scheme.equals("http", ignoreCase = true))
        validScheme && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
