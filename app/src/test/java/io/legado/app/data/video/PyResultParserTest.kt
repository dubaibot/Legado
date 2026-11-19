package io.legado.app.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PyResultParserTest {

    @Test
    fun homePreservesCategoryFiltersAndEmptyAllValue() {
        val (categories, videos) = PyResultParser.parseHome(
            """{"class":[{"type_id":1,"type_name":"Movie"}],"filters":{"1":[{"key":"class","name":"Genre","value":[{"n":"All","v":""},{"n":"Action","v":"action"}]},{"key":"bad","value":[]}]},"list":[{"vod_id":"v","vod_name":"Recommended"}]}"""
        )
        assertEquals("1", categories.single().id)
        assertEquals(listOf(VideoFilterValue("All", ""), VideoFilterValue("Action", "action")), categories.single().filters.single().values)
        assertEquals("Recommended", videos.single().title)
    }

    @Test
    fun homeWithoutFiltersKeepsCategories() {
        val (categories, _) = PyResultParser.parseHome("""{"categories":[{"id":"1","name":"Movie"}]}""")
        assertTrue(categories.single().filters.isEmpty())
    }

    @Test
    fun detailSupportsListVideoAndDetailWrappers() {
        val video = """{"vod_id":"v1","vod_name":"Movie","play_urls":[{"name":"Episode","url":"id1","flag":"route"}]}"""
        listOf(video, """{"list":[$video]}""", """{"video":$video}""", """{"detail":$video}""")
            .forEach { json ->
                val detail = PyResultParser.parseDetail(json, "fallback")
                assertEquals("v1", detail.id)
                assertEquals("Movie", detail.title)
                assertEquals(PlayLine("Episode", "id1", flag = "route"), detail.playLines.single())
            }
    }

    @Test
    fun nestedEpisodesInheritFlagAndHeadersAndAllowOverrides() {
        val detail = PyResultParser.parseDetail(
            """{"play_lines":[{"name":"Route A","flag":"a","headers":{"Referer":"site","Cookie":"old"},"episodes":[{"name":"One","url":"id1"},{"title":"Two","link":"id2","flag":"b","header":{"Cookie":"new"}}]},{"name":"Flat","url":"id3","flag":"c"}]}""",
            "fallback",
        )

        assertEquals(3, detail.playLines.size)
        assertEquals("Route A·One", detail.playLines[0].name)
        assertEquals("a", detail.playLines[0].flag)
        assertEquals(mapOf("Referer" to "site", "Cookie" to "old"), detail.playLines[0].headers)
        assertEquals("Route A·Two", detail.playLines[1].name)
        assertEquals("b", detail.playLines[1].flag)
        assertEquals(mapOf("Referer" to "site", "Cookie" to "new"), detail.playLines[1].headers)
        assertEquals(PlayLine("Flat", "id3", flag = "c"), detail.playLines[2])
    }

    @Test
    fun nestedPlayUrlsSkipInvalidEpisodes() {
        val detail = PyResultParser.parseDetail(
            """{"play_urls":[null,{"flag":"route","episodes":[null,{},"bad",{"url":"id1"}]}]}""",
            "fallback",
        )

        assertEquals(1, detail.playLines.size)
        assertEquals("id1", detail.playLines.single().url)
        assertEquals("route", detail.playLines.single().flag)
    }

    @Test
    fun cmsKeepsEmptyGroupPositionsFlagsAndDollarInUrl() {
        val detail = PyResultParser.parseDetail(
            """{"list":[{"vod_play_from":"a${'$'}${'$'}${'$'}${'$'}${'$'}${'$'}c","vod_play_url":"One${'$'}https://cdn/a${'$'}token${'$'}${'$'}${'$'}${'$'}${'$'}${'$'}Three${'$'}id3"}]}""",
            "fallback",
        )

        assertEquals(2, detail.playLines.size)
        assertEquals("https://cdn/a${'$'}token", detail.playLines[0].url)
        assertEquals("a", detail.playLines[0].flag)
        assertEquals("c·Three", detail.playLines[1].name)
        assertEquals("c", detail.playLines[1].flag)
    }

    @Test
    fun cmsWithoutRouteNameUsesEmptyFlag() {
        val line = PyResultParser.parseDetail(
            """{"vod_play_url":"https://cdn/a"}""", "fallback"
        ).playLines.single()

        assertEquals("https://cdn/a", line.url)
        assertEquals("", line.flag)
    }

    @Test
    fun emptyListFallsBackToWrappedDetail() {
        val detail = PyResultParser.parseDetail(
            """{"list":[],"video":{"title":"Movie","play_lines":[]}}""", "fallback"
        )

        assertEquals("fallback", detail.id)
        assertEquals("Movie", detail.title)
        assertTrue(detail.playLines.isEmpty())
    }

    @Test
    fun playPreservesNestedHeadersAndParse() {
        val result = PyResultParser.parsePlay(
            """{"parse":1,"headers":{"Cookie":"old","Referer":"site"},"playUrl":{"url":"https://cdn/a","parse":"0","header":"Cookie=new&User-Agent=player"}}""",
            "fallback",
        )

        assertEquals("https://cdn/a", result.url)
        assertEquals(0, result.parse)
        assertEquals(mapOf("Cookie" to "new", "Referer" to "site", "User-Agent" to "player"), result.headers)
    }

    @Test
    fun playSupportsPlainAndJsonUrlsAndFallback() {
        listOf("https://cdn/a", "\"https://cdn/a\"").forEach { raw ->
            assertEquals("https://cdn/a", PyResultParser.parsePlay(raw, "fallback").url)
        }
        listOf("", "null", "{}", "<html>error</html>").forEach { raw ->
            assertEquals("fallback", PyResultParser.parsePlay(raw, "fallback").url)
        }
        val result = PyResultParser.parsePlay(
            """{"url":"https://cdn/a","parse":1,"header":"{\"Cookie\":\"a=b\"}"}""",
            "fallback",
        )
        assertEquals(1, result.parse)
        assertEquals("a=b", result.headers["Cookie"])
    }
}
