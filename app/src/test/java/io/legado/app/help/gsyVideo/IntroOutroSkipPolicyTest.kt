package io.legado.app.help.gsyVideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroOutroSkipPolicyTest {
    @Test
    fun rejectsUnsafeRanges() {
        assertFalse(IntroOutroSkipPolicy.isValid(0, 10, 10, false))
        assertFalse(IntroOutroSkipPolicy.isValid(-1, 10, 10, false))
        assertFalse(IntroOutroSkipPolicy.isValid(300_000, 10, 10, true))
        assertFalse(IntroOutroSkipPolicy.isValid(60_000, 60, 0, false))
        assertFalse(IntroOutroSkipPolicy.isValid(60_000, 30, 30, false))
        assertFalse(IntroOutroSkipPolicy.isValid(60_000, Int.MAX_VALUE, Int.MAX_VALUE, false))
        assertFalse(IntroOutroSkipPolicy.isValid(60_000, -1, 0, false))
        assertTrue(IntroOutroSkipPolicy.isValid(60_000, 30, 29, false))
        assertTrue(IntroOutroSkipPolicy.isValid(60_000, 0, 0, false))
    }

    @Test
    fun marksSecondsFromTheCorrectEnd() {
        assertEquals(12, IntroOutroSkipPolicy.markedSeconds(60_000, 12_345, true))
        assertEquals(47, IntroOutroSkipPolicy.markedSeconds(60_000, 12_345, false))
        assertNull(IntroOutroSkipPolicy.markedSeconds(0, 0, true))
        assertNull(IntroOutroSkipPolicy.markedSeconds(60_000, -1, false))
        assertNull(IntroOutroSkipPolicy.markedSeconds(60_000, 60_000, false))
        assertNull(IntroOutroSkipPolicy.markedSeconds(60_000, 999, true))
        assertNull(IntroOutroSkipPolicy.markedSeconds(Long.MAX_VALUE, 0, false))
    }
}
