package io.legado.app.ui.main.drama

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.drama.DramaCandidate
import io.legado.app.data.drama.DramaSourceKind
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.SourceGroupTagLinker
import io.legado.app.help.book.removeType
import io.legado.app.model.SourceCallBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 短剧「收藏」= 加入阅读 Max 真实书架。
 *
 * 复用书源详情页的写入流程（removeType(notShelf) / order / SourceGroupTagLinker /
 * book.save() / SourceCallBack），保证书源分组联动与书架行为一致。
 */
object ShortDramaBookshelf {

    suspend fun isInBookshelf(candidate: DramaCandidate): Boolean = withContext(Dispatchers.IO) {
        appDb.bookDao.getBook(candidate.pageUrl) != null
    }

    /**
     * 加入书架并记录当前观看进度（第几集）。
     *
     * @param episodeIndex 1 基集号，>0 时写入 [Book.durChapterIndex]（0 基）
     * @param episodeTitle 当前集标题，写入 [Book.durChapterTitle]
     * @param totalEpisodes 总集数，>0 时写入 [Book.totalChapterNum]
     */
    suspend fun add(
        candidate: DramaCandidate,
        source: BookSource? = null,
        episodeIndex: Int = 0,
        episodeTitle: String = "",
        totalEpisodes: Int = 0,
    ) {
        withContext(Dispatchers.IO) {
            val bookSource = source ?: appDb.bookSourceDao.getBookSource(candidate.source.key)
            var book = appDb.bookDao.getBook(candidate.pageUrl)
            if (book == null) {
                book = Book(
                    bookUrl = candidate.pageUrl,
                    origin = candidate.source.key,
                    originName = candidate.source.name,
                    name = candidate.title,
                    author = candidate.author,
                    coverUrl = candidate.cover.takeIf { it.isNotBlank() },
                    kind = candidate.tags.joinToString(",").takeIf { it.isNotBlank() },
                    intro = candidate.intro.takeIf { it.isNotBlank() },
                    type = BookType.video,
                )
            }
            // 关键：写入外部视频源标识。书架/发现列表打开视频书时会读取
            // videoSourceId 决定走 pyPluginId 播放链路；缺失会退化成书源路径并提示“未找到源”。
            if (candidate.source.kind == DramaSourceKind.PY ||
                candidate.source.kind == DramaSourceKind.TVBOX
            ) {
                book.putVariable("videoSourceId", candidate.source.key)
                book.putVariable(
                    "videoItemId",
                    candidate.itemId.ifBlank { candidate.pageUrl },
                )
            }
            if (episodeIndex > 0) {
                book.durChapterIndex = episodeIndex - 1
                book.durChapterPos = 0
            }
            if (episodeTitle.isNotBlank()) {
                book.durChapterTitle = episodeTitle
                book.latestChapterTitle = episodeTitle
            }
            if (totalEpisodes > 0) book.totalChapterNum = totalEpisodes
            book.removeType(BookType.notShelf)
            if (book.order == 0) book.order = appDb.bookDao.minOrder - 1
            bookSource?.let { SourceGroupTagLinker.apply(book, it) }
            book.save()
            SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, bookSource, book)
        }
    }

    suspend fun remove(candidate: DramaCandidate) {
        withContext(Dispatchers.IO) {
            appDb.bookDao.getBook(candidate.pageUrl)?.delete()
        }
    }
}