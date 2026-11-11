package com.lanyue.reader.feature.detail

/**
 * 详情页的展示逻辑（T13）。
 *
 * 不持有仓库、不读数据库：入参是已经取到的一本书的信息，出参是可直接渲染的视图。
 * 这样「简介折叠」「加入书架按钮该显示什么」「元信息怎么拼」都能在纯 JVM 里断言，
 * 界面只负责画。落库仍归仓储层，符合 T10 立下的「UI 不碰 DAO」。
 *
 * 字段刻意用原始类型而不是直接吃 BookEntity：详情页还要展示「搜索结果点进来但尚未入库」
 * 的书，那种情况拿不到实体。由调用方把实体或搜索项映射成 [DetailInput]。
 */
object BookDetailModel {

    /** 详情页的输入。 [bookUrl] 是唯一标识，加入书架时靠它定位。 */
    data class DetailInput(
        val bookUrl: String,
        val title: String,
        val author: String = "",
        val coverUrl: String = "",
        val intro: String = "",
        val originName: String = "",
        val latestChapterTitle: String = "",
        val totalChapters: Int = 0,
        val inBookshelf: Boolean = false,
    )

    /** 简介折叠后的一行。 */
    data class IntroPart(val text: String, val truncated: Boolean)

    /** 一行的元信息，label 为空表示该项无内容、界面应跳过。 */
    data class MetaItem(val label: String, val value: String)

    /** 加入书架动作的结果。changed=false 时 reason 说明为什么没动。 */
    data class ShelfAction(
        val input: DetailInput,
        val changed: Boolean,
        val reason: String,
        val toast: String,
    )

    /** 简介默认折叠长度。取 120 是权衡：手机上约三行，能看出是不是想看的书。 */
    const val INTRO_COLLAPSE_AT = 120

    // ============================ 字段规整 ============================

    /** 未知作者的显示口径，避免界面上出现空白或 "null"。 */
    fun authorText(author: String): String = author.trim().ifEmpty { "佚名" }

    /** 标题缺失时不能显示空白，否则列表里一行什么都没有。 */
    fun titleText(title: String): String = title.trim().ifEmpty { "未命名" }

    /** 有封面链接才算有封面；界面据此决定画占位图还是网络图。 */
    fun hasCover(input: DetailInput): Boolean = input.coverUrl.isNotBlank()

    /**
     * 简介折叠。
     *
     * 按字符数截断而不是按行：手机宽度随字号变化，按行在模型层算不准。
     * 截断时补省略号，让用户知道后面还有内容。
     */
    fun introSummary(intro: String, limit: Int = INTRO_COLLAPSE_AT): IntroPart {
        val clean = intro.trim()
        if (clean.isEmpty()) return IntroPart("", false)
        if (limit <= 0) return IntroPart("", clean.isNotEmpty())
        if (clean.length <= limit) return IntroPart(clean, false)
        return IntroPart(clean.take(limit) + "…", true)
    }

    /** 是否需要给用户一个「展开/收起」控件。 */
    fun canExpand(intro: String, limit: Int = INTRO_COLLAPSE_AT): Boolean =
        intro.trim().length > limit

    /** 展开态直接给全文。 */
    fun introFull(intro: String): String = intro.trim()

    // ============================ 元信息 ============================

    /**
     * 章节数文案。0 章不是「共 0 章」而是「章数未知」——还没拉目录时
     * 显示 0 会让人以为这本书是空的。
     */
    fun chaptersText(total: Int): String = if (total > 0) "共 $total 章" else "章数未知"

    /** 最新章节。空的时候不显示「最新章节：」这种半截话。 */
    fun latestText(latest: String): String =
        if (latest.isBlank()) "" else "最新：${latest.trim()}"

    /** 来源。空则整项跳过。 */
    fun originText(originName: String): String =
        if (originName.isBlank()) "" else "来源：${originName.trim()}"

    /**
     * 元信息行。只保留有内容的项，界面按顺序画即可，
     * 不必在 Compose 里写一串 if 判断空值。
     */
    fun metaItems(input: DetailInput): List<MetaItem> = listOf(
        MetaItem("作者", authorText(input.author)),
        MetaItem("来源", originText(input.originName)),
        MetaItem("章数", chaptersText(input.totalChapters)),
        MetaItem("最新", latestText(input.latestChapterTitle)),
    ).filter { it.value.isNotEmpty() }

    /** 拼成一行，用于列表卡片这类空间紧张的地方。 */
    fun metaLine(input: DetailInput): String =
        metaItems(input).joinToString(" · ") { it.value }

    // ============================ 加入书架 ============================

    /** 按钮文案要能表达当前状态，而不是永远写「加入书架」。 */
    fun shelfLabel(inBookshelf: Boolean): String =
        if (inBookshelf) "已在书架" else "加入书架"

    /** 已在书架时按钮不必再点，界面据此置灰。 */
    fun shelfEnabled(inBookshelf: Boolean): Boolean = !inBookshelf

    /**
     * 加入书架。
     *
     * 三个前置条件缺一不可：有唯一标识（否则落库后无法定位）、有标题（否则书架里
     * 是空行）、且当前不在书架。已在书架时不重复写入——重复写入会产生脏数据。
     */
    fun addToShelf(input: DetailInput): ShelfAction {
        if (input.inBookshelf) {
            return ShelfAction(input, false, "already-in-shelf", "已在书架")
        }
        if (input.bookUrl.isBlank()) {
            return ShelfAction(input, false, "no-url", "这本书缺少地址，无法加入书架")
        }
        if (input.title.isBlank()) {
            return ShelfAction(input, false, "no-title", "这本书缺少书名，无法加入书架")
        }
        return ShelfAction(
            input.copy(inBookshelf = true),
            true,
            "",
            "已加入书架",
        )
    }

    /** 移出书架。不在书架时同样静默不动。 */
    fun removeFromShelf(input: DetailInput): ShelfAction {
        if (!input.inBookshelf) {
            return ShelfAction(input, false, "not-in-shelf", "这本书不在书架里")
        }
        return ShelfAction(
            input.copy(inBookshelf = false),
            true,
            "",
            "已移出书架",
        )
    }

    /** 点按钮：按当前状态决定加还是移。 */
    fun toggleShelf(input: DetailInput): ShelfAction =
        if (input.inBookshelf) removeFromShelf(input) else addToShelf(input)

    // ============================ 阅读入口 ============================

    /**
     * 「开始阅读 / 继续阅读」文案。
     *
     * 有进度且不在开头才算「继续」，否则第一次点进来会显示成「继续阅读」，
     * 让人以为自己读过。
     */
    fun readButtonLabel(hasProgress: Boolean, atStart: Boolean): String =
        if (hasProgress && !atStart) "继续阅读" else "开始阅读"

    /** 没有目录时不能进阅读页，否则阅读器拿到空章列表会崩。 */
    fun canRead(totalChapters: Int): Boolean = totalChapters > 0

    fun readBlockedHint(totalChapters: Int): String =
        if (canRead(totalChapters)) "" else "还没有目录，请先刷新目录后再阅读"
}
