package com.lanyue.reader.source.model

import com.lanyue.reader.core.engine.RateLimit

/**
 * 1.4.6 四类源统一接口。
 * 本文件是源体系的唯一契约入口，任何源实现都必须满足这里的形状。
 */

enum class SourceType { BOOK, SUBSCRIPTION, VIDEO, DANMAKU }

/** 1.4.2 能力判定 + 4/5 章扩展能力位。 */
enum class SourceCapability {
    SEARCHABLE,
    DISCOVERABLE,
    HAS_CONTENT,
    RSS_FALLBACK,
    FILTERABLE,
    REAL_DOM,
    BROWSER_FP,
    SNIFF,
    HIGH_PRIORITY
}

interface ISource {
    val sourceId: String
    val sourceType: SourceType
    val capabilityFlags: Set<SourceCapability>
    val concurrentRate: RateLimit?
}

/** 1.4.6 排序/分类规则，书源与订阅源共用形状。 */
data class SortRule(val name: String, val url: String)

data class SearchBookItem(
    val name: String,
    val author: String? = null,
    val bookUrl: String,
    val cover: String? = null,
    val intro: String? = null,
    val latestChapter: String? = null,
    val origin: String,
    val originName: String? = null
)

data class BookDetail(
    val name: String,
    val author: String? = null,
    val cover: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val lastChapter: String? = null,
    val wordCount: String? = null,
    val bookUrl: String
)

data class TocItem(
    val title: String,
    val url: String,
    val index: Int = 0
)

/**
 * 1.4.7 音频书源正文结果。
 * bookSourceType=1 时 ruleContent 返回纯 URL 或含 url 字段的 JSON，
 * SourceRouter 识别后直接注入 Media3 AudioPlay，不做 HTML 解析。
 */
sealed class ContentResult {
    /** 普通 HTML 正文。 */
    data class Html(val html: String) : ContentResult()

    /** 音频直链 + 可选请求头（防盗链）。 */
    data class Audio(val url: String, val headers: Map<String, String> = emptyMap()) : ContentResult()

    /** 无 ruleContent 的订阅源正文：交给 WebView 原样打开。 */
    data class WebViewFallback(val url: String) : ContentResult()
}

interface IBookSource : ISource {
    suspend fun search(key: String, page: Int): List<SearchBookItem>
    suspend fun explore(sort: SortRule, page: Int): List<SearchBookItem>
    suspend fun getDetail(url: String): BookDetail
    suspend fun getToc(url: String): List<TocItem>
    /** 返回 HTML（1.4.6 契约）；音频源返回 Audio。 */
    suspend fun getContent(url: String): ContentResult
}

/** 1.4.1 type 0 网页 / 1 图片 / 2 视频。 */
enum class RssContentType { WEB, IMAGE, VIDEO }

data class SubscriptionItem(
    val title: String,
    val url: String,
    val cover: String? = null,
    val pubDate: Long? = null,
    val description: String? = null,
    val sort: String? = null,
    val origin: String,
    val contentType: RssContentType = RssContentType.WEB
)

/** 1.4.6 / ADR-012：独立接口，无 getToc。 */
interface ISubscriptionSource : ISource {
    suspend fun getSorts(): List<SortRule>
    suspend fun getArticles(sort: SortRule?, page: Int): List<SubscriptionItem>
    suspend fun search(key: String, page: Int): List<SubscriptionItem>
    /** 返回 HTML；无 ruleContent 时走 WebView。 */
    suspend fun getContent(url: String): ContentResult
}

// ---------- 影视 ----------

data class Category(val name: String, val id: String)

/** 7.3 CMS 筛选。ext.filter 结构。 */
data class Filter(val key: String, val name: String, val values: List<FilterValue>)
data class FilterValue(val name: String, val value: String)

data class VideoItem(
    val name: String,
    val url: String,
    val cover: String? = null,
    val remark: String? = null,
    val siteKey: String,
    val configUrl: String
)

data class VideoEpisode(val name: String, val url: String)

/** 影视线路（>5 条折叠，见 3.5）。 */
data class VideoPlayLine(val name: String, val episodes: List<VideoEpisode>)

data class VideoDetail(
    val name: String,
    val cover: String? = null,
    val intro: String? = null,
    val actor: String? = null,
    val director: String? = null,
    val year: String? = null,
    val area: String? = null,
    val remark: String? = null,
    val lines: List<VideoPlayLine> = emptyList()
)

/** 7.3 播放解析链结果类型。 */
enum class PlayKind { DIRECT, PARSE, SNIFF }

data class DanmakuHint(
    val title: String,
    val episode: Int?,
    val fileName: String? = null,
    val fileHash: String? = null,
    val fileSize: Long? = null,
    val durationMs: Long? = null
)

data class PlayResult(
    val kind: PlayKind,
    val url: String,
    val headers: Map<String, String>? = null,
    val flag: String? = null,
    val danmakuHint: DanmakuHint? = null
)

interface IVideoSource : ISource {
    suspend fun getCategories(): List<Category>
    suspend fun explore(category: Category, filter: Filter?, page: Int): List<VideoItem>
    suspend fun search(key: String, page: Int): List<VideoItem>
    suspend fun getDetail(url: String): VideoDetail
    suspend fun getPlay(episode: VideoEpisode): PlayResult
}

// ---------- 弹幕 ----------

data class DanmakuMatchRequest(
    val title: String,
    val episode: Int?,
    val fileName: String? = null,
    val fileHash: String? = null,
    val fileSize: Long? = null,
    val durationMs: Long? = null
)

data class DanmakuMatch(
    val episodeId: Long,
    val animeTitle: String,
    val episodeTitle: String,
    val shift: Long = 0,
    val sourceId: String = ""
)

/** 4.10.8 弹幕事件。mode: 1 滚动 / 2 底部 / 3 顶部。 */
data class DanmakuEvent(
    val timeMs: Long,
    val mode: Int,
    val color: Int,
    val text: String,
    val source: String? = null
)

interface IDanmakuSource : ISource {
    suspend fun match(req: DanmakuMatchRequest): DanmakuMatch?
    suspend fun search(key: String): List<DanmakuMatch>
    suspend fun getComments(match: DanmakuMatch, fromMs: Long, durationMs: Long): List<DanmakuEvent>
}
