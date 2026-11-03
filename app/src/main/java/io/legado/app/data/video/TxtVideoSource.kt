package io.legado.app.data.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TXT 视频源：解析静态文本文件为视频列表。
 *
 * 文件格式：
 * ```
 * # 站点名称
 * [电影]
 * 标题1|播放URL|封面URL
 * 标题2|播放URL|封面URL
 *
 * [电视剧]
 * 剧名|第1集$URL1#第2集$URL2|封面URL
 * ```
 *
 * - 以 [ 开头的行是分类名，后续行属于该分类
 * - 视频行格式：标题|URL|封面（封面可省略）
 * - URL 含 $ 时为多集（集名$地址，# 分隔），同苹果CMS 约定
 * - 第一个 [ 前的 # 注释行可作为站点名
 *
 * 注意：FongMi 逐行格式（名称$接口地址）由 VideoSourceRegistry 处理，
 * 每行创建一个 CMS JsonVideoSource。本类仅处理静态列表格式。
 */
class TxtVideoSource(
    override val sourceId: String,
    private val content: String,
) : VideoSource {

    override val sourceType = SourceType.TXT

    override val sourceName: String
    private val categories = mutableListOf<VideoCategory>()
    private val videosByCategory = linkedMapOf<String, MutableList<VideoItem>>()

    init {
        var name = sourceId
        var currentCat = ""
        var catOrder = 0
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> return@forEach
                line.startsWith("#") -> {
                    if (name == sourceId && categories.isEmpty()) {
                        name = line.removePrefix("#").trim().ifBlank { sourceId }
                    }
                    return@forEach
                }
                line.startsWith("[") && line.endsWith("]") -> {
                    currentCat = line.substring(1, line.length - 1).trim()
                    if (currentCat.isNotBlank()) {
                        categories.add(VideoCategory(id = catOrder.toString(), name = currentCat))
                        videosByCategory.getOrPut(currentCat) { mutableListOf() }
                        catOrder++
                    }
                    return@forEach
                }
                currentCat.isNotBlank() -> {
                    val parts = line.split("|")
                    if (parts.size >= 2) {
                        val title = parts[0].trim()
                        val url = parts[1].trim()
                        val pic = parts.getOrNull(2)?.trim() ?: ""
                        if (title.isNotBlank() && url.isNotBlank()) {
                            videosByCategory.getOrPut(currentCat) { mutableListOf() }.add(
                                VideoItem(
                                    id = "${currentCat}_$title",
                                    title = title,
                                    url = url,
                                    pic = pic,
                                    sourceId = sourceId,
                                )
                            )
                        }
                    }
                }
            }
        }
        sourceName = name
    }

    override suspend fun home(): Pair<List<VideoCategory>, List<VideoItem>> =
        withContext(Dispatchers.IO) {
            val rec = videosByCategory.values.firstOrNull()?.take(20) ?: emptyList()
            Pair(categories.toList(), rec)
        }

    override suspend fun category(tid: String, pg: Int): CategoryResult =
        withContext(Dispatchers.IO) {
            val catName = categories.find { it.id == tid }?.name ?: tid
            val videos = videosByCategory[catName] ?: emptyList()
            // 静态列表一次返回全部，第 1 页返回全部数据
            if (pg <= 1) {
                CategoryResult(page = 1, pageCount = 1, videos = videos)
            } else {
                CategoryResult(page = pg, pageCount = 1, videos = emptyList())
            }
        }

    override suspend fun search(keyword: String, pg: Int): List<VideoItem> =
        withContext(Dispatchers.IO) {
            if (pg > 1) return@withContext emptyList()
            videosByCategory.values.flatten()
                .filter { it.title.contains(keyword, ignoreCase = true) }
        }

    override suspend fun detail(id: String): VideoDetail = withContext(Dispatchers.IO) {
        val video = videosByCategory.values.flatten().find { it.id == id }
            ?: return@withContext VideoDetail(id = id, title = id)
        val lines = parseEpisodeUrls(video.url)
        VideoDetail(
            id = video.id,
            title = video.title,
            pic = video.pic,
            playLines = lines,
        )
    }

    override suspend fun play(url: String): PlayResult = withContext(Dispatchers.IO) {
        PlayResult(url)
    }

    override suspend fun danmaku(videoId: String): DanmakuList =
        withContext(Dispatchers.IO) { DanmakuList() }

    /** 解析 "集名$URL#集名$URL" 多集格式，单 URL 视频返回单线路 */
    private fun parseEpisodeUrls(url: String): List<PlayLine> {
        if (!url.contains("$")) {
            return listOf(PlayLine(name = "默认线路", url = url))
        }
        val lines = mutableListOf<PlayLine>()
        url.split("#").filter { it.isNotBlank() }.forEachIndexed { idx, ep ->
            val parts = ep.split("$")
            val name = if (parts.size >= 2) parts[0].trim() else "第${idx + 1}集"
            val epUrl = parts.last().trim()
            if (epUrl.isNotBlank()) {
                lines.add(PlayLine(name = name, url = epUrl))
            }
        }
        return lines
    }
}
