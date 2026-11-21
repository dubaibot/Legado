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
                        filters = parseFilters(obj, str(o, "id", "type_id", "tid")),
                    )
                )
            } catch (e: Exception) {
            }
        }
        return Pair(categories, parseVideoArray(obj.getAsJsonArray("videos") ?: obj.getAsJsonArray("list")))
    }

    private fun parseFilters(obj: JsonObject, tid: String): List<VideoFilter> {
        val root = obj.get("filters")?.let { value ->
            runCatching { if (value.isJsonPrimitive) JsonParser.parseString(value.asString) else value }.getOrNull()
        }
        val groups = root?.takeIf { it.isJsonObject }?.asJsonObject?.get(tid)
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return groups.mapNotNull { element ->
            runCatching {
                val group = element.asJsonObject
                val key = str(group, "key")
                val values = group.get("value")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { item ->
                        runCatching {
                            val value = item.asJsonObject
                            VideoFilterValue(str(value, "n", "name", "title"),
                                (value.get("v") ?: value.get("value"))?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
                        }.getOrNull()
                    }.orEmpty()
                if (key.isBlank() || values.isEmpty()) null else VideoFilter(key, str(group, "name").ifBlank { key }, values)
            }.getOrNull()
        }
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
        val video = obj.get("list")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: obj.get("video")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: obj.get("detail")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: obj
        return VideoDetail(
            id = str(video, "id", "vod_id").ifBlank { fallbackId },
            title = str(video, "title", "vod_name", "name").ifBlank { fallbackId },
            pic = str(video, "pic", "vod_pic", "cover"),
            desc = str(video, "desc", "vod_content", "description", "remark"),
            playLines = TvBoxResultParser.parsePlayLines(video),
            director = str(video, "director", "vod_director"),
            actor = str(video, "actor", "vod_actor", "vod_starring", "starring"),
        )
    }

    fun parsePlay(json: String, fallbackUrl: String): PlayResult {
        return TvBoxResultParser.parsePlay(json, fallbackUrl)
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
