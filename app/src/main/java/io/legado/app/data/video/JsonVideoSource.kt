package io.legado.app.data.video

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * JSON 视频源：支持两种模式。
 *
 * 1. CMS 模式（苹果CMS V10 标准 API，国内影视站通用）：
 * {
 *   "name": "站点名",
 *   "type": "cms",
 *   "api": "https://api.example.com/api.php/provide/vod"
 * }
 *
 * 2. 自定义端点模式：
 * {
 *   "name": "站点名",
 *   "type": "custom",
 *   "home": "...", "category": "...?tid={tid}&pg={pg}", "search": "...?kw={kw}",
 *   "detail": "...?id={id}", "play": "...?url={url}", "danmaku": "...?id={id}"
 * }
 *
 * CMS 模式接口约定：
 * - 列表: {api}?ac=list&t={tid}&pg={pg}  → class + list
 * - 详情: {api}?ac=detail&ids={id}       → vod_play_url "集名$url#集名$url"
 * - 搜索: {api}?ac=videolist&wd={kw}
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    init {
        var name = sourceId
        var m = "cms"
        var api = ""
        val ep = mutableMapOf<String, String>()
        try {
            val obj = JsonParser.parseString(configJson).asJsonObject
            name = obj.optString("name", sourceId)
            m = obj.optString("type", "cms").lowercase()
            api = obj.optString("api", "")
            for (key in listOf("home", "category", "search", "detail", "play", "danmaku")) {
                val v = obj.optString(key, "")
                if (v.isNotBlank()) ep[key] = v
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "parse config failed: ${e.message}")
        }
        sourceName = name
        mode = m
        baseUrl = api
        endpoints = ep
    }

    val isCmsMode: Boolean get() = mode == "cms"

    override suspend fun home(): Pair<List<VideoCategory>, List<VideoItem>> = withContext(Dispatchers.IO) {
        if (isCmsMode) {
            val url = "$baseUrl?ac=list"
            val json = httpGetJson(url) ?: return@withContext Pair(emptyList(), emptyList())
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
            val json = httpGetJson(url) ?: return@withContext Pair(emptyList(), emptyList())
            val categories = mutableListOf<VideoCategory>()
            json.getAsJsonArray("categories")?.forEach { el ->
                val o = el.asJsonObject
                categories.add(VideoCategory(o.optString("id"), o.optString("name")))
            }
            Pair(categories, parseVideoArray(json.getAsJsonArray("videos")))
        }
    }

    override suspend fun category(tid: String, pg: Int): CategoryResult = withContext(Dispatchers.IO) {
        if (isCmsMode) {
            val url = "$baseUrl?ac=list&t=${enc(tid)}&pg=$pg"
            val json = httpGetJson(url) ?: return@withContext CategoryResult()
            CategoryResult(
                page = json.optInt("page", pg),
                pageCount = json.optInt("pagecount", 1),
                videos = parseCmsList(json.getAsJsonArray("list")),
            )
        } else {
            val tpl = endpoints["category"] ?: return@withContext CategoryResult()
            val json = httpGetJson(fill(tpl, tid, pg)) ?: return@withContext CategoryResult()
            CategoryResult(
                page = json.optInt("page", pg),
                pageCount = json.optInt("pagecount", 1),
                videos = parseVideoArray(json.getAsJsonArray("videos")),
            )
        }
    }

    override suspend fun search(keyword: String, pg: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        if (isCmsMode) {
            val url = "$baseUrl?ac=videolist&wd=${enc(keyword)}"
            val json = httpGetJson(url) ?: return@withContext emptyList()
            parseCmsList(json.getAsJsonArray("list"))
        } else {
            val tpl = endpoints["search"] ?: return@withContext emptyList()
            val url = tpl.replace("{kw}", enc(keyword)).replace("{pg}", pg.toString())
            val json = httpGetJson(url) ?: return@withContext emptyList()
            parseVideoArray(
                json.getAsJsonArray("videos") ?: json.getAsJsonArray("list")
            )
        }
    }

    override suspend fun detail(id: String): VideoDetail = withContext(Dispatchers.IO) {
        if (isCmsMode) {
            val url = "$baseUrl?ac=detail&ids=${enc(id)}"
            val json = httpGetJson(url) ?: return@withContext VideoDetail(id = id, title = id)
            val vod = json.getAsJsonArray("list")?.firstOrNull()?.asJsonObject
                ?: return@withContext VideoDetail(id = id, title = id)
            parseCmsDetail(vod)
        } else {
            val tpl = endpoints["detail"] ?: return@withContext VideoDetail(id = id, title = id)
            val url = tpl.replace("{id}", enc(id))
            val json = httpGetJson(url) ?: return@withContext VideoDetail(id = id, title = id)
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
        val json = httpGetJson(realUrl) ?: return@withContext PlayResult(url)
        val headers = mutableMapOf<String, String>()
        json.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
            headers[k] = v.asString
        }
        PlayResult(url = json.optString("url", url), headers = headers)
    }

    override suspend fun danmaku(videoId: String): DanmakuList = withContext(Dispatchers.IO) {
        val tpl = endpoints["danmaku"] ?: return@withContext DanmakuList()
        val url = tpl.replace("{id}", enc(videoId))
        val body = httpGet(url) ?: return@withContext DanmakuList()
        DanmakuParser.parse(body)
    }

    // ---- CMS helpers ----

    private fun parseCmsList(arr: com.google.gson.JsonArray?): List<VideoItem> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el.asJsonObject
                VideoItem(
                    id = o.optString("vod_id"),
                    title = o.optString("vod_name"),
                    url = o.optString("vod_id"),
                    pic = o.optString("vod_pic"),
                    remarks = o.optString("vod_remarks"),
                    sourceId = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseCmsDetail(vod: JsonObject): VideoDetail {
        val lines = mutableListOf<PlayLine>()
        val playFrom = vod.optString("vod_play_from")
        val playUrl = vod.optString("vod_play_url")
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
            id = vod.optString("vod_id"),
            title = vod.optString("vod_name"),
            pic = vod.optString("vod_pic"),
            desc = vod.optString("vod_content"),
            playLines = lines,
        )
    }

    private fun parseVideoArray(arr: com.google.gson.JsonArray?): List<VideoItem> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el.asJsonObject
                VideoItem(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    url = o.optString("url").ifBlank { o.optString("id") },
                    pic = o.optString("pic"),
                    remarks = o.optString("remarks"),
                    sourceId = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    // ---- http helpers ----

    private fun httpGet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogUtils.d(TAG, "http $url -> ${resp.code}")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "httpGet failed: $url: ${e.message}")
            null
        }
    }

    private fun httpGetJson(url: String): JsonObject? {
        val body = httpGet(url) ?: return null
        return try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            LogUtils.d(TAG, "parse json failed: $url: ${e.message}")
            null
        }
    }

    private fun fill(tpl: String, tid: String, pg: Int): String {
        return tpl.replace("{tid}", enc(tid)).replace("{pg}", pg.toString())
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun JsonObject.optString(key: String, fallback: String = ""): String {
        val v = get(key) ?: return fallback
        return try {
            if (v.isJsonNull) fallback else v.asString
        } catch (e: Exception) {
            fallback
        }
    }

    private fun JsonObject.optInt(key: String, fallback: Int): Int {
        val v = get(key) ?: return fallback
        return try {
            if (v.isJsonNull) fallback else v.asInt
        } catch (e: Exception) {
            fallback
        }
    }

    companion object {
        private const val TAG = "JsonVideoSource"
    }
}
