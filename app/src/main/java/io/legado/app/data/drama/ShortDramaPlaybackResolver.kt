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
import io.legado.app.data.video.VideoDetail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 订阅/书源整页解析+嗅探超时：25s 过长会让首集起播久等，15s 足够覆盖绝大多数可用源 */
    private const val RESOLVE_PAGE_TIMEOUT_MS = 15_000L
    /** detail 缓存有效期：playLines 中间 id 短期内稳定，10 分钟内复用可省一次网络往返 */
    private const val DETAIL_CACHE_TTL_MS = 10L * 60 * 1000
    private const val DETAIL_CACHE_MAX = 200

    private data class DetailEntry(val detail: VideoDetail?, val at: Long)
    /** 值可能是已完成的详情，也可能是进行中的 single-flight 请求。 */
    private val detailCache = HashMap<String, DetailEntry>()
    private val inflight = HashMap<String, CompletableDeferred<VideoDetail?>>()
    private val detailCacheLock = Mutex()

    /**
     * 获取视频源详情，命中缓存直接返回。
     * 起播 resolve() 与选集 resolveEpisodes() 会对同一部剧并发调用，这里用 single-flight：
     * 同一 key 的并发请求共享一次 detail 网络调用，彻底消除重复往返。
     * 失败结果不写入缓存（下次可重试）；成功结果按 TTL 缓存并设容量上限。
     */
    private suspend fun getVideoDetail(candidate: DramaCandidate): VideoDetail? {
        val key = videoDetailKey(candidate)
        val now = System.currentTimeMillis()

        // 1) 命中有效缓存
        detailCacheLock.withLock {
            val hit = detailCache[key]
            if (hit != null && now - hit.at < DETAIL_CACHE_TTL_MS && hit.detail != null) {
                return hit.detail
            }
        }
        // 2) 已有进行中的同 key 请求 → 等待其结果
        val waiter: CompletableDeferred<VideoDetail?>? = detailCacheLock.withLock {
            inflight[key]
        }
        if (waiter != null) return waiter.await()

        // 3) 本协程成为该 key 的加载者
        val mine = CompletableDeferred<VideoDetail?>()
        detailCacheLock.withLock { inflight[key] = mine }
        var detail: VideoDetail? = null
        try {
            detail = runCatching {
                val info = VideoSourceRegistry.getSourceInfo(candidate.source.key)
                    ?: return@runCatching null
                val source = VideoSourceRegistry.createSource(info)
                    ?: return@runCatching null
                source.detail(candidate.itemId.ifBlank { candidate.pageUrl })
                    .takeIf { it.playLines.isNotEmpty() }
            }.getOrNull()
        } finally {
            // 无论成功/失败/取消，都清理 inflight 并唤醒等待者，避免挂起
            detailCacheLock.withLock {
                inflight.remove(key)
                if (detail != null) {
                    if (detailCache.size >= DETAIL_CACHE_MAX) {
                        val oldest = detailCache.entries
                            .sortedBy { it.value.at }
                            .take(DETAIL_CACHE_MAX / 4)
                        oldest.forEach { detailCache.remove(it.key) }
                    }
                    detailCache[key] = DetailEntry(detail, System.currentTimeMillis())
                }
            }
            mine.complete(detail)
        }
        return detail
    }

    private fun videoDetailKey(candidate: DramaCandidate): String =
        "${candidate.source.key}|${candidate.itemId.ifBlank { candidate.pageUrl }}"

    suspend fun resolve(candidate: DramaCandidate): DramaPlayable? = withContext(Dispatchers.IO) {
        // 快速路径：条目已带媒体直链时无需 detail/抓页/嗅探，立即起播。
        // 书源/订阅源的候选中相当一部分 pageUrl 本身即 m3u8/mp4，可省去整个解析链路的等待。
        if (candidate.source.kind != DramaSourceKind.PY &&
            candidate.source.kind != DramaSourceKind.TVBOX &&
            candidate.pageUrl.isMediaUrl()
        ) {
            return@withContext DramaPlayable(
                url = candidate.pageUrl,
                headers = mapOf("Referer" to candidate.pageUrl),
                title = candidate.title,
            )
        }
        when (candidate.source.kind) {
            DramaSourceKind.PY, DramaSourceKind.TVBOX -> resolveVideo(candidate)
            DramaSourceKind.RSS -> resolveRss(candidate)
            DramaSourceKind.BOOK -> resolveBook(candidate)
        }
    }

    /** http(s) 且路径像 m3u8/mp4 的直链（含查询串），用于起播快速判定。 */
    private fun String.isMediaUrl(): Boolean {
        if (!startsWith("http", ignoreCase = true)) return false
        val path = substringBefore("?").substringBefore("#")
        return path.contains(".m3u8", ignoreCase = true) ||
            path.contains(".mp4", ignoreCase = true) ||
            path.contains(".mkv", ignoreCase = true) ||
            path.contains(".ts", ignoreCase = true)
    }

    private suspend fun resolveVideo(candidate: DramaCandidate): DramaPlayable? {
        return runCatching {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                val detail = getVideoDetail(candidate) ?: return@withTimeoutOrNull null
                val line = detail.playLines.firstOrNull() ?: return@withTimeoutOrNull null
                // play() 仍需源实例；detail 已缓存，这里只做直链解析
                val info = VideoSourceRegistry.getSourceInfo(candidate.source.key)
                    ?: return@withTimeoutOrNull null
                val source = VideoSourceRegistry.createSource(info)
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
                DramaSourceKind.BOOK -> resolveBookEpisodes(candidate)
                DramaSourceKind.RSS -> emptyList()
            }
        }

    /** PY/TVBOX 源：detail() 取 playLines，每集 url 保留中间 id（vid_qkey），flag 供播放时解析。 */
    private suspend fun resolveVideoEpisodes(candidate: DramaCandidate): List<DramaEpisode> {
        return runCatching {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                val detail = getVideoDetail(candidate) ?: return@withTimeoutOrNull emptyList()
                detail.playLines.mapIndexed { i, line ->
                    // 线路名：优先取 flag，其次取 name 中「·」前缀（与主播放器 groupPyPlayLines 一致）
                    val route = line.flag.takeIf { it.isNotBlank() }
                        ?: line.name.substringBefore("·")
                            .takeIf { it.isNotBlank() && it != line.name }
                        ?: "线路1"
                    val title = line.name.substringAfter("·", line.name)
                        .ifBlank { "第${i + 1}集" }
                    DramaEpisode(
                        index = i,
                        title = title,
                        url = line.url,
                        flag = line.flag,
                        route = route,
                    )
                }
            }
        }.onFailure {
            AppLog.put("ShortDramaPlaybackResolver.resolveVideoEpisodes failed", it)
        }.getOrDefault(emptyList()).orEmpty()
    }

    /** 书源：取详情/目录章节，仅保留直链章节。 */
    private suspend fun resolveBookEpisodes(candidate: DramaCandidate): List<DramaEpisode> {
            if (candidate.pageUrl.isBlank()) {
                return emptyList()
            }
            val source = appDb.bookSourceDao.getBookSource(candidate.source.key)
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
                    }.filter { VideoUrlExtractor.isDirectVideoStreamUrl(it.url) }
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
                AppLog.put("ShortDramaPlaybackResolver.resolveEpisodePlayable failed", it)
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