package com.lanyue.reader.source.video

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lanyue.reader.core.engine.SnifferRenderPool
import com.lanyue.reader.core.engine.SnifferSession

/**
 * 4.8 SnifferRenderPool 的 WebView 实现 —— 请求侧拦截即覆盖绝大多数视频站点
 * （JS hook 重数后续可在此文件扩展注入，响应侧 Content-Type 不使用 onLoadResource
 * 因其仅提供 URL 参数不含响应头，统一走 URL 后缀判定 + Content-Type 透传 Sniffer）。
 *
 * 观察链路（主线程）：
 *   WebViewClient.shouldInterceptRequest  —— 请求侧观察，对所有请求 URL 嗅探
 *   JS hook                              —— 见 TODO-DEV-5 扩展
 *
 * 约束（§4.8）：
 * - open() 必须在主线程调用；WebView 只接受主线程操作
 * - shouldInterceptRequest 返回 null 表示放行，页面正常加载
 * - 命中首个媒体 URL 后 Sniffer.sniff 自行 return，本池不中断页面
 * - 会话结束 navigateToBlankAndClear + close
 * - 与 JsRuntime / DomParser 池隔离（ADR-023）：采用独立 WebView 实例，不复用 JsRuntime 池
 */
class WebViewSnifferPool(
    private val appContext: Context
) : SnifferRenderPool {

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun open(
        url: String,
        cookies: String?,
        onSubresource: (url: String, contentType: String?, headers: Map<String, String>) -> String?
    ): SnifferSession? {
        val wv = WebView(appContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false

        // Cookie 注入：先写入系统 CookieManager（§4.6 单一真源），readCookie 已由 SystemCookieBridge 完成
        cookies?.takeIf { it.isNotEmpty() }?.let {
            CookieManager.getInstance().setCookie(url, it)
        }

        val client = object : WebViewClient() {
            /** 请求侧观察 —— Content-Type 传 null，由 Sniffer.isMedia 按 URL 后缀匹配 */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val reqUrl = request.url?.toString() ?: ""
                onSubresource(reqUrl, null, emptyMap())
                return null // 放行，不修改请求
            }
        }
        wv.webViewClient = client
        wv.loadUrl(url)

        return object : SnifferSession {
            override fun navigateToBlankAndClear() {
                main.post {
                    runCatching { wv.loadUrl("about:blank") }
                    runCatching { wv.clearHistory() }
                }
            }

            override fun close() {
                main.post {
                    runCatching { wv.destroy() }
                }
            }
        }
    }
}
