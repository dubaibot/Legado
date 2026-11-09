package com.lanyue.reader.source.import

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.data.db.entity.RssSourceEntity

/**
 * 导入的原始书源 JSON -> 可运行实体。
 *
 * 支持两套真实格式：
 * 1. Legado 标准格式（英文键；ruleSearch/ruleBookInfo/ruleToc/ruleContent 为嵌套 JSON 对象）；
 * 2. 乐享系中文扁平键（名称/搜索/详情/目录/正文；搜索往往是地址模板而非规则）。
 *
 * 别名表与 [SourceImportParser] 保持一致；改动时两边同步。
 * 规则值归一化：嵌套 JSON 对象原样序列化（适配层按 JSON 取子字段）；
 * 扁平字符串规则包一层 {"bookList": ...} / {"chapterList": ...}，
 * 让适配层至少拿到可用的列表规则。地址模板里的 {name}/{key}/{page}
 * 占位符归一化为本引擎的 {{key}}/{{page}}。
 */
object SourceEntityConverter {

    // ============================ 别名表（与 SourceImportParser 同步） ============================

    private val URL_KEYS = listOf("bookSourceUrl", "sourceUrl", "url", "bookSourceURL", "id", "网址")
    private val NAME_KEYS = listOf("bookSourceName", "sourceName", "name", "title", "名称", "书源名称")
    private val GROUP_KEYS = listOf("bookSourceGroup", "sourceGroup", "group", "category", "分组")
    private val TYPE_KEYS = listOf("bookSourceType", "sourceType", "type", "类型")
    private val HEADER_KEYS = listOf("header", "请求头")
    private val RATE_KEYS = listOf("concurrentRate", "并发率", "并发")
    private val UPDATED_KEYS = listOf("lastUpdateTime", "更新时间")

    // ============================ 公开入口 ============================

    /** 书源 JSON -> BookSourceEntity；地址缺失或非 http 返回 null。 */
    fun toBookEntity(raw: String): BookSourceEntity? {
        val obj = parseObject(raw) ?: return null
        // 显式地址字段优先；乐享中文扁平格式没有地址键，从「搜索/详情」规则模板
        // 推导基址（与 SourceImportParser.deriveUrl 一致），否则运行时无法装配。
        val url = firstString(obj, URL_KEYS)?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: SourceImportParser.httpBase(
                firstString(obj, listOf("搜索", "详情", "详细", "searchUrl"))
            )?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        val name = firstString(obj, NAME_KEYS)?.trim()?.takeIf { it.isNotEmpty() }
            ?: hostOf(url)

        // searchUrl：显式 searchUrl，或扁平「搜索」里的地址模板
        val searchUrl = firstString(obj, listOf("searchUrl"))?.takeIf { it.isNotBlank() }
            ?: urlLikeRule(obj, "搜索")

        return BookSourceEntity(
            bookSourceUrl = url,
            bookSourceName = name,
            bookSourceGroup = firstString(obj, GROUP_KEYS)?.trim()?.takeIf { it.isNotEmpty() },
            bookSourceType = (firstInt(obj, TYPE_KEYS) ?: 0).coerceIn(0, 3),
            enabled = firstBool(obj, listOf("enabled", "启用")) ?: true,
            enabledExplore = firstBool(obj, listOf("enabledExplore", "启用发现")) ?: false,
            searchUrl = searchUrl?.let { normalizeTemplate(it) },
            ruleSearch = ruleObject(obj, listOf("ruleSearch", "搜索规则"), "搜索", "bookList"),
            ruleBookInfo = ruleObject(obj, listOf("ruleBookInfo", "详情规则"), "详情", null),
            ruleToc = ruleObject(obj, listOf("ruleToc", "目录规则", "目录"), "目录", "chapterList"),
            ruleContent = ruleObject(obj, listOf("ruleContent", "正文规则", "内容规则", "内容", "正文"), "内容", null),
            header = firstString(obj, HEADER_KEYS)?.trim()?.takeIf { it.isNotEmpty() },
            loginUrl = firstString(obj, listOf("loginUrl"))?.trim()?.takeIf { it.isNotEmpty() },
            jsLib = firstString(obj, listOf("jsLib"))?.trim()?.takeIf { it.isNotEmpty() },
            concurrentRate = firstString(obj, RATE_KEYS)?.trim()?.takeIf { it.isNotEmpty() },
            lastUpdateTime = firstLong(obj, UPDATED_KEYS) ?: 0L,
            exploreUrl = firstString(obj, listOf("exploreUrl"))?.takeIf { it.isNotBlank() }
                ?: urlLikeRule(obj, "发现")?.let { normalizeTemplate(it) }
        )
    }

    /** 订阅源 JSON -> RssSourceEntity；地址缺失或非 http 返回 null。 */
    fun toRssEntity(raw: String): RssSourceEntity? {
        val obj = parseObject(raw) ?: return null
        val url = firstString(obj, URL_KEYS)?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        val name = firstString(obj, NAME_KEYS)?.trim()?.takeIf { it.isNotEmpty() }
            ?: hostOf(url)

        // ruleArticles 在适配层是扁平列表规则；Legado 嵌套对象取 list 子键
        val articles = flatRule(obj, listOf("ruleArticles", "文章列表", "列表"), "最新")
        val title = flatRule(obj, listOf("ruleTitle", "标题"), "标题")
        val link = flatRule(obj, listOf("ruleLink", "链接"), "链接")

        return RssSourceEntity(
            sourceUrl = url,
            sourceName = name,
            sourceGroup = firstString(obj, GROUP_KEYS)?.trim()?.takeIf { it.isNotEmpty() },
            sourceComment = firstString(obj, listOf("sourceComment", "说明"))?.trim()?.takeIf { it.isNotEmpty() },
            enabled = firstBool(obj, listOf("enabled", "启用")) ?: true,
            header = firstString(obj, HEADER_KEYS)?.trim()?.takeIf { it.isNotEmpty() },
            concurrentRate = firstString(obj, RATE_KEYS)?.trim()?.takeIf { it.isNotEmpty() },
            loginUrl = firstString(obj, listOf("loginUrl"))?.trim()?.takeIf { it.isNotEmpty() },
            sortUrl = firstString(obj, listOf("sortUrl", "分类地址"))?.trim()?.takeIf { it.isNotEmpty() }
                ?: flatRule(obj, listOf("sortUrl", "分类"), "分类")?.let { normalizeTemplate(it) },
            ruleArticles = articles?.let { normalizeTemplate(it) },
            ruleNextPage = flatRule(obj, listOf("ruleNextPage", "下一页"), "下一页")?.let { normalizeTemplate(it) },
            ruleTitle = title,
            rulePubDate = flatRule(obj, listOf("rulePubDate", "发布时间", "时间"), "时间"),
            ruleImage = flatRule(obj, listOf("ruleImage", "封面", "图片"), "图片"),
            ruleLink = link,
            ruleDescription = flatRule(obj, listOf("ruleDescription", "描述", "简介"), "描述"),
            ruleContent = flatRule(obj, listOf("ruleContent", "正文规则", "内容规则"), "内容"),
            type = (firstInt(obj, TYPE_KEYS) ?: 0).coerceIn(0, 2),
            lastUpdateTime = firstLong(obj, UPDATED_KEYS) ?: 0L,
            searchUrl = firstString(obj, listOf("searchUrl"))?.takeIf { it.isNotBlank() }
                ?.let { normalizeTemplate(it) }
        )
    }

    // ============================ 规则归一化 ============================

    /**
     * 规则字段取值：优先显式键（英文），回落中文键；
     * 值是 JSON 对象则原样序列化，是扁平字符串则包一层 [wrapKey]。
     * 「规则/rules」嵌套对象里也找一遍。
     */
    private fun ruleObject(obj: JsonObject, keys: List<String>, cnKey: String, wrapKey: String?): String? {
        val raw = ruleRaw(obj, keys) ?: nestedRuleRaw(obj, cnKey) ?: return null
        val t = raw.trim()
        if (t.isEmpty()) return null
        return when (val el = parse(t)) {
            is JsonObject -> t
            else -> wrapKey?.let { """{"$wrapKey":${quote(t)}}""" } ?: t
        }
    }

    /** 订阅源规则：扁平字符串原样返回（适配层拼 @title/@href 用）。 */
    private fun flatRule(obj: JsonObject, keys: List<String>, cnKey: String): String? {
        val raw = ruleRaw(obj, keys) ?: nestedRuleRaw(obj, cnKey) ?: return null
        val t = raw.trim()
        if (t.isEmpty()) return null
        // Legado 嵌套对象形态：list/title/link 子键 —— 列表取 list
        val el = parse(t)
        if (el is JsonObject) {
            return (el.get("list") ?: el.get("列表"))?.takeIf { it.isJsonPrimitive }?.asString
                ?.trim()?.takeIf { it.isNotEmpty() }
        }
        return t
    }

    /** 取规则字段原始文本：JsonObject 序列化为 JSON，JsonPrimitive 取字符串。 */
    private fun ruleRaw(obj: JsonObject, keys: List<String>): String? {
        for (k in keys) {
            val el = obj.get(k) ?: continue
            when {
                el.isJsonObject -> return el.toString()
                el.isJsonPrimitive -> {
                    val v = try { el.asString } catch (e: Throwable) { continue }
                    if (v.isNotBlank()) return v
                }
            }
        }
        return null
    }

    /** 「规则」/「rules」嵌套对象里找中文键的原始文本（对象序列化或字符串）。 */
    private fun nestedRuleRaw(obj: JsonObject, cnKey: String): String? {
        for (wrapper in listOf("规则", "rules")) {
            val el = obj.get(wrapper) ?: continue
            if (el.isJsonObject) {
                ruleRaw(el.asJsonObject, listOf(cnKey))?.let { return it }
            }
        }
        return null
    }

    /** 值像地址模板（http 开头或 / 开头）才返回。 */
    private fun urlLikeRule(obj: JsonObject, cnKey: String): String? {
        val v = firstString(obj, listOf(cnKey)) ?: return null
        val t = v.trim()
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("/")) return t
        return null
    }

    /** {name}/{key}/{searchKey} -> {{key}}；{page}/{searchPage} -> {{page}}。
     *  其他占位符（如乐享 {type}）原样保留。单遍正则 + 负向断言，
     *  避免把已归一化的 {{key}} 里的 {key} 再吃一遍产生 {{{key}}}。 */
    fun normalizeTemplate(template: String): String =
        PLACEHOLDER_RX.replace(template) { m ->
            val name = m.groupValues[1]
            PLACEHOLDER_MAP[name]?.let { "{{$it}}" } ?: m.value
        }

    private val PLACEHOLDER_MAP = mapOf(
        "searchKey" to "key",
        "searchPage" to "page",
        "name" to "key",
        "key" to "key",
        "page" to "page"
    )
    private val PLACEHOLDER_RX =
        Regex("""(?<!\{)\{([a-zA-Z_][a-zA-Z0-9_]*)\}""")

    // ============================ 基础取值 ============================

    private fun parseObject(raw: String): JsonObject? =
        (parse(raw) as? JsonObject)

    private fun parse(text: String): JsonElement? = try {
        JsonParser.parseString(text)
    } catch (e: Throwable) {
        null
    }

    private fun firstString(obj: JsonObject, keys: List<String>): String? {
        for (k in keys) {
            val el = obj.get(k) ?: continue
            if (!el.isJsonPrimitive) continue
            val v = try { el.asString } catch (e: Throwable) { continue }
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun firstInt(obj: JsonObject, keys: List<String>): Int? =
        firstString(obj, keys)?.trim()?.toDoubleOrNull()?.toInt()

    private fun firstLong(obj: JsonObject, keys: List<String>): Long? =
        firstString(obj, keys)?.trim()?.toLongOrNull()

    private fun firstBool(obj: JsonObject, keys: List<String>): Boolean? = when (
        firstString(obj, keys)?.trim()?.lowercase()
    ) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/')

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
