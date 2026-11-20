package io.legado.app.help.book

import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.splitNotBlank
import splitties.init.appCtx

/**
 * 书源自定义分组与书架标签组的联动。
 *
 * 开启智能分组后，书籍加入书架（或换源）时，把书源分组名里已存在的标签组位按位或进
 * `books.group`。只新增位、不清理用户手动勾选的位；名称匹配不到的组直接跳过。
 */
object SourceGroupTagLinker {

    /**
     * @return true 表示 [book] 的分组位发生了变化
     */
    fun apply(book: Book, source: BookSource?): Boolean {
        if (!SmartTagConfig.isEnabled(appCtx)) return false
        val raw = source?.bookSourceGroup?.takeIf { it.isNotBlank() } ?: return false
        val mask = raw.splitNotBlank(AppPattern.splitGroupRegex).fold(0L) { acc, name ->
            val group = appDb.bookGroupDao.getByName(name) ?: return@fold acc
            if (group.groupId <= 0) acc else acc or group.groupId
        }
        if (mask == 0L) return false
        val merged = book.group or mask
        if (merged == book.group) return false
        book.group = merged
        return true
    }
}