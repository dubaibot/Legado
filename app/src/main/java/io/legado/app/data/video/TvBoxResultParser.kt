package io.legado.app.data.video

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Shared parser for the MacCMS/FongMi result shapes used by CMS and jar spiders. */
object TvBoxResultParser {

    fun parsePlay(raw: String, fallbackUrl: String): PlayResult {
        val element = runCatching { JsonParser.parseString(raw) }.getOrNull()
        if (element?.isJsonObject != true) {
            val candidate = element?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString ?: raw.trim()
            return PlayResult(candidate.takeIf {
                it.startsWith("https://") || it.startsWith("http://")
            } ?: fallbackUrl)
        }
        val root = element.asJsonObject

        val playObject = root.get("playUrl")?.takeIf { it.isJsonObject }?.asJsonObject
        val source = playObject ?: root
        val url = firstString(source, "url", "playUrl", "play_url", "link", "video", "m3u8", "msg")
            .ifBlank { firstString(root, "url", "playUrl", "play_url", "link") }
            .replace(Regex("[\\r\\n]+"), "")
            .ifBlank { fallbackUrl }
        val headers = parseHeaders(root)
        if (source !== root) headers.putAll(parseHeaders(source))
        val parse = source.get("parse")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.toIntOrNull()
            ?: root.get("parse")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.toIntOrNull() ?: -1
        return PlayResult(url = url, headers = headers, parse = parse)
    }

    fun parsePlayLines(vod: JsonObject): List<PlayLine> {
        val direct = listOf("play_lines", "play_urls").firstNotNullOfOrNull { key ->
            vod.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        }
        if (direct != null) {
            val parsed = mutableListOf<PlayLine>()
            direct.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val item = element.asJsonObject
                val episodes = item.get("episodes")?.takeIf { it.isJsonArray }?.asJsonArray
                val routeName = firstString(item, "name", "title")
                val flag = firstString(item, "flag")
                val routeHeaders = parseHeaders(item)
                if (episodes != null) {
                    episodes.forEach episodeLoop@{ episode ->
                        if (!episode.isJsonObject) return@episodeLoop
                        val ep = episode.asJsonObject
                        val url = firstString(ep, "url", "link")
                        if (url.isBlank()) return@episodeLoop
                        val name = firstString(ep, "name", "title", "ep")
                            .ifBlank { "第${parsed.size + 1}集" }
                        parsed.add(PlayLine(
                            name = if (routeName.isBlank()) name else "$routeName·$name",
                            url = url,
                            headers = routeHeaders + parseHeaders(ep),
                            flag = firstString(ep, "flag").ifBlank { flag },
                        ))
                    }
                } else {
                    val url = firstString(item, "url", "link")
                    if (url.isNotBlank()) parsed.add(PlayLine(
                        name = firstString(item, "name", "title", "ep").ifBlank { "第${parsed.size + 1}集" },
                        url = url,
                        headers = routeHeaders,
                        flag = flag,
                    ))
                }
            }
            if (parsed.isNotEmpty()) return parsed
        }

        val fromNames = firstString(vod, "vod_play_from", "play_from")
            .split("$$$")
        val groups = firstString(vod, "vod_play_url", "play_url")
            .split("$$$")
        val lines = mutableListOf<PlayLine>()
        groups.forEachIndexed { index, group ->
            val routeName = fromNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "线路${index + 1}"
            group.split("#").filter { it.isNotBlank() }.forEach { episode ->
                val parts = episode.split("$", limit = 2)
                val episodeName = parts.first().takeIf { parts.size == 2 && it.isNotBlank() }
                    ?: "第${lines.size + 1}集"
                val url = parts.lastOrNull().orEmpty()
                if (url.isNotBlank()) lines.add(PlayLine(
                    name = "$routeName·$episodeName",
                    url = url,
                    flag = fromNames.getOrNull(index).orEmpty(),
                ))
            }
        }
        return lines
    }

    private fun parseHeaders(obj: JsonObject): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        listOf("headers", "header", "headerInfo").forEach { key ->
            val value = obj.get(key) ?: return@forEach
            if (value.isJsonObject) {
                value.asJsonObject.entrySet().forEach { (name, header) ->
                    if (header.isJsonPrimitive) result[name] = header.asString
                }
            } else if (value.isJsonPrimitive) {
                parseHeaderText(value.asString).forEach { (name, header) -> result[name] = header }
            }
        }
        return result
    }

    private fun parseHeaderText(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        runCatching {
            JsonParser.parseString(raw).asJsonObject.entrySet().associate { (key, value) -> key to value.asString }
        }.getOrNull()?.let { return it }
        return raw.split("&").mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
        }.filter { it.second.isNotBlank() }.toMap()
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = obj.get(key) ?: return@forEach
            if (!value.isJsonNull && value.isJsonPrimitive && value.asString.isNotBlank()) return value.asString
        }
        return ""
    }
}
