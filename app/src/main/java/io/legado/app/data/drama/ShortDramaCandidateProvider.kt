package io.legado.app.data.drama

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.video.VideoCategory
import io.legado.app.data.video.VideoItem
import io.legado.app.data.video.VideoListRef
import io.legado.app.data.video.VideoSource
import io.legado.app.data.video.VideoSourceRegistry
import io.legado.app.data.video.expandFilterCombos
import io.legado.app.data.video.listWeight
import io.legado.app.help.source.exploreKindsJson
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    // 分类页去重后不足该阈值时，追加搜索补量（只维护 home 的 spider 常见）
    private const val VIDEO_SEARCH_FALLBACK_THRESHOLD = 30
    private val VIDEO_EXPAND_KEYWORDS = listOf("短剧", "热播", "爱情")
    private const val VIDEO_HOME_PAGE_FALLBACK_PAGES = 4
    /** 参与扩容的分类数（按名称优先级挑推荐/短剧/热门类 tab） */
    private const val VIDEO_CATEGORY_TARGETS = 4
    /** 每个分类额外拉取的页数；首页已覆盖第 1 页推荐，这里从第 1 页开始按分类取新内容 */
    private const val VIDEO_PAGES_PER_CATEGORY = 3
    /** 单源参与调度的列表入口上限（红果类源可展开 40+ 个，取权重最高的这些） */
    private const val VIDEO_LISTS_PER_SOURCE = 12
    /** 后台扩容总预算：到点就只用已拿到的条目（不影响已展示的信息流） */
    private const val VIDEO_EXPAND_TOTAL_BUDGET_MS = 40_000L
    /** 单个列表页的等待上限：红果类源走官网 SSR，冷启常超 6s */
    private const val VIDEO_EXPAND_PAGE_TIMEOUT_MS = 12_000L
    /** 同时请求的列表入口数：过高会拖慢单请求，过低则吃不满预算 */
    private const val VIDEO_EXPAND_CONCURRENCY = 3

    /** 源 key -> 首页分类（load 时填充，expand 复用，避免重复请求 home） */
    private val cachedCategories = java.util.concurrent.ConcurrentHashMap<String, List<VideoCategory>>()

    // 首页完整条目（未截断）：首屏只展示 20 条，剩余部分由 expand 直接补池，避免白丢数据
    private val cachedHomeItems = java.util.concurrent.ConcurrentHashMap<String, List<VideoItem>>()

    // 源 key -> 可召回列表入口（含子分类/榜单展开），expand 复用避免重复解析 home
    private val cachedLists =
        java.util.concurrent.ConcurrentHashMap<String, List<VideoListRef>>()

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

        // 首页只取 20 条保证首屏速度；后续分类翻页由 [expand] 在后台补池
        val categories = loaded.categories
        if (categories.isNotEmpty()) {
            cachedCategories[ref.key] = categories
            // 预热列表入口：home 已拿到带 filters 的分类，这里直接展开，expand 无需再请求 home
            val lists = buildListsFromCategories(categories)
            if (lists.isNotEmpty()) cachedLists[ref.key] = lists
        }
        AppLog.put(
            "ShortDramaProvider.loadVideoSource(${ref.key}): type=${loaded.source.sourceType}, " +
                "home=${loaded.items.size}, categories=${categories.size}, " +
                "cats=${categories.joinToString("/") { it.id + ":" + it.name }}"
        )
        if (loaded.items.isNotEmpty()) {
            cachedHomeItems[ref.key] = loaded.items
        }
        val categoryNames = categories.map { it.name }
        return loaded.items
            .take(VIDEO_PER_SOURCE)
            .distinctBy { it.stableKey() }
            .map { it.toCandidate(ref, categoryNames) }
    }

    /**
     * 后台补池：按分类翻页从同源拿首页之外的新条目。
     *
     * 与首屏加载解耦——首屏 20 条先出，滑到底时调用本函数把池子补到
     * [VIDEO_MAX_PER_SOURCE]；分类缺失时用空 tid 探页（多数 FongMi spider 的空 tid
     * 即「推荐/最新」分页入口），保证只有首页一页的源也能继续出内容。
     */
    suspend fun expand(
        sources: List<DramaSourceRef>,
        existing: Set<String>,
        limit: Int = 60,
    ): List<DramaCandidate> = withContext(Dispatchers.IO) {
        val refs = sources.filter {
            it.kind == DramaSourceKind.PY || it.kind == DramaSourceKind.TVBOX
        }
        if (refs.isEmpty()) return@withContext emptyList()
        val results = coroutineScope {
            refs.map { ref ->
                async {
                    runCatching { expandVideoSource(ref, existing) }
                        .onFailure {
                            LogUtils.d(
                                "ShortDramaProvider",
                                "expand(${ref.key}) failed: ${it.message}"
                            )
                        }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        val deduped = results.flatten()
            .filter { it.seriesId !in existing }
            .distinctBy { it.seriesId }
            .take(limit)
        // 汇总日志：一次扩容的整体结果，便于快速判断「到底补进了多少新内容」
        AppLog.put(
            "ShortDramaProvider.expand[汇总]: 源=${refs.size}, 原始=${results.sumOf { it.size }}, " +
                "新增=${deduped.size}, 上限=$limit"
        )
        deduped
    }

    private suspend fun expandVideoSource(
        ref: DramaSourceRef,
        existing: Set<String>,
    ): List<DramaCandidate> {
        val info = VideoSourceRegistry.getSourceInfo(ref.key) ?: return emptyList()
        val source = VideoSourceRegistry.createSource(info) ?: return emptyList()

        val items = ArrayList<VideoItem>()
        val startedAt = System.currentTimeMillis()

        // 0) 首页本身超出 20 条的剩余部分优先补入（首屏只截取了前 20 条）
        cachedHomeItems[ref.key]?.let { items.addAll(it) }

        // 1) 首页翻页：苹果 CMS 的 ac=list 支持 pg，是最稳的「首页 20 条之外」通道
        for (page in 2..VIDEO_HOME_PAGE_FALLBACK_PAGES) {
            if (items.size >= VIDEO_MAX_PER_SOURCE) break
            if (System.currentTimeMillis() - startedAt > VIDEO_EXPAND_TOTAL_BUDGET_MS) break
            val pageItems = withTimeoutOrNull(VIDEO_EXPAND_PAGE_TIMEOUT_MS) {
                runCatching { source.homePage(page) }
                    .onFailure {
                        AppLog.put(
                            "ShortDramaProvider.expand(${ref.key}) homePage pg=$page 失败: ${it.message}"
                        )
                    }
                    .getOrDefault(emptyList())
            }.orEmpty()
            AppLog.put(
                "ShortDramaProvider.expand(${ref.key}) homePage pg=$page -> ${pageItems.size}"
            )
            if (pageItems.isEmpty()) break
            items.addAll(pageItems)
        }

        // 2) 按列表入口召回：一个分类下的多个子分类/榜单各自是独立可翻页列表，
        //    按权重轮转分配配额，避免反复拉同一个「全部」列表。
        var lists = cachedLists[ref.key].orEmpty()
        if (lists.isEmpty()) {
            val resolved = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                runCatching { source.lists() }.getOrDefault(emptyList())
            }.orEmpty()
            if (resolved.isNotEmpty()) {
                cachedLists[ref.key] = resolved
                lists = resolved
            }
        }
        AppLog.put(
            "ShortDramaProvider.expand(${ref.key}) lists=${lists.size} " +
                lists.take(6).joinToString(",") { it.name }
        )

        if (lists.isNotEmpty()) {
            val scheduled = lists
                .sortedByDescending { it.weight }
                .take(VIDEO_LISTS_PER_SOURCE)
            // 每个入口拉第 1 页；并发调度避免红果这类慢源串行把预算吃光。
            // 第 1 页拿满池即可，不逐页追加（多入口并集通常已超池上限）。
            val semaphore = Semaphore(VIDEO_EXPAND_CONCURRENCY)
            val perListPages = coroutineScope {
                scheduled.map { list ->
                    async {
                        semaphore.withPermit {
                            fetchListPages(source, ref.key, list, startedAt, maxPages = 1)
                        }
                    }
                }.awaitAll()
            }
            scheduled.forEachIndexed { index, list ->
                val pages = perListPages.getOrElse(index) { emptyList() }
                if (pages.isNotEmpty()) {
                    items.addAll(pages)
                }
                AppLog.put(
                    "ShortDramaProvider.expand(${ref.key}) list=${list.name} -> ${pages.size} " +
                        "(pool=${items.size})"
                )
            }
        } else {
            // 源未提供列表入口（只有 home 的 spider）：退回首页分类兜底
            val categories = cachedCategories[ref.key]
                ?: runCatching {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) { source.home().first }
                }.getOrNull().orEmpty()
            if (categories.isNotEmpty()) cachedCategories[ref.key] = categories
            val targets = categories
                .filter { it.id.isNotBlank() }
                .sortedByDescending { categoryPriority(it.name) }
                .take(VIDEO_CATEGORY_TARGETS)
                .map { it.id }
                .ifEmpty { listOf("") }
            for (tid in targets) {
                for (page in 1..VIDEO_PAGES_PER_CATEGORY) {
                    if (items.size >= VIDEO_MAX_PER_SOURCE) break
                    if (System.currentTimeMillis() - startedAt > VIDEO_EXPAND_TOTAL_BUDGET_MS) break
                    val videos = withTimeoutOrNull(VIDEO_EXPAND_PAGE_TIMEOUT_MS) {
                        runCatching { source.category(tid, page).videos }
                            .onFailure {
                                AppLog.put(
                                    "ShortDramaProvider.expand(${ref.key}) category tid=$tid pg=$page 失败: ${it.message}"
                                )
                            }
                            .getOrDefault(emptyList())
                    }.orEmpty()
                    AppLog.put(
                        "ShortDramaProvider.expand(${ref.key}) category tid=$tid pg=$page -> ${videos.size}"
                    )
                    if (videos.isEmpty()) break
                    items.addAll(videos)
                }
            }
        }

        // 3) 仍然不够时用通用短剧词搜索拓宽来源
        if (items.map { it.stableKey() }.toSet().size < VIDEO_SEARCH_FALLBACK_THRESHOLD) {
            for (keyword in VIDEO_EXPAND_KEYWORDS) {
                if (items.size >= VIDEO_MAX_PER_SOURCE) break
                if (System.currentTimeMillis() - startedAt > VIDEO_EXPAND_TOTAL_BUDGET_MS) break
                val searched = withTimeoutOrNull(VIDEO_EXPAND_PAGE_TIMEOUT_MS) {
                    runCatching { source.search(keyword, 1) }
                        .onFailure {
                            AppLog.put(
                                "ShortDramaProvider.expand(${ref.key}) search kw=$keyword 失败: ${it.message}"
                            )
                        }
                        .getOrDefault(emptyList())
                }.orEmpty()
                AppLog.put(
                    "ShortDramaProvider.expand(${ref.key}) search kw=$keyword -> ${searched.size}"
                )
                if (searched.isEmpty()) continue
                items.addAll(searched)
            }
        }

        val fresh = items
            .distinctBy { it.stableKey() }
            .map { it.toCandidate(ref, emptyList()) }
            .filter { it.seriesId !in existing }
        AppLog.put(
            "ShortDramaProvider.expand(${ref.key}): raw=${items.size}, fresh=${fresh.size}, " +
                "lists=${lists.size}, existing=${existing.size}"
        )
        return fresh
    }

    private class LoadedVideoSource(
        val source: VideoSource,
        val categories: List<VideoCategory>,
        val items: List<VideoItem>,
    )

    /** 分类优先级：短剧/推荐类最相关，热门/最新次之，其余按源返回顺序。 */
    /**
     * 抓取单个列表入口的前若干页，返回累计条目。
     * 一页拉空即停；超出总预算或达到单源上限也停。
     */
    private suspend fun fetchListPages(
        source: VideoSource,
        refKey: String,
        list: VideoListRef,
        startedAt: Long,
        maxPages: Int,
    ): List<VideoItem> {
        val collected = ArrayList<VideoItem>()
        for (page in 1..maxPages) {
            if (System.currentTimeMillis() - startedAt > VIDEO_EXPAND_TOTAL_BUDGET_MS) break
            val videos = withTimeoutOrNull(VIDEO_EXPAND_PAGE_TIMEOUT_MS) {
                runCatching { source.category(list.tid, page, list.filters).videos }
                    .onFailure {
                        AppLog.put(
                            "ShortDramaProvider.expand($refKey) list=${list.name} " +
                                "tid=${list.tid} pg=$page 失败: ${it.message}"
                        )
                    }
                    .getOrDefault(emptyList())
            }.orEmpty()
            AppLog.put(
                "ShortDramaProvider.expand($refKey) list=${list.name} pg=$page -> ${videos.size}"
            )
            if (videos.isEmpty()) break
            collected.addAll(videos)
            if (collected.size >= VIDEO_MAX_PER_SOURCE) break
        }
        return collected
    }

    /**
     * 从首页分类表展开可召回列表入口。
     *
     * 与 [VideoSource.lists] 默认实现保持一致：每个分类的每个 filter 值视为独立列表，
     * 无 filter 的分类保留一条无参数入口。榜单（rank/new）通常把各 tab 放在 filters 里，
     * 展开后每个 tab 都是可翻页的独立列表。
     */
    private fun buildListsFromCategories(
        categories: List<VideoCategory>,
    ): List<VideoListRef> {
        return categories.flatMap { category ->
            val combos = expandFilterCombos(category.filters)
            if (combos.isEmpty()) {
                listOf(
                    VideoListRef(
                        tid = category.id,
                        filters = emptyMap(),
                        name = category.name,
                        weight = listWeight(category.name),
                    )
                )
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