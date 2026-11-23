package io.legado.app.ui.main.drama

import android.app.Activity
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.drama.DramaCandidate
import io.legado.app.data.drama.DramaSourceKind
import io.legado.app.data.drama.ShortDramaPlaybackResolver
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.info.BookInfoActivity
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
 * - 书源：缓存 searchBook 后进书籍详情页，由详情页带完整上下文进入播放
 */
object ShortDramaLauncher {

    suspend fun open(activity: Activity, candidate: DramaCandidate) {
        when (candidate.source.kind) {
            DramaSourceKind.RSS -> openRss(activity, candidate)
            DramaSourceKind.BOOK, DramaSourceKind.PUSH -> openBook(activity, candidate)
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

    /**
     * 书源条目先落一条 searchBooks 缓存，再进详情页。
     *
     * 短剧书不在书架、也不在搜索结果里，直接打开播放页会因拿不到书源上下文而提示“未找到源”。
     * 详情页会按书源规则解析详情与目录，由用户从详情页进入播放。
     */
    private suspend fun openBook(activity: Activity, candidate: DramaCandidate) {
        val bookUrl = candidate.pageUrl
        if (bookUrl.isBlank()) return
        withContext(Dispatchers.IO) {
            if (appDb.searchBookDao.getSearchBook(bookUrl) == null) {
                runCatching {
                    appDb.searchBookDao.insert(
                        SearchBook(
                            bookUrl = bookUrl,
                            origin = candidate.source.key,
                            originName = candidate.source.name,
                            type = BookType.video,
                            name = candidate.title,
                            author = candidate.author,
                            coverUrl = candidate.cover.takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }
        activity.startActivity<BookInfoActivity> {
            putExtra("bookUrl", bookUrl)
            putExtra("name", candidate.title)
            putExtra("author", candidate.author)
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