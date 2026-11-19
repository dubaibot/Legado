package io.legado.app.data.drama

import io.legado.app.constant.AppLog
import io.legado.app.data.video.VideoSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 短剧播放解析：把候选解析成「可直接交给播放器的入口」。
 *
 * 视频源：`detail(itemId)` 取第一条线路，`play(url, flag)` 取最终地址。
 * 订阅源与书源由播放页按各自上下文解析，这里只返回入口地址占位。
 */
object ShortDramaPlaybackResolver {

    private const val RESOLVE_TIMEOUT_MS = 10_000L

    suspend fun resolve(candidate: DramaCandidate): DramaPlayable? = withContext(Dispatchers.IO) {
        when (candidate.source.kind) {
            DramaSourceKind.PY, DramaSourceKind.TVBOX -> resolveVideo(candidate)
            DramaSourceKind.RSS, DramaSourceKind.BOOK ->
                if (candidate.pageUrl.isBlank()) null
                else DramaPlayable(url = candidate.pageUrl, title = candidate.title)
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
}
