package io.legado.app.ui.main.homepage

import android.net.Uri
import io.legado.app.utils.LogUtils
import splitties.init.appCtx
import java.io.File

/**
 * HTML 自定义首页源存储：管理用户导入的 HTML 页面。
 *
 * 目录结构：filesDir/html_pages/<pageId>/page.html + meta.json
 * meta.json：{"id":"<pageId>","name":"<显示名>","type":"html"}
 *
 * pageId 取文件名（去除扩展名），显示名导入时可自定义。
 */
object HomeSourceStore {

    private const val TAG = "HomeSourceStore"

    data class HtmlPage(
        val id: String,
        val name: String,
    )

    private val pagesDir: File by lazy {
        File(appCtx.filesDir, "html_pages").apply { mkdirs() }
    }

    /** 列出全部 HTML 页面 */
    fun listHtml(): List<HtmlPage> {
        return pagesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val metaFile = File(dir, "meta.json")
                if (!metaFile.exists()) return@mapNotNull null
                try {
                    val obj = org.json.JSONObject(metaFile.readText())
                    HtmlPage(
                        id = obj.optString("id").ifBlank { dir.name },
                        name = obj.optString("name").ifBlank { dir.name },
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?: emptyList()
    }

    /**
     * 从 SAF Uri 导入 HTML 页面。
     * 返回是否成功。
     */
    fun importHtml(uri: Uri, displayName: String): Boolean {
        return try {
            val fileName = uri.lastPathSegment?.substringAfterLast('/')
                ?: displayName.ifBlank { null }
                ?: "page.html"
            val pageId = fileName.substringBeforeLast('.').ifBlank { fileName }
            val content = appCtx.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return false

            val pageDir = File(pagesDir, pageId)
            pageDir.mkdirs()
            File(pageDir, "page.html").writeText(content)
            File(pageDir, "meta.json").writeText(
                """{"id":"$pageId","name":"${displayName.ifBlank { pageId }}","type":"html"}"""
            )
            true
        } catch (e: Exception) {
            LogUtils.d(TAG, "importHtml failed: ${e.message}")
            false
        }
    }

    /** 读取 HTML 页面内容，失败返回 null */
    fun readHtml(pageId: String): String? {
        return try {
            val file = File(File(pagesDir, pageId), "page.html")
            if (!file.exists()) null else file.readText()
        } catch (e: Exception) {
            LogUtils.d(TAG, "readHtml failed: $pageId: ${e.message}")
            null
        }
    }

    /** 删除 HTML 页面 */
    fun deleteHtml(pageId: String): Boolean {
        return try {
            File(pagesDir, pageId).deleteRecursively()
            true
        } catch (e: Exception) {
            LogUtils.d(TAG, "deleteHtml failed: $pageId: ${e.message}")
            false
        }
    }

    // ---- JSON / TXT 视频源管理 ----

    /** JSON/TXT 源存储目录：filesDir/video_sources/<id>/meta.json + source.<ext> */
    private val videoSourcesDir: File by lazy {
        File(appCtx.filesDir, "video_sources").apply { mkdirs() }
    }

    data class VideoSourceMeta(
        val id: String,
        val name: String,
        val type: String, // "json" | "txt"
    )

    /** 列出全部 JSON/TXT 视频源 */
    fun listVideoSources(): List<VideoSourceMeta> {
        return videoSourcesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val metaFile = File(dir, "meta.json")
                if (!metaFile.exists()) return@mapNotNull null
                try {
                    val obj = org.json.JSONObject(metaFile.readText())
                    VideoSourceMeta(
                        id = obj.optString("id").ifBlank { dir.name },
                        name = obj.optString("name").ifBlank { dir.name },
                        type = obj.optString("type").ifBlank { "json" },
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?: emptyList()
    }

    /**
     * 从 SAF Uri 导入 JSON/TXT 源。type 为 "json" 或 "txt"。
     */
    fun importVideoSource(uri: Uri, displayName: String, type: String): Boolean {
        return try {
            val fileName = uri.lastPathSegment?.substringAfterLast('/')
                ?: displayName.ifBlank { null }
                ?: "source.$type"
            val sourceId = fileName.substringBeforeLast('.').ifBlank { fileName }
            val content = appCtx.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return false

            val dir = File(videoSourcesDir, sourceId)
            dir.mkdirs()
            File(dir, "source.$type").writeText(content)
            File(dir, "meta.json").writeText(
                """{"id":"$sourceId","name":"${displayName.ifBlank { sourceId }}","type":"$type"}"""
            )
            true
        } catch (e: Exception) {
            LogUtils.d(TAG, "importVideoSource failed: ${e.message}")
            false
        }
    }

    /** 读取源文件内容，失败返回 null */
    fun readVideoSource(sourceId: String, type: String): String? {
        return try {
            val file = File(File(videoSourcesDir, sourceId), "source.$type")
            if (!file.exists()) null else file.readText()
        } catch (e: Exception) {
            LogUtils.d(TAG, "readVideoSource failed: $sourceId: ${e.message}")
            null
        }
    }

    /** 删除 JSON/TXT 源 */
    fun deleteVideoSource(sourceId: String): Boolean {
        return try {
            File(videoSourcesDir, sourceId).deleteRecursively()
            true
        } catch (e: Exception) {
            LogUtils.d(TAG, "deleteVideoSource failed: $sourceId: ${e.message}")
            false
        }
    }
}
