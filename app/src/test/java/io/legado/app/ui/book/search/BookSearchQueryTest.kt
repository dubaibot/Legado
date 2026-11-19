package io.legado.app.ui.book.search

import org.junit.Assert.*
import org.junit.Test

class BookSearchQueryTest {
    @Test fun authorFilterAppliesWithExactDisabled() {
        val query = BookSearchQuery.parse("三体 刘慈欣")
        assertEquals("三体", query.title)
        assertTrue(query.matches("三体全集", "作者：刘慈欣 著", false))
        assertFalse(query.matches("三体", "其他作者", false))
    }

    @Test fun exactMatchesWholeTitleAndRetainsAuthorFilter() {
        val query = BookSearchQuery.parse("三体 刘慈欣")
        assertTrue(query.matches("三体", "刘慈欣", true))
        assertFalse(query.matches("三体全集", "刘慈欣", true))
        assertFalse(query.matches("三体", "其他作者", true))
    }

    @Test fun singleKeywordRetainsAuthorSearch() {
        val query = BookSearchQuery.parse("刘慈欣")
        assertTrue(query.matches("三体", "刘慈欣", false))
        assertFalse(query.matches("三体", "刘慈欣", true))
    }

    @Test fun usesLastWhitespaceToKeepSpacesInTitle() {
        assertEquals(BookSearchQuery("A Book", "Writer"), BookSearchQuery.parse("  A Book\tWriter  "))
    }
}
