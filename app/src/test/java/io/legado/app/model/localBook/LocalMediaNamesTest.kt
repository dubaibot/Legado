package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocalMediaNamesTest {
    @Test
    fun extensionPolicyAcceptsUppercaseAndMultipleDots() {
        assertEquals(LocalMediaNames.Kind.IMAGE, LocalMediaNames.kind("chapter.02.JPEG"))
        assertEquals(LocalMediaNames.Kind.IMAGE, LocalMediaNames.kind("插图.webp"))
        assertEquals(LocalMediaNames.Kind.VIDEO, LocalMediaNames.kind("Episode.12.MKV"))
        assertEquals(LocalMediaNames.Kind.VIDEO, LocalMediaNames.kind("01.MP4"))
        listOf("mp4", "movie.mp4.bak", "cover.jpg.tmp", ".cover.jpg", "page.", "").forEach {
            assertNull(it, LocalMediaNames.kind(it))
        }
    }

    @Test
    fun numericRunsSortWithoutOverflowAndWithLeadingZeros() {
        val names = listOf("page10.jpg", "page002.jpg", "page1.jpg", "page2.jpg",
            "page999999999999999999999999.jpg", "page1000000000000000000000000.jpg")
        assertEquals(listOf("page1.jpg", "page002.jpg", "page2.jpg", "page10.jpg",
            "page999999999999999999999999.jpg", "page1000000000000000000000000.jpg"),
            names.sortedWith(LocalMediaNames.comparator))
    }

    @Test
    fun orderingIsDeterministicForCaseTiesAndMultipleNumbers() {
        val names = listOf("S2E10.mp4", "s2e2.mp4", "S10E1.mp4", "S2E2.mp4")
        val expected = listOf("S2E2.mp4", "s2e2.mp4", "S2E10.mp4", "S10E1.mp4")
        assertEquals(expected, names.sortedWith(LocalMediaNames.comparator))
        assertEquals(expected, names.reversed().sortedWith(LocalMediaNames.comparator))
        names.forEach { a -> names.forEach { b ->
            val ab = LocalMediaNames.comparator.compare(a, b)
            val ba = LocalMediaNames.comparator.compare(b, a)
            assertEquals(Integer.signum(ab), -Integer.signum(ba))
        } }
        assertTrue(LocalMediaNames.comparator.compare("", "1") < 0)
    }

    @Test
    fun extensionMatchingDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(LocalMediaNames.Kind.VIDEO, LocalMediaNames.kind("CLIP.AVI"))
            assertEquals(LocalMediaNames.Kind.IMAGE, LocalMediaNames.kind("PAGE.GIF"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
