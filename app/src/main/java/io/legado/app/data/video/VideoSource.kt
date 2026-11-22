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
    JAR,
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
    val filters: List<VideoFilter> = emptyList(),
)

data class VideoFilter(val key: String, val name: String, val values: List<VideoFilterValue>)
data class VideoFilterValue(val name: String, val value: String)

/**
 * 可翻页的列表入口。
 *
 * 同一个分类下可能带多个子分类/榜单（如红果的 `real-drama` 下有 25 个 `cat`），
 * 它们在数据侧是相互独立、各自可分页的列表，信息流召回需要把它们当作独立通道，
 * 否则会反复拉取「全部」这一条列表而拿不到新内容。
 *
 * @param tid    category 接口的 tid（route）
 * @param filters 传给 category 的 extend，例如 `{"cat":"romance"}`
 * @param name   展示/日志用名称
 * @param weight 召回权重，越大越优先分配配额
 */
data class VideoListRef(
    val tid: String,
    val filters: Map<String, String> = emptyMap(),
    val name: String = "",
    val weight: Int = 0,
) {
    val key: String get() = if (filters.isEmpty()) tid
    else tid + "#" + filters.entries.joinToString(",") { "${it.key}=${it.value}" }
}

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
    val flag: String = "",
)

/** 视频详情 */
data class VideoDetail(
    val id: String,
    val title: String,
    val pic: String = "",
    val desc: String = "",
    val playLines: List<PlayLine> = emptyList(),
    val director: String = "",
    val actor: String = "",
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
    /**
     * FongMi Spider 协议 parse 字段（playerContent 返回的 "parse"）：
     * 0 = url 已是可直接播放的流媒体直链（含无扩展名的签名直链，如抖音 TOS）；1 = url 是需嗅探的网页。
     * -1 = 未声明（jar 未返回该字段），保持按 URL 形状判定的既有行为。
     */
    val parse: Int = -1,
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

    /**
     * 首页列表的翻页。部分源（如苹果 CMS 的 ac=list）本身支持 pg 分页，
     * 可取到首页 20 条之外的「最新」条目；源不支持时返回空。
     */
    suspend fun homePage(pg: Int): List<VideoItem> = emptyList()

    /**
     * 可召回列表入口集合：由 [home] 返回的分类与其 filters 展开。
     *
     * 默认实现把每个分类的每个 filter 值视为独立列表，缺省保留「全部」这一项；
     * 源若要自定义（例如榜单要整体当一个入口），可覆写。
     */
    suspend fun lists(): List<VideoListRef> {
        val categories = runCatching { home().first }.getOrDefault(emptyList())
        return categories.flatMap { category ->
            val combos = expandFilterCombos(category.filters)
            if (combos.isEmpty()) {
                listOf(VideoListRef(category.id, emptyMap(), category.name, listWeight(category.name)))
            } else {
                combos.map { (filters, label) ->
                    VideoListRef(
                        tid = category.id,
                        filters = filters,
                        name = if (label.isBlank()) category.name else "${category.name}·$label",
                        weight = listWeight(category.name, label),
                    )
                }
            }
        }.filter { it.tid.isNotBlank() }
    }

    /** 分类页：返回某分类的视频列表 */
    suspend fun category(tid: String, pg: Int): CategoryResult

    suspend fun category(tid: String, pg: Int, filters: Map<String, String>): CategoryResult = category(tid, pg)

    /** 搜索：返回匹配视频列表 */
    suspend fun search(keyword: String, pg: Int): List<VideoItem>

    /** 详情：返回视频详细信息 + 播放线路 */
    suspend fun detail(id: String): VideoDetail

    /** 播放：返回最终播放地址 */
    suspend fun play(url: String): PlayResult

    suspend fun play(url: String, flag: String): PlayResult = play(url)

    /** 弹幕：返回弹幕列表（可选，源不支持时返回空） */
    suspend fun danmaku(videoId: String): DanmakuList
}

/**
 * 把一个分类的 filters 展开成「filter 组合 + 展示标签」。
 *
 * 跳过 value 为空的选项（通常是「全部」，对应 `category(tid)` 的默认行为，
 * 已由无 filter 的入口覆盖），其余每个子分类/榜单各成一条独立列表。
 * 多个 filter 组时做笛卡尔积，但设上限防止组合爆炸。
 */
internal fun expandFilterCombos(filters: List<VideoFilter>): List<Pair<Map<String, String>, String>> {
    if (filters.isEmpty()) return emptyList()
    val groups = filters.mapNotNull { filter ->
        val values = filter.values.filter { it.value.isNotBlank() }
        if (values.isEmpty()) null else filter.key to values
    }
    if (groups.isEmpty()) return emptyList()
    var combos: List<Pair<Map<String, String>, List<String>>> =
        listOf(emptyMap<String, String>() to emptyList())
    for ((key, values) in groups) {
        val next = ArrayList<Pair<Map<String, String>, List<String>>>(combos.size * values.size)
        for ((baseFilters, baseLabels) in combos) {
            for (value in values) {
                if (next.size >= MAX_LIST_COMBOS) break
                next.add(
                    (baseFilters + (key to value.value)) to (baseLabels + value.name)
                )
            }
        }
        combos = next
        if (combos.size >= MAX_LIST_COMBOS) break
    }
    return combos.map { (filters, labels) -> filters to labels.joinToString("/") }
}

/** 单个分类最多展开的列表数，防止畸形 filters 拖垮召回。 */
internal const val MAX_LIST_COMBOS = 40

/**
 * 列表召回权重：榜单/热门/推荐类优先，短剧次之，其余兜底。
 * 同名分类下带具体子分类标签的略微加权，让垂类列表先被调度。
 */
internal fun listWeight(categoryName: String, filterLabel: String = ""): Int {
    val n = categoryName.trim()
    var weight = when {
        n.contains("排行") || n.contains("榜") -> 5
        n.contains("热门") || n.contains("精选") || n.contains("推荐") -> 4
        n.contains("最新") || n.contains("新剧") -> 4
        n.contains("短剧") -> 3
        n.contains("全部") -> 1
        else -> 2
    }
    if (filterLabel.isNotBlank()) weight += 1
    return weight
}
