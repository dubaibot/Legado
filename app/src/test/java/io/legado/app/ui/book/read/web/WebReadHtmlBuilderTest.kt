package io.legado.app.ui.book.read.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebReadHtmlBuilderTest {

    @Test
    fun escape() {
        assertEquals("&lt;b&gt;", WebReadHtmlBuilder.escape("<b>"))
        assertEquals("&amp;&lt;&gt;&quot;&#39;", WebReadHtmlBuilder.escape("&<>\"'"))
        assertEquals("plain", WebReadHtmlBuilder.escape("plain"))
        assertEquals("", WebReadHtmlBuilder.escape(""))
    }

    @Test
    fun unwrapUseHtml() {
        assertEquals("<img src=\"x\">", WebReadHtmlBuilder.unwrapUseHtml("<usehtml><img src=\"x\"></usehtml>"))
        assertEquals("keep", WebReadHtmlBuilder.unwrapUseHtml("keep"))
    }

    @Test
    fun buildBodyEscapesParagraphs() {
        val style = WebReadStyle()
        val html = WebReadHtmlBuilder.buildBody("T", listOf("a<b>c"), style)
        assertTrue(html.contains("a&lt;b&gt;c"))
        assertTrue(html.contains("<h1 class=\"title\""))
        assertTrue(html.contains("T"))
    }

    @Test
    fun buildBodyHidesTitleWhenRequested() {
        val style = WebReadStyle(hideTitle = true)
        val html = WebReadHtmlBuilder.buildBody("T", listOf("p"), style)
        assertFalse(html.contains("<h1"))
        assertTrue(html.contains("<p>p</p>"))
    }

    @Test
    fun buildDocumentContainsHeadAndStyle() {
        val style = WebReadStyle(bgColor = "#000000", textColor = "#FFFFFF", textSize = 20)
        val doc = WebReadHtmlBuilder.buildDocument("T", listOf("p"), style)
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("background-color: #000000"))
        assertTrue(doc.contains("color: #FFFFFF"))
        assertTrue(doc.contains("font-size: 20px"))
        assertTrue(doc.contains("<p>p</p>"))
    }

    @Test
    fun buildDocumentTitleAlignment() {
        val style = WebReadStyle(titleMode = 1)
        val doc = WebReadHtmlBuilder.buildDocument("T", emptyList(), style)
        assertTrue(doc.contains("text-align:center"))
    }

    @Test
    fun buildDocumentSkipsEmptyParagraphs() {
        val style = WebReadStyle()
        val html = WebReadHtmlBuilder.buildBody("T", listOf("", "x", ""), style)
        assertFalse(html.contains("<p></p>"))
        assertTrue(html.contains("<p>x</p>"))
    }

    @Test
    fun buildDocumentInjectsUseHtmlRaw() {
        val style = WebReadStyle()
        val html = WebReadHtmlBuilder.buildBody("T", listOf("<usehtml><b>raw</b></usehtml>"), style)
        assertTrue(html.contains("<b>raw</b>"))
    }

    @Test
    fun buildCssContainsFontFamily() {
        val style = WebReadStyle(fontFamily = "serif")
        val css = WebReadHtmlBuilder.buildCss(style)
        assertTrue(css.contains("font-family: serif"))
    }

    @Test
    fun buildCssDefaultFontFamily() {
        val style = WebReadStyle()
        val css = WebReadHtmlBuilder.buildCss(style)
        assertTrue(css.contains("font-family: serif"))
    }
}
