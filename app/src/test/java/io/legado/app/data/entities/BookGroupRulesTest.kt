package io.legado.app.data.entities

import io.legado.app.constant.BookType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookGroupRulesTest {
    @Test
    fun classifiesContentAndLocalFiles() {
        assertEquals(BookGroup.IdNovel, BookGroupRules.typeGroup(BookType.text))
        assertEquals(BookGroup.IdNovel, BookGroupRules.typeGroup(BookType.webFile))
        assertEquals(BookGroup.IdNovel, BookGroupRules.typeGroup(BookType.local))
        assertEquals(BookGroup.IdNovel, BookGroupRules.typeGroup(BookType.local or BookType.text or BookType.archive))
        assertEquals(BookGroup.IdAudio, BookGroupRules.typeGroup(BookType.local or BookType.audio))
        assertEquals(BookGroup.IdManga, BookGroupRules.typeGroup(BookType.local or BookType.image))
        assertEquals(BookGroup.IdVideo, BookGroupRules.typeGroup(BookType.local or BookType.video))
        assertEquals(BookGroup.IdOther, BookGroupRules.typeGroup(0))
        assertEquals(BookGroup.IdOther, BookGroupRules.typeGroup(BookType.updateError or BookType.archive))
    }

    @Test
    fun mediaFlagsHaveDeterministicPriority() {
        assertEquals(BookGroup.IdVideo, BookGroupRules.typeGroup(BookType.allBookTypeLocal))
        assertEquals(BookGroup.IdAudio, BookGroupRules.typeGroup(BookType.audio or BookType.image or BookType.text))
        assertEquals(BookGroup.IdManga, BookGroupRules.typeGroup(BookType.image or BookType.text))
    }

    @Test
    fun partitionsEveryShelfTypeExactlyOnce() {
        val groups = listOf(BookGroup.IdNovel, BookGroup.IdAudio, BookGroup.IdManga, BookGroup.IdVideo, BookGroup.IdOther)
        for (type in 0..2047) {
            val expected = if (type and BookType.notShelf == 0) 1 else 0
            assertEquals("type=$type", expected, groups.count { BookGroupRules.contains(it, type, 3L) })
            assertEquals(expected == 1, BookGroupRules.contains(BookGroup.IdAll, type, 3L))
        }
    }

    @Test
    fun preservesCustomAndLegacyMembership() {
        assertTrue(BookGroupRules.contains(2L, BookType.text, 3L))
        assertFalse(BookGroupRules.contains(4L, BookType.text, 3L))
        assertFalse(BookGroupRules.contains(2L, BookType.text or BookType.notShelf, 3L))
        assertTrue(BookGroupRules.contains(BookGroup.IdNetNone, BookType.text, 0L))
        assertFalse(BookGroupRules.contains(BookGroup.IdNetNone, BookType.text, 2L, 2L))
        assertTrue(BookGroupRules.contains(BookGroup.IdLocalNone, BookType.local or BookType.audio, 0L))
        assertFalse(BookGroupRules.contains(-999L, BookType.text, 0L))
    }
}
