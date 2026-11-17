package com.lanyue.reader.source.import

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.source.model.SourceValidator

/**
 * 书源 / 订阅源 JSON -> 数据库实体（纯逻辑，无 Android / 无网络）。
 *
 * 为什么需要这一层：`SourceImportParser` 只回答「这段文本里有几个书源、各自的
 * url 和名字是什么」，它刻意只保留 `url / name / group / type / raw` 五个字段。
 * 而真正让书源能解析的 `searchUrl`、`ruleSearch`、`ruleToc`、`ruleContent`、
 * `ruleBookInfo`、`sortUrl`、`ruleArticles` 等全部躺在 `raw` 原文里，此前**没有
 * 任何代码把它们拆出来写进实体**——结果是源进了列表却解析不出任何内容，
 * `LegadoBookSource` / `LegadoRssSource` 也因此永远拿不到规则。
 *
 * 三个设计取舍：
 * 1. **保留原文**：实体里嵌套规则字段按 `Converters.objectToJson` 的同一约定存
 *    紧凑 JSON 字符串，不拆成列。书源更新时要能原样回写，且解析失败也不能丢原文。
 * 2. **类型宽容**：真实书源里 `bookSourceType` 既有 `0` 也有 `"0"`，`enabled` 既有
 *    `true` 也有 `"true"`。逐字段兜底，不让一个字段的类型癖好废掉整个源。
 * 3. **单条失败不连坐**：导入 200 个源时，2 个坏条目不该让 198 个陪葬，
 *    所以提供批量映射并把坏条目连同原因单独返回。
 */
object SourceEntityMapper {

    /** 一条被拒绝的原始条目。 */
    data class Reject(val index: Int, val reason: String)

    /** 书源批量映射结果。 */
    data class BookMapResult(
        val entities: List<BookSourceEntity>,
        val rejects: List<Reject>
    ) {
        val accepted: Int get() = entities.size
        val rejected: Int get() = rejects.size
        val total: Int get() = accepted + rejected
    }

    /** 订阅源批量映射结果。 */
    data class RssMapResult(
        val entities: List<RssSourceEntity>,
        val rejects: List<Reject>
    ) {
        val accepted: Int get() = entities.size
        val rejected: Int get() = rejects.size
        val total: Int get() = accepted + rejected
    }

    // ==================== 书源 ====================

    /**
     * 单条书源原文 -> 实体。无法使用时返回 null（不要抛异常打断整批导入）。
     *
     * 判废条件只有两条：不是 JSON 对象、`bookSourceUrl` 不是 http/https。
     * 后者与 `SourceValidator.validateBook` 的 ERROR 档保持一致——地址错的源
     * 即使入库也解析不出任何东西，早一步拒绝比留个空壳更有用。
     */
    fun toBookEntity(raw: String?): BookSourceEntity? = bookEntityOrReason(raw)?.first

    /** 同上，但把判废原因一并带出，供导入结果提示用。 */
    fun bookEntityOrReason(raw: String?): Pair<BookSourceEntity, String>? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return null

        val url = str(obj, "bookSourceUrl") ?: return null
        if (!SourceValidator.isHttpUrl(url)) return null

        val entity = BookSourceEntity(
            bookSourceUrl = url,
            bookSourceName = str(obj, "bookSourceName") ?: "",
            bookSourceGroup = str(obj, "bookSourceGroup"),
            bookSourceType = int(obj, "bookSourceType") ?: 0,
            enabled = bool(obj, "enabled") ?: true,
            enabledExplore = bool(obj, "enabledExplore") ?: false,
            searchUrl = blankToNull(str(obj, "searchUrl")),
            ruleSearch = rule(obj, "ruleSearch"),
            exploreUrl = blankToNull(str(obj, "exploreUrl")),
            ruleExplore = rule(obj, "ruleExplore"),
            ruleToc = rule(obj, "ruleToc"),
            ruleContent = rule(obj, "ruleContent"),
            ruleBookInfo = rule(obj, "ruleBookInfo"),
            header = jsonish(obj, "header"),
            loginUrl = blankToNull(str(obj, "loginUrl")),
            loginUi = blankToNull(str(obj, "loginUi")),
            loginCheckJs = blankToNull(str(obj, "loginCheckJs")),
            jsLib = blankToNull(str(obj, "jsLib")),
            concurrentRate = blankToNull(numAsString(obj, "concurrentRate")),
            lastUpdateTime = long(obj, "lastUpdateTime") ?: 0L,
            userEditedFields = str(obj, "userEditedFields"),
            searchDegraded = bool(obj, "searchDegraded") ?: false,
            searchFailCount = int(obj, "searchFailCount") ?: 0,
            replaceRule = rule(obj, "replaceRule")
        )
        return entity to ""
    }

    /**
     * 批量映射书源。坏条目记入 [BookMapResult.rejects] 并继续处理后面的条目。
     * 同一 `bookSourceUrl` 重复出现时保后者——与 `SourceRepository.install`
     * 「输入内去重、后到的覆盖先到的」语义一致，避免两处行为不一致。
     */
    fun mapBooks(raws: List<String?>): BookMapResult {
        val entities = LinkedHashMap<String, BookSourceEntity>()
        val rejects = ArrayList<Reject>()

        raws.forEachIndexed { index, raw ->
            val parsed = bookEntityOrReason(raw)
            if (parsed == null) {
                rejects.add(Reject(index, reasonOfBook(raw)))
            } else {
                entities[parsed.first.bookSourceUrl] = parsed.first
            }
        }
        return BookMapResult(entities.values.toList(), rejects)
    }

    /** 判废原因，只在 [mapBooks] 里用，保证提示与判废条件一一对应。 */
    private fun reasonOfBook(raw: String?): String {
        if (raw.isNullOrBlank()) return "内容为空"
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return "不是 JSON 对象"
        val url = str(obj, "bookSourceUrl")
        if (url.isNullOrBlank()) return "缺少 bookSourceUrl"
        if (!SourceValidator.isHttpUrl(url)) return "bookSourceUrl 不是 http/https"
        return "无法映射"
    }

    // ==================== 订阅源 ====================

    /**
     * 单条订阅源原文 -> 实体。判废条件同书源：非 JSON 对象、`sourceUrl` 非 http/https。
     *
     * 订阅源无目录层（ADR-012），规则是扁平字符串而非嵌套对象，
     * 因此 `ruleArticles` / `ruleTitle` 等按原样字符串存，不走 [rule] 的 JSON 归一。
     */
    fun toRssEntity(raw: String?): RssSourceEntity? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return null

        val url = str(obj, "sourceUrl") ?: return null
        if (!SourceValidator.isHttpUrl(url)) return null

        return RssSourceEntity(
            sourceUrl = url,
            sourceName = str(obj, "sourceName") ?: "",
            sourceIcon = blankToNull(str(obj, "sourceIcon")),
            sourceGroup = blankToNull(str(obj, "sourceGroup")),
            sourceComment = blankToNull(str(obj, "sourceComment")),
            enabled = bool(obj, "enabled") ?: true,
            enabledCookieJar = bool(obj, "enabledCookieJar") ?: false,
            enableJs = bool(obj, "enableJs") ?: true,
            loadWithBaseUrl = bool(obj, "loadWithBaseUrl") ?: false,
            singleUrl = bool(obj, "singleUrl") ?: false,
            header = jsonish(obj, "header"),
            concurrentRate = blankToNull(numAsString(obj, "concurrentRate")),
            loginUrl = blankToNull(str(obj, "loginUrl")),
            loginUi = blankToNull(str(obj, "loginUi")),
            loginCheckJs = blankToNull(str(obj, "loginCheckJs")),
            sortUrl = blankToNull(str(obj, "sortUrl")),
            ruleArticles = blankToNull(str(obj, "ruleArticles")),
            ruleNextPage = blankToNull(str(obj, "ruleNextPage")),
            ruleTitle = blankToNull(str(obj, "ruleTitle")),
            rulePubDate = blankToNull(str(obj, "rulePubDate")),
            ruleImage = blankToNull(str(obj, "ruleImage")),
            ruleLink = blankToNull(str(obj, "ruleLink")),
            ruleDescription = blankToNull(str(obj, "ruleDescription")),
            ruleContent = blankToNull(str(obj, "ruleContent")),
            style = blankToNull(str(obj, "style")),
            injectJs = blankToNull(str(obj, "injectJs")),
            preloadJs = blankToNull(str(obj, "preloadJs")),
            startHtml = blankToNull(str(obj, "startHtml")),
            showWebLog = bool(obj, "showWebLog") ?: false,
            shouldOverrideUrlLoading = blankToNull(str(obj, "shouldOverrideUrlLoading")),
            contentWhitelist = blankToNull(str(obj, "contentWhitelist")),
            contentBlacklist = blankToNull(str(obj, "contentBlacklist")),
            type = int(obj, "type") ?: 0,
            articleStyle = int(obj, "articleStyle") ?: 0,
            coverDecodeJs = blankToNull(str(obj, "coverDecodeJs")),
            searchUrl = blankToNull(str(obj, "searchUrl")),
            jsLib = blankToNull(str(obj, "jsLib")),
            variables = blankToNull(str(obj, "variables")),
            variableComment = blankToNull(str(obj, "variableComment")),
            customOrder = int(obj, "customOrder") ?: 0,
            lastUpdateTime = long(obj, "lastUpdateTime") ?: 0L,
            preload = bool(obj, "preload") ?: false,
            cacheFirst = bool(obj, "cacheFirst") ?: false,
            searchDegraded = bool(obj, "searchDegraded") ?: false,
            searchFailCount = int(obj, "searchFailCount") ?: 0,
            userEditedFields = str(obj, "userEditedFields")
        )
    }

    /** 批量映射订阅源，语义同 [mapBooks]。 */
    fun mapRss(raws: List<String?>): RssMapResult {
        val entities = LinkedHashMap<String, RssSourceEntity>()
        val rejects = ArrayList<Reject>()

        raws.forEachIndexed { index, raw ->
            val entity = toRssEntity(raw)
            if (entity == null) {
                rejects.add(Reject(index, reasonOfRss(raw)))
            } else {
                entities[entity.sourceUrl] = entity
            }
        }
        return RssMapResult(entities.values.toList(), rejects)
    }

    private fun reasonOfRss(raw: String?): String {
        if (raw.isNullOrBlank()) return "内容为空"
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return "不是 JSON 对象"
        val url = str(obj, "sourceUrl")
        if (url.isNullOrBlank()) return "缺少 sourceUrl"
        if (!SourceValidator.isHttpUrl(url)) return "sourceUrl 不是 http/https"
        return "无法映射"
    }

    // ==================== 能力判定 ====================

    /**
     * 书源是否可搜索。与 `SourceDaos.searchable()` 的 SQL 条件保持一致
     * （enabled + 未降级 + searchUrl 非空 + ruleSearch 非空），
     * 便于在纯 JVM 层校验「入库后会进搜索池」而不用连数据库。
     */
    fun isSearchable(entity: BookSourceEntity): Boolean =
        entity.enabled &&
            !entity.searchDegraded &&
            !entity.searchUrl.isNullOrBlank() &&
            !entity.ruleSearch.isNullOrBlank()

    /** 书源是否可发现（enabledExplore + exploreUrl 非空），对应 `discoverable()`。 */
    fun isDiscoverable(entity: BookSourceEntity): Boolean =
        entity.enabled &&
            entity.enabledExplore &&
            !entity.exploreUrl.isNullOrBlank()

    // ==================== 字段读取兜底 ====================

    private fun el(obj: com.google.gson.JsonObject, key: String): JsonElement? {
        val v = obj.get(key) ?: return null
        return if (v.isJsonNull) null else v
    }

    /** 取字符串：数字/布尔也接受，因为真实书源里 `"type": "0"` 很常见。 */
    private fun str(obj: com.google.gson.JsonObject, key: String): String? {
        val v = el(obj, key) ?: return null
        return when {
            v.isJsonPrimitive -> v.asJsonPrimitive.asString
            else -> v.toString()
        }
    }

    private fun numAsString(obj: com.google.gson.JsonObject, key: String): String? {
        val v = el(obj, key) ?: return null
        return if (v.isJsonPrimitive) v.asJsonPrimitive.asString else null
    }

    private fun int(obj: com.google.gson.JsonObject, key: String): Int? =
        str(obj, key)?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    private fun long(obj: com.google.gson.JsonObject, key: String): Long? =
        str(obj, key)?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

    /** 布尔：`true` / `"true"` / `1` / `"1"` 都算真；认不出就返回 null 交给默认值。 */
    private fun bool(obj: com.google.gson.JsonObject, key: String): Boolean? {
        val v = el(obj, key) ?: return null
        if (v.isJsonPrimitive) {
            val p = v.asJsonPrimitive
            if (p.isBoolean) return p.asBoolean
            return when (p.asString.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
        }
        return null
    }

    /**
     * 规则字段：字符串规则原样保留，对象/数组按紧凑 JSON 串存。
     *
     * 不能统一 `toString()` —— `"class.content@html"` 这类字符串规则若被加上引号，
     * `RuleEngine` 解析时会把整串当成字面量选择器，规则立刻失效。
     */
    private fun rule(obj: com.google.gson.JsonObject, key: String): String? {
        val v = el(obj, key) ?: return null
        if (v.isJsonPrimitive) {
            val s = v.asJsonPrimitive.asString
            return blankToNull(s)
        }
        val json = v.toString()
        return blankToNull(json)
    }

    /** `header` 可能是 JSON 串也可能已是对象，统一成 JSON 串。 */
    private fun jsonish(obj: com.google.gson.JsonObject, key: String): String? {
        val v = el(obj, key) ?: return null
        return blankToNull(if (v.isJsonPrimitive) v.asJsonPrimitive.asString else v.toString())
    }

    private fun blankToNull(s: String?): String? =
        if (s.isNullOrBlank()) null else s
}
