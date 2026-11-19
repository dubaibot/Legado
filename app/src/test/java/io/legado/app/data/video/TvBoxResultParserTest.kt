package io.legado.app.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvBoxResultParserTest {

    @Test
    fun parsePlaySupportsNestedHeadersAndParseFlag() {
        val result = TvBoxResultParser.parsePlay(
            """{"playUrl":{"url":"https://cdn.example/video","header":"Referer=https://site.example&Cookie=a=b"},"parse":"0"}""",
            "fallback",
        )

        assertEquals("https://cdn.example/video", result.url)
        assertEquals("https://site.example", result.headers["Referer"])
        assertEquals("a=b", result.headers["Cookie"])
        assertEquals(0, result.parse)
    }

    @Test
    fun parsePlayLinesSupportsCmsRoutesAndDirectLines() {
        val cms = com.google.gson.JsonParser.parseString(
            """{"vod_play_from":"线路A${'$'}${'$'}${'$'}线路B","vod_play_url":"第一集${'$'}u1#第二集${'$'}u2${'$'}${'$'}${'$'}正片${'$'}u3"}"""
        ).asJsonObject
        val cmsLines = TvBoxResultParser.parsePlayLines(cms)
        assertEquals(3, cmsLines.size)
        assertEquals("线路B·正片", cmsLines.last().name)

        val direct = com.google.gson.JsonParser.parseString(
            """{"play_lines":[{"name":"在线播放","url":"u"}]}"""
        ).asJsonObject
        val directLines = TvBoxResultParser.parsePlayLines(direct)
        assertEquals(1, directLines.size)
        assertTrue(directLines[0].url == "u")
    }

    @Test
    fun parsePlayHandlesStringUrlAndMalformedResults() {
        assertEquals("https://cdn.example/a", TvBoxResultParser.parsePlay(
            """{"playUrl":"https://cdn.example/a","headers":{"Cookie":"a=b"}}""", "fallback"
        ).url)
        assertEquals("https://cdn.example/a", TvBoxResultParser.parsePlay(
            "\"https://cdn.example/a\"", "fallback"
        ).url)
        assertEquals("fallback", TvBoxResultParser.parsePlay("<html>error</html>", "fallback").url)
    }

    @Test
    fun nestedPlaybackFieldsOverrideRootDefaults() {
        val result = TvBoxResultParser.parsePlay(
            """{"url":"fallback","parse":1,"header":{"Cookie":"old"},"playUrl":{"url":"https://cdn.example/a","parse":0,"headers":{"Cookie":"new"}}}""",
            "original",
        )
        assertEquals(0, result.parse)
        assertEquals("new", result.headers["Cookie"])
    }

    @Test
    fun unnamedAndEmptyRoutesPreserveGroupPositionsAndDollarInUrls() {
        val vod = com.google.gson.JsonParser.parseString(
            """{"vod_play_from":"A${'$'}${'$'}${'$'}${'$'}${'$'}${'$'}C","vod_play_url":"one${'$'}https://cdn/a${'$'}token${'$'}${'$'}${'$'}${'$'}${'$'}${'$'}https://cdn/c"}"""
        ).asJsonObject
        val lines = TvBoxResultParser.parsePlayLines(vod)
        assertEquals(2, lines.size)
        assertEquals("https://cdn/a${'$'}token", lines[0].url)
        assertEquals("C·第2集", lines[1].name)
        val unnamed = com.google.gson.JsonParser.parseString(
            """{"vod_play_url":"https://cdn/a"}"""
        ).asJsonObject
        assertEquals("https://cdn/a", TvBoxResultParser.parsePlayLines(unnamed).single().url)
    }
}
