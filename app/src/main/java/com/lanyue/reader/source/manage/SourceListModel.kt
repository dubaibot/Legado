package com.lanyue.reader.source.manage

import com.lanyue.reader.source.repo.SourceRepository
import com.lanyue.reader.source.repo.SourceRepository.InstalledSource

/**
 * 书源管理列表的展示逻辑（T11）。
 *
 * 刻意不持仓库、不做持久化：入参是 [InstalledSource] 列表，出参是列表或行模型。
 * 这样筛选/排序/空态/启停都能在纯 JVM 里断言，而落库仍归 [SourceRepository] 管。
 * UI 只要把 `repo.all()` 传进来即可，换缓存策略时这里一行不用改。
 */
object SourceListModel {

    /** 列表筛选：全部 / 仅启用 / 仅停用。 */
    enum class Filter(val id: String, val label: String) {
        ALL("all", "全部"),
        ENABLED("enabled", "已启用"),
        DISABLED("disabled", "已停用"),
    }

    /** 排序方式。 */
    enum class Sort(val id: String, val label: String) {
        /** 用户自定义顺序（sortOrder），是默认值——导入不能打乱既有排列。 */
        DEFAULT("default", "默认"),
        NAME("name", "名称"),
        GROUP("group", "分组"),
        STATUS("status", "状态"),
    }

    /** 一次查询的全部条件。 */
    data class Query(
        val keyword: String = "",
        val filter: Filter = Filter.ALL,
        /** null 表示不限分组；空串等价于「未分组」。 */
        val group: String? = null,
        val sort: Sort = Sort.DEFAULT,
    )

    /** 界面上的一行。label 已在模型层算好，避免 UI 里散落 when 分支。 */
    data class Row(
        val source: InstalledSource,
        val groupLabel: String,
        val typeLabel: String,
        val statusLabel: String,
    )

    // ============================ 字段展示 ============================

    /** 分组名空着就归入「未分组」，与落库口径一致。 */
    fun groupLabelOf(source: InstalledSource): String =
        source.group.trim().ifEmpty { SourceRepository.DEFAULT_GROUP }

    /** 0 文本 / 1 音频 / 2 图片 / 3 文件；未知值不崩，如实显示。 */
    fun typeLabel(type: Int): String = when (type) {
        0 -> "文本"
        1 -> "音频"
        2 -> "图片"
        3 -> "文件"
        else -> "未知($type)"
    }

    fun statusLabel(enabled: Boolean): String = if (enabled) "已启用" else "已停用"

    // ============================ 查询管线 ============================

    /** 筛选 + 排序一次算完，界面只消费结果。 */
    fun query(sources: List<InstalledSource>, cond: Query): List<InstalledSource> {
        val k = cond.keyword.trim().lowercase()
        var list = sources
        if (k.isNotEmpty()) {
            list = list.filter {
                it.name.lowercase().contains(k) ||
                    it.url.lowercase().contains(k) ||
                    groupLabelOf(it).lowercase().contains(k)
            }
        }
        cond.group?.let { wanted ->
            val target = wanted.trim().ifEmpty { SourceRepository.DEFAULT_GROUP }
            list = list.filter { groupLabelOf(it) == target }
        }
        list = when (cond.filter) {
            Filter.ALL -> list
            Filter.ENABLED -> list.filter { it.enabled }
            Filter.DISABLED -> list.filter { !it.enabled }
        }
        return when (cond.sort) {
            Sort.DEFAULT -> list.sortedWith(compareBy({ it.sortOrder }, { it.name }))
            Sort.NAME -> list.sortedBy { it.name }
            Sort.GROUP -> list.sortedWith(compareBy({ groupLabelOf(it) }, { it.name }))
            // 启用优先，同状态内按名称，保证翻页时位置稳定
            Sort.STATUS -> list.sortedWith(compareBy({ !it.enabled }, { it.name }))
        }
    }

    fun rows(sources: List<InstalledSource>, cond: Query = Query()): List<Row> =
        query(sources, cond).map {
            Row(
                source = it,
                groupLabel = groupLabelOf(it),
                typeLabel = typeLabel(it.type),
                statusLabel = statusLabel(it.enabled),
            )
        }

    /** 分组下拉的选项：未分组排在最前，其余按名称。 */
    fun groupOptions(sources: List<InstalledSource>): List<String> {
        val labels = sources.map { groupLabelOf(it) }.distinct().sorted()
        val ungrouped = SourceRepository.DEFAULT_GROUP
        return if (labels.contains(ungrouped)) {
            listOf(ungrouped) + labels.filter { it != ungrouped }
        } else {
            labels
        }
    }

    // ============================ 空态 ============================

    /**
     * 空列表和「筛没了」是两种处境：前者要引导去导入，后者要提示放宽条件。
     * 返回 null 表示没有空态要显示。
     */
    fun emptyHint(all: List<InstalledSource>, cond: Query = Query(), shown: List<Any>? = null): String? {
        val visible = shown?.size ?: query(all, cond).size
        if (visible > 0) return null
        if (all.isEmpty()) return "还没有书源，点击右上角导入 JSON 文件或粘贴书源文本"
        if (cond.keyword.isNotBlank()) return "没有名称、地址或分组包含「${cond.keyword.trim()}」的书源"
        if (cond.group != null) return "「${cond.group.ifBlank { SourceRepository.DEFAULT_GROUP }}」分组下没有书源"
        return when (cond.filter) {
            Filter.ENABLED -> "当前没有已启用的书源"
            Filter.DISABLED -> "当前没有已停用的书源"
            Filter.ALL -> "没有符合筛选条件的书源"
        }
    }

    // ============================ 启停 ============================

    /**
     * 切换某一行的启用状态，返回新列表（原列表不动）。
     *
     * 地址不存在时原样返回——列表可能因并发同步而过期，此时静默不动比抛错合适。
     */
    fun withEnabled(
        sources: List<InstalledSource>,
        url: String,
        enabled: Boolean,
    ): List<InstalledSource> {
        var hit = false
        val next = sources.map {
            if (it.url == url && it.enabled != enabled) {
                hit = true
                it.copy(enabled = enabled)
            } else {
                it
            }
        }
        return if (hit) next else sources
    }

    /** 批量启停只动命中的那些，用于「全部启用 / 全部停用」。 */
    fun withAllEnabled(sources: List<InstalledSource>, enabled: Boolean): List<InstalledSource> {
        if (sources.all { it.enabled == enabled }) return sources
        return sources.map { if (it.enabled == enabled) it else it.copy(enabled = enabled) }
    }

    /** 状态汇总，供页面顶部展示。 */
    data class Counts(val total: Int, val enabled: Int, val disabled: Int, val groups: Int) {
        val summary: String get() = "共 $total 个书源 · 启用 $enabled · 停用 $disabled · $groups 个分组"
    }

    fun counts(sources: List<InstalledSource>): Counts = Counts(
        total = sources.size,
        enabled = sources.count { it.enabled },
        disabled = sources.count { !it.enabled },
        groups = sources.map { groupLabelOf(it) }.distinct().size,
    )
}
