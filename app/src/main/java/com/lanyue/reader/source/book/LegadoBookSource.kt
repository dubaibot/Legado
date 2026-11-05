package com.lanyue.reader.source.book

import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.engine.NetworkException
import com.lanyue.reader.core.engine.RateLimit
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.source.model.BookDetail
import com.lanyue.reader.source.model.CapabilityResolver
import com.lanyue.reader.source.model.ContentResult
import com.lanyue.reader.source.model.IBookSource
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.model.SortRule
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.SourceType
import com.lanyue.reader.source.model.TocItem
import com.lanyue.reader.source.rule.RuleEngine
import com.lanyue.reader.source.rule.UrlTemplate
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 7.1 书源实现。
 *
 * 链路：searchUrl -> NetworkDispatcher -> 规则解析 -> 格式化回传。
 * 特殊链路：bookSourceType=1 音频源正文直接返回 Audio；登录源走 loginCheckJs。
 */
class LegadoBookSource(
    private val entity: BookSourceEntity,
    private val net: NetworkDispatcher,
    private val rules: RuleEngine,
    private val metrics: LocalMetrics
) : IBookSource {

    override val sourceId: String = entity.bookSourceUrl

    override val sourceType: SourceType = SourceType.BOOK

    override val capabilityFlags: Set<SourceCapability> = CapabilityResolver.ofBook(entity)

    override val concurrentRate: RateLimit? = parseRate(entity.concurrentRate)

    /** 1.4.2 HAS_CONTENT：无 ruleContent 时正文只能走 WebView。 */
    override suspend fun getContent(url: String): ContentResult {
        val ctx = ruleContext(url, "content")

        if (!capabilityFlags.contains(SourceCapability.HAS_CONTENT)) {
            return ContentResult.WebViewFallback(url)
        }

        val html = fetchHtml(url, "content")

        // 1.4.7 bookSourceType=1：不解析 HTML，直接取音频地址
        if (entity.bookSourceType == 1) {
            val raw = rules.evalString(html, entity.ruleContent, ctx, needsRealDom = false)
                ?: return ContentResult.WebViewFallback(url)
            return parseAudioResult(raw)
        }

        return ContentResult.Html(html)
    }

    override suspend fun search(key: String, page: Int): List<SearchBookItem> {
        val searchUrl = entity.searchUrl ?: return emptyList()
        // 1.4.3 占位符替换；无占位符时原样请求（不自动追加 ?q=）
        val url = UrlTemplate.fill(searchUrl, key, page)
        val html = fetchHtml(url, "search")
        val ctx = ruleContext(url, "search")
        return parseBookItems(html, searchBookListRule(), ctx)
    }

    override suspend fun explore(sort: SortRule, page: Int): List<SearchBookItem> {
        val url = UrlTemplate.fillPage(sort.url, page)
        val html = fetchHtml(url, "explore")
        val ctx = ruleContext(url, "explore")
        return parseBookItems(html, entity.ruleExplore, ctx)
    }

    override suspend fun getDetail(url: String): BookDetail {
        val html = fetchHtml(url, "detail")
        val ctx = ruleContext(url, "detail")

        // ruleBookInfo 是嵌套对象，按 JSON 取 name/author/cover/intro 等字段
        val info = runCatching { JsonParser.parseString(entity.ruleBookInfo ?: "{}").asJsonObject }
            .getOrElse { JsonObject() }

        suspend fun field(key: String): String? {
            if (!info.has(key)) return null
            return rules.evalString(html, info.get(key).asString, ctx)
        }

        return BookDetail(
            name = field("name") ?: "",
            author = field("author"),
            cover = field("cover"),
            intro = field("intro"),
            kind = field("kind"),
            lastChapter = field("lastChapter"),
            wordCount = field("wordCount"),
            bookUrl = url
        )
    }

    override suspend fun getToc(url: String): List<TocItem> {
        val html = fetchHtml(url, "toc")
        val ctx = ruleContext(url, "toc")

        val toc = runCatching { JsonParser.parseString(entity.ruleToc ?: "{}").asJsonObject }
            .getOrElse { JsonObject() }

        // chapterList 选中章节元素集合，再从中取名字与链接
        val chapterListRule = toc.get("chapterList")?.asString
        if (chapterListRule.isNullOrBlank()) return emptyList()

        val nameRule = toc.get("chapterName")?.asString ?: "text"
        val urlRule = toc.get("chapterUrl")?.asString ?: "href"

        val names = rules.evalList(html, chapterListRule + "@" + nameRule, ctx)
        val links = rules.evalList(html, chapterListRule + "@" + urlRule, ctx)

        return names.mapIndexed { index, name ->
            TocItem(
                title = name,
                url = links.getOrNull(index) ?: url,
                index = index
            )
        }
    }

    // ---------- 内部 ----------

    private suspend fun fetchHtml(url: String, op: String): String {
        val traceId = sourceId + ":" + op
        val response = net.execute(
            HttpRequest(
                url = url,
                method = "GET",
                headers = parseHeader(),
                sourceId = sourceId
            ),
            limit = concurrentRate,
            traceId = traceId
        )
        if (!response.isSuccess) {
            metrics.failure(traceId, "book_source", "HTTP_" + response.code)
            throw NetworkException("http " + response.code + " for " + url, response.code)
        }
        return response.body ?: ""
    }

    private suspend fun parseBookItems(
        html: String,
        listRule: String?,
        ctx: RuleEngine.RuleContext
    ): List<SearchBookItem> {
        if (listRule.isNullOrBlank()) return emptyList()

        val names = rules.evalList(html, listRule, ctx)
        if (names.isEmpty()) return emptyList()

        val links = rules.evalList(html, listRule + "@attr:href", ctx)
        return names.mapIndexedNotNull { index, name ->
            val link = links.getOrNull(index)
            if (link.isNullOrBlank()) return@mapIndexedNotNull null
            SearchBookItem(
                name = name,
                bookUrl = link,
                origin = sourceId,
                originName = entity.bookSourceName
            )
        }
    }

    /** ruleSearch 嵌套对象里的 bookList。 */
    private fun searchBookListRule(): String? {
        val raw = entity.ruleSearch ?: return null
        if (!raw.trimStart().startsWith("{")) return raw
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.get("bookList")?.asString
        }.getOrNull()
    }

    /** 1.4.7 音频 URL 解析：纯 URL，或 JSON 含 url 字段，或 ,{headers JSON} 后缀。 */
    private fun parseAudioResult(raw: String): ContentResult {
        var url = raw.trim()
        var headers: Map<String, String> = emptyMap()

        // Legado 惯例：URL 后缀 ,{headers JSON} 注入请求头（防盗链必需）
        val commaIdx = url.lastIndexOf(",{")
        if (commaIdx > 0 && url.endsWith("}")) {
            val jsonPart = url.substring(commaIdx + 1)
            val parsed = runCatching {
                JsonParser.parseString(jsonPart).asJsonObject.entrySet()
                    .associate { it.key to it.value.asString }
            }.getOrNull()
            if (parsed != null) {
                headers = parsed
                url = url.substring(0, commaIdx)
            }
        }

        // JSON 形态：{"url": "..."}
        if (url.startsWith("{")) {
            val objUrl = runCatching {
                JsonParser.parseString(url).asJsonObject.get("url")?.asString
            }.getOrNull()
            if (objUrl != null) return ContentResult.Audio(objUrl, headers)
        }

        return ContentResult.Audio(url, headers)
    }

    private fun ruleContext(url: String, op: String) = RuleEngine.RuleContext(
        baseUrl = url,
        sourceId = sourceId,
        traceId = sourceId + ":" + op
    )

    /** 源 header 字段：JSON 对象字符串。 */
    private fun parseHeader(): Map<String, String> {
        val raw = entity.header ?: return emptyMap()
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.entrySet()
                .associate { it.key to it.value.asString }
        }.getOrDefault(emptyMap())
    }

    /** concurrentRate 字符串 "permits/periodMs" 或 "permits,periodMs"。 */
    private fun parseRate(raw: String?): RateLimit? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("/", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val permits = parts[0].toIntOrNull() ?: return null
        val period = parts[1].toLongOrNull() ?: return null
        return RateLimit(permits, period)
    }
}
