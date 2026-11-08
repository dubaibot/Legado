package io.legado.app.data.video

import com.github.catvod.crawler.Spider
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.HashMap

/**
 * jar 爬虫（FongMi Spider）视频源适配器。
 *
 * 把 jar 内 Spider 的 MacCMS 格式 JSON 输出适配为统一 VideoSource 数据模型。
 * 解析逻辑与 JsonVideoSource CMS 模式一致（vod_* 字段）。
 */
class JarVideoSource(
    override val sourceId: String,
    override val sourceName: String,
    private val jarSpec: String,
    private val siteApi: String,
    private val ext: String = "",
) : VideoSource {

    override val sourceType = SourceType.JAR

    /** spider 实例懒加载（jar 下载+DexClassLoader 首次调用较慢） */
    private val spider: Spider by lazy { JarSpiderLoader.load(jarSpec, siteApi, sourceId, ext) }

    override suspend fun home(): Pair<List<VideoCategory>, List<VideoItem>> = withContext(Dispatchers.IO) {
        val json = try {
            parse(spider.homeContent(false))
        } catch (e: Exception) {
            throw IllegalStateException("jar spider homeContent 失败: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        val categories = mutableListOf<VideoCategory>()
        json.getAsJsonArray("class")?.forEach { el ->
            val o = el.asJsonObject
            categories.add(VideoCategory(str(o, "type_id", "id"), str(o, "type_name", "name")))
        }
        val videos = parseList(json.getAsJsonArray("list")).toMutableList()
        // homeVideoContent 合并（部分 spider 只实现 homeVideoContent）
        try {
            val extra = parse(spider.homeVideoContent())
            val extraVideos = parseList(extra.getAsJsonArray("list") ?: extra.getAsJsonArray("vod_list"))
            if (extraVideos.isNotEmpty()) videos.addAll(extraVideos)
        } catch (_: Exception) {
        }
        Pair(categories, videos)
    }

    override suspend fun category(tid: String, pg: Int): CategoryResult = withContext(Dispatchers.IO) {
        val json = try {
            parse(spider.categoryContent(tid, pg.toString(), false, HashMap()))
        } catch (e: Exception) {
            throw IllegalStateException("jar spider categoryContent 失败: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        CategoryResult(
            page = json.get("page")?.takeIf { it.isJsonPrimitive }?.asInt ?: pg,
            pageCount = json.get("pagecount")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1,
            videos = parseList(json.getAsJsonArray("list")),
        )
    }

    override suspend fun search(keyword: String, pg: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val json = try {
            parse(spider.searchContent(keyword, false))
        } catch (e: Exception) {
            throw IllegalStateException("jar spider searchContent 失败: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        parseList(json.getAsJsonArray("list"))
    }

    override suspend fun detail(id: String): VideoDetail = withContext(Dispatchers.IO) {
        val vod = try {
            parse(spider.detailContent(listOf(id))).getAsJsonArray("list")
        } catch (e: Exception) {
            throw IllegalStateException("jar spider detailContent 失败: ${e.javaClass.simpleName}: ${e.message}", e)
        }
            ?.firstOrNull()?.asJsonObject
            ?: return@withContext VideoDetail(id = id, title = id)
        parseDetail(vod)
    }

    override suspend fun play(url: String): PlayResult = withContext(Dispatchers.IO) {
        val rawJson = try {
            spider.playerContent("", url, emptyList())
        } catch (e: Exception) {
            throw IllegalStateException("jar spider playerContent 失败: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        AppLog.putDebug("JarSource.play: raw jsonSize=${rawJson.length}")
        val json = try {
            JsonParser.parseString(rawJson).getAsJsonObject()
        } catch (_: Exception) {
            JsonObject()
        }
        var resultUrl = ""
        val headers = mutableMapOf<String, String>()
        // FongMi Spider 规范：playerContent 返回顶层 {"url":"...","header":"..."}；
        // 部分爬虫兼容 {"playUrl":{"url":"...","header":"..."}} 或 {"playUrl":"..."} 两种形态
        val playUrl = json.get("playUrl")
        val playUrlObj = if (playUrl is JsonObject) playUrl else null
        if (playUrlObj != null) {
            resultUrl = str(playUrlObj, "url", "playUrl", "link")
            parseHeaderString(str(playUrlObj, "header", "headers", "headerInfo"))
                .let { headers.putAll(it) }
        } else if (playUrl != null && playUrl.isJsonPrimitive) {
            resultUrl = playUrl.asString
        }
        // 顶层 url 键（FongMi 通用形式，playUrl 缺失时兜底解析）
        if (resultUrl.isBlank()) {
            val topUrl = str(json, "url", "link", "video", "m3u8", "msg")
            if (topUrl.isNotBlank()) {
                resultUrl = topUrl.replace("[\\r\\n]+".toRegex(), "")
                parseHeaderString(str(json, "header", "headers", "headerInfo"))
                    .let { headers.putAll(it) }
            }
        }
        if (resultUrl.isBlank()) resultUrl = url
        PlayResult(url = resultUrl, headers = headers)
    }

    override suspend fun danmaku(videoId: String): DanmakuList = DanmakuList()

    /** 解析 MacCMS 视频列表（vod_* 字段） */
    private fun parseList(arr: com.google.gson.JsonArray?): List<VideoItem> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el.asJsonObject
                VideoItem(
                    id = str(o, "vod_id", "id"),
                    title = str(o, "vod_name", "name", "title"),
                    url = str(o, "vod_url", "url", "link").ifBlank { str(o, "vod_id", "id") },
                    pic = str(o, "vod_pic", "pic", "img", "cover"),
                    remarks = str(o, "vod_remarks", "remarks", "remark", "note"),
                    sourceId = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 解析 MacCMS 详情（vod_play_from/vod_play_url 线路格式） */
    private fun parseDetail(vod: JsonObject): VideoDetail {
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

    /** spider JSON 字符串 → JsonObject（失败返回空对象） */
    private fun parse(json: String): JsonObject = try {
        JsonParser.parseString(json).getAsJsonObject()
    } catch (e: Exception) {
        JsonObject()
    }

    private fun parseHeaderString(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            JsonParser.parseString(raw).getAsJsonObject()
                .entrySet()
                .associate { (k, v) -> k to v.asString }
        } catch (_: Exception) {
            raw.split("&")
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
                }
                .toMap()
                .filterValues { it.isNotEmpty() }
        }
    }

    private fun str(obj: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val v = obj.get(key) ?: continue
            try {
                if (!v.isJsonNull) {
                    val s = if (v.isJsonPrimitive) v.asString else v.toString()
                    if (s.isNotBlank()) return s
                }
            } catch (e: Exception) {
                continue
            }
        }
        return ""
    }
}
