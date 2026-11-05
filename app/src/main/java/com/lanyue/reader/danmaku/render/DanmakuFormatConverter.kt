package com.lanyue.reader.danmaku.render

import com.lanyue.reader.source.model.DanmakuEvent
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 4.10.8 格式转换器。
 * - B 站 XML / AcFun XML -> DanmakuEvent
 * - p 格式：出现时间(秒,浮点),弹幕模式,颜色,[来源]用户ID；模式 1=滚动 2=底部 3=顶部
 */
object DanmakuFormatConverter {

    /**
     * B 站 / AcFun XML。
     * <d p="time,mode,color,uid,...">text</d>
     */
    fun fromXml(xml: String): List<DanmakuEvent> = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val out = mutableListOf<DanmakuEvent>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("d", ignoreCase = true)) {
                val p = parser.getAttributeValue(null, "p")
                val text = parser.nextText()
                if (p != null) parseP(p, text)?.let { out.add(it) }
            }
            event = parser.next()
        }
        out
    }.getOrDefault(emptyList())

    /** p 格式解析。 */
    fun parseP(p: String, text: String): DanmakuEvent? {
        val parts = p.split(",")
        if (parts.size < 3) return null
        val seconds = parts[0].toDoubleOrNull() ?: return null
        val mode = normalizeMode(parts[1].toIntOrNull() ?: 1)
        val color = parts[2].toLongOrNull()?.toInt() ?: 0xFFFFFF
        return DanmakuEvent(
            timeMs = (seconds * 1000).toLong(),
            mode = mode,
            color = color,
            text = text
        )
    }

    /** B 站 mode：1/2/3 滚动, 4 底部, 5 顶部 -> 内部 1 滚动 / 2 底部 / 3 顶部。 */
    private fun normalizeMode(raw: Int): Int = when (raw) {
        4 -> 2
        5 -> 3
        in 1..3 -> 1
        else -> 1
    }
}
