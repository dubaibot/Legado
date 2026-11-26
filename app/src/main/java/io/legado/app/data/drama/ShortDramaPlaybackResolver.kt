package io.legado.app.data.drama

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.video.PlayLine
import io.legado.app.data.video.VideoSourceRegistry
import io.legado.app.help.video.BiliDirectResolver
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
    private val CID_REGEX = Regex("cid=(\\d+)")

    /** 线路名：优先取 flag，其次取 name 中「·」前缀（与主播放器 groupPyPlayLines 一致）。 */
    private fun routeOf(line: PlayLine): String =
        line.flag.takeIf { it.isNotBlank() }
            ?: line.name.substringBefore("·")
                .takeIf { it.isNotBlank() && it != line.name }
            ?: "线路1"

    suspend fun resolve(candidate: DramaCandidate, route: String? = null): DramaPlayable? =
        withContext(Dispatchers.IO) {
            when (candidate.source.kind) {
                DramaSourceKind.PY, DramaSourceKind.TVBOX -> resolveVideo(candidate, route)
                DramaSourceKind.RSS -> resolveRss(candidate)
                DramaSourceKind.BOOK, DramaSourceKind.PUSH -> resolveBook(candidate)
            }
        }

    private suspend fun resolveVideo(candidate: DramaCandidate, route: String?): DramaPlayable? {
        return runCatching {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                val info = VideoSourceRegistry.getSourceInfo(candidate.source.key)
                    ?: return@withTimeoutOrNull null
                val source = VideoSourceRegistry.createSource(info)
                    ?: return@withTimeoutOrNull null
                val detail = source.detail(candidate.itemId.ifBlank { candidate.pageUrl })
                val line = detail.playLines.firstOrNull { routeOf(it) == route }
                    ?: detail.playLines.firstOrNull()
                    ?: return@withTimeoutOrNull null
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
        val source = when (candidate.source.kind) {
            DramaSourceKind.PUSH -> ShortDramaCandidateProvider.pushBookSource(candidate.source)
            else -> appDb.bookSourceDao.getBookSource(candidate.source.key)
        } ?: return null
        val book = appDb.searchBookDao.getSearchBook(candidate.pageUrl)?.toBook()
            ?: Book(
                bookUrl = candidate.pageUrl,
                origin = candidate.source.key,
                originName = candidate.source.name,
                name = candidate.title,
                author = candidate.author,
                type = BookType.video,
            )
        val sid = candidate.seriesId
        return runCatching {
            withTimeoutOrNull(RESOLVE_PAGE_TIMEOUT_MS) {
                runCatching { WebBook.getBookInfoAwait(source, book, canReName = false) }
                    .onFailure { AppLog.put("ShortDrama[$sid] bookInfo failed: ${it.message}") }
                val chapters = runCatching { WebBook.getChapterListAwait(source, book) }
                    .getOrNull()?.getOrNull()
                    .orEmpty()
                    .filterNot { it.isVolume }
                if (chapters.isEmpty()) {
                    AppLog.put("ShortDrama[$sid] resolveBook chapters empty")
                    return@withTimeoutOrNull null
                }
                val chapter = chapters.first()
                BiliDirectResolver.parsePseudoUrl(chapter.url)?.let { aid ->
                    BiliDirectResolver.resolve(
                        aid,
                        cid = CID_REGEX.find(candidate.pageUrl)?.groupValues?.get(1)
                    )?.let { url ->
                        return@withTimeoutOrNull DramaPlayable(
                            url = url,
                            headers = mapOf("Referer" to chapter.url),
                            title = chapter.title.ifBlank { candidate.title },
                        )
                    }
                }
                if (VideoUrlExtractor.isDirectVideoStreamUrl(chapter.url)) {
                    return@withTimeoutOrNull DramaPlayable(
                        url = chapter.url,
                        headers = mapOf("Referer" to chapter.url),
                        title = chapter.title.ifBlank { candidate.title },
                    )
                }
                val referer = mapOf("Referer" to chapter.url)
                val bodyResult = runCatching {
                    WebBook.getContentAwait(source, book, chapter, needSave = false)
                }
                val body = bodyResult.getOrNull().orEmpty()
                if (body.isBlank()) {
                    AppLog.put("ShortDrama[$sid] getContent failed: ${bodyResult.exceptionOrNull()?.message}")
                }
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
                BiliDirectResolver.parsePseudoUrl(body)?.let { aid ->
                    BiliDirectResolver.resolve(
                        aid,
                        cid = CID_REGEX.find(candidate.pageUrl)?.groupValues?.get(1)
                    )?.let { url ->
                        return@withTimeoutOrNull DramaPlayable(
                            url = url,
                            headers = referer,
                            title = chapter.title.ifBlank { candidate.title },
                        )
                    }
                }
                AppLog.put("ShortDrama[$sid] extractPrecise no media hit, fallback sniff")
                val sniffed = VideoUrlExtractor.extractVideoUrlForEpisode(chapter.url, source, book)
                val mediaUrl = sniffed?.url?.takeIf { it.isNotBlank() }
                if (mediaUrl == null) {
                    AppLog.put("ShortDrama[$sid] sniff failed")
                    return@withTimeoutOrNull null
                }
                DramaPlayable(
                    url = mediaUrl,
                    headers = sniffed.headers.ifEmpty { referer },
                    title = chapter.title.ifBlank { candidate.title },
                )
            }
        }.onFailure {
            AppLog.put("ShortDrama[$sid] resolveBook failed", it)
        }.getOrNull()
    }

    /** 取整部剧的选集列表（供选集弹窗/下一集/续播用）。按源类别分派。 */
    suspend fun resolveEpisodes(candidate: DramaCandidate): List<DramaEpisode> =
        withContext(Dispatchers.IO) {
            when (candidate.source.kind) {
                DramaSourceKind.PY, DramaSourceKind.TVBOX -> resolveVideoEpisodes(candidate)
                DramaSourceKind.BOOK, DramaSourceKind.PUSH -> resolveBookEpisodes(candidate)
                DramaSourceKind.RSS -> emptyList()
            }
        }

    /** PY/TVBOX 源：detail() 取 playLines，每集 url 保留中间 id（vid_qkey），flag 供播放时解析。 */
    private suspend fun resolveVideoEpisodes(candidate: DramaCandidate): List<DramaEpisode> {
        return runCatching {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                val info = VideoSourceRegistry.getSourceInfo(candidate.source.key)
                    ?: return@withTimeoutOrNull emptyList()
                val source = VideoSourceRegistry.createSource(info)
                    ?: return@withTimeoutOrNull emptyList()
                val detail = source.detail(candidate.itemId.ifBlank { candidate.pageUrl })
                detail.playLines.mapIndexed { i, line ->
                    val title = line.name.substringAfter("·", line.name)
                        .ifBlank { "第${i + 1}集" }
                    DramaEpisode(
                        index = i,
                        title = title,
                        url = line.url,
                        flag = line.flag,
                        route = routeOf(line),
                    )
                }
            }
        }.onFailure {
            AppLog.put("ShortDramaPlaybackResolver.resolveVideoEpisodes failed", it)
        }.getOrDefault(emptyList()).orEmpty()
    }

    /** 书源/推送源：取详情/目录章节，仅保留直链章节。 */
    private suspend fun resolveBookEpisodes(candidate: DramaCandidate): List<DramaEpisode> {
            if (candidate.pageUrl.isBlank()) {
                return emptyList()
            }
            val source = when (candidate.source.kind) {
                DramaSourceKind.PUSH -> ShortDramaCandidateProvider.pushBookSource(candidate.source)
                else -> appDb.bookSourceDao.getBookSource(candidate.source.key)
            }
                ?: return emptyList()
            val book = appDb.searchBookDao.getSearchBook(candidate.pageUrl)?.toBook()
                ?: Book(
                    bookUrl = candidate.pageUrl,
                    origin = candidate.source.key,
                    originName = candidate.source.name,
                    name = candidate.title,
                    author = candidate.author,
                    type = BookType.video,
                )
            val sid = candidate.seriesId
            return runCatching {
                withTimeoutOrNull(RESOLVE_PAGE_TIMEOUT_MS) {
                    runCatching { WebBook.getBookInfoAwait(source, book, canReName = false) }
                        .onFailure { AppLog.put("ShortDrama[$sid] resolveEpisodes bookInfo failed: ${it.message}") }
                    val chapters = runCatching { WebBook.getChapterListAwait(source, book) }
                        .getOrNull()?.getOrNull()
                        .orEmpty()
                        .filterNot { it.isVolume }
                    if (chapters.isEmpty()) {
                        AppLog.put("ShortDrama[$sid] resolveEpisodes chapters empty")
                        return@withTimeoutOrNull emptyList()
                    }
                    chapters.mapIndexed { i, c ->
                        DramaEpisode(i, c.title.ifBlank { "第${i + 1}集" }, c.url)
                    }.filter {
                        VideoUrlExtractor.isDirectVideoStreamUrl(it.url) ||
                            BiliDirectResolver.parsePseudoUrl(it.url) != null
                    }
                }
            }.onFailure {
                AppLog.put("ShortDrama[$sid] resolveEpisodes failed", it)
            }.getOrDefault(emptyList()).orEmpty()
    }

    /**
     * 把某集解析成可直接播放的直链。
     * PY/TVBOX 的选集 url 是中间 id（vid_qkey），经 source.play(url, flag) 换直链；
     * 其它源（书源）选集已是直链，原样返回。
     */
    suspend fun resolveEpisodePlayable(
        candidate: DramaCandidate,
        episode: DramaEpisode,
    ): DramaPlayable? = withContext(Dispatchers.IO) {
        when (candidate.source.kind) {
            DramaSourceKind.PY, DramaSourceKind.TVBOX -> runCatching {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    val info = VideoSourceRegistry.getSourceInfo(candidate.source.key)
                        ?: return@withTimeoutOrNull null
                    val source = VideoSourceRegistry.createSource(info)
                        ?: return@withTimeoutOrNull null
                    val result = runCatching { source.play(episode.url, episode.flag) }
                        .getOrElse { source.play(episode.url) }
                    if (result.url.isBlank()) {
                        null
                    } else {
                        DramaPlayable(
                            url = result.url,
                            headers = result.headers,
                            title = episode.title.ifBlank { candidate.title },
                        )
                    }
                }
            }.onFailure {
                AppLog.put(
                    "ShortDramaPlaybackResolver.resolveEpisodePlayable failed: " +
                        "${it.javaClass.simpleName}: ${it.message} (url=${episode.url}, flag=${episode.flag})",
                    it
                )
            }.getOrNull()
            else -> if (episode.url.isBlank()) {
                null
            } else {
                DramaPlayable(
                    url = episode.url,
                    title = episode.title.ifBlank { candidate.title },
                )
            }
        }
    }
}