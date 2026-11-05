package io.legado.app.ui.book.read.web

object WebReadHtmlBuilder {

    private const val USEHTML_PREFIX = "<usehtml>"
    private const val USEHTML_SUFFIX = "</usehtml>"

    fun escape(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun unwrapUseHtml(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith(USEHTML_PREFIX) && trimmed.endsWith(USEHTML_SUFFIX)) {
            val inner = trimmed.substring(USEHTML_PREFIX.length, trimmed.length - USEHTML_SUFFIX.length)
            return inner.trim()
        }
        return text
    }

    fun buildBody(title: String, paragraphs: List<String>, style: WebReadStyle): String {
        val sb = StringBuilder()
        if (!style.hideTitle && title.isNotEmpty()) {
            val titleAlign = when (style.titleMode) {
                1 -> "center"
                3 -> "right"
                else -> "left"
            }
            sb.append("<h1 class=\"title\" style=\"text-align:$titleAlign;\">")
                .append(escape(title))
                .append("</h1>")
        }
        for (paragraph in paragraphs) {
            if (paragraph.isEmpty()) continue
            val text = if (paragraph.trim().startsWith(USEHTML_PREFIX)) {
                unwrapUseHtml(paragraph)
            } else {
                escape(paragraph)
            }
            sb.append("<p>").append(text).append("</p>")
        }
        return sb.toString()
    }

    fun buildDocument(title: String, paragraphs: List<String>, style: WebReadStyle): String {
        val css = buildCss(style)
        val js = buildJs()
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>$css</style></head><body>" +
            buildBody(title, paragraphs, style) +
            "<script>$js</script></body></html>"
    }

    private fun buildJs(): String {
        return """
window.addEventListener('load', function() { window.legadoRead.onPageLoaded(); });
document.addEventListener('scroll', function() {
  var el = document.documentElement;
  var max = el.scrollHeight - el.clientHeight;
  var ratio = max > 0 ? el.scrollTop / max : 0;
  window.legadoRead.onScrollRatio(ratio);
});
document.addEventListener('click', function(e) {
  var x = e.clientX / window.innerWidth;
  var y = e.clientY / window.innerHeight;
  var zone;
  if (x < 0.33) {
    if (y < 0.33) zone = 0;
    else if (y < 0.66) zone = 1;
    else zone = 2;
  } else if (x < 0.66) {
    if (y < 0.33) zone = 3;
    else if (y < 0.66) zone = 4;
    else zone = 5;
  } else {
    if (y < 0.33) zone = 6;
    else if (y < 0.66) zone = 7;
    else zone = 8;
  }
  window.legadoRead.onClickZone(zone);
});
        """.trimIndent()
    }

    fun buildCss(style: WebReadStyle): String {
        val fontFamily = style.fontFamily.ifEmpty { "serif" }
        return """
body {
  background-color: ${style.bgColor};
  color: ${style.textColor};
  font-size: ${style.textSize}px;
  line-height: ${style.lineHeight};
  letter-spacing: ${style.letterSpacing}em;
  font-weight: ${style.textWeight};
  font-family: $fontFamily;
  padding: ${style.paddingTop}px ${style.paddingRight}px ${style.paddingBottom}px ${style.paddingLeft}px;
  text-align: ${style.textAlign};
}
h1.title {
  font-size: ${style.titleSize}px;
  font-weight: ${style.titleWeight};
  margin: 0 0 12px 0;
}
p {
  margin: 0 0 ${style.paragraphSpacing}px 0;
  text-indent: ${style.textIndent};
}
p img {
  max-width: 100%;
  height: auto;
}
        """.trimIndent()
    }
}

data class WebReadStyle(
    val textColor: String = "#3E3D3B",
    val bgColor: String = "#EEEEEE",
    val textSize: Int = 18,
    val lineHeight: Float = 1.5f,
    val letterSpacing: Float = 0.0f,
    val paragraphSpacing: Int = 8,
    val paddingTop: Int = 16,
    val paddingRight: Int = 16,
    val paddingBottom: Int = 16,
    val paddingLeft: Int = 16,
    val textAlign: String = "justify",
    val textIndent: String = "2em",
    val fontFamily: String = "",
    val textWeight: Int = 400,
    val titleWeight: Int = 700,
    val titleSize: Int = 20,
    val titleMode: Int = 0,
    val hideTitle: Boolean = false
)
