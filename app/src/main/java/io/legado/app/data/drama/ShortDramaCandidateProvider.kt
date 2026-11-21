package io.legado.app.data.drama

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.video.VideoItem
import io.legado.app.data.video.VideoSourceRegistry
import io.legado.app.help.source.exploreKindsJson
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    // 单源拉取超时：过长会让"正在准备短剧"卡住，9s 足够 home()/explore 首屏返回
    private const val SOURCE_TIMEOUT_MS = 9_000L
    // 首屏软截止：快的源先返回，慢源不再阻塞首屏（结果通过 onLate 回调补充）
    private const val FIRST_BATCH_DEADLINE_MS = 2_500L
    // 首屏最少拿到的源数：即使超时也至少等这些源，避免首屏内容过少
    private const val FIRST_BATCH_MIN_SOURCES = 2
    private const val RSS_PER_SOURCE = 10
    private const val VIDEO_PER_SOURCE = 20
    private const val BOOK_PER_SOURCE = 20

    /**
     * 渐进式加载候选池。
     *
     * 旧的 `awaitAll()` 会等最慢的源（最长 9s）才返回，导致"正在准备短剧"卡住。
     * 这里改为：所有源并发拉取，快源先汇入首屏；到 [FIRST_BATCH_DEADLINE_MS] 软截止时，
     * 只要已拿到不少于 [FIRST_BATCH_MIN_SOURCES] 个源的结果就先返回首屏，
     * 仍在拉取的慢源结果随后通过 [onLate] 回调追加，由 ViewModel 决定是否补进信息流。
     *
     * @param onLate 慢源结果的追加回调（主线程无关，内部自行切协程）。传 null 时退化为"等全部"。
     */
    suspend fun load(
        sources: List<DramaSourceRef>,
        limit: Int = 60,
        onLate: (suspend (List<DramaCandidate>) -> Unit)? = null,
    ): List<DramaCandidate> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()
        // 未启用渐进（无回调）时保持原行为，等全部源完成，避免调用方漏数据
        if (onLate == null) {
            val results = coroutineScope {
                sources.map { ref ->
                    async { loadOne(ref) }
                }.awaitAll()
            }
            return@withContext results.flatten().distinctBy { it.seriesId }.take(limit)
        }

        val total = sources.size
        val channel = Channel<Pair<String, List<DramaCandidate>>>(Channel.UNLIMITED)
        // 独立 supervisor 作用域：首屏返回后慢源协程继续跑完，不受本次 withContext 取消影响
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sources.forEach { ref ->
            workerScope.launch {
                val items = runCatching { loadOne(ref) }.getOrDefault(emptyList())
                channel.send(ref.id to items)
            }
        }

        val head = LinkedHashMap<String, List<DramaCandidate>>()
        val deadline = System.currentTimeMillis() + FIRST_BATCH_DEADLINE_MS
        var received = 0
        while (received < total) {
            val remainMs = deadline - System.currentTimeMillis()
            // 已达到最少源数且过了软截止 → 先返回首屏
            if (head.size >= FIRST_BATCH_MIN_SOURCES && remainMs <= 0) break
            val chunk = if (remainMs > 0) {
                withTimeoutOrNull(remainMs) { channel.receive() }
            } else {
                // 已超时但首屏源数不足，仍阻塞等一个，保证首屏不空
                channel.receive()
            } ?: break
            head[chunk.first] = chunk.second
            received++
        }

        // 剩余慢源：开一个协程持续接收并通过回调追加，之后关闭 channel 与 scope
        if (received < total) {
            workerScope.launch {
                var left = total - received
                while (left > 0) {
                    val chunk = runCatching { channel.receive() }.getOrNull() ?: break
                    runCatching { onLate(chunk.second) }
                    left--
                }
                channel.close()
            }
        } else {
            channel.close()
        }

        head.values.flatten().distinctBy { it.seriesId }.take(limit)
    }

    /** 单个源的候选加载分派。 */
    private suspend fun loadOne(ref: DramaSourceRef): List<DramaCandidate> = when (ref.kind) {
        DramaSourceKind.PY, DramaSourceKind.TVBOX -> loadVideoSource(ref)
        DramaSourceKind.RSS -> loadRssSource(ref)
        DramaSourceKind.BOOK -> loadBookSource(ref)
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
        return runCatching {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                val info = VideoSourceRegistry.getSourceInfo(ref.key) ?: return@withTimeoutOrNull emptyList()
                val source = VideoSourceRegistry.createSource(info) ?: return@withTimeoutOrNull emptyList()
                val (categories, items) = source.home()
                val categoryNames = categories.map { it.name }
                items.take(VIDEO_PER_SOURCE).map { item ->
                    item.toCandidate(ref, categoryNames)
                }
            } ?: emptyList()
        }.onFailure {
            LogUtils.d("ShortDramaProvider", "loadVideoSource(${ref.key}) failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

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