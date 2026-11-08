// tests/RssFallbackParserStub.kt: 真实 RssFallbackParser 依赖 android.util.Xml，
// JVM 验证环境排除该类；此桩提供同名签名供 LegadoRssSource 编译，由测试注入固定 feed。
package com.lanyue.reader.core.engine

class RssFallbackParser {
    var feed: RssFeed? = null

    fun parse(xml: String): RssFeed? = feed

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
}
