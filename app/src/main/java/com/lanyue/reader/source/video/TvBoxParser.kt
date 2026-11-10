package com.lanyue.reader.source.video

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lanyue.reader.data.db.entity.LiveSourceEntity
import com.lanyue.reader.data.db.entity.VideoApiEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity

/**
 * 7.3 TVBox 配置解析。
 *
 * 关键约束：
 * - 一条配置展开为多站点源
 * - 重新导入按同一接口配置内 site.key 去重合并
 * - hotSearch 字段一期有意裁剪：不解析、不存储（符合零分发红线，属主动裁剪）
 * - type 3 且 api 非 .js（即 spider jar）标记「暂不支持」，不崩溃
 * - lives 解析后持久化，一期不暴露 UI
 */
class TvBoxParser {

    data class ParsedConfig(
        val api: VideoApiEntity,
        val sites: List<VideoSiteEntity>,
        val lives: List<LiveSourceEntity>,
        val dropsJarSites: Int,
        /** 7.3 / ADR-014：parses 链，供播放解析使用。 */
        val parses: List<ParseRule>,
        val flags: List<String>
    )

    /** 7.3 parses 项。type 0/1 JSON 解析，type 2 聚合，type 3 网页嗅探。 */
    data class ParseRule(
        val name: String,
        val type: Int,
        val url: String,
        val ext: String? = null
    )

    sealed class Result {
        data class Ok(val config: ParsedConfig) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun parse(configUrl: String, json: String): Result {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return Result.Invalid("config is not a JSON object")

        // 7.3 sites 必须存在，否则这条配置无意义
        val sitesArray = root.getAsJsonArray("sites") ?: return Result.Invalid("missing sites")

        val spider = root.get("spider")?.takeIf { !it.isJsonNull }?.asString
        val api = VideoApiEntity(
            url = configUrl,
            name = root.get("name")?.takeIf { !it.isJsonNull }?.asString,
            spider = spider,
            lastUpdate = System.currentTimeMillis(),
            rawJson = json
        )

        var jarDrops = 0
        val sites = sitesArray.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val key = obj.str("key") ?: return@mapNotNull null
            val siteApi = obj.str("api")
            val type = obj.int("type", 1)

            // 7.3 type 3：api 以 .js 结尾是 drpy（一期支持）；否则是 spider jar（二期）
            val isDrpy = type == 3 && siteApi?.endsWith(".js") == true
            val unsupported = type == 3 && !isDrpy

            if (unsupported) jarDrops += 1

            VideoSiteEntity(
                configUrl = configUrl,
                siteKey = key,
                name = obj.str("name") ?: key,
                type = type,
                api = siteApi,
                searchable = obj.bool("searchable", true),
                quickSearch = obj.bool("quickSearch", true),
                filterable = obj.bool("filterable", false),
                ext = obj.get("ext")?.takeIf { !it.isJsonNull }?.toString(),
                jar = obj.str("jar"),
                timeout = obj.long("timeout", 0L),
                enabled = !unsupported,
                unsupported = unsupported
            )
        }

        // 7.3 parses 解析链
        val parses = root.getAsJsonArray("parses")?.mapNotNull { el ->
            val obj = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val url = obj.str("url") ?: return@mapNotNull null
            ParseRule(
                name = obj.str("name") ?: "",
                type = obj.int("type", 0),
                url = url,
                ext = obj.get("ext")?.takeIf { !it.isJsonNull }?.toString()
            )
        }.orEmpty()

        val flags = root.getAsJsonArray("flags")?.mapNotNull { it.asString }.orEmpty()

        // 4.12 live_sources：一期解析并持久化，UI 不暴露
        val lives = root.getAsJsonArray("lives")?.mapIndexedNotNull { index, el ->
            val obj = runCatching { el.asJsonObject }.getOrNull() ?: return@mapIndexedNotNull null
            val url = obj.str("url") ?: return@mapIndexedNotNull null
            LiveSourceEntity(
                id = configUrl + "#live#" + index,
                apiUrl = configUrl,
                name = obj.str("name") ?: "",
                type = obj.str("type") ?: "txt",
                url = url,
                epg = obj.str("epg"),
                logo = obj.str("logo")
            )
        }.orEmpty()

        // hotSearch 有意裁剪：此处不读取、不写入（见 6.3 / ADR-014）

        return Result.Ok(
            ParsedConfig(
                api = api,
                sites = sites,
                lives = lives,
                dropsJarSites = jarDrops,
                parses = parses,
                flags = flags
            )
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.int(key: String, default: Int): Int =
        runCatching { get(key)?.asInt ?: default }.getOrDefault(default)

    private fun JsonObject.long(key: String, default: Long): Long =
        runCatching { get(key)?.asLong ?: default }.getOrDefault(default)

    private fun JsonObject.bool(key: String, default: Boolean): Boolean =
        runCatching { get(key)?.asBoolean ?: default }.getOrDefault(default)
}
