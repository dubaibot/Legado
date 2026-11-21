package io.legado.app.data.drama

/**
 * 短剧源类别。
 *
 * 短剧不引入新的源格式，直接复用项目已导入的四类源：
 * 书源（视频类）、订阅源、PY 源、TVBOX 源。
 */
enum class DramaSourceKind(val label: String) {
    BOOK("书源"),
    RSS("订阅源"),
    PY("PY源"),
    TVBOX("TVBOX源"),
}

/** 源的稳定标识：kind + 源 key（书源用 bookSourceUrl，订阅源用 sourceUrl，视频源用注册表 id）。 */
data class DramaSourceRef(
    val kind: DramaSourceKind,
    val key: String,
    val name: String,
) {
    val id: String get() = "${kind.name}:$key"
}

/**
 * 归一化后的短剧候选。
 *
 * 只描述「从哪个源的哪个条目进入」以及可选的播放线索（pageUrl / 请求头）。
 * 真正播放时由 [ShortDramaPlaybackResolver] 解析出流媒体地址，复用既有嗅探链路。
 */
data class DramaCandidate(
    val seriesId: String,
    val source: DramaSourceRef,
    val title: String,
    /** 书源条目作者，用于详情页定位 */
    val author: String = "",
    val cover: String = "",
    val tags: List<String> = emptyList(),
    /** 视频源条目标识：detail/play 需要 */
    val itemId: String = "",
    /** 可直接打开的页面或直链（订阅源文章、书源章节视频页） */
    val pageUrl: String = "",
    /** 视频源播放线路名 */
    val flag: String = "",
    val episodeCount: Int = 1,
    /** 发布时间（毫秒），用于新鲜度；0 表示未知 */
    val publishedAt: Long = 0L,
    /** 剧简介（description） */
    val intro: String = "",
    /** 当前集标题，如"第32集" */
    val chapterTitle: String = "",
)

/** 单集信息（选集弹窗 / 下一集 / 续播用）。 */
data class DramaEpisode(
    val index: Int,
    val title: String,
    val url: String,
    /** 视频源播放线路名（PY/TVBOX 源 play(url, flag) 需要；书源为空） */
    val flag: String = "",
    /** 所属线路名，供选集弹窗按线路分组/切换；无多线路时为空。 */
    val route: String = "",
)

/** 某部剧的整季选集列表。 */
data class DramaSeriesPlaylist(
    val seriesId: String,
    val episodes: List<DramaEpisode>,
    val currentIndex: Int,
)

/** 解析完成的可播放信息。 */
data class DramaPlayable(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String = "",
)

/** 一批候选及其补充标记。 */
data class DramaBatch(
    val candidates: List<DramaCandidate>,
    val supplement: Boolean = false,
)