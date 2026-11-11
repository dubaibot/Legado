package io.legado.app.help.video

import com.google.gson.JsonParser
import io.legado.app.constant.PreferKey
import io.legado.app.data.video.DanmakuItem
import io.legado.app.data.video.DanmakuList
import io.legado.app.data.video.VideoHttpClient
import io.legado.app.utils.LogUtils
import io.legado.app.utils.getPrefString
import splitties.init.appCtx
import java.net.URLEncoder

/**
 * LogVar 弹幕 API 客户端（LogVar 弹幕服务器兼容弹弹play dandanplay 接口规范）。
 *
 * 核心端点：
 * - GET {base}/api/v2/search/episodes?anime={关键字}  按片名检索番剧及剧集
 * - GET {base}/api/v2/comment/{episodeId}?format=json  拉取弹幕
 * 服务器地址兼容三种写法：根地址（无 token）、含 token 路径段（/TOKEN）、
 * LogVar UI 复制的完整 API 端点（含 /api/v2 后缀，自动剥离）。
 *
 * 弹幕响应（dandanplay 规范）：
 * ```json
 * {"count":N,"comments":[{"cid":1,"p":"time,mode,color[,source]","m":"文本"}]}
 * ```
 * p 字段：time=秒, mode=1-3滚动/4底部/5顶部/6逆向, color=十进制色值
 */
object LogVarDanmaku {

    private const val TAG = "DanmakuLogVar"

    /** 剧集引用 */
    data class EpisodeRef(val episodeId: Long, val episodeTitle: String)

    /** 番剧引用（含剧集列表） */
    data class AnimeRef(val animeId: Long, val animeTitle: String, val episodes: List<EpisodeRef>)

    /** 服务器地址（PreferKey.danmakuServerUrl），兼容根地址/含token路径/完整API端点；未配置返回 null */
    fun serverUrl(): String? {
        val raw = appCtx.getPrefString(PreferKey.danmakuServerUrl, "")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        var base = raw.trimEnd('/')
        // LogVar UI 复制的完整端点形如 https://host/TOKEN/api/v2，剥离尾部 /api/v2
        if (base.endsWith("/api/v2")) base = base.removeSuffix("/api/v2").trimEnd('/')
        return base
    }

    /** 按关键字检索番剧剧集（兼容弹弹play /api/v2/search/episodes） */
    fun searchEpisodes(server: String, keyword: String): List<AnimeRef> {
        if (keyword.isBlank()) return emptyList()
        val url = "$server/api/v2/search/episodes?anime=${urlEncode(keyword)}"
        val body = VideoHttpClient.get(url, emptyMap()) ?: return emptyList()
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val animes = root.getAsJsonArray("animes") ?: return emptyList()
            animes.mapNotNull { el ->
                try {
                    val o = el.asJsonObject
                    val eps = o.getAsJsonArray("episodes")?.mapNotNull { e ->
                        try {
                            val eo = e.asJsonObject
                            val epId = eo.get("episodeId")?.takeIf { !it.isJsonNull }?.asLong
                                ?: return@mapNotNull null
                            EpisodeRef(
                                episodeId = epId,
                                episodeTitle = eo.get("episodeTitle")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            )
                        } catch (e: Exception) {
                            null
                        }
                    } ?: emptyList()
                    if (eps.isEmpty()) return@mapNotNull null
                    AnimeRef(
                        animeId = o.get("animeId")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                        animeTitle = o.get("animeTitle")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        episodes = eps
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "searchEpisodes parse failed: ${e.message}")
            emptyList()
        }
    }

    /** 拉取指定剧集弹幕（dandanplay comments JSON → DanmakuList，按时间升序） */
    fun fetchComments(server: String, episodeId: Long): DanmakuList {
        val url = "$server/api/v2/comment/$episodeId?format=json"
        val body = VideoHttpClient.get(url, emptyMap()) ?: return DanmakuList()
        val items = mutableListOf<DanmakuItem>()
        try {
            val root = JsonParser.parseString(body).asJsonObject
            val arr = root.getAsJsonArray("comments") ?: return DanmakuList()
            arr.forEach { el ->
                try {
                    val o = el.asJsonObject
                    val text = o.get("m")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                    val p = o.get("p")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                    val parts = p.split(",")
                    val time = parts.getOrNull(0)?.toFloatOrNull() ?: return@forEach
                    val mode = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    val color = (parts.getOrNull(2)?.toLongOrNull() ?: 0xFFFFFFL).toInt() and 0xFFFFFF
                    // dandanplay 模式：1-3滚动,4底部,5顶部,6逆向 → DanmakuItem 统一空间: 0滚动,1顶部,4底部
                    val modeNorm = when (mode) {
                        4 -> 4
                        5 -> 1
                        else -> 0
                    }
                    if (text.isNotBlank()) {
                        items.add(DanmakuItem(time = time, text = text, color = color, mode = modeNorm))
                    }
                } catch (e: Exception) {
                    // skip invalid item
                }
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "fetchComments parse failed: ${e.message}")
        }
        return DanmakuList(items.sortedBy { it.time })
    }

    /**
     * 端到端：检索番剧 → 匹配剧集 → 拉弹幕 → 转 B站 XML。
     * GSY 的 DanmakuLoaderFactory.TAG_BILI / BiliDanmukuParser 只认 B站 XML，
     * 统一在此转换，渲染层零改动。匹配失败或弹幕为空返回 null。
     */
    fun fetchForXml(server: String, showTitle: String, episodeTitle: String?): String? {
        val animes = searchEpisodes(server, showTitle)
        val anime = animes
            .map { it to animeScore(showTitle, it.animeTitle) }
            .filter { it.second >= 6 }
            .maxByOrNull { it.second }?.first ?: return null
        val ref = pickEpisode(anime, episodeTitle) ?: return null
        val list = fetchComments(server, ref.episodeId)
        if (list.items.isEmpty()) return null
        LogUtils.d(TAG, "matched: anime=${anime.animeTitle}, episode=${ref.episodeTitle}, items=${list.items.size}")
        return toBiliXml(list)
    }

    // ---- 匹配逻辑 ----

    /** 标题归一：去括号注释/空白/标点，小写 */
    internal fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[（(【\\[].*?[)）】\\]]"), "")
        .replace(Regex("[\\s·・]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** 提取集数：第N集/话/期、EP.N、结尾纯数字；年份数字（4位）与无集数文本返回 null */
    internal fun episodeNumber(s: String): Int? {
        val m = Regex("(?:第|ep\\.?|\\[)?\\s*(\\d{1,3})\\s*(?:[集话話期]|\\]|$)", RegexOption.IGNORE_CASE).find(s)
            ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
    }

    /** 片名匹配分：完全相等 100 > 包含 80 > 共同前缀（每字 2 分，阈值 6=至少 3 字共同前缀） */
    internal fun animeScore(show: String, anime: String): Int {
        val a = normalize(show)
        val b = normalize(anime)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 100
        if (b.contains(a) || a.contains(b)) return 80
        var i = 0
        val n = minOf(a.length, b.length)
        while (i < n && a[i] == b[i]) i++
        return i * 2
    }

    /** 剧集匹配：集数（集名编号 → 序号定位）→ 集名一致 → 单集作品兜底 → 首集 */
    internal fun pickEpisode(anime: AnimeRef, episodeTitle: String?): EpisodeRef? {
        val eps = anime.episodes
        if (eps.isEmpty()) return null
        val wanted = episodeTitle?.let { episodeNumber(it) }
        if (wanted != null) {
            eps.firstOrNull { episodeNumber(it.episodeTitle) == wanted }?.let { return it }
            eps.getOrNull(wanted - 1)?.let { return it }
        }
        if (!episodeTitle.isNullOrBlank()) {
            eps.firstOrNull { normalize(it.episodeTitle) == normalize(episodeTitle) }?.let { return it }
        }
        if (eps.size == 1) return eps[0]
        return eps.firstOrNull()
    }

    /** DanmakuList → B站 XML（DFM TAG_BILI loader 格式，p=time,mode,fontSize,color） */
    fun toBiliXml(list: DanmakuList): String = buildString {
        append("<i><chatserver>danmu</chatserver><mission>0</mission><source>k-v</source>")
        append("<maxlimit>1000</maxlimit><state>0</state><real_id>0</real_id>")
        list.items.forEach { item ->
            // DanmakuItem 模式（0滚动/1顶部/4底部）→ B站模式（1-3滚动/4底部/5顶部）
            val mode = when (item.mode) {
                1 -> 5
                4 -> 4
                else -> 1
            }
            append("<d p=\"").append(item.time.toString()).append(',').append(mode)
                .append(",25,").append(item.color and 0xFFFFFF).append("\">")
            append(escapeXml(item.text))
            append("</d>")
        }
        append("</i>")
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
