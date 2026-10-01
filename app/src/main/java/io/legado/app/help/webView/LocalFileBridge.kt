package io.legado.app.help.webView

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * 本地文件安全桥接：网页 JS 通过 window.lycLocal.openLocalFile(requestId, acceptTypes)
 * 唤起系统文件选择器，选中后把文件内容以 Base64 回传 window.__onLocalFileResult({id, ok, name, contentBase64, error})
 * 安全设计：仅用户当场选文件并回传内容，不暴露路径；单文件上限 5MB；结果用 JSONObject 构造防注入。
 */
class LocalFileBridge(
    activity: AppCompatActivity,
    private val webViewProvider: () -> WebView?
) {
    companion object {
        const val NAME = "lycLocal"
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024L
    }

    private val activityRef: WeakReference<AppCompatActivity> = WeakReference(activity)

    // 单个文件选择器
    private val openDocument = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            callback(null, ok = false, error = "canceled")
        } else {
            readAndCallback(null, uri)
        }
    }

    @JavascriptInterface
    fun openLocalFile(requestId: String?, acceptTypes: String?) {
        val activity = activityRef.get() ?: return
        activity.runOnUiThread {
            val mimeTypes = parseAcceptTypes(acceptTypes)
            openDocument.launch(mimeTypes)
        }
    }

    private fun parseAcceptTypes(acceptTypes: String?): Array<String> {
        if (acceptTypes.isNullOrBlank()) return arrayOf("*/*")
        val list = acceptTypes.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return if (list.isEmpty()) arrayOf("*/*") else list.toTypedArray()
    }

    private fun readAndCallback(requestId: String?, uri: Uri) {
        Thread {
            try {
                val activity = activityRef.get() ?: return@Thread
                val resolver = activity.contentResolver
                val name = queryDisplayName(resolver, uri)
                resolver.openInputStream(uri)?.use { input ->
                    if (input.available().toLong() > MAX_FILE_SIZE) {
                        callback(requestId, ok = false, error = "too_large")
                        return@Thread
                    }
                    val bytes = input.readBytes()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    callback(requestId, ok = true, error = null, name = name, contentBase64 = base64)
                } ?: callback(requestId, ok = false, error = "read_failed")
            } catch (e: Exception) {
                callback(requestId, ok = false, error = e.message ?: "read_failed")
            }
        }.start()
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun callback(
        requestId: String?,
        ok: Boolean,
        error: String?,
        name: String? = null,
        contentBase64: String? = null
    ) {
        val webView = webViewProvider() ?: return
        val json = JSONObject().apply {
            requestId?.let { put("id", it) }
            put("ok", ok)
            name?.let { put("name", it) }
            contentBase64?.let { put("contentBase64", it) }
            error?.let { put("error", it) }
        }
        val js = "window.__onLocalFileResult && window.__onLocalFileResult(${json.toString()});"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
