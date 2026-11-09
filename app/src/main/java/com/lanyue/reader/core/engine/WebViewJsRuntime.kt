package com.lanyue.reader.core.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 4.5 JsRuntime 的 Android 真实实现：单 WebView 常驻，evaluateJavascript 直跑。
 *
 * 与 LanyueBrowserEngine（池化门面）的关系：池化机制要求真实 WebView 实例
 * 参与 dispatch 协议；本实现先把「能跑通」立起来——单实例单任务、
 * 超时、崩溃重建，池化扩容是后续轮次的事。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewJsRuntime(
    private val appContext: Context,
    private val timeoutMs: Long = 15_000L
) : JsRuntime {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    override suspend fun eval(
        script: String,
        mode: JsExecMode,
        tier: PoolTier,
        traceId: String,
        dispatch: (taskId: String, script: String) -> Boolean
    ): String {
        // evaluateJavascript 要求主线程；回调也在主线程
        val raw = withContext(Dispatchers.Main) {
            val wv = obtain()
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    try {
                        wv.evaluateJavascript(script) { result ->
                            if (cont.isActive) cont.resume(result)
                        }
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } ?: throw EngineException.timeout("js eval 超时（${timeoutMs}ms）")
        return decode(raw)
    }

    /** 4.13.5 渲染进程崩溃：销毁实例，下次 eval 时重建。 */
    override fun onRenderProcessGone(error: Throwable) {
        main.post {
            webView?.destroy()
            webView = null
        }
    }

    override suspend fun drainPending(): Int = 0

    /** 主线程懒创建；about:blank 空页承载脚本执行。 */
    private fun obtain(): WebView {
        webView?.let { return it }
        val wv = WebView(appContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
        webView = wv
        return wv
    }

    /** evaluateJavascript 返回 JSON 编码的结果字符串；还原成裸值。 */
    private fun decode(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        val t = raw.trim()
        if (t.startsWith("\"")) {
            return try {
                JsonParser.parseString(t).asString
            } catch (e: Throwable) {
                t
            }
        }
        return t
    }
}

/** 4.6 Cookie：系统 CookieManager 唯一真源。 */
object SystemCookieBridge : CookieBridge {
    override fun read(url: String): String? = CookieManager.getInstance().getCookie(url)

    override fun write(url: String, setCookie: String) {
        CookieManager.getInstance().setCookie(url, setCookie)
    }

    override fun markInjected(url: String, cookie: String) {
        // 4.6 注入标记仅用于节流；系统 CookieManager 自身幂等，无需额外记录
    }

    override fun flush() {
        CookieManager.getInstance().flush()
    }
}
