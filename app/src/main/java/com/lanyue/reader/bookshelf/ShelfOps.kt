package com.lanyue.reader.bookshelf

/**
 * 书架操作语义（纯逻辑，无 IO）。
 *
 * 与 [com.lanyue.reader.source.repo.SourceRepository] 同一套路：
 * 把「加书架 / 移出 / 分组 / 排序」这套有明确冲突语义的规则和 Room 事务解耦，
 * 让每条决策都能脱机单测；上层拿到最终状态一次落库。
 *
 * 冲突语义（加书架）：
 * - 书架没有 -> 新增，排序接在末尾；
 * - 已在书架 -> 只刷新书籍元信息（封面/简介/最新章/章节数），
 *   保留用户的分组与排序，绝不动阅读位置；
 * - 曾被移出书架 -> 恢复展示，分组与排序同样保留。
 *
 * 移出书架是软删除：ReadRecord 进度记录保留，重新加回不丢进度。
 */
class ShelfOps(initial: List<ShelfBook> = emptyList()) {

    /** 书架条目。独立于 Room 实体，纯 JVM 可加载。 */
    data class ShelfBook(
        val bookUrl: String,
        val name: String,
        val author: String? = null,
        val cover: String? = null,
        val intro: String? = null,
        val origin: String,
        val originName: String? = null,
        /** 0 小说 / 1 音频 / 2 追剧 / 3 订阅。 */
        val sourceType: Int = 0,
        val groupName: String = DEFAULT_GROUP,
        val latestChapter: String? = null,
        val totalChapter: Int = 0,
        val lastReadAt: Long = 0,
        val sortOrder: Int = 0,
        val inBookshelf: Boolean = true
    )

    /** 加书架结果。 */
    enum class AddOutcome { ADDED, UPDATED, RESTORED }

    data class AddReport(val outcome: AddOutcome, val bookUrl: String) {
        val message: String
            get() = when (outcome) {
                AddOutcome.ADDED -> "已加入书架"
                AddOutcome.UPDATED -> "已在书架，信息已更新"
                AddOutcome.RESTORED -> "已重新加入书架，分组与进度保留"
            }
    }

    /** 排序模式。 */
    enum class SortMode { MANUAL, RECENT, NAME }

    private val books = LinkedHashMap<String, ShelfBook>()

    init {
        initial.forEach { if (it.bookUrl.isNotBlank()) books[it.bookUrl] = it }
    }

    // ============================ 查询 ============================

    /** 书架展示列表：仅 inBookshelf=true，按当前模式排序。 */
    fun shelf(mode: SortMode = SortMode.MANUAL): List<ShelfBook> =
        visible().sortedFor(mode)

    fun visible(): List<ShelfBook> = books.values.filter { it.inBookshelf }

    /** 移出书架但仍有进度的书（「稍后再读」候选）。 */
    fun parked(): List<ShelfBook> = books.values.filterNot { it.inBookshelf }

    fun find(url: String): ShelfBook? = books[url]

    fun size(): Int = visible().size

    /** 分组 -> 书列表，仅书架内的书。 */
    fun byGroup(): Map<String, List<ShelfBook>> {
        val map = LinkedHashMap<String, MutableList<ShelfBook>>()
        visible().forEach { book ->
            map.getOrPut(book.groupName.ifBlank { DEFAULT_GROUP }) { ArrayList() }.add(book)
        }
        return map.mapValues { it.value.toList() }
    }

    /** 分组名列表（去重、按出现顺序）。 */
    fun groups(): List<String> = byGroup().keys.toList()

    /** 分组 -> 未读提示所需的统计。当前实现给「章节数合计」，供角标复用。 */
    fun groupSummary(): Map<String, Int> = byGroup().mapValues { it.value.size }

    // ============================ 加书架 / 移出 ============================

    /**
     * 加书架主入口。兼容从聚合搜索结果或书籍详情页两种来源：
     * 总章节数与最新章可能未知，传默认值即可，后续详情页补齐时会走 UPDATED 分支。
     */
    fun add(incoming: ShelfBook): AddReport {
        val url = incoming.bookUrl
        require(url.isNotBlank()) { "bookUrl 不得为空" }
        require(incoming.name.isNotBlank()) { "书名不得为空" }
        val existing = books[url]
        if (existing == null) {
            val cursor = (books.values.maxOfOrNull { it.sortOrder } ?: 0) + 1
            books[url] = incoming.copy(sortOrder = cursor, inBookshelf = true)
            return AddReport(AddOutcome.ADDED, url)
        }
        if (!existing.inBookshelf) {
            // 曾被移出：恢复展示，保留用户分组与排序，只刷新元信息
            books[url] = mergeMeta(existing, incoming).copy(inBookshelf = true)
            return AddReport(AddOutcome.RESTORED, url)
        }
        // 已在书架：刷新元信息，分组与排序不动
        val changed = hasMetaChanged(existing, incoming)
        if (changed) {
            books[url] = mergeMeta(existing, incoming)
        }
        return AddReport(AddOutcome.UPDATED, url)
    }

    /** 软移出：保留进度记录，书架列表不再显示。 */
    fun remove(url: String): Boolean {
        val cur = books[url] ?: return false
        if (!cur.inBookshelf) return false
        books[url] = cur.copy(inBookshelf = false)
        return true
    }

    // ============================ 分组 ============================

    /** 移动到分组；空串归入默认分组。 */
    fun moveToGroup(url: String, group: String): Boolean {
        val cur = books[url] ?: return false
        val target = group.ifBlank { DEFAULT_GROUP }
        if (cur.groupName == target) return false
        books[url] = cur.copy(groupName = target)
        return true
    }

    /** 重命名分组：组内全部书跟随改名。 */
    fun renameGroup(old: String, new: String): Int {
        val target = new.trim()
        if (target.isEmpty() || target == old) return 0
        var changed = 0
        books.keys.toList().forEach { url ->
            val cur = books[url] ?: return@forEach
            if (cur.groupName == old) {
                books[url] = cur.copy(groupName = target)
                changed++
            }
        }
        return changed
    }

    /** 删除分组：组内书回到默认分组（软规则，不删书）。 */
    fun removeGroup(group: String): Int {
        if (group == DEFAULT_GROUP) return 0
        return renameGroup(group, DEFAULT_GROUP)
    }

    // ============================ 排序 ============================

    /** 手动排序：给定顺序覆盖，未列出的书按原顺序接在后面。 */
    fun reorder(urls: List<String>): Boolean {
        val known = urls.filter { books[it]?.inBookshelf == true }
        if (known.isEmpty()) return false
        var cursor = 0
        known.forEach { url ->
            val cur = books[url] ?: return@forEach
            books[url] = cur.copy(sortOrder = cursor++)
        }
        visible().filter { it.bookUrl !in known.toSet() }
            .sortedBy { it.sortOrder }
            .forEach { cur -> books[cur.bookUrl] = cur.copy(sortOrder = cursor++) }
        return true
    }

    /** 标记读过：更新 lastReadAt，RECENT 排序依赖它。 */
    fun touch(url: String, at: Long = System.currentTimeMillis()): Boolean {
        val cur = books[url] ?: return false
        books[url] = cur.copy(lastReadAt = at)
        return true
    }

    private fun List<ShelfBook>.sortedFor(mode: SortMode): List<ShelfBook> = when (mode) {
        SortMode.MANUAL -> sortedWith(compareBy({ it.sortOrder }, { it.name }))
        SortMode.RECENT -> sortedWith(compareByDescending<ShelfBook> { it.lastReadAt }.thenBy { it.sortOrder })
        SortMode.NAME -> sortedWith(compareBy({ it.name }, { it.bookUrl }))
    }

    private fun hasMetaChanged(old: ShelfBook, new: ShelfBook): Boolean =
        old.name != new.name || old.cover != new.cover || old.intro != new.intro ||
            old.latestChapter != new.latestChapter || old.totalChapter != new.totalChapter ||
            old.author != new.author || old.originName != new.originName

    /** 刷新元信息，但保留用户域（分组/排序/最后阅读时间）。 */
    private fun mergeMeta(old: ShelfBook, new: ShelfBook): ShelfBook = old.copy(
        name = new.name.ifBlank { old.name },
        author = new.author ?: old.author,
        cover = new.cover ?: old.cover,
        intro = new.intro ?: old.intro,
        originName = new.originName ?: old.originName,
        latestChapter = new.latestChapter ?: old.latestChapter,
        totalChapter = if (new.totalChapter > 0) new.totalChapter else old.totalChapter
    )

    companion object {
        /** 默认分组名，与 BookEntity.groupName 默认值一致。 */
        const val DEFAULT_GROUP = "全部"
    }
}
