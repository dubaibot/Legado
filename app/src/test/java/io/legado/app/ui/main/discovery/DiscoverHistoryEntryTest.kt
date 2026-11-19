package io.legado.app.ui.main.discovery

import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.entities.RssReadRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverHistoryEntryTest {
    @Test
    fun mergesSourcesByLastReadWithUndatedRecordsLast() {
        val history = mergeDiscoverHistory(
            books = listOf(ReadRecordShow(bookName = "Book", readTime = 5, lastRead = 20)),
            rss = listOf(
                RssReadRecord(record = "undated", title = " ", origin = "second"),
                RssReadRecord(record = "new", title = "Article", readTime = 30, origin = "first"),
                RssReadRecord(record = "old", readTime = 10, origin = "second"),
            ),
        )
        assertEquals(listOf("Article", "Book", "old", "undated"), history.map { it.title })
    }

    @Test
    fun keysDistinguishBookNamesAndRecordTypes() {
        val history = mergeDiscoverHistory(
            listOf(
                ReadRecordShow(bookName = "first", readTime = 1, lastRead = 1),
                ReadRecordShow(bookName = "second", readTime = 1, lastRead = 1),
            ),
            listOf(RssReadRecord(record = "same")),
        )
        assertEquals(3, history.map { it.key }.toSet().size)
        assertEquals(emptyList<DiscoverHistoryEntry>(), mergeDiscoverHistory(emptyList(), emptyList()))
    }
}
