package io.legado.app.help.config

import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import java.io.File

/**
 * 多源合并桥接 shim（D9）：仅覆盖 BookCoverImage/CoverDisplayResolver 消费的最小成员面。
 * 封面收藏（cover collection）特性整体落在 D10，届时以 m 原版覆盖本文件。
 */
object CoverCollectionManager {

    /**
     * D9 桥接：工作区无封面收藏特性，返回空串作为稳定的 remember key，
     * 表示"未启用收藏封面"。
     */
    fun selectionKey(): String = ""

    /** D9 桥接：无收藏封面特性，混合模式恒关闭。 */
    fun isMixedMode(): Boolean = false

    /** D9 桥接：无收藏封面库，恒返回 null = 沿用原有封面解析链。 */
    fun selectedCollectionCover(book: Book): String? = null

    /** D9 桥接：同上（搜索页入口）。 */
    fun selectedCollectionCover(searchBook: SearchBook): String? = null

    fun String?.isRealCoverPath(): Boolean {
        val value = this?.trim().orEmpty()
        if (value.isBlank() || value.equals("use_default_cover", ignoreCase = true)) {
            return false
        }
        val lowerValue = value.lowercase()
        return when {
            lowerValue.startsWith("http://") ||
                lowerValue.startsWith("https://") ||
                lowerValue.startsWith("content://") ||
                lowerValue.startsWith("android.resource://") ||
                lowerValue.startsWith("file:///android_asset/") -> true
            lowerValue.startsWith("file://") -> runCatching {
                File(Uri.parse(value).path.orEmpty()).isFile
            }.getOrDefault(false)
            File(value).isAbsolute -> File(value).isFile
            else -> true
        }
    }
}
