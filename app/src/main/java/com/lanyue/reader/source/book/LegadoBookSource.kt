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
import com.lanyue.reader.source.rule.SortUrlParser
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
    private val metrics: LocalMetrics,
    private val sortParser: SortUrlParser? = null
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
        val resolved = resolveDynamic(searchUrl, "search") ?: return emptyList()
        // 1.4.3 占位符替换；无占位符时原样请求（不自动追加 ?q=）
        val url = UrlTemplate.fill(resolved, key, page)
        val html = fetchHtml(url, "search")
        val ctx = ruleContext(url, "search")
        return parseBookItems(html, listSpec(entity.ruleSearch), ctx)
    }

    override suspend fun explore(sort: SortRule, page: Int): List<SearchBookItem> {
        val rawUrl = if (containsDynamic(sort.url)) {
            sortParser?.resolveUrl(sort.url, "$sourceId:explore") ?: return emptyList()
        } else sort.url
        // 防线：分类网格解析失败时 UI 可能把整段 exploreUrl 文本当 URL 传入，
        // 含换行/::/JSON 开头的串必是分类配置而非地址，直接判空不发请求
        if (rawUrl.isBlank() || rawUrl.contains("\n") || rawUrl.contains("::") ||
            rawUrl.trimStart().startsWith("{") || rawUrl.trimStart().startsWith("[")
        ) {
            metrics.failure("$sourceId:explore", "book_source", "bad_url:" + rawUrl.take(32))
            return emptyList()
        }
        val url = UrlTemplate.fillPage(rawUrl, page)
        val abs = resolveAbsolute(url)
        val html = fetchHtml(abs, "explore")
        val ctx = ruleContext(abs, "explore")
        return parseBookItems(html, listSpec(entity.ruleExplore), ctx)
    }

    /**
     * 「全部分类」兜底地址：仅当 exploreUrl 本身是单个干净 http(s) URL 时可用。
     * 多行/JSON/动态形态是分类配置，塞进请求只会打出垃圾地址（Legado 亦无此形态）。
     */
    fun fallbackExploreUrl(): String? {
        val raw = entity.exploreUrl?.trim()
        if (raw.isNullOrEmpty()) return null
        if (containsDynamic(raw)) return null
        if (raw.contains("\n") || raw.contains("::") || raw.contains("&&")) return null
        if (raw.startsWith("{") || raw.startsWith("[")) return null
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else null
    }

    /** 分类值可能是相对路径（/category/x），相对源地址拼接。 */
    private fun resolveAbsolute(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) return url
        val base = entity.bookSourceUrl ?: return url
        val origin = try {
            val u = java.net.URI(base)
            u.scheme + "://" + (u.host ?: base)
        } catch (e: Exception) {
            base.substringBefore("/")
        }
        return if (url.startsWith("/")) origin + url else "$origin/$url"
    }

    /** 搜索/源地址可能也是 @js: 动态脚本。 */
    private suspend fun resolveDynamic(raw: String, op: String): String? {
        if (!raw.contains("@js:") && !raw.contains("<js>")) return raw
        val parser = sortParser ?: return null
        return parser.resolveUrl(raw, "$sourceId:$op")
    }

    /**
     * 1.4.4 书源发现分类：对齐真实 Legado `exploreKinds()`——分类来自 exploreUrl 字段
     * （ruleExplore 是书籍列表规则，从不承载分类）。支持形态：
     * 1. @js:/<js> 动态脚本生成（走 sortParser，无 parser 降级跳过）
     * 2. JSON 数组 [{title,url,...}]（ExploreKind 形态，type 非 url 的控件项跳过）
     * 3. JSON 对象 {"分类":{...}}（旧版形态，值为 URL 串或 {分类名称,URL} 对象）
     * 4. 「分类名::url」多行/&& 静态行
     * 以上均无结果时回退 ruleExplore 内嵌「分类」（历史兼容）。
     */
    suspend fun getSorts(): List<SortRule> {
        val raw = entity.exploreUrl?.takeIf { it.isNotBlank() } ?: return legacySorts()
        if (containsDynamic(raw)) {
            // 对齐 Legado：脚本输出字符串后再按 JSON 数组/行解析（而非脚本直接产出分类）
            val parser = sortParser ?: return legacySorts()
            val out = parser.resolveUrl(raw, "$sourceId:sorts") ?: return emptyList()
            return parseKinds(out)
        }
        return parseKinds(raw).ifEmpty { legacySorts() }
    }

    /** 历史：老导入路径曾把「分类」JSON 写进 ruleExplore。 */
    private suspend fun legacySorts(): List<SortRule> {
        val raw = entity.ruleExplore ?: return emptyList()
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        if (obj != null) {
            val list = categoryRules(obj.get("分类"))
            if (!list.isNullOrEmpty()) return list
        }
        return parseStaticLines(raw)
    }

    /** 静态分类解析：JSON 数组 / JSON 对象 / name::url 行。嵌套值可能含 @js，需挂起解析。 */
    private suspend fun parseKinds(raw: String): List<SortRule> {
        val element = runCatching { JsonParser.parseString(raw) }.getOrNull()
        return when {
            element != null && element.isJsonArray -> element.asJsonArray.mapNotNull { e ->
                if (e !is JsonObject) return@mapNotNull null
                // type=text/button/toggle/select 是配合 @js URL 的输入控件，非可点分类
                val type = e.optString("type")
                if (type != null && type != "url") return@mapNotNull null
                val name = e.optString("title")
                    ?: e.optString("name")
                    ?: e.optString("分类名称")
                    ?: return@mapNotNull null
                val url = e.optString("url") ?: e.optString("URL") ?: return@mapNotNull null
                if (name.isBlank() || url.isBlank()) null else SortRule(name.trim(), url.trim())
            }
            element != null && element.isJsonObject ->
                categoryRules(element.asJsonObject.get("分类")).orEmpty()
            else -> parseStaticLines(raw)
        }
    }

    /** 单个分类值：动态脚本走 sortParser 产出（分类组/JSON/行），静态值直接采用（无 parser 时跳过动态组）。 */
    private suspend fun resolveCategory(name: String, urlRaw: String): List<SortRule> {
        if (containsDynamic(urlRaw)) {
            val parser = sortParser ?: return emptyList()
            val out = parser.resolveUrl(urlRaw, "$sourceId:sorts") ?: return emptyList()
            val kinds = parseKinds(out)
            if (kinds.isNotEmpty()) return kinds
            // 脚本只回了一个地址串（如 "/xuanhuan/{{page}}"）——挂回分类名
            return listOf(SortRule(name, out.trim()))
        }
        return listOf(SortRule(name, urlRaw))
    }

    private fun containsDynamic(urlRaw: String): Boolean =
        urlRaw.contains("@js:") || urlRaw.contains("<js>")

    private fun parseStaticLines(raw: String): List<SortRule> {
        val lines = raw.split("\n", "&&").map { it.trim() }.filter { it.isNotEmpty() }
        return lines.mapNotNull { line ->
            val idx = line.indexOf("::")
            if (idx <= 0) return@mapNotNull null
            val name = line.substring(0, idx).trim()
            val url = line.substring(idx + 2).trim()
            if (name.isEmpty() || url.isEmpty() || containsDynamic(url)) return@mapNotNull null
            SortRule(name, url)
        }
    }

    private suspend fun categoryRules(cat: com.google.gson.JsonElement?): List<SortRule>? =
        when (cat) {
            is com.google.gson.JsonObject -> cat.entrySet().flatMap { (name, v) ->
                val url = URLFrom(v) ?: return@flatMap emptyList()
                resolveCategory(name, url)
            }
            is com.google.gson.JsonArray -> cat.flatMap { element ->
                if (element is JsonObject) {
                    val name = element.optString("分类名称") ?: element.optString("name")
                        ?: return@flatMap emptyList()
                    val url = URLFrom(element) ?: return@flatMap emptyList()
                    resolveCategory(name, url)
                } else emptyList()
            }
            null -> null
            else -> null
        }

    private fun URLFrom(v: com.google.gson.JsonElement?): String? = when (v) {
        null -> null
        is JsonObject -> v.optString("URL") ?: v.optString("url")
        is com.google.gson.JsonPrimitive -> if (v.isJsonNull) null else v.asString
        else -> null
    }

    /** JSON 字符串字段安全读取：缺键、显式 null、非字符串一律返回 null（JsonNull.asString 会抛异常）。 */
    private fun JsonObject.optString(key: String): String? =
        (get(key) as? com.google.gson.JsonPrimitive)?.takeIf { !it.isJsonNull }?.asString

    /**
     * ruleExplore/ruleSearch 是嵌套对象时提取 bookList、name、bookUrl。
     *
     * 对齐 Legado 书源 JSON：Gson 键名首字母小写（bookList/name/bookUrl），
     * 兼容大写 BookList。未写成 JSON 时整体当图书列表规则字符串（旧版行为）。
     */
    data class ListRuleSpec(
        val listRule: String? = null,
        val nameRule: String? = null,
        val urlRule: String? = null
    )

    private fun listSpec(rawJson: String?): ListRuleSpec {
        val raw = rawJson?.trim().orEmpty()
        if (raw.isEmpty()) return ListRuleSpec()
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        if (obj == null) return ListRuleSpec(listRule = raw)
        return ListRuleSpec(
            listRule = obj.optString("bookList") ?: obj.optString("BookList") ?: raw,
            nameRule = obj.optString("name"),
            urlRule = obj.optString("bookUrl") ?: obj.optString("url")
        )
    }

    /**
     * 书源未配 bookUrl 的默认链接规则：
     * 先取项内第一个 <a> 的 href 并补绝对地址，再退化为项自身 href。
     * 相对地址补域名对齐 Legado `@abs:href`（NetworkUtils.getAbsoluteURL）。
     */
    private val DEFAULT_URL_RULE = "@a@abs:href||a@abs:href"

    private suspend fun parseBookItems(
        html: String,
        spec: ListRuleSpec,
        ctx: RuleEngine.RuleContext
    ): List<SearchBookItem> {
        val listRule = spec.listRule ?: return emptyList()
        val nameRule = spec.nameRule   // 空则取项文本（Legado 默认）
        val urlRule = spec.urlRule ?: DEFAULT_URL_RULE

        val names = rules.evalListPerItem(html, listRule, nameRule, ctx)
        if (names.isEmpty()) return emptyList()

        val links = rules.evalListPerItem(html, listRule, urlRule, ctx)
        return names.mapIndexedNotNull { index, name ->
            val link = links.getOrNull(index)?.trim().orEmpty()
            var absolute: String = ""
            if (link.isNotEmpty() && !link.startsWith("javascript:")) {
                absolute = rules.absolutize(link, ctx.baseUrl)
            }
            if (absolute.isEmpty()) return@mapIndexedNotNull null
            SearchBookItem(
                name = name,
                bookUrl = absolute,
                origin = sourceId,
                originName = entity.bookSourceName
            )
        }
    }

    override suspend fun getDetail(url: String): BookDetail {
        val html = fetchHtml(url, "detail")
        val ctx = ruleContext(url, "detail")

        // ruleBookInfo 是嵌套对象，按 JSON 取 name/author/cover/intro 等字段
        val info = runCatching { JsonParser.parseString(entity.ruleBookInfo ?: "{}").asJsonObject }
            .getOrElse { JsonObject() }

        suspend fun field(key: String): String? {
            val raw = info.optString(key) ?: return null
            return rules.evalString(html, raw, ctx)
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
        val chapterListRule = toc.optString("chapterList")
        if (chapterListRule.isNullOrBlank()) return emptyList()

        val nameRule = toc.optString("chapterName") ?: "text"
        val urlRule = toc.optString("chapterUrl") ?: "href"

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

    /** Legado AnalyzeUrl 的 `,{json}` URL 选项子集（method/headers/body/charset）。 */
    data class UrlRequestOptions(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val charset: String? = null
    )

    /**
     * 拆出 URL 选项后缀：`https://a/x,{"method":"POST","body":"page={{page}}"}`。
     * 真实书源的发现/搜索常用 POST 分页，未拆分时整串当 URL 请求必然 404。
     * 尾串能解析为 JSON 对象才认作选项，否则视为普通逗号原样保留。
     */
    private fun splitUrlOption(raw: String): UrlRequestOptions {
        val idx = raw.lastIndexOf(",{")
        if (idx <= 0 || !raw.endsWith("}")) return UrlRequestOptions(url = raw)
        val jsonPart = raw.substring(idx + 1)
        val obj = runCatching { JsonParser.parseString(jsonPart).asJsonObject }.getOrNull()
            ?: return UrlRequestOptions(url = raw)
        val headers = obj.get("headers")?.takeIf { it.isJsonObject }
            ?.asJsonObject?.entrySet()?.associate { (k, v) ->
                k to ((v as? com.google.gson.JsonPrimitive)?.takeIf { !it.isJsonNull }?.asString ?: "")
            }
            ?: emptyMap()
        return UrlRequestOptions(
            url = raw.substring(0, idx),
            method = obj.optString("method")?.uppercase() ?: "GET",
            headers = headers,
            body = obj.optString("body"),
            charset = obj.optString("charset")
        )
    }

    private suspend fun fetchHtml(url: String, op: String): String {
        val traceId = sourceId + ":" + op
        val opts = splitUrlOption(url)
        // 防线：拦截流失的非法 scheme（@js: 脚本/相对 URL 未解析）
        if (!opts.url.startsWith("http://") && !opts.url.startsWith("https://") &&
            !opts.url.startsWith("file://")
        ) {
            metrics.failure(traceId, "book_source", "bad_scheme:" + opts.url.take(32))
            throw NetworkException("非法地址（缺少 http/https scheme），不发起请求")
        }
        // 源 header 在前，URL 选项 header 覆盖（对齐 Legado headerMap 合并顺序）
        val headers = parseHeader() + opts.headers
        val method = if (opts.body != null && opts.method == "GET") "POST" else opts.method
        // POST 表单体缺 Content-Type 时补 form 类型（对齐 Legado analyzeFields 默认）
        val effectiveHeaders = if (method == "POST" && opts.body != null &&
            headers["Content-Type"] == null && !opts.body.trimStart().startsWith("{")
        ) {
            headers + mapOf("Content-Type" to "application/x-www-form-urlencoded")
        } else headers
        val response = net.execute(
            HttpRequest(
                url = opts.url,
                method = method,
                headers = effectiveHeaders,
                body = opts.body?.toByteArray(),
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
                    .associate { (k, v) ->
                        k to ((v as? com.google.gson.JsonPrimitive)?.takeIf { !it.isJsonNull }?.asString ?: "")
                    }
            }.getOrNull()
            if (parsed != null) {
                headers = parsed
                url = url.substring(0, commaIdx)
            }
        }

        // JSON 形态：{"url": "..."}
        if (url.startsWith("{")) {
            val objUrl = runCatching {
                JsonParser.parseString(url).asJsonObject.optString("url")
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
                .associate { (k, v) ->
                    k to ((v as? com.google.gson.JsonPrimitive)?.takeIf { !it.isJsonNull }?.asString ?: "")
                }
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
