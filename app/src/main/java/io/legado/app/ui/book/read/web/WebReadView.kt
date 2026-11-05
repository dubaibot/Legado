package io.legado.app.ui.book.read.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.ReadBook
import io.legado.app.utils.argbHexString
import io.legado.app.utils.dpToPx
import io.legado.app.utils.spToPx

class WebReadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface CallBack {
        fun showActionMenu()
        fun onPageChanged()
        fun onContentLoadFinish()
    }

    private var pooledWebView: PooledWebView? = null
    private var webView: WebView? = null
    private var callBack: CallBack? = null
    private var loadedChapterIndex = -1
    private var currentScrollRatio = 0.0
    private var contentChanged = false

    fun attach(callBack: CallBack) {
        this.callBack = callBack
        if (webView != null) return
        val pooled = WebViewPool.acquire(context)
        pooledWebView = pooled
        val web = pooled.realWebView
        webView = web
        web.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (contentChanged) {
                    contentChanged = false
                    callBack?.onContentLoadFinish()
                }
            }
        }
        web.addJavascriptInterface(WebReadJsInterface(), "legadoRead")
    }

    fun detach() {
        if (pooledWebView == null) return
        val pooled = pooledWebView
        webView = null
        pooled?.let { WebViewPool.release(it) }
        pooledWebView = null
        callBack = null
        loadedChapterIndex = -1
        currentScrollRatio = 0.0
    }

    fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        val web = webView ?: return
        val textChapter = ReadBook.curTextChapter ?: return
        if (relativePosition == 0 && loadedChapterIndex != ReadBook.durChapterIndex) {
            loadedChapterIndex = ReadBook.durChapterIndex
            contentChanged = true
            val style = buildStyle()
            val paragraphs = textChapter.paragraphs.map { it.text }
            val html = WebReadHtmlBuilder.buildDocument(textChapter.title, paragraphs, style)
            web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
        } else if (resetPageOffset) {
            web.evaluateJavascript("window.scrollTo(0, 0)", null)
        }
    }

    fun upStyle() {
        val web = webView ?: return
        val style = buildStyle()
        val css = WebReadHtmlBuilder.buildCss(style)
        val escapedCss = css.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
        web.evaluateJavascript(
            "document.querySelector('style').textContent = `$escapedCss`",
            null
        )
    }

    fun pageNext() {
        val web = webView ?: return
        web.evaluateJavascript(
            "window.scrollBy(0, window.innerHeight)",
            null
        )
    }

    fun pagePrev() {
        val web = webView ?: return
        web.evaluateJavascript(
            "window.scrollBy(0, -window.innerHeight)",
            null
        )
    }

    fun getReadPos(): Int {
        val textChapter = ReadBook.curTextChapter ?: return 0
        return (currentScrollRatio * textChapter.getContent().length).toInt()
    }

    fun onDestroy() {
        detach()
    }

    private fun buildStyle(): WebReadStyle {
        val config = ReadBookConfig
        val textSizePx = config.textSize.spToPx()
        val titleSizePx = if (config.titleSize > 0) config.titleSize.spToPx() else textSizePx
        return WebReadStyle(
            textColor = config.textColor.argbHexString,
            bgColor = config.durConfig.curBgStr(),
            textSize = textSizePx,
            lineHeight = 1.0f + config.lineSpacingExtra / 20f,
            letterSpacing = config.letterSpacing,
            paragraphSpacing = config.paragraphSpacing.dpToPx(),
            paddingTop = config.paddingTop.dpToPx(),
            paddingRight = config.paddingRight.dpToPx(),
            paddingBottom = config.paddingBottom.dpToPx(),
            paddingLeft = config.paddingLeft.dpToPx(),
            textAlign = if (config.textFullJustify) "justify" else "left",
            textIndent = config.paragraphIndent,
            fontFamily = config.textFont.ifEmpty { "serif" },
            textWeight = config.getTextBoldWeight(),
            titleWeight = config.getTitleBoldWeight(),
            titleSize = titleSizePx,
            titleMode = config.titleMode,
            hideTitle = config.titleMode == 2
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    inner class WebReadJsInterface {
        @JavascriptInterface
        fun onScrollRatio(ratio: Double) {
            currentScrollRatio = ratio
            callBack?.onPageChanged()
        }

        @JavascriptInterface
        fun onClickZone(zone: Int) {
            when (zone) {
                4 -> callBack?.showActionMenu()
                6, 7, 8 -> pageNext()
                0, 1, 2 -> pagePrev()
            }
        }

        @JavascriptInterface
        fun onPageLoaded() {
            val durPos = ReadBook.durChapterPos
            val textChapter = ReadBook.curTextChapter ?: return
            val totalLength = textChapter.getContent().length
            if (totalLength > 0 && durPos > 0) {
                val ratio = (durPos.toFloat() / totalLength).coerceIn(0f, 1f)
                webView?.evaluateJavascript(
                    "window.scrollTo(0, (document.documentElement.scrollHeight - window.innerHeight) * $ratio)",
                    null
                )
            }
        }
    }
}
