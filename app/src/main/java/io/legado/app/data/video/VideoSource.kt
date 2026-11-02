package io.legado.app.data.video

/**
 * 统一视频源接口：支持 py / html / json / txt 四种源类型。
 *
 * 每种源实现 home/category/search/detail/play/danmaku 六个方法，
 * 返回统一数据模型，UI 层无需关心底层实现。
 */

/** 视频源类型 */
enum class SourceType {
    PY,
    HTML,
    JSON,
    TXT,
}

/** 视频条目（首页/分类/搜索列表中的单个视频） */
data class VideoItem(
    val id: String,
    val title: String,
    val url: String,
    val pic: String = "",
    val remarks: String = "",
    val sourceId: String = "",
)

/** 分类标签 */
data class VideoCategory(
    val id: String,
    val name: String,
)

/** 分类视频列表结果 */
data class CategoryResult(
    val page: Int = 1,
    val pageCount: Int = 1,
    val videos: List<VideoItem> = emptyList(),
)

/** 播放线路 */
data class PlayLine(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 视频详情 */
data class VideoDetail(
    val id: String,
    val title: String,
    val pic: String = "",
    val desc: String = "",
    val playLines: List<PlayLine> = emptyList(),
)

/** 弹幕条目 */
data class DanmakuItem(
    val time: Float,
    val text: String,
    val color: Int = 0xFFFFFFFF.toInt(),
    val mode: Int = 0, // 0=滚动, 1=顶部, 4=底部
)

/** 弹幕列表结果 */
data class DanmakuList(
    val items: List<DanmakuItem> = emptyList(),
)

/** 播放结果 */
data class PlayResult(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 视频源抽象接口。
 * 所有具体源（PySource, JsonSource, TxtSource）实现此接口。
 * HTML 源特殊处理，直接渲染 WebView，不实现完整接口。
 */
interface VideoSource {
    val sourceId: String
    val sourceName: String
    val sourceType: SourceType

    /** 首页：返回分类列表 + 推荐视频 */
    suspend fun home(): Pair<List<VideoCategory>, List<VideoItem>>

    /** 分类页：返回某分类的视频列表 */
    suspend fun category(tid: String, pg: Int): CategoryResult

    /** 搜索：返回匹配视频列表 */
    suspend fun search(keyword: String, pg: Int): List<VideoItem>

    /** 详情：返回视频详细信息 + 播放线路 */
    suspend fun detail(id: String): VideoDetail

    /** 播放：返回最终播放地址 */
    suspend fun play(url: String): PlayResult

    /** 弹幕：返回弹幕列表（可选，源不支持时返回空） */
    suspend fun danmaku(videoId: String): DanmakuList
}
