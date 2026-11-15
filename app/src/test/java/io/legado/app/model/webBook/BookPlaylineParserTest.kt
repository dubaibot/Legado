package io.legado.app.model.webBook

import org.junit.Assert.*
import org.junit.Test

class BookPlaylineParserTest {
    private data class Episode(val url: String, val route: String?)

    @Test fun groupsInterleavedRoutesWithoutLosingEpisodeOrder() {
        val chapters = listOf(Episode("a1", "A"), Episode("b1", "B"), Episode("a2", "A"))
        val routes = groupPlaylines(chapters) { it.route }
        assertEquals(listOf("A", "B"), routes.keys.toList())
        assertEquals(listOf("a1", "a2"), routes.getValue("A").map { it.url })
        assertEquals(chapters.toSet(), routes.values.flatten().toSet())
    }

    @Test fun handlesEmptyAndUnnamedCatalogs() {
        assertTrue(groupPlaylines(emptyList<Episode>()) { it.route }.isEmpty())
        val unnamed = listOf(Episode("a", null), Episode("b", ""))
        assertEquals(unnamed, groupPlaylines(unnamed) { it.route }.getValue("线路1"))
    }

    @Test fun retainsUnnamedEpisodesAndTrimsRouteNames() {
        val chapters = listOf(Episode("a", " A "), Episode("b", ""), Episode("c", "A"))
        val routes = groupPlaylines(chapters) { it.route }
        assertEquals(listOf("A", "线路1"), routes.keys.toList())
        assertEquals(listOf("a", "c"), routes.getValue("A").map { it.url })
    }
}
