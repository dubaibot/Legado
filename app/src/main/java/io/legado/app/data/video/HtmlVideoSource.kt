package io.legado.app.data.video

import io.legado.app.model.analyzeRule.AnalyzeRule
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element

/**
 * HTML 视频源：基于 AnalyzeRule xpath/css 规则解析 HTML 页面。
 *
 * 配置格式：
 * ```json
 * {
 *   "name": "站点名",
 *   "type": "html",
 *   "home": "https://example.com",
 *   "search": "https://example.com/search?kw={kw}",
 *   "category": "https://example.com/category/{tid}?pg={pg}",
 *   "detail": "https://example.com/detail/{id}",
 *   "play": "https://example.com/play?url={url}",
 *   "rules": {
 *     "list": "css.selector",
 *     "title": "css.selector@text",
 *     "url": "css.selector@href",
 *     "pic": "css.selector@src",
 *     "remarks": "css.selector@text",
 *     "categories": "css.selector",
 *     "category_name": "css.selector@text",
 *     "category_id": "css.selector@href",
 *     "detail_title": "css.selector@text",
 *     "detail_pic": "css.selector@src",
 *     "episodes": "css.selector",
 *     "episode_name": "css.selector@text",
 *     "episode_url": "css.selector@href",
 *     "play_url": "css.selector@src"
 *   }
 * }
 * ```
 *
 * 规则语法（AnalyzeRule 标准）：
 * - CSS: `css.selector` 或 `css.selector@attribute`
 * - XPath: `xpath://...`
 * - JSONPath: `json:$.path`
 * - Regex: `regex@pattern`
 * - JS: `js:...`
 *
 * 支持多步规则链：`rule1&&rule2`
 */
class HtmlVideoSource(
    override val sourceId: String,
    private val configJson: String,
) : VideoSource {

    override val sourceType = SourceType.HTML

    override val sourceName: String
    private val homeUrl: String
    private val searchUrlTemplate: String
    private val categoryUrlTemplate: String
    private val detailUrlTemplate: String
    private val playUrlTemplate: String
    private val rules: Map<String, String>

    private val client = VideoHttpClient

    init {
        var name = sourceId
        var home = ""
        var search = ""
        var category = ""
        var detail = ""
        var play = ""
        val ep = mutableMapOf<String, String>()

        try {
            val root = JsonParser.parseString(configJson).asJsonObject
            name = root.optString("name", sourceId)
            home = root.optString("home", "")
            search = root.optString("search", "")
            category = root.optString("category", "")
            detail = root.optString("detail", "")
            play = root.optString("play", "")
            val rulesObj = root.getAsJsonObject("rules")
            if (rulesObj != null) {
                for (key in rulesObj.keySet()) {
                    val v = rulesObj.optString(key, "")
                    if (v.isNotBlank()) ep[key] = v
                }
            }
        } catch (e: Exception) {
            io.legado.app.utils.LogUtils.d(TAG, "parse config failed: ${e.message}")
        }

        sourceName = name
        homeUrl = home
        searchUrlTemplate = search
        categoryUrlTemplate = category
        detailUrlTemplate = detail
        playUrlTemplate = play
        rules = ep
    }

    override suspend fun home(): Pair<List<VideoCategory>, List<VideoItem>> = withContext(Dispatchers.IO) {
        if (homeUrl.isBlank()) return@withContext Pair(emptyList(), emptyList())
        val body = VideoHttpClient.get(homeUrl) ?: return@withContext Pair(emptyList(), emptyList())
        val analyzeRule = AnalyzeRule().setContent(body).setBaseUrl(homeUrl)
        analyzeRule.setRedirectUrl(homeUrl)

        val categories = mutableListOf<VideoCategory>()
        if (rules.containsKey("categories")) {
            val catElements = analyzeRule.getElements(rules["categories"]!!)
            val catNameRule = rules["category_name"] ?: "text"
            val catIdRule = rules["category_id"] ?: "href"
            catElements.forEachIndexed { idx, el ->
                try {
                    val catName = analyzeRule.getString(catNameRule, mContent = el)
                    val catId = analyzeRule.getString(catIdRule, mContent = el, isUrl = true)
                    if (catName.isNotBlank()) {
                        categories.add(
                            VideoCategory(
                                id = catId.ifBlank { idx.toString() },
                                name = catName,
                            )
                        )
                    }
                } catch (e: Exception) {
                }
            }
        }

        val videos = mutableListOf<VideoItem>()
        if (rules.containsKey("list")) {
            val listElements = analyzeRule.getElements(rules["list"]!!)
            listElements.forEach { el ->
                try {
                    val item = extractVideoItem(analyzeRule, el)
                    if (item != null) videos.add(item)
                } catch (e: Exception) {
                }
            }
        }

        Pair(categories, videos)
    }

    override suspend fun category(tid: String, pg: Int): CategoryResult = withContext(Dispatchers.IO) {
        if (categoryUrlTemplate.isBlank()) return@withContext CategoryResult()
        val url = fillTemplate(categoryUrlTemplate, mapOf("tid" to tid, "pg" to pg.toString()))
        val body = VideoHttpClient.get(url) ?: return@withContext CategoryResult()
        val analyzeRule = AnalyzeRule().setContent(body).setBaseUrl(url)
        analyzeRule.setRedirectUrl(url)

        val videos = mutableListOf<VideoItem>()
        if (rules.containsKey("list")) {
            val listElements = analyzeRule.getElements(rules["list"]!!)
            listElements.forEach { el ->
                try {
                    val item = extractVideoItem(analyzeRule, el)
                    if (item != null) videos.add(item)
                } catch (e: Exception) {
                }
            }
        }

        CategoryResult(page = pg, pageCount = 1, videos = videos)
    }

    override suspend fun search(keyword: String, pg: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        if (searchUrlTemplate.isBlank()) return@withContext emptyList()
        val url = fillTemplate(searchUrlTemplate, mapOf("kw" to keyword, "pg" to pg.toString()))
        val body = VideoHttpClient.get(url) ?: return@withContext emptyList()
        val analyzeRule = AnalyzeRule().setContent(body).setBaseUrl(url)
        analyzeRule.setRedirectUrl(url)

        val videos = mutableListOf<VideoItem>()
        if (rules.containsKey("list")) {
            val listElements = analyzeRule.getElements(rules["list"]!!)
            listElements.forEach { el ->
                try {
                    val item = extractVideoItem(analyzeRule, el)
                    if (item != null) videos.add(item)
                } catch (e: Exception) {
                }
            }
        }

        videos
    }

    override suspend fun detail(id: String): VideoDetail = withContext(Dispatchers.IO) {
        if (detailUrlTemplate.isBlank()) return@withContext VideoDetail(id = id, title = id)
        val url = fillTemplate(detailUrlTemplate, mapOf("id" to id))
        val body = VideoHttpClient.get(url) ?: return@withContext VideoDetail(id = id, title = id)
        val analyzeRule = AnalyzeRule().setContent(body).setBaseUrl(url)
        analyzeRule.setRedirectUrl(url)

        val title = rules["detail_title"]?.let {
            analyzeRule.getString(it)
        } ?: id
        val pic = rules["detail_pic"]?.let {
            analyzeRule.getString(it, isUrl = true)
        } ?: ""

        val episodes = mutableListOf<PlayLine>()
        if (rules.containsKey("episodes")) {
            val epElements = analyzeRule.getElements(rules["episodes"]!!)
            val epNameRule = rules["episode_name"] ?: "text"
            val epUrlRule = rules["episode_url"] ?: "href"
            epElements.forEachIndexed { idx, el ->
                try {
                    val epName = analyzeRule.getString(epNameRule, mContent = el)
                    val epUrl = analyzeRule.getString(epUrlRule, mContent = el, isUrl = true)
                    if (epUrl.isNotBlank()) {
                        episodes.add(
                            PlayLine(
                                name = epName.ifBlank { "第${idx + 1}集" },
                                url = epUrl,
                            )
                        )
                    }
                } catch (e: Exception) {
                }
            }
        }

        VideoDetail(
            id = id,
            title = title.ifBlank { id },
            pic = pic,
            playLines = episodes,
        )
    }

    override suspend fun play(url: String): PlayResult = withContext(Dispatchers.IO) {
        if (playUrlTemplate.isBlank()) return@withContext PlayResult(url)
        val playUrl = fillTemplate(playUrlTemplate, mapOf("url" to url))
        val body = VideoHttpClient.get(playUrl) ?: return@withContext PlayResult(url)

        // 尝试从 body 中提取视频 URL
        if (rules.containsKey("play_url")) {
            val analyzeRule = AnalyzeRule().setContent(body).setBaseUrl(playUrl)
            analyzeRule.setRedirectUrl(playUrl)
            val playUrlResult = analyzeRule.getString(rules["play_url"]!!, isUrl = true)
            if (playUrlResult.isNotBlank()) {
                return@withContext PlayResult(url = playUrlResult)
            }
        }

        // 兜底：body 本身就是视频 URL
        if (body.startsWith("http")) {
            PlayResult(url = body)
        } else {
            PlayResult(url = url)
        }
    }

    override suspend fun danmaku(videoId: String): DanmakuList = withContext(Dispatchers.IO) {
        DanmakuList()
    }

    /** 从单个元素提取 VideoItem */
    private fun extractVideoItem(analyzeRule: AnalyzeRule, el: Any): VideoItem? {
        val id = rules["id"]?.let { analyzeRule.getString(it, mContent = el) } ?: ""
        val title = rules["title"]?.let { analyzeRule.getString(it, mContent = el) } ?: ""
        val url = rules["url"]?.let { analyzeRule.getString(it, mContent = el, isUrl = true) } ?: ""
        val pic = rules["pic"]?.let { analyzeRule.getString(it, mContent = el, isUrl = true) } ?: ""
        val remarks = rules["remarks"]?.let { analyzeRule.getString(it, mContent = el) } ?: ""

        if (title.isNotBlank() && url.isNotBlank()) {
            return VideoItem(
                id = id.ifBlank { title },
                title = title,
                url = url,
                pic = pic,
                remarks = remarks,
                sourceId = sourceId,
            )
        }
        return null
    }

    /** 填充 URL 模板 */
    private fun fillTemplate(template: String, params: Map<String, String>): String {
        var result = template
        params.forEach { (k, v) ->
            result = result.replace("{$k}", v)
        }
        return result
    }

    companion object {
        private const val TAG = "HtmlVideoSource"
    }
}

private fun com.google.gson.JsonObject.optString(key: String, default: String = ""): String =
    takeIf { has(key) }?.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: default
