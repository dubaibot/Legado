package com.lanyue.reader.core.engine

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * 4.7 / ADR-016 RSS 兜底解析器。
 * 订阅源 ruleArticles 为空时走这里，内置 XmlPullParser，不依赖规则引擎。
 */
class RssFallbackParser {

    data class RssItem(
        val title: String?,
        val link: String?,
        val pubDate: String?,
        val description: String?,
        val image: String?
    )

    data class RssFeed(
        val title: String?,
        val items: List<RssItem>
    )

    fun parse(xml: String): RssFeed? = runCatching {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val items = mutableListOf<RssItem>()
        var feedTitle: String? = null

        var inItem = false
        var curTitle: String? = null
        var curLink: String? = null
        var curPub: String? = null
        var curDesc: String? = null
        var curImage: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "item" || name == "entry" -> {
                        inItem = true
                        curTitle = null; curLink = null; curPub = null; curDesc = null; curImage = null
                    }
                    name == "title" && !inItem -> feedTitle = readText(parser)
                    name == "title" -> curTitle = readText(parser)
                    name == "link" -> curLink = parser.getAttributeValue(null, "href") ?: readText(parser)
                    name == "pubdate" || name == "published" || name == "updated" -> curPub = readText(parser)
                    name == "description" || name == "summary" || name == "content" -> curDesc = readText(parser)
                    name == "enclosure" -> curImage = parser.getAttributeValue(null, "url")
                }
                XmlPullParser.END_TAG -> if (name == "item" || name == "entry") {
                    inItem = false
                    items += RssItem(curTitle, curLink, curPub, curDesc, curImage)
                }
            }
            event = parser.next()
        }
        RssFeed(feedTitle, items)
    }.getOrNull()

    private fun readText(parser: XmlPullParser): String? {
        var depth = 1
        val sb = StringBuilder()
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim().ifEmpty { null }
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }
}
