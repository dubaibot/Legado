package io.legado.app.data.video

import com.google.gson.JsonParser
import io.legado.app.utils.LogUtils
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 弹幕解析器：支持 XML（B站标准格式）与 JSON 两种格式。
 *
 * XML 格式：
 * ```xml
 * <i>
 * <d p="1.5,0,25,16777215">弹幕内容</d>
 * </i>
 * ```
 * p 属性：时间(秒),模式,字号,颜色(十进制色值)
 * 模式：1-3=滚动, 4=底部, 5=顶部
 *
 * JSON 格式：
 * ```json
 * [{"time":1.5,"text":"弹幕","color":16777215,"mode":0}]
 * ```
 */
object DanmakuParser {

    private const val TAG = "DanmakuParser"

    fun parse(body: String): DanmakuList {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return DanmakuList()
        return if (trimmed.startsWith("<")) {
            parseXml(trimmed)
        } else {
            parseJson(trimmed)
        }
    }

    private fun parseXml(xml: String): DanmakuList {
        val items = mutableListOf<DanmakuItem>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "d") {
                    val p = parser.getAttributeValue(null, "p") ?: ""
                    val text = parser.nextText()
                    val item = parseDAttribute(p, text)
                    if (item != null) items.add(item)
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "parse danmaku xml failed: ${e.message}")
        }
        return DanmakuList(items.sortedBy { it.time })
    }

    private fun parseJson(json: String): DanmakuList {
        val items = mutableListOf<DanmakuItem>()
        try {
            val root = JsonParser.parseString(json)
            val arr = if (root.isJsonArray) root.asJsonArray
            else root.asJsonObject.getAsJsonArray("items") ?: return DanmakuList()
            arr.forEach { el ->
                try {
                    val o = el.asJsonObject
                    items.add(
                        DanmakuItem(
                            time = o.get("time")?.asFloat ?: 0f,
                            text = o.get("text")?.asString ?: return@forEach,
                            color = (o.get("color")?.takeIf { !it.isJsonNull }?.asInt
                                ?: 0xFFFFFF).toInt(),
                            mode = o.get("mode")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                        )
                    )
                } catch (e: Exception) {
                    // skip invalid item
                }
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "parse danmaku json failed: ${e.message}")
        }
        return DanmakuList(items.sortedBy { it.time })
    }

    /** p 属性格式：time,mode,fontSize,color */
    private fun parseDAttribute(p: String, text: String): DanmakuItem? {
        if (text.isBlank()) return null
        return try {
            val parts = p.split(",")
            val time = parts.getOrNull(0)?.toFloatOrNull() ?: return null
            val mode = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val color = parts.getOrNull(3)?.toLongOrNull()?.toInt() ?: 0xFFFFFF
            // B站模式映射：1-3=滚动, 4=底部, 5=顶部 → 统一 0=滚动,1=顶部,4=底部
            val normalizedMode = when (mode) {
                4 -> 4
                5 -> 1
                else -> 0
            }
            DanmakuItem(time = time, text = text, color = color, mode = normalizedMode)
        } catch (e: Exception) {
            null
        }
    }
}
