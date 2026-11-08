package com.lanyue.reader.source.video

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.engine.NetworkException
import com.lanyue.reader.core.engine.RateLimit
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import com.lanyue.reader.source.model.CapabilityResolver
import com.lanyue.reader.source.model.Category
import com.lanyue.reader.source.model.Filter
import com.lanyue.reader.source.model.IVideoSource
import com.lanyue.reader.source.model.PlayKind
import com.lanyue.reader.source.model.PlayResult
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.SourceType
import com.lanyue.reader.source.model.VideoDetail
import com.lanyue.reader.source.model.VideoEpisode
import com.lanyue.reader.source.model.VideoItem
import com.lanyue.reader.source.model.VideoPlayLine
import java.net.URLEncoder

/**
 * 7.3 CMS 影视源（type 0 XML / 1 JSON / 2 JSON 扩展 / 4 JSON 扩展1）。
 *
 * CMS 协议要点：
 * - 分类：api?ac=list
 * - 详情：api?ac=videolist&ids=
 * - 搜索：api?wd=key&pg=n
 * - 筛选：ext.filter
 *
 * 播放解析链：选集 url -> flags 匹配 -> 直链 / parses 分发（见 LocalParseProxy）
 */
class CmsVideoSource(
    private val entity: VideoSiteEntity,
    private val net: NetworkDispatcher,
    private val metrics: LocalMetrics,
    private val parses: List<TvBoxParser.ParseRule>,
    private val flags: List<String>
) : IVideoSource {

    override val sourceId: String = entity.configUrl + "#" + entity.siteKey

    override val sourceType: SourceType = SourceType.VIDEO

    override val capabilityFlags: Set<SourceCapability> = CapabilityResolver.ofVideoSite(entity)

    override val concurrentRate: RateLimit? = null

    private val apiBase: String get() = entity.api ?: ""

    override suspend fun getCategories(): List<Category> {
        val url = apiBase + "?ac=list"
        val json = fetchJson(url, "categories")
        val classes = runCatching { json.get("class")?.asJsonArray }.getOrNull()
            ?: return emptyList()

        return classes.mapNotNull { el ->
            val obj = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj.str("type_id") ?: return@mapNotNull null
            Category(name = obj.str("type_name") ?: id, id = id)
        }
    }

    override suspend fun explore(category: Category, filter: Filter?, page: Int): List<VideoItem> {
        val sb = StringBuilder(apiBase)
        sb.append("?ac=detail&t=").append(category.id).append("&pg=").append(page)
        // 7.3 筛选：ext.filter
        if (filter != null) {
            sb.append("&f=").append(URLEncoder.encode(filter.key + "=" + filter.value(), "UTF-8"))
        }
        val json = fetchJson(sb.toString(), "explore")
        return parseVideoList(json)
    }

    override suspend fun search(key: String, page: Int): List<VideoItem> {
        val url = apiBase + "?wd=" + URLEncoder.encode(key, "UTF-8") + "&pg=" + page
        val json = fetchJson(url, "search")
        return parseVideoList(json)
    }

    override suspend fun getDetail(url: String): VideoDetail {
        // url 形如 api?ac=detail&ids=xxx
        val json = fetchJson(url, "detail")
        val list = json.getAsJsonArray("list") ?: return VideoDetail(name = "")
        val obj = runCatching { list.first().asJsonObject }.getOrNull() ?: return VideoDetail(name = "")

        return VideoDetail(
            name = obj.str("vod_name") ?: "",
            cover = obj.str("vod_pic"),
            intro = obj.str("vod_content")?.let { stripHtml(it) },
            actor = obj.str("vod_actor"),
            director = obj.str("vod_director"),
            year = obj.str("vod_year"),
            area = obj.str("vod_area"),
            remark = obj.str("vod_remarks"),
            lines = parsePlayLines(obj)
        )
    }

    override suspend fun getPlay(episode: VideoEpisode): PlayResult {
        val url = episode.url

        // 7.3 播放解析链第一跳：直链（m3u8/mp4 等）直接播放
        if (isDirectMedia(url)) {
            return PlayResult(PlayKind.DIRECT, url)
        }

        // 命中 flags -> parses 选取
        val matchedFlag = flags.firstOrNull { url.contains(it, ignoreCase = true) }
        val parseRule = pickParseRule()
        return when {
            parseRule == null -> PlayResult(PlayKind.SNIFF, url)
            parseRule.type == 3 -> PlayResult(PlayKind.SNIFF, url, flag = matchedFlag)
            else -> PlayResult(PlayKind.PARSE, url, flag = matchedFlag)
        }
    }

    // ---------- 内部 ----------

    private suspend fun fetchJson(url: String, op: String): JsonObject {
        val traceId = sourceId + ":" + op
        val resp = net.execute(
            HttpRequest(url = url, method = "GET", sourceId = sourceId, timeoutMs = entity.timeout.takeIf { it > 0 } ?: 20_000L),
            limit = concurrentRate,
            traceId = traceId
        )
        if (!resp.isSuccess) {
            metrics.failure(traceId, "cms", "HTTP_" + resp.code)
            throw NetworkException("cms http " + resp.code, resp.code)
        }
        return runCatching { JsonParser.parseString(resp.body ?: "").asJsonObject }.getOrNull()
            ?: throw NetworkException("cms response is not json")
    }

    private fun parseVideoList(json: JsonObject): List<VideoItem> {
        val list = json.getAsJsonArray("list") ?: return emptyList()
        return list.mapNotNull { el ->
            val obj = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj.str("vod_id") ?: return@mapNotNull null
            VideoItem(
                name = obj.str("vod_name") ?: "",
                url = apiBase + "?ac=detail&ids=" + id,
                cover = obj.str("vod_pic"),
                remark = obj.str("vod_remarks"),
                siteKey = entity.siteKey,
                configUrl = entity.configUrl
            )
        }
    }

    /** vod_play_from 与 vod_play_url 按 ### 分隔，多条线路按 $$$ 分隔。 */
    private fun parsePlayLines(obj: JsonObject): List<VideoPlayLine> {
        val fromRaw = obj.str("vod_play_from") ?: return emptyList()
        val urlRaw = obj.str("vod_play_url") ?: return emptyList()

        val names = fromRaw.split("$$$")
        val groups = urlRaw.split("$$$")

        return names.mapIndexedNotNull { index, lineName ->
            val group = groups.getOrNull(index) ?: return@mapIndexedNotNull null
            val episodes = group.split("#").mapNotNull { seg ->
                val parts = seg.split("$")
                if (parts.size < 2) null
                else VideoEpisode(name = parts[0].trim(), url = parts[1].trim())
            }
            if (episodes.isEmpty()) null else VideoPlayLine(lineName.trim(), episodes)
        }
    }

    private fun pickParseRule(): TvBoxParser.ParseRule? =
        parses.firstOrNull { it.type in 0..3 }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains(".flv") || lower.contains(".mpd") || lower.contains(".ts")
    }

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]*>"), "").trim()

    private fun Filter.value(): String =
        values.firstOrNull()?.value ?: ""

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}
