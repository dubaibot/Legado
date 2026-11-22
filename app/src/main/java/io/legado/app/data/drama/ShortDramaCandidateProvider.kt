package io.legado.app.data.drama

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.video.SourceType
import io.legado.app.data.video.VideoCategory
import io.legado.app.data.video.VideoItem
import io.legado.app.data.video.VideoSource
import io.legado.app.data.video.VideoSourceRegistry
import io.legado.app.help.source.exploreKindsJson
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 短剧候选提供者：把已勾选的四类源统一产出 [DramaCandidate]。
 *
 * - PY / TVBOX 源：走 [VideoSourceRegistry] 的 `home()`，取首页推荐条目
 * - 订阅源：取本地已缓存的最新文章（不触发网络刷新）
 * - 书源（视频类）：取发现入口第一页
 *
 * 单个源失败只记录日志并跳过，整体批次仍可返回；每类源都设置超时，避免卡住信息流。
 */
object ShortDramaCandidateProvider {

    // 单源拉取超时：过长会让“正在准备短剧”卡住，9s 足够 home()/explore 首屏返回
    private const val SOURCE_TIMEOUT_MS = 9_000L
    private const val RSS_PER_SOURCE = 10
    private const val VIDEO_PER_SOURCE = 20
    private const val BOOK_PER_SOURCE = 20

    /**
     * 单源扩容上限：py 源首页通常只给 20 条，滑到底就没内容了。
     * 首页 20 条之外，再按分类翻页补足到 60 条，兼顾首屏速度与信息流长度。
     */
    private const val VIDEO_MAX_PER_SOURCE = 60
    /** 参与扩容的分类数（按名称优先级挑推荐/短剧/热门类 tab） */
    private const val VIDEO_CATEGORY_TARGETS = 4
    /** 每个分类额外拉取的页数；首页已覆盖第 1 页推荐，这里从第 1 页开始按分类取新内容 */
    private const val VIDEO_PAGES_PER_CATEGORY = 3
    /** 扩容阶段总预算：到点就只用已拿到的条目，不让慢分类拖垮首屏 */
    private const val VIDEO_EXPAND_BUDGET_MS = 5_000L
    /** 单个分类页的等待上限 */
    private const val VIDEO_PAGE_TIMEOUT_MS = 3_500L

    suspend fun load(
        sources: List<DramaSourceRef>,
        limit: Int = 60,
    ): List<DramaCandidate> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()
        val results = coroutineScope {
            sources
                .flatMap { ref ->
                    val loaders = when (ref.kind) {
                        DramaSourceKind.PY, DramaSourceKind.TVBOX -> listOf(async { loadVideoSource(ref) })
                        DramaSourceKind.RSS -> listOf(async { loadRssSource(ref) })
                        DramaSourceKind.BOOK -> listOf(async { loadBookSource(ref) })
                    }
                    loaders
                }
                .awaitAll()
        }
        results.flatten().distinctBy { it.seriesId }.take(limit)
    }

    /** 关键字搜索：并行搜索所有已选源，按 seriesId 去重后截断。 */
    suspend fun search(
        keyword: String,
        sources: List<DramaSourceRef>,
        limit: Int = 60,
    ): List<DramaCandidate> = withContext(Dispatchers.IO) {
        val key = keyword.trim()
        if (key.isBlank() || sources.isEmpty()) return@withContext emptyList()
        val results = coroutineScope {
            sources.flatMap { ref ->
                when (ref.kind) {
                    DramaSourceKind.PY, DramaSourceKind.TVBOX -> listOf(async { searchVideoSource(ref, key) })
                    DramaSourceKind.BOOK -> listOf(async { searchBookSource(ref, key) })
                    DramaSourceKind.RSS -> listOf(async { searchRssSource(ref, key) })
                }
            }.awaitAll()
        }
        results.flatten().distinctBy { it.seriesId }.take(limit)
    }

    private suspend fun searchVideoSource(ref: DramaSourceRef, key: String): List<DramaCandidate> {
        return runCatching {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                val info = VideoSourceRegistry.getSourceInfo(ref.key) ?: return@withTimeoutOrNull emptyList()
                val source = VideoSourceRegistry.createSource(info) ?: return@withTimeoutOrNull emptyList()
                source.search(key, 1).take(VIDEO_PER_SOURCE).map { it.toCandidate(ref, emptyList()) }
            } ?: emptyList()
        }.onFailure {
            LogUtils.d("ShortDramaProvider", "searchVideoSource(${ref.key}) failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private suspend fun searchBookSource(ref: DramaSourceRef, key: String): List<DramaCandidate> {
        return runCatching {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                val source = appDb.bookSourceDao.getBookSource(ref.key) ?: return@withTimeoutOrNull emptyList()
                if (source.bookSourceType != BookSourceType.video) return@withTimeoutOrNull emptyList()
                WebBook.searchBookAwait(source, key, 1)
                    .take(BOOK_PER_SOURCE)
                    .map { book ->
                        DramaCandidate(
                            seriesId = "book:${ref.key}:${book.bookUrl}",
                            source = ref,
                            title = book.name,
                            author = book.author,
                            cover = book.coverUrl.orEmpty(),
                            intro = book.intro.orEmpty(),
                            tags = book.kind?.split(',', '，', '/')
                                ?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }
                                ?.take(4) ?: emptyList(),
                            pageUrl = book.bookUrl,
                            publishedAt = 0L,
                        )
                    }
            } ?: emptyList()
        }.onFailure {
            LogUtils.d("ShortDramaProvider", "searchBookSource(${ref.key}) failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private suspend fun searchRssSource(ref: DramaSourceRef, key: String): List<DramaCandidate> {
        return runCatching {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                appDb.rssArticleDao.getLatestByOrigin(ref.key, 200)
                    .filter { it.title.contains(key, ignoreCase = true) }
                    .take(RSS_PER_SOURCE)
                    .map { it.toCandidate(ref) }
            } ?: emptyList()
        }.onFailure {
            LogUtils.d("ShortDramaProvider", "searchRssSource(${ref.key}) failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private suspend fun loadVideoSource(ref: DramaSourceRef): List<DramaCandidate> {
        val loaded = runCatching {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                val info = VideoSourceRegistry.getSourceInfo(ref.key) ?: return@withTimeoutOrNull null
                val source = VideoSourceRegistry.createSource(info) ?: return@withTimeoutOrNull null
                val (categories, items) = source.home()
                LoadedVideoSource(source, categories, items)
            }
        }.onFailure {
            LogUtils.d("ShortDramaProvider", "loadVideoSource(${ref.key}) failed: ${it.message}")
        }.getOrNull() ?: return emptyList()

        val merged = LinkedHashMap<String, VideoItem>()
        loaded.items.take(VIDEO_PER_SOURCE).forEach { merged[it.stableKey()] = it }
        // 分类翻页扩容：PY 与 JSON（TVBox 展开的 py/json 站点）都支持 category(tid,pg)，
        // 首页通常只有 20 条，不扩容信息流滑到底就没内容了。TXT/HTML/JAR 保持单页避免无谓请求。
        val expandable = loaded.source.sourceType == SourceType.PY ||
            loaded.source.sourceType == SourceType.JSON
        AppLog.put(
            "ShortDramaProvider.loadVideoSource(${ref.key}): type=${loaded.source.sourceType}, " +
                "base=${merged.size}, categories=${loaded.categories.size}, expandable=$expandable"
        )
        if (expandable && merged.size < VIDEO_MAX_PER_SOURCE) {
            val expanded = expandSource(loaded.source, loaded.categories)
            expanded.forEach { merged.putIfAbsent(it.stableKey(), it) }
            AppLog.put(
                "ShortDramaProvider.expand(${ref.key}): expanded=${expanded.size}, " +
                    "merged=${merged.size}, cats=${loaded.categories.joinToString("/") { it.id + ":" + it.name }}"
            )
        }
        val categoryNames = loaded.categories.map { it.name }
        return merged.values
            .take(VIDEO_MAX_PER_SOURCE)
            .map { it.toCandidate(ref, categoryNames) }
    }

    private class LoadedVideoSource(
        val source: VideoSource,
        val categories: List<VideoCategory>,
        val items: List<VideoItem>,
    )

    /**
     * 分类扩容：按「短剧/推荐/热门/最新」优先挑几个分类，各翻 1~2 页补足首页之外的条目。
     *
     * 总预算 [VIDEO_EXPAND_BUDGET_MS] 内能拿多少算多少；单页失败或超时直接跳过，
     * 不影响已经拿到的首页内容。py 源由 PyTaskQueue 串行执行，这里按分类顺序请求。
     */
    private suspend fun expandSource(
        source: VideoSource,
        categories: List<VideoCategory>,
    ): List<VideoItem> {
        val targets = categories
            .filter { it.id.isNotBlank() }
            .sortedByDescending { categoryPriority(it.name) }
            .take(VIDEO_CATEGORY_TARGETS)
        if (targets.isEmpty()) {
            AppLog.put("ShortDramaProvider.expandSource: 分类为空，跳过扩容, type=${source.sourceType}")
            return emptyList()
        }

        val result = ArrayList<VideoItem>()
        withTimeoutOrNull(VIDEO_EXPAND_BUDGET_MS) {
            for (category in targets) {
                for (page in 1..VIDEO_PAGES_PER_CATEGORY) {
                    if (result.size >= VIDEO_MAX_PER_SOURCE) break
                    val videos = withTimeoutOrNull(VIDEO_PAGE_TIMEOUT_MS) {
                        runCatching { source.category(category.id, page).videos }
                            .onFailure {
                                LogUtils.d(
                                    "ShortDramaProvider",
                                    "expand ${category.id}/${page} failed: ${it.message}"
                                )
                            }
                            .getOrDefault(emptyList())
                    }.orEmpty()
                    LogUtils.d(
                        "ShortDramaProvider",
                        "expand ${category.name}(${category.id}) page=$page -> ${videos.size}"
                    )
                    // 该分类没有更多页，换下一个分类
                    if (videos.isEmpty()) break
                    result.addAll(videos)
                }
            }
        }
        return result
    }

    /** 分类优先级：短剧/推荐类最相关，热门/最新次之，其余按源返回顺序。 */
    private fun categoryPriority(name: String): Int {
        val n = name.trim()
        return when {
            n.contains("短剧") || n.contains("推荐") -> 3
            n.contains("热门") || n.contains("精选") || n.contains("最新") -> 2
            n.contains("全部") -> 1
            else -> 0
        }
    }

    /** 分类间去重键：优先条目 id，缺失时退回 url。 */
    private fun VideoItem.stableKey(): String = if (id.isNotBlank()) id else url

    private fun VideoItem.toCandidate(
        ref: DramaSourceRef,
        categoryNames: List<String>,
    ): DramaCandidate {
        val remarkTags = remarks.split(' ', '/', '·', '，', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 8 }
        return DramaCandidate(
            seriesId = "video:${ref.key}:${if (id.isNotBlank()) id else url}",
            source = ref,
            title = title,
            cover = pic,
            tags = (remarkTags + listOfNotNull(ref.name.takeIf { it.isNotBlank() })).distinct().take(4),
            itemId = id,
            pageUrl = url,
            episodeCount = episodeCountFromRemarks(remarks),
        )
    }

    private fun episodeCountFromRemarks(remarks: String): Int {
        val regex = Regex("(\\d+)\\s*[集话]")
        return regex.find(remarks)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    private suspend fun loadRssSource(ref: DramaSourceRef): List<DramaCandidate> {
        return runCatching {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                appDb.rssArticleDao.getLatestByOrigin(ref.key, RSS_PER_SOURCE)
                    .map { it.toCandidate(ref) }
            } ?: emptyList()
        }.onFailure {
            LogUtils.d("ShortDramaProvider", "loadRssSource(${ref.key}) failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun RssArticle.toCandidate(ref: DramaSourceRef): DramaCandidate {
        val tags = listOfNotNull(
            group.takeIf { it.isNotBlank() },
            ref.name.takeIf { it.isNotBlank() },
        )
        return DramaCandidate(
            seriesId = "rss:${ref.key}:$link",
            source = ref,
            title = title,
            cover = image.orEmpty(),
            tags = tags.distinct(),
            pageUrl = link,
            publishedAt = order.takeIf { it > 0 } ?: 0L,
        )
    }

    private suspend fun loadBookSource(ref: DramaSourceRef): List<DramaCandidate> {
        return runCatching {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                val source = appDb.bookSourceDao.getBookSource(ref.key) ?: return@withTimeoutOrNull emptyList()
                if (source.bookSourceType != BookSourceType.video) return@withTimeoutOrNull emptyList()
                val exploreUrl = firstExploreUrl(source) ?: return@withTimeoutOrNull emptyList()
                WebBook.exploreBookAwait(source, exploreUrl, 1)
                    .take(BOOK_PER_SOURCE)
                    .map { book ->
                        DramaCandidate(
                            seriesId = "book:${ref.key}:${book.bookUrl}",
                            source = ref,
                            title = book.name,
                            author = book.author,
                            cover = book.coverUrl.orEmpty(),
                            intro = book.intro.orEmpty(),
                            tags = book.kind?.split(',', '，', '/')
                                ?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }
                                ?.take(4) ?: emptyList(),
                            pageUrl = book.bookUrl,
                            publishedAt = 0L,
                        )
                    }
            } ?: emptyList()
        }.onFailure {
            LogUtils.d("ShortDramaProvider", "loadBookSource(${ref.key}) failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun firstExploreUrl(source: BookSource): String? {
        val json = source.exploreKindsJson()
        if (json.isBlank()) return null
        val kinds = GSON.fromJsonArray<ExploreKind>(json).getOrNull() ?: return null
        val url = kinds.firstOrNull {
            it.type == ExploreKind.Type.url && !it.url.isNullOrBlank()
        }?.url ?: return null
        return url.replace("{{page}}", "1")
    }
}