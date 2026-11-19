package io.legado.app.ui.book.search

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.*
import org.junit.Test

class SearchPreferencesTest {
    @Test fun ongoingMarkersTakePrecedenceOverCompletionSubstring() {
        val book = SearchBook(kind = "未完结")
        assertTrue(SearchPreferences(status = 1).accepts(book))
        assertFalse(SearchPreferences(status = 2).accepts(book))
        assertFalse(SearchPreferences(status = 1).accepts(SearchBook()))
    }

    @Test fun wordThresholdUsesNumberAdjacentToUnit() {
        val book = SearchBook(wordCount = "共100章，约30万字")
        assertTrue(SearchPreferences(minWords = 300000).accepts(book))
        assertFalse(SearchPreferences(minWords = 500000).accepts(book))
        assertTrue(SearchPreferences(minWords = 110000000).accepts(SearchBook(wordCount = "1亿2000万字")))
    }

    @Test fun missingMetadataIsAcceptedOnlyWithApplicableFilterDisabled() {
        val book = SearchBook()
        assertTrue(SearchPreferences().accepts(book))
        assertFalse(SearchPreferences(hideNoCover = true).accepts(book))
        assertFalse(SearchPreferences(minWords = 1).accepts(book))
        assertFalse(SearchPreferences(status = 2).accepts(book))
    }
}
