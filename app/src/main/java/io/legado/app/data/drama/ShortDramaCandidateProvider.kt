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

    private const val SOURCE_TIMEOUT_MS = 15_000L
    private const val RSS_PER_SOURCE = 10
    private const val VIDEO_PER_SOURCE = 20
    private const val BOOK_PER_SOURCE = 20

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