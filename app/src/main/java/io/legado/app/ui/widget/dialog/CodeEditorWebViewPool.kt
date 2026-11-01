package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Resources
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.AppColorScheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * 保活本地代码编辑器 WebView，避免每次打开弹窗都重新加载整页资源。
 */
object CodeEditorWebViewPool {

    interface Client {
        fun onEditorReady()
        fun onEditorBootError(message: String?)
        fun onEditorSave(text: String)
    }

    private const val EDITOR_URL = "file:///android_asset/web/code-editor/editor.html"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var webView: WebView? = null
    private var currentClient: Client? = null
    private var editorReady = false
    private var bootFailed = false
    private var lastBootError: String? = null

    // 编辑器文档当前承载的会话（客户端注入初始内容后登记）；
    // 同会话重挂载（旋转/重建）可据此跳过初始内容重发，保住未保存编辑
    private var contentSessionKey: String? = null

    private val jsBridge = object {
        @JavascriptInterface
        fun onEditorReady() {
            mainHandler.post {
                bootFailed = false
                lastBootError = null
                editorReady = true
                currentClient?.onEditorReady()
            }
        }

        @JavascriptInterface
        fun onEditorBootError(message: String?) {
            mainHandler.post {
                if (editorReady) return@post
                bootFailed = true
                lastBootError = message
                currentClient?.onEditorBootError(message)
            }
        }

        @JavascriptInterface
        fun save(text: String) {
            mainHandler.post {
                currentClient?.onEditorSave(text)
            }
        }
    }

    fun prewarm(context: Context) {
        runOnMain {
            ensureWebView(context)
        }
    }

    fun attach(container: ViewGroup, client: Client): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CodeEditorWebViewPool.attach must run on the main thread"
        }
        val activeClient = currentClient
        if (activeClient != null && activeClient !== client) {
            return false
        }
        val target = ensureWebView(container.context, forceRecreate = bootFailed)
        val parent = target.parent as? ViewGroup
        if (parent != null && parent !== container && currentClient !== client) {
            return false
        }
        currentClient = client
        (target.context as? MutableContextWrapper)?.baseContext = container.context
        if (parent !== container) {
            parent?.removeView(target)
            container.removeAllViews()
            container.addView(
                target,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        when {
            bootFailed -> client.onEditorBootError(lastBootError)
            editorReady -> client.onEditorReady()
        }
        return true
    }

    fun detach(client: Client) {
        runOnMain {
            if (currentClient !== client) return@runOnMain
            val target = webView
            val parent = target?.parent as? ViewGroup
            parent?.removeView(target)
            currentClient = null
            val baseContext = appContext ?: target?.context?.applicationContext
            if (target != null && baseContext != null) {
                (target.context as? MutableContextWrapper)?.baseContext = baseContext
            }
        }
    }

    fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)? = null) {
        runOnMain {
            val target = webView
            if (target == null) {
                resultCallback?.invoke(null)
                return@runOnMain
            }
            target.evaluateJavascript(script, resultCallback)
        }
    }

    /** 编辑器文档是否已承载 [sessionKey] 会话的内容（同会话重挂载可跳过初始注入） */
    fun isContentSession(sessionKey: String?): Boolean {
        return sessionKey != null && editorReady && !bootFailed &&
            contentSessionKey == sessionKey
    }

    /** 客户端注入初始内容成功后登记会话；detach 不清除，以便同会话重建时续用 */
    fun markContentSession(sessionKey: String?) {
        contentSessionKey = sessionKey
    }

    /**
     * 把应用主题注入编辑器（editor.html setAppTheme）：日夜 + AppColorScheme 调色 +
     * 系统字号缩放(editor.html "字号:自动" 据此换算)。
     * 幂等，ready 后调用即可；auto（跟随应用）模式立即生效。
     */
    fun applyAppTheme() {
        val scheme = AppColorScheme.current
        fun hex(color: Int) = String.format("#%06X", 0xFFFFFF and color)
        val colors = JSONObject()
            .put("bg", hex(scheme.background))
            .put("toolbarBg", hex(scheme.surfaceContainer))
            .put("gutterBg", hex(scheme.surfaceContainer))
            .put("gutterText", hex(scheme.onSurfaceVariant))
            .put("text", hex(scheme.onSurface))
            .put("border", hex(scheme.outlineVariant))
            .put("activeLine", hex(scheme.surfaceContainerLow))
            .put("accent", hex(scheme.primary))
            .put("selection", hex(scheme.primaryContainer))
        val fontScale = appContext?.resources?.configuration?.fontScale ?: 1f
        val payload = JSONObject()
            .put("dark", AppConfig.isNightTheme)
            .put("colors", colors)
            .put("fontScale", fontScale.toDouble())
        evaluateJavascript("window.setAppTheme && window.setAppTheme($payload);")
    }

    /**
     * 把底部安全区高度注入编辑器(editor.html setBottomInset,物理 px 入参、内部换算 css px):
     * 宿主让 WebView 全出血到屏幕底时传导航栏高度,页面在滚动内容里避让;
     * 固定边界宿主(弹窗)传 0 清除池内残留值。
     */
    fun applyBottomInset(insetPx: Int) {
        val density = appContext?.resources?.displayMetrics?.density
            ?: Resources.getSystem().displayMetrics.density
        val cssPx = (insetPx / density).toInt()
        evaluateJavascript("window.setBottomInset && window.setBottomInset($cssPx);")
    }

    /** 解包 evaluateJavascript 的 JSON 编码返回值为原始字符串 */
    fun decodeJsResult(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        return try {
            JSONArray("[$value]").getString(0)
        } catch (e: Exception) {
            value
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensureWebView(context: Context, forceRecreate: Boolean = false): WebView {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        val existing = webView
        if (existing != null && !forceRecreate) {
            return existing
        }
        if (existing != null) {
            destroyWebView(existing)
        }
        editorReady = false
        bootFailed = false
        lastBootError = null
        contentSessionKey = null
        return WebView(MutableContextWrapper(applicationContext)).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                textZoom = 100
                // 编辑器是本地 asset,缓存只会让 APK 更新后的资源不生效
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(jsBridge, "Android")
            loadUrl(EDITOR_URL)
            webView = this
        }
    }

    private fun destroyWebView(target: WebView) {
        (target.parent as? ViewGroup)?.removeView(target)
        target.removeJavascriptInterface("Android")
        target.stopLoading()
        target.loadUrl("about:blank")
        target.clearHistory()
        target.removeAllViews()
        target.destroy()
    }
}
