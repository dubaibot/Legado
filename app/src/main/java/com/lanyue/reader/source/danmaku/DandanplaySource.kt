package com.lanyue.reader.source.danmaku

import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.engine.NetworkException
import com.lanyue.reader.core.engine.RateLimit
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import com.lanyue.reader.source.model.DanmakuEvent
import com.lanyue.reader.source.model.DanmakuMatch
import com.lanyue.reader.source.model.DanmakuMatchRequest
import com.lanyue.reader.source.model.IDanmakuSource
import com.lanyue.reader.source.model.SourceCapability
import com.lanyue.reader.source.model.SourceType
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder

/**
 * 7.5 弹幕源（dandanplay v2 兼容 REST）。
 *
 * 端点：
 * - POST /api/v2/match          {fileName, fileHash, fileSize, videoDuration, matchMode}
 * - GET  /api/v2/search/episodes?anime=&episode=
 * - GET  /api/v2/comment/{episodeId}?withRelated=true&chConvert=1&from={ms}&duration={ms}
 *
 * 官方源签名：X-AppId / X-Timestamp / X-Signature
 *   签名算法以官方开放平台文档为准（社区常见 SHA256(appId + timestamp + appSecret)，
 *   实现前须核对 —— TODO-DEV-4）
 */
class DandanplaySource(
    private val entity: DanmakuSourceEntity,
    private val net: NetworkDispatcher,
    private val metrics: LocalMetrics,
    private val secretProvider: (String) -> String?
) : IDanmakuSource {

    override val sourceId: String = entity.id

    override val sourceType: SourceType = SourceType.DANMAKU

    /** 5.5 弹幕源：无 JS（REST），GET + POST（仅 dandanplay v2 端点）。 */
    override val capabilityFlags: Set<SourceCapability> =
        setOf(SourceCapability.SEARCHABLE, SourceCapability.HAS_CONTENT, SourceCapability.HIGH_PRIORITY)

    override val concurrentRate: RateLimit? = RateLimit(5, 1000)

    private val base: String get() = entity.apiBase.trimEnd('/')

    override suspend fun match(req: DanmakuMatchRequest): DanmakuMatch? {
        // 7.5 / 4.10.3 本地文件：POST /api/v2/match
        val payload = JsonObject().apply {
            addProperty("fileName", req.fileName ?: req.title)
            addProperty("fileHash", req.fileHash ?: "")
            addProperty("fileSize", req.fileSize ?: 0L)
            addProperty("videoDuration", req.durationMs ?: 0L)
            addProperty("matchMode", "hashAndFileName")
        }.toString()

        val resp = runCatching {
            net.execute(
                HttpRequest(
                    url = base + "/api/v2/match",
                    method = "POST",
                    headers = signedHeaders(),
                    body = payload.toByteArray(),
                    sourceId = sourceId
                ),
                limit = concurrentRate,
                traceId = sourceId + ":match"
            )
        }.getOrNull() ?: return null

        if (!resp.isSuccess) return null
        val json = runCatching { JsonParser.parseString(resp.body ?: "").asJsonObject }.getOrNull()
            ?: return null

        if (json.get("isMatched")?.asBoolean == true) {
            val matches = json.getAsJsonArray("matches") ?: return null
            return pickBest(matches.map { it.asJsonObject }, req.title, req.episode)
        }
        return null
    }

    override suspend fun search(key: String): List<DanmakuMatch> {
        if (key.isBlank()) return emptyList()
        return searchEpisodes(key, null)
    }

    /** 4.10.3 在线影视：GET /api/v2/search/episodes?anime=&episode= */
    suspend fun searchEpisodes(anime: String, episode: Int?): List<DanmakuMatch> {
        val sb = StringBuilder(base)
        sb.append("/api/v2/search/episodes?anime=").append(URLEncoder.encode(anime, "UTF-8"))
        if (episode != null) sb.append("&episode=").append(episode)

        val resp = runCatching {
            net.execute(
                HttpRequest(
                    url = sb.toString(),
                    method = "GET",
                    headers = signedHeaders(),
                    sourceId = sourceId
                ),
                limit = concurrentRate,
                traceId = sourceId + ":search"
            )
        }.getOrNull() ?: return emptyList()

        if (!resp.isSuccess) return emptyList()
        val json = runCatching { JsonParser.parseString(resp.body ?: "").asJsonObject }.getOrNull()
            ?: return emptyList()

        // 响应结构：{ success, animes: [ { animeTitle, episodes: [ {episodeId, episodeTitle} ] } ] }
        val animes = json.getAsJsonArray("animes") ?: return emptyList()
        val out = mutableListOf<DanmakuMatch>()
        animes.forEach { animeEl ->
            val animeObj = runCatching { animeEl.asJsonObject }.getOrNull() ?: return@forEach
            val animeTitle = animeObj.get("animeTitle")?.asString ?: ""
            animeObj.getAsJsonArray("episodes")?.forEach { epEl ->
                val epObj = runCatching { epEl.asJsonObject }.getOrNull() ?: return@forEach
                val id = epObj.get("episodeId")?.asLong ?: return@forEach
                out += DanmakuMatch(
                    episodeId = id,
                    animeTitle = animeTitle,
                    episodeTitle = epObj.get("episodeTitle")?.asString ?: "",
                    shift = epObj.get("shift")?.asLong ?: 0L,
                    sourceId = sourceId
                )
            }
        }
        return out
    }

    override suspend fun getComments(match: DanmakuMatch, fromMs: Long, durationMs: Long): List<DanmakuEvent> {
        // 4.10.4 拉取：from/duration 为自建兼容源扩展参数（TODO-DEV-4 核对官方参数集）
        val url = base + "/api/v2/comment/" + match.episodeId +
            "?withRelated=true&chConvert=1&from=" + fromMs + "&duration=" + durationMs

        val resp = runCatching {
            net.execute(
                HttpRequest(url = url, method = "GET", headers = signedHeaders(), sourceId = sourceId),
                limit = concurrentRate,
                traceId = sourceId + ":comments"
            )
        }.getOrNull() ?: return emptyList()

        if (!resp.isSuccess) return emptyList()
        val json = runCatching { JsonParser.parseString(resp.body ?: "").asJsonObject }.getOrNull()
            ?: return emptyList()

        val comments = json.getAsJsonArray("comments") ?: return emptyList()
        return comments.mapNotNull { el ->
            val obj = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val p = obj.get("p")?.asString ?: return@mapNotNull null
            val text = obj.get("m")?.asString ?: return@mapNotNull null
            parseP(p, text)
        }
    }

    // ---------- 内部 ----------

    /**
     * 4.10.8 p 格式：出现时间(秒,浮点),弹幕模式,颜色,[来源]用户ID
     * 模式 1=滚动 2=底部 3=顶部
     */
    private fun parseP(p: String, text: String): DanmakuEvent? {
        val parts = p.split(",")
        if (parts.size < 3) return null
        val timeSec = parts[0].toDoubleOrNull() ?: return null
        val mode = parts[1].toIntOrNull() ?: 1
        val color = parts[2].toLongOrNull()?.toInt() ?: 0xFFFFFF
        return DanmakuEvent(
            timeMs = (timeSec * 1000).toLong(),
            mode = mode,
            color = color,
            text = text,
            source = sourceId
        )
    }

    /**
     * 4.10.3 多结果选取策略（策略单点定义，match 与 search 共用）：
     * 1. 标题完全一致 > 2. 集数一致 > 3. 第一个结果
     */
    fun pickBest(candidates: List<JsonObject>, title: String, episode: Int?): DanmakuMatch? {
        if (candidates.isEmpty()) return null

        val mapped = candidates.mapNotNull { obj ->
            val id = obj.get("episodeId")?.asLong ?: return@mapNotNull null
            DanmakuMatch(
                episodeId = id,
                animeTitle = obj.get("animeTitle")?.asString ?: "",
                episodeTitle = obj.get("episodeTitle")?.asString ?: "",
                shift = obj.get("shift")?.asLong ?: 0L,
                sourceId = sourceId
            )
        }
        if (mapped.isEmpty()) return null

        // 1. 标题完全一致
        mapped.firstOrNull { it.animeTitle.equals(title, ignoreCase = true) }?.let { return it }

        // 2. 集数一致
        if (episode != null) {
            mapped.firstOrNull { it.episodeTitle.contains(episode.toString()) }?.let { return it }
        }

        // 3. 第一个结果
        return mapped.first()
    }

    /**
     * 7.5 官方源签名。仅当配置了 appId + appSecret 时添加。
     * 自建兼容源不填则直接可用。
     */
    private fun signedHeaders(): Map<String, String> {
        val appId = entity.appId ?: return emptyMap()
        val secret = secretProvider(entity.id) ?: return emptyMap()
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signature = sha256Hex(appId + timestamp + secret)
        return mapOf(
            "X-AppId" to appId,
            "X-Timestamp" to timestamp,
            "X-Signature" to signature,
            "Content-Type" to "application/json"
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 4.10.1 保存时探活校验。 */
    suspend fun probe(): Boolean {
        val resp = runCatching {
            net.execute(
                HttpRequest(url = base + "/api/v2/search/episodes?anime=test", method = "GET", sourceId = sourceId),
                limit = concurrentRate,
                traceId = sourceId + ":probe"
            )
        }.getOrNull()
        return resp?.isSuccess == true
    }
}
