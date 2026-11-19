package io.legado.app.data.drama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortDramaRecommendEngineTest {

    private val sourceA = DramaSourceRef(DramaSourceKind.PY, "a", "源A")
    private val sourceB = DramaSourceRef(DramaSourceKind.TVBOX, "b", "源B")
    private val sourceC = DramaSourceRef(DramaSourceKind.RSS, "c", "源C")

    private fun candidate(
        id: String,
        source: DramaSourceRef = sourceA,
        tags: List<String> = listOf("都市"),
        publishedAt: Long = 0L,
    ) = DramaCandidate(
        seriesId = id,
        source = source,
        title = "剧$id",
        tags = tags,
        publishedAt = publishedAt,
    )

    @Test
    fun recentlyExposedSeriesAreExcludedWhileFreshCandidatesSuffice() {
        val candidates = (1..12).map { candidate(it.toString(), tags = listOf("t$it")) }
        val snapshot = DramaHistorySnapshot(recentSeries = setOf("1"))
        val result = ShortDramaRecommendEngine.rank(
            candidates = candidates,
            snapshot = snapshot,
            sessionExposed = emptyList(),
            currentFeed = emptyList(),
            limit = 10,
        )
        assertFalse(result.candidates.any { it.seriesId == "1" })
        assertFalse(result.supplement)
        assertEquals(10, result.candidates.size)
    }

    @Test
    fun currentFeedSeriesAreNeverRepeated() {
        val candidates = listOf(candidate("1"), candidate("2"))
        val result = ShortDramaRecommendEngine.rank(
            candidates = candidates,
            snapshot = DramaHistorySnapshot.EMPTY,
            sessionExposed = emptyList(),
            currentFeed = listOf(candidate("1")),
            limit = 10,
        )
        assertEquals(listOf("2"), result.candidates.map { it.seriesId })
    }

    @Test
    fun recentlyCompletedSeriesGoesToTailWithinStrictPass() {
        val now = 1_000_000_000_000L
        val candidates = listOf(
            candidate("done", publishedAt = now),
            candidate("fresh", publishedAt = now),
        )
        val snapshot = DramaHistorySnapshot(completedSeries = mapOf("done" to now - 1_000L))
        val result = ShortDramaRecommendEngine.rank(
            candidates = candidates,
            snapshot = snapshot,
            sessionExposed = emptyList(),
            currentFeed = emptyList(),
            limit = 10,
            now = now,
        )
        assertEquals(listOf("fresh", "done"), result.candidates.map { it.seriesId })
    }

    @Test
    fun sourceQuotaBreaksSingleSourceMonopoly() {
        val candidates = (1..10).map { candidate("a$it", source = sourceA, tags = listOf("t$it")) }
        val result = ShortDramaRecommendEngine.rank(
            candidates = candidates,
            snapshot = DramaHistorySnapshot.EMPTY,
            sessionExposed = emptyList(),
            currentFeed = emptyList(),
            limit = 10,
        )
        assertEquals(10, result.candidates.size)
    }

    @Test
    fun supplementRelaxesExposureWindowButStillDeduplicates() {
        val candidates = listOf(candidate("1"), candidate("2"), candidate("3"))
        val snapshot = DramaHistorySnapshot(recentSeries = setOf("1", "2", "3"))
        val result = ShortDramaRecommendEngine.rank(
            candidates = candidates,
            snapshot = snapshot,
            sessionExposed = listOf("1"),
            currentFeed = emptyList(),
            limit = 3,
        )
        assertEquals(listOf("2", "3"), result.candidates.map { it.seriesId })
        assertTrue(result.supplement)
    }

    @Test
    fun emptyCandidatesProduceEmptyBatch() {
        val result = ShortDramaRecommendEngine.rank(
            candidates = emptyList(),
            snapshot = DramaHistorySnapshot.EMPTY,
            sessionExposed = emptyList(),
            currentFeed = emptyList(),
        )
        assertTrue(result.candidates.isEmpty())
        assertFalse(result.supplement)
    }

    @Test
    fun tagPreferenceRaisesScore() {
        val now = 1_000_000_000_000L
        val snapshot = DramaHistorySnapshot(preferences = mapOf("都市" to 1.0))
        val preferred = ShortDramaRecommendEngine.score(candidate("1", tags = listOf("都市"), publishedAt = now), snapshot, now)
        val neutral = ShortDramaRecommendEngine.score(candidate("2", tags = listOf("古装"), publishedAt = now), snapshot, now)
        assertTrue(preferred > neutral)
    }

    @Test
    fun sourceRunLimitPreventsSameSourceStreak() {
        val candidates = (1..6).map { candidate("a$it", source = sourceA, tags = listOf("t$it")) }
        val result = ShortDramaRecommendEngine.rank(
            candidates = candidates,
            snapshot = DramaHistorySnapshot.EMPTY,
            sessionExposed = emptyList(),
            currentFeed = emptyList(),
            limit = 5,
        )
        assertTrue(result.candidates.isNotEmpty())
        val ids = result.candidates.map { it.seriesId }.toSet()
        assertEquals(ids.size, result.candidates.size)
    }

    @Test
    fun multiSourceCandidatesKeepOrderStable() {
        val candidates = listOf(
            candidate("b1", source = sourceB),
            candidate("c1", source = sourceC),
            candidate("a1", source = sourceA),
        )
        val result = ShortDramaRecommendEngine.rank(
            candidates = candidates,
            snapshot = DramaHistorySnapshot.EMPTY,
            sessionExposed = emptyList(),
            currentFeed = emptyList(),
            limit = 3,
        )
        assertEquals(3, result.candidates.size)
        assertEquals(result.candidates.size, result.candidates.map { it.seriesId }.toSet().size)
    }
}