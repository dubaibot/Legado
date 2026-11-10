package com.lanyue.reader.source.rss

import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.engine.NetworkException
import com.lanyue.reader.core.engine.RateLimit
import com.lanyue.reader.core.engine.RssFallbackParser
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.source.model.CapabilityResolver
import com.lanyue.reader.source.model.ContentResult
import com.lanyue.reader.source.model.ISubscriptionSource
import com.lanyue.reader.source.model.RssContentType
import com.lanyue.reader.source.model.SortRule
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.SourceType
import com.lanyue.reader.source.model.SubscriptionItem
import com.lanyue.reader.source.rule.RuleEngine
import com.lanyue.reader.source.rule.SortUrlParser
import com.lanyue.reader.source.rule.UrlTemplate
import com.google.gson.JsonParser

/**
 * 7.2 订阅源实现（Legado RssSource）。
 *
 * 与书源的结构性差异（1.4.5）：
 * - 无目录层（ADR-012：无 getToc）
 * - 规则是扁平字符串，不是嵌套对象
 * - ruleArticles 为空时走内置 RSS/Atom 兜底（ADR-016）
 * - 搜索为览阅扩展：searchUrl 非空即可搜，结果复用列表规则
 */
class LegadoRssSource(
    private val entity: RssSourceEntity,
    private val net: NetworkDispatcher,
    private val rules: RuleEngine,
    private val sortParser: SortUrlParser,
    private val rssFallback: RssFallbackParser,
    private val metrics: LocalMetrics
) : ISubscriptionSource {

    override val sourceId: String = entity.sourceUrl

    override val sourceType: SourceType = SourceType.SUBSCRIPTION

    override val capabilityFlags: Set<SourceCapability> = CapabilityResolver.ofRss(entity)

    override val concurrentRate: RateLimit? = parseRate(entity.concurrentRate)

    /**
     * 1.4.4 sortUrl 解析。动态生成失败时降级为无分类。
     * 1.4.2 DISCOVERABLE 的二次确认：必须真的解析出 ≥1 分类。
     */
    override suspend fun getSorts(): List<SortRule> {
        val result = sortParser.parse(entity.sortUrl, sourceId, sourceId + ":sorts")
        return result.sorts
    }

    override suspend fun getArticles(sort: SortRule?, page: Int): List<SubscriptionItem> {
        val url = if (sort != null) UrlTemplate.fillPage(sort.url, page) else entity.sourceUrl
        val html = fetch(url, "articles")
        val ctx = RuleEngine.RuleContext(url, sourceId, sourceId + ":articles")

        // ADR-016：ruleArticles 为空 -> RSS/Atom 兜底
        if (entity.ruleArticles.isNullOrBlank()) {
            return parseFallbackItems(html, sort?.name)
        }

        return parseListItems(html, ctx, sort?.name)
    }

    /** 1.4.3 搜索：占位符替换 -> 列表规则解析；失败由上层计数降级。 */
    override suspend fun search(key: String, page: Int): List<SubscriptionItem> {
        val searchUrl = entity.searchUrl ?: return emptyList()
        val url = UrlTemplate.fill(searchUrl, key, page)
        val html = fetch(url, "search")

        // ADR-016：与列表一致，ruleArticles 为空时走 RSS/Atom 兜底
        if (entity.ruleArticles.isNullOrBlank()) {
            return parseFallbackItems(html, null)
        }

        val ctx = RuleEngine.RuleContext(url, sourceId, sourceId + ":search")
        return parseListItems(html, ctx, null)
    }

    override suspend fun getContent(url: String): ContentResult {
        // 1.4.1 ruleContent 为空 -> WebView 原样打开
        if (entity.ruleContent.isNullOrBlank()) {
            return ContentResult.WebViewFallback(url)
        }
        val html = fetch(url, "content")
        val ctx = RuleEngine.RuleContext(url, sourceId, sourceId + ":content")
        val body = rules.evalString(html, entity.ruleContent, ctx)
        return if (body.isNullOrBlank()) {
            ContentResult.WebViewFallback(url)
        } else {
            ContentResult.Html(body)
        }
    }

    // ---------- 内部 ----------

    /** ADR-016 RSS/Atom 兜底：ruleArticles 为空时把响应当 feed 解析。 */
    private fun parseFallbackItems(html: String, sortName: String?): List<SubscriptionItem> {
        val feed = rssFallback.parse(html) ?: return emptyList()
        return feed.items.map { item ->
            SubscriptionItem(
                title = item.title ?: "",
                url = item.link ?: "",
                cover = item.image,
                pubDate = parseDate(item.pubDate),
                description = item.description,
                sort = sortName,
                origin = sourceId,
                contentType = contentTypeOf(entity.type)
            )
        }.filter { it.url.isNotEmpty() }
    }

    private suspend fun fetch(url: String, op: String): String {
        val traceId = sourceId + ":" + op
        // 防线：拦截流失的非法 scheme（@js: 脚本当 URL 使用的兜底）
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            metrics.failure(traceId, "rss_source", "bad_scheme:" + url.take(32))
            throw NetworkException("非法地址（缺少 http/https scheme），不发起请求")
        }
        val resp = net.execute(
            HttpRequest(
                url = url,
                method = "GET",
                headers = parseHeader(),
                sourceId = sourceId
            ),
            limit = concurrentRate,
            traceId = traceId
        )
        if (!resp.isSuccess) {
            metrics.failure(traceId, "rss_source", "HTTP_" + resp.code)
            throw NetworkException("http " + resp.code + " for " + url, resp.code)
        }
        return resp.body ?: ""
    }

    private suspend fun parseListItems(
        html: String,
        ctx: RuleEngine.RuleContext,
        sortName: String?
    ): List<SubscriptionItem> {
        val articleRule = entity.ruleArticles ?: return emptyList()
        val titles = rules.evalList(html, articleRule + "@" + (entity.ruleTitle ?: "text"), ctx)
        if (titles.isEmpty()) return emptyList()

        val links = rules.evalList(html, articleRule + "@" + (entity.ruleLink ?: "href"), ctx)
        val covers = entity.ruleImage?.let { rules.evalList(html, articleRule + "@" + it, ctx) }.orEmpty()
        val dates = entity.rulePubDate?.let { rules.evalList(html, articleRule + "@" + it, ctx) }.orEmpty()
        val descs = entity.ruleDescription?.let { rules.evalList(html, articleRule + "@" + it, ctx) }.orEmpty()

        return titles.mapIndexedNotNull { index, title ->
            val link = links.getOrNull(index)
            if (link.isNullOrBlank()) return@mapIndexedNotNull null
            SubscriptionItem(
                title = title,
                url = link,
                cover = covers.getOrNull(index),
                pubDate = parseDate(dates.getOrNull(index)),
                description = descs.getOrNull(index),
                sort = sortName,
                origin = sourceId,
                contentType = contentTypeOf(entity.type)
            )
        }
    }

    /** 1.4.1 type：0 网页 / 1 图片 / 2 视频。 */
    private fun contentTypeOf(type: Int): RssContentType = when (type) {
        1 -> RssContentType.IMAGE
        2 -> RssContentType.VIDEO
        else -> RssContentType.WEB
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd"
        )
        formats.forEach { pattern ->
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                    .parse(raw.trim())?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return raw.toLongOrNull()
    }

    private fun parseHeader(): Map<String, String> {
        val raw = entity.header ?: return emptyMap()
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.entrySet()
                .associate { it.key to it.value.asString }
        }.getOrDefault(emptyMap())
    }

    private fun parseRate(raw: String?): RateLimit? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("/", ",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val permits = parts[0].toIntOrNull() ?: return null
        val period = parts[1].toLongOrNull() ?: return null
        return RateLimit(permits, period)
    }
}
