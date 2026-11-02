package io.legado.app.data.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.utils.LogUtils

/**
 * py 插件返回结果解析器：把插件 JSON 字符串解析为统一数据模型。
 * 兼容多种常见字段命名（videos/list、play_lines/play_urls、CMS 格式等）。
 */
object PyResultParser {

    private const val TAG = "PyResultParser"

    fun parseHome(json: String): Pair<List<VideoCategory>, List<VideoItem>> {
        val obj = parseObj(json) ?: return Pair(emptyList(), emptyList())
        val categories = mutableListOf<VideoCategory>()
        val catArr = obj.getAsJsonArray("categories")
            ?: obj.getAsJsonArray("class")
        catArr?.forEach { el ->
            try {
                val o = el.asJsonObject
                categories.add(
                    VideoCategory(
                        id = str(o, "id", "type_id", "tid"),
                        name = str(o, "name", "type_name", "title"),
                    )
                )
            } catch (e: Exception) {
            }
        }
        return Pair(categories, parseVideoArray(obj.getAsJsonArray("videos") ?: obj.getAsJsonArray("list")))
    }

    fun parseCategory(json: String, pg: Int): CategoryResult {
        val obj = parseObj(json) ?: return CategoryResult()
        return CategoryResult(
            page = intOf(obj, pg, "page"),
            pageCount = intOf(obj, 1, "pagecount", "page_count"),
            videos = parseVideoArray(obj.getAsJsonArray("videos") ?: obj.getAsJsonArray("list")),
        )
    }

    fun parseVideoList(json: String): List<VideoItem> {
        val root = parseObj(json)
        val arr = root?.getAsJsonArray("videos")
            ?: root?.getAsJsonArray("list")
            ?: runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull()
        return parseVideoArray(arr)
    }

    fun parseDetail(json: String, fallbackId: String): VideoDetail {
        val obj = parseObj(json) ?: return VideoDetail(id = fallbackId, title = fallbackId)
        // detail 可能直接返回 video 对象或包在 video/detail 字段里
        val video = (obj.getAsJsonObject("video") ?: obj.getAsJsonObject("detail")) ?: obj
        val lines = mutableListOf<PlayLine>()
        // 统一格式: play_lines / play_urls = [{"name","url"}]
        val lineArr = video.getAsJsonArray("play_lines") ?: video.getAsJsonArray("play_urls")
        lineArr?.forEach { el ->
            try {
                val o = el.asJsonObject
                val name = str(o, "name", "title", "ep")
                val url = str(o, "url", "link")
                if (url.isNotBlank()) {
                    lines.add(PlayLine(name = name.ifBlank { "第${lines.size + 1}集" }, url = url))
                }
            } catch (e: Exception) {
            }
        }
        // CMS 格式: vod_play_url = "集名$url#集名$url"（多线路 $$$ 分隔）
        if (lines.isEmpty()) {
            val cmsFrom = str(video, "vod_play_from")
            val cmsUrl = str(video, "vod_play_url")
            if (cmsUrl.isNotBlank()) {
                val fromNames = cmsFrom.split("$$$").filter { it.isNotBlank() }
                val urlGroups = cmsUrl.split("$$$").filter { it.isNotBlank() }
                fromNames.forEachIndexed { idx, name ->
                    urlGroups.getOrNull(idx)?.split("#")?.filter { it.isNotBlank() }
                        ?.forEach { ep ->
                            val parts = ep.split("$")
                            val epUrl = parts.last()
                            if (epUrl.isNotBlank()) {
                                val epName = if (parts.size >= 2) parts[0] else "第${lines.size + 1}集"
                                lines.add(PlayLine(name = "$name·$epName", url = epUrl))
                            }
                        }
                }
            }
        }
        return VideoDetail(
            id = str(video, "id", "vod_id").ifBlank { fallbackId },
            title = str(video, "title", "vod_name", "name").ifBlank { fallbackId },
            pic = str(video, "pic", "vod_pic", "cover"),
            desc = str(video, "desc", "vod_content", "description", "remark"),
            playLines = lines,
        )
    }

    fun parsePlay(json: String, fallbackUrl: String): PlayResult {
        // play 可能直接返回 URL 字符串
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) {
            return PlayResult(url = trimmed.removeSurrounding("\""))
        }
        val obj = parseObj(json) ?: return PlayResult(fallbackUrl)
        val url = str(obj, "url", "play_url", "link").ifBlank { fallbackUrl }
        val headers = mutableMapOf<String, String>()
        obj.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
            try {
                headers[k] = v.asString
            } catch (e: Exception) {
            }
        }
        return PlayResult(url = url, headers = headers)
    }

    private fun parseVideoArray(arr: JsonArray?): List<VideoItem> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el.asJsonObject
                val id = str(o, "id", "vod_id", "book_id")
                val url = str(o, "url", "link").ifBlank { id }
                VideoItem(
                    id = id,
                    title = str(o, "title", "vod_name", "name"),
                    url = url,
                    pic = str(o, "pic", "vod_pic", "cover", "img"),
                    remarks = str(o, "remarks", "remark", "note", "vod_remarks"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseObj(json: String): JsonObject? {
        return try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            LogUtils.d(TAG, "parse json failed: ${e.message}")
            null
        }
    }

    private fun str(obj: JsonObject, vararg keys: String): String {
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

    private fun intOf(obj: JsonObject, default: Int, vararg keys: String): Int {
        for (key in keys) {
            val v = obj.get(key) ?: continue
            try {
                if (!v.isJsonNull) return v.asInt
            } catch (e: Exception) {
                continue
            }
        }
        return default
    }
}
