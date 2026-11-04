package io.legado.app.data.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * JSON 视频源：支持多种格式。
 *
 * 1. FongMi Config 格式（多站点）：
 * {
 *   "sites": [
 *     {"key":"app1","name":"App1","type":0,"api":"https://.../api.php/provide/vod"},
 *     {"key":"app2","name":"App2","type":1,"api":"https://.../api.php/provide/vod","searchApi":"..."},
 *     {"key":"app3","name":"App3","type":3,"api":"https://.../api.php/provide/vod","ext":"...","header":{"Referer":"..."}}
 *   ]
 * }
 * 此时 sourceId 格式为 "configId:siteKey"，选择指定站点。
 *
 * 2. CMS 模式（苹果CMS V10 标准 API，国内影视站通用）：
 * {
 *   "name": "站点名",
 *   "type": "cms",
 *   "api": "https://api.example.com/api.php/provide/vod",
 *   "ext": "扩展参数（可选）",
 *   "header": {"Referer": "https://...", "Cookie": "..."}
 * }
 *
 * 3. 自定义端点模式：
 * {
 *   "name": "站点名",
 *   "type": "custom",
 *   "home": "...", "category": "...?tid={tid}&pg={pg}", "search": "...?kw={kw}",
 *   "detail": "...?id={id}", "play": "...?url={url}", "danmaku": "...?id={id}"
 * }
 *
 * CMS 模式接口约定（苹果CMS V10）：
 * - 首页: {api}（不带参数，返回 class + list）
 * - 分类: {api}?ac=videolist&t={tid}&pg={pg}（返回 list + pagecount）
 * - 搜索: {api}?wd={kw}&quick=true（返回 list，不带 ac 参数）
 * - 详情: {api}?ac=detail&ids={id}（返回 list，含 vod_play_url）
 * - 所有请求自动附加 extend 参数（如果配置了 ext）
 *
 * 性能优化：
 * - 共享 VideoHttpClient 连接池
 * - 响应缓存 TTL 5 分钟
 * - 所有请求在 Dispatchers.IO 执行
 * - 支持自定义请求头（Referer, Cookie 等）
 */
class JsonVideoSource(
    override val sourceId: String,
    private val configJson: String,
) : VideoSource {

    override val sourceType = SourceType.JSON

    override val sourceName: String
    private val mode: String
    private val baseUrl: String
    private val endpoints: Map<String, String>
    private val siteConfig: FongMiConfigParser.SiteConfig?
    private var configSites: List<FongMiConfigParser.SiteConfig>
    private val siteHeaders: Map<String, String>
    private val siteExt: String

    init {
        var name = sourceId
        var m = "cms"
        var api = ""
        val ep = mutableMapOf<String, String>()
        var fongMiSites = emptyList<FongMiConfigParser.SiteConfig>()
        var selectedSite: FongMiConfigParser.SiteConfig? = null
        var headers = emptyMap<String, String>()
        var ext = ""

        try {
            val root = JsonParser.parseString(configJson).asJsonObject
            // 检测 FongMi Config 格式（含 sites 数组）
            val sitesArr = root.getAsJsonArray("sites")
            if (sitesArr != null) {
                fongMiSites = FongMiConfigParser.parse(configJson)
                // sourceId 格式: "configId:siteKey"，选择指定站点
                val colonIdx = sourceId.indexOf(':')
                if (colonIdx > 0) {
                    val siteKey = sourceId.substring(colonIdx + 1)
                    selectedSite = fongMiSites.find { it.key == siteKey }
                    configSites = fongMiSites
                    if (selectedSite != null) {
                        name = selectedSite.name
                        m = "cms"
                        api = selectedSite.api
                        headers = selectedSite.header
                        ext = selectedSite.ext
                    }
                }
            }

            if (selectedSite == null) {
                // 单站点模式
                name = root.optString("name", sourceId)
                // FongMi type: 0/1/3 = CMS, "cms" = CMS, "custom" = 自定义端点
                val typeStr = root.optString("type", "cms")
                m = when {
                    typeStr == "custom" -> "custom"
                    else -> "cms"  // 0, 1, 3, "cms" 等均按 CMS 处理
                }
                api = root.optString("api", "")
                for (key in listOf("home", "category", "search", "detail", "play", "danmaku")) {
                    val v = root.optString(key, "")
                    if (v.isNotBlank()) ep[key] = v
                }
                // 解析 header（支持 JSON 对象和 JSON 字符串）
                val headerEl = root.get("header")
                if (headerEl != null && headerEl.isJsonObject) {
                    headers = headerEl.asJsonObject().entrySet().associate { (k, v) -> k to v.asString }
                }
                ext = root.optString("ext", "")
            }
        } catch (e: Exception) {
            io.legado.app.utils.LogUtils.d(TAG, "parse config failed: ${e.message}")
        }

        sourceName = name
        mode = m
        baseUrl = api
        endpoints = ep
        siteConfig = selectedSite
        configSites = fongMiSites
        siteHeaders = headers
        siteExt = ext
    }

    val isCmsMode: Boolean get() = mode == "cms"

    /** 构建 CMS API URL（苹果CMS V10 标准参数） */
    private fun buildCmsUrl(path: String, params: Map<String, String> = emptyMap()): String {
        val searchApi = siteConfig?.searchApi ?: ""
        val effectiveApi = if (path == "search" && searchApi.isNotBlank()) searchApi else baseUrl
        val allParams = params.toMutableMap()
        if (siteExt.isNotBlank()) allParams["extend"] = siteExt
        val sep = if ("?" in effectiveApi) "&" else "?"
        val paramStr = allParams.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return if (paramStr.isNotBlank()) "$effectiveApi$sep$paramStr" else effectiveApi
    }

    override suspend fun home(): Pair<List<VideoCategory>, List<VideoItem>> = withContext(Dispatchers.IO) {
        if (isCmsMode) {
            // 苹果CMS V10: 首页直接请求 {api}，不带 ac 参数
            val url = buildCmsUrl("home")
            val json = VideoHttpClient.getJson(url, siteHeaders)
            if (json == null) {
                io.legado.app.utils.LogUtils.d(TAG, "home CMS request failed: $url")
                return@withContext Pair(emptyList(), emptyList())
            }
            val categories = mutableListOf<VideoCategory>()
            json.getAsJsonArray("class")?.forEach { el ->
                val o = el.asJsonObject
                categories.add(
                    VideoCategory(
                        id = o.optString("type_id"),
                        name = o.optString("type_name"),
                    )
                )
            }
            val videos = parseCmsList(json.getAsJsonArray("list"))
            Pair(categories, videos)
        } else {
            val url = endpoints["home"] ?: return@withContext Pair(emptyList(), emptyList())
            val json = VideoHttpClient.getJson(url, siteHeaders)
            if (json == null) {
                io.legado.app.utils.LogUtils.d(TAG, "home custom request failed: $url")
                return@withContext Pair(emptyList(), emptyList())
            }
            val categories = mutableListOf<VideoCategory>()
            json.getAsJsonArray("categories")?.forEach { el ->
                val o = el.asJsonObject
                categories.add(VideoCategory(o.optString("id"), o.optString("name")))
            }
            Pair(categories, parseVideoArray(json.getAsJsonArray("videos") ?: json.getAsJsonArray("list")))
        }
    }

    override suspend fun category(tid: String, pg: Int): CategoryResult = withContext(Dispatchers.IO) {
        if (isCmsMode) {
            // 苹果CMS V10: 分类用 ac=videolist
            val url = buildCmsUrl("category", mapOf("ac" to "videolist", "t" to tid, "pg" to pg.toString()))
            val json = VideoHttpClient.getJson(url, siteHeaders)
            if (json == null) {
                io.legado.app.utils.LogUtils.d(TAG, "category CMS request failed: $url")
                return@withContext CategoryResult()
            }
            CategoryResult(
                page = json.optInt("page", pg),
                pageCount = json.optInt("pagecount", 1),
                videos = parseCmsList(json.getAsJsonArray("list")),
            )
        } else {
            val tpl = endpoints["category"] ?: return@withContext CategoryResult()
            val url = fill(tpl, tid, pg)
            val json = VideoHttpClient.getJson(url, siteHeaders)
            if (json == null) {
                io.legado.app.utils.LogUtils.d(TAG, "category custom request failed: $url")
                return@withContext CategoryResult()
            }
            CategoryResult(
                page = json.optInt("page", pg),
                pageCount = json.optInt("pagecount", 1),
                videos = parseVideoArray(json.getAsJsonArray("videos") ?: json.getAsJsonArray("list")),
            )
        }
    }

    override suspend fun search(keyword: String, pg: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        if (isCmsMode) {
            // 苹果CMS V10: 搜索不带 ac 参数，用 wd + quick
            val url = buildCmsUrl("search", mapOf("wd" to keyword, "quick" to "true"))
            val json = VideoHttpClient.getJson(url, siteHeaders)
            if (json == null) {
                io.legado.app.utils.LogUtils.d(TAG, "search CMS request failed: $url")
                return@withContext emptyList()
            }
            parseCmsList(json.getAsJsonArray("list"))
        } else {
            val tpl = endpoints["search"] ?: return@withContext emptyList()
            val url = tpl.replace("{kw}", enc(keyword)).replace("{pg}", pg.toString())
            val json = VideoHttpClient.getJson(url, siteHeaders)
            if (json == null) {
                io.legado.app.utils.LogUtils.d(TAG, "search custom request failed: $url")
                return@withContext emptyList()
            }
            parseVideoArray(
                json.getAsJsonArray("videos") ?: json.getAsJsonArray("list")
            )
        }
    }

    override suspend fun detail(id: String): VideoDetail = withContext(Dispatchers.IO) {
        if (isCmsMode) {
            // 苹果CMS V10: 详情用 ac=detail&ids={id}
            val url = buildCmsUrl("detail", mapOf("ac" to "detail", "ids" to id))
            val json = VideoHttpClient.getJson(url, siteHeaders)
            if (json == null) {
                io.legado.app.utils.LogUtils.d(TAG, "detail CMS request failed: $url")
                return@withContext VideoDetail(id = id, title = id)
            }
            val vod = json.getAsJsonArray("list")?.firstOrNull()?.asJsonObject
                ?: return@withContext VideoDetail(id = id, title = id)
            parseCmsDetail(vod)
        } else {
            val tpl = endpoints["detail"] ?: return@withContext VideoDetail(id = id, title = id)
            val url = tpl.replace("{id}", enc(id))
            val json = VideoHttpClient.getJson(url, siteHeaders)
            if (json == null) {
                io.legado.app.utils.LogUtils.d(TAG, "detail custom request failed: $url")
                return@withContext VideoDetail(id = id, title = id)
            }
            val o = if (json.has("video")) json.getAsJsonObject("video") else json
            VideoDetail(
                id = o.optString("id", id),
                title = o.optString("title", id),
                pic = o.optString("pic"),
                desc = o.optString("desc"),
                playLines = o.getAsJsonArray("play_lines")?.map { el ->
                    val p = el.asJsonObject
                    PlayLine(p.optString("name"), p.optString("url"))
                } ?: emptyList(),
            )
        }
    }

    override suspend fun play(url: String): PlayResult = withContext(Dispatchers.IO) {
        val tpl = endpoints["play"]
        if (tpl.isNullOrBlank()) return@withContext PlayResult(url)
        val realUrl = tpl.replace("{url}", enc(url))
        val json = VideoHttpClient.getJson(realUrl, siteHeaders)
        if (json == null) {
            io.legado.app.utils.LogUtils.d(TAG, "play request failed: $realUrl")
            return@withContext PlayResult(url)
        }
        val headers = mutableMapOf<String, String>()
        json.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
            headers[k] = v.asString
        }
        PlayResult(url = json.optString("url", url), headers = headers)
    }

    override suspend fun danmaku(videoId: String): DanmakuList = withContext(Dispatchers.IO) {
        val tpl = endpoints["danmaku"] ?: return@withContext DanmakuList()
        val url = tpl.replace("{id}", enc(videoId))
        val body = VideoHttpClient.get(url, siteHeaders) ?: return@withContext DanmakuList()
        DanmakuParser.parse(body)
    }

    // ---- CMS helpers ----

    private fun parseCmsList(arr: JsonArray?): List<VideoItem> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el.asJsonObject
                VideoItem(
                    id = str(o, "vod_id", "id"),
                    title = str(o, "vod_name", "name", "title"),
                    url = str(o, "vod_id", "id").ifBlank { str(o, "vod_url", "url", "link") },
                    pic = str(o, "vod_pic", "pic", "img", "cover"),
                    remarks = str(o, "vod_remarks", "remarks", "remark", "note"),
                    sourceId = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseCmsDetail(vod: JsonObject): VideoDetail {
        val lines = mutableListOf<PlayLine>()
        val playFrom = str(vod, "vod_play_from", "play_from")
        val playUrl = str(vod, "vod_play_url", "play_url")
        val fromNames = playFrom.split("$$$").filter { it.isNotBlank() }
        val urlGroups = playUrl.split("$$$").filter { it.isNotBlank() }
        fromNames.forEachIndexed { idx, name ->
            val group = urlGroups.getOrNull(idx) ?: return@forEachIndexed
            group.split("#").filter { it.isNotBlank() }.forEach { ep ->
                val parts = ep.split("$")
                val epName = if (parts.size >= 2) parts[0] else "第${lines.size + 1}集"
                val epUrl = parts.last()
                if (epUrl.isNotBlank()) {
                    lines.add(PlayLine(name = "$name·$epName", url = epUrl))
                }
            }
        }
        return VideoDetail(
            id = str(vod, "vod_id", "id"),
            title = str(vod, "vod_name", "title", "name"),
            pic = str(vod, "vod_pic", "pic", "img", "cover"),
            desc = str(vod, "vod_content", "desc", "content", "description"),
            playLines = lines,
        )
    }

    private fun parseVideoArray(arr: JsonArray?): List<VideoItem> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el.asJsonObject
                VideoItem(
                    id = str(o, "id", "vod_id"),
                    title = str(o, "title", "vod_name", "name"),
                    url = str(o, "url", "link", "vod_url").ifBlank { str(o, "id", "vod_id") },
                    pic = str(o, "pic", "vod_pic", "img", "cover"),
                    remarks = str(o, "remarks", "vod_remarks", "remark", "note"),
                    sourceId = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun fill(tpl: String, tid: String, pg: Int): String {
        return tpl.replace("{tid}", enc(tid)).replace("{pg}", pg.toString())
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "JsonVideoSource"
    }
}

private fun com.google.gson.JsonObject.optString(key: String, default: String = ""): String =
    takeIf { has(key) }?.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: default

private fun com.google.gson.JsonObject.optInt(key: String, default: Int = 0): Int =
    takeIf { has(key) }?.get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: default

private fun str(obj: com.google.gson.JsonObject, vararg keys: String): String {
    for (key in keys) {
        val v = obj.get(key) ?: continue
        try {
            if (!v.isJsonNull) {
                val s = v.asString
                if (s.isNotBlank()) return s
            }
        } catch (e: Exception) {
            continue
        }
    }
    return ""
}
