package io.legado.app.ui.main.drama

import android.app.Activity
import io.legado.app.constant.PreferKey
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.drama.DramaCandidate
import io.legado.app.data.drama.DramaSourceKind
import io.legado.app.data.drama.ShortDramaPlaybackResolver
import io.legado.app.data.entities.RssArticle
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 短剧播放入口：按源类别选择合适的既有播放链路，避免自建播放器。
 *
 * - PY / TVBOX：传 `pyPluginId` + `pyVideoItemId`，由播放页完成 detail/play 与集数列表
 * - 订阅源：先补 `VideoPlay` 的文章上下文，再以 `sourceType=rss` 打开
 * - 书源：传 `bookUrl`，走既有书源播放链路
 */
object ShortDramaLauncher {

    suspend fun open(activity: Activity, candidate: DramaCandidate) {
        when (candidate.source.kind) {
            DramaSourceKind.RSS -> openRss(activity, candidate)
            DramaSourceKind.BOOK -> openBook(activity, candidate)
            DramaSourceKind.PY, DramaSourceKind.TVBOX -> openVideoSource(activity, candidate)
        }
    }

    private suspend fun openRss(activity: Activity, candidate: DramaCandidate) {
        val article = withContext(Dispatchers.IO) {
            appDb.rssArticleDao.getByLink(candidate.source.key, candidate.pageUrl)
        } ?: RssArticle(
            origin = candidate.source.key,
            title = candidate.title,
            link = candidate.pageUrl,
            image = candidate.cover,
        )
        ReadRss.prepareVideoPlayContext(article, null)
        activity.startActivity<VideoPlayerActivity> {
            putExtra("sourceKey", candidate.source.key)
            putExtra("sourceType", SourceType.rss)
            putExtra("record", candidate.pageUrl)
            putExtra("videoTitle", candidate.title)
            putExtra(PreferKey.layoutModeOverride, 1)
        }
    }

    private fun openBook(activity: Activity, candidate: DramaCandidate) {
        activity.startActivity<VideoPlayerActivity> {
            putExtra("bookUrl", candidate.pageUrl)
            putExtra("inBookshelf", false)
            putExtra("videoTitle", candidate.title)
            putExtra(PreferKey.layoutModeOverride, 1)
        }
    }

    private suspend fun openVideoSource(activity: Activity, candidate: DramaCandidate) {
        // 少数源的条目只有播放页地址、没有稳定条目 id：先解析出直链，再交给播放页
        if (candidate.itemId.isBlank()) {
            val playable = ShortDramaPlaybackResolver.resolve(candidate)
            if (playable != null && playable.url.isNotBlank()) {
                activity.startActivity<VideoPlayerActivity> {
                    putExtra("videoUrl", playable.url)
                    putExtra("videoTitle", candidate.title)
                    putExtra(PreferKey.layoutModeOverride, 1)
                }
                return
            }
        }
        activity.startActivity<VideoPlayerActivity> {
            putExtra("pyPluginId", candidate.source.key)
            putExtra("pyVideoItemId", candidate.itemId.ifBlank { candidate.pageUrl })
            putExtra("videoTitle", candidate.title)
            putExtra(PreferKey.layoutModeOverride, 1)
        }
    }
}