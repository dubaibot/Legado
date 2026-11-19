package io.legado.app.ui.main.discovery

import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.entities.RssReadRecord

internal sealed interface DiscoverHistoryEntry {
    val time: Long?
    val title: String
    val key: String

    data class Book(val record: ReadRecordShow) : DiscoverHistoryEntry {
        override val time get() = record.lastRead.takeIf { it > 0 }
        override val title get() = record.bookName
        override val key get() = "book:${record.bookName}"
    }

    data class Rss(val record: RssReadRecord) : DiscoverHistoryEntry {
        override val time get() = record.readTime?.takeIf { it > 0 }
        override val title get() = record.title?.takeIf { it.isNotBlank() } ?: record.record
        override val key get() = "rss:${record.record}"
    }
}

internal fun mergeDiscoverHistory(books: List<ReadRecordShow>, rss: List<RssReadRecord>): List<DiscoverHistoryEntry> =
    (books.map { DiscoverHistoryEntry.Book(it) } + rss.map { DiscoverHistoryEntry.Rss(it) })
        .sortedByDescending { it.time }
