package io.legado.app.data.drama

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.video.VideoSourceRegistry
import io.legado.app.help.video.VideoUrlExtractor
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 短剧播放解析：把候选解析成「可直接交给播放器的媒体地址」。
 *
 * - 视频源：`detail(itemId)` 取第一条线路，`play(url, flag)` 取最终地址
 * - 订阅源：抓文章页 HTML 抽取视频地址，失败再走三层嗅探
 * - 书源：取详情/目录首个章节，正文抽取或嗅探出媒体地址
 *
 * 页内播放要求拿到真正的媒体地址，详情页/文章页地址交给 ExoPlayer 会直接报错。
 */
object ShortDramaPlaybackResolver {

    private const val RESOLVE_TIMEOUT_MS = 10_000L
    private const val RESOLVE_PAGE_TIMEOUT_MS = 25_000L

    suspend fun resolve(candidate: DramaCandidate): DramaPlayable? = withContext(Dispatchers.IO) {
        when (candidate.source.kind) {
            DramaSourceKind.PY, DramaSourceKind.TVBOX -> resolveVideo(candidate)
            DramaSourceKind.RSS -> resolveRss(candidate)
            DramaSourceKind.BOOK -> resolveBook(candidate)
        }
    }

    private suspend fun resolveVideo(candidate: DramaCandidate): DramaPlayable? {
        return runCatching {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                val info = VideoSourceRegistry.getSourceInfo(candidate.source.key)
                    ?: return@withTimeoutOrNull null
                val source = VideoSourceRegistry.createSource(info)
                    ?: return@withTimeoutOrNull null
                val detail = source.detail(candidate.itemId.ifBlank { candidate.pageUrl })
                val line = detail.playLines.firstOrNull() ?: return@withTimeoutOrNull null
                val result = runCatching { source.play(line.url, line.flag) }
                    .getOrElse { source.play(line.url) }
                DramaPlayable(
                    url = result.url,
                    headers = result.headers.ifEmpty { line.headers },
                    title = detail.title.ifBlank { candidate.title },
                )
            }
        }.onFailure {
            AppLog.put("ShortDramaPlaybackResolver.resolveVideo failed", it)
        }.getOrNull()
    }

    private suspend fun resolveRss(candidate: DramaCandidate): DramaPlayable? {
        if (candidate.pageUrl.isBlank()) return null
        val source = appDb.rssSourceDao.getByKey(candidate.source.key) ?: return null
        val article = appDb.rssArticleDao.getByLink(candidate.source.key, candidate.pageUrl)
            ?: RssArticle(
                origin = candidate.source.key,
                title = candidate.title,
                link = candidate.pageUrl,
                image = candidate.cover,
            )
        return runCatching {
            withTimeoutOrNull(RESOLVE_PAGE_TIMEOUT_MS) {
                val referer = mapOf("Referer" to candidate.pageUrl)
                val body = runCatching {
                    AnalyzeUrl(candidate.pageUrl, source = source, ruleData = article)
                        .getStrResponseAwait().body
                }.getOrNull().orEmpty()
                val fromPage = if (body.isNotBlank()) {
                    VideoUrlExtractor.extractPrecise(body, candidate.pageUrl)
                        .firstOrNull { VideoUrlExtractor.isDirectVideoStreamUrl(it) }
                } else null
                if (fromPage != null) {
                    return@withTimeoutOrNull DramaPlayable(
                        url = fromPage,
                        headers = referer,
                        title = candidate.title,
                    )
                }
                VideoUrlExtractor.extractVideoUrlForEpisode(candidate.pageUrl, source, article)
                    ?.url?.takeIf { it.isNotBlank() }
                    ?.let {
                        DramaPlayable(
                            url = it,
                            headers = referer,
                            title = candidate.title,
                        )
                    }
            }
        }.onFailure {
            AppLog.put("ShortDramaPlaybackResolver.resolveRss failed", it)
        }.getOrNull()
    }

    private suspend fun resolveBook(candidate: DramaCandidate): DramaPlayable? {
        if (candidate.pageUrl.isBlank()) return null
        val source = appDb.bookSourceDao.getBookSource(candidate.source.key) ?: return null
        val book = appDb.searchBookDao.getSearchBook(candidate.pageUrl)?.toBook()
            ?: Book(
                bookUrl = candidate.pageUrl,
                origin = candidate.source.key,
                originName = candidate.source.name,
                name = candidate.title,
                author = candidate.author,
                type = BookType.video,
            )
        return runCatching {
            withTimeoutOrNull(RESOLVE_PAGE_TIMEOUT_MS) {
                runCatching { WebBook.getBookInfoAwait(source, book, canReName = false) }
                    .onFailure { AppLog.put("ShortDramaPlaybackResolver bookInfo failed", it) }
                val chapters = runCatching { WebBook.getChapterListAwait(source, book) }
                    .getOrNull()?.getOrNull()
                    .orEmpty()
                    .filterNot { it.isVolume }
                val chapter = chapters.firstOrNull() ?: return@withTimeoutOrNull null
                if (VideoUrlExtractor.isDirectVideoStreamUrl(chapter.url)) {
                    return@withTimeoutOrNull DramaPlayable(
                        url = chapter.url,
                        headers = mapOf("Referer" to chapter.url),
                        title = chapter.title.ifBlank { candidate.title },
                    )
                }
                val referer = mapOf("Referer" to chapter.url)
                val body = runCatching {
                    WebBook.getContentAwait(source, book, chapter, needSave = false)
                }.getOrNull().orEmpty()
                val fromContent = if (body.isNotBlank() && body != chapter.url) {
                    VideoUrlExtractor.extractPrecise(body, chapter.url)
                        .firstOrNull { VideoUrlExtractor.isDirectVideoStreamUrl(it) }
                } else null
                if (fromContent != null) {
                    return@withTimeoutOrNull DramaPlayable(
                        url = fromContent,
                        headers = referer,
                        title = chapter.title.ifBlank { candidate.title },
                    )
                }
                val sniffed = VideoUrlExtractor.extractVideoUrlForEpisode(chapter.url, source, book)
                sniffed?.url?.takeIf { it.isNotBlank() }?.let {
                    DramaPlayable(
                        url = it,
                        headers = sniffed.headers.ifEmpty { referer },
                        title = chapter.title.ifBlank { candidate.title },
                    )
                }
            }
        }.onFailure {
            AppLog.put("ShortDramaPlaybackResolver.resolveBook failed", it)
        }.getOrNull()
    }
}