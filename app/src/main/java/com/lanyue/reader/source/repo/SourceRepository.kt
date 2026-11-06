package com.lanyue.reader.source.repo

import com.lanyue.reader.source.model.SourceValidator

/**
 * 已安装书源的内存仓库（纯逻辑，无 IO）。
 *
 * 为什么不直接写数据库：
 * 1. 书源的「安装 / 升级 / 分组 / 排序 / 删除分组」是一套有明确冲突语义的业务规则，
 *    把它和 Room 事务混在一起会导致规则无法单测；
 * 2. 导入 200 个源时的合并决策必须可重放、可审查；
 * 3. 上层只需要「给我最终列表」，由它负责一次事务落库，
 *    避免每个条目一次写入造成的 200 次事务。
 *
 * 冲突语义（与 [com.lanyue.reader.data.backup.BackupMerger] 保持一致的手感）：
 * - 本机没有 -> 新增
 * - 本机有且用户手工改过 -> 跳过并记录，绝不覆盖（否则调好的规则会被站点导出冲掉）
 * - 本机有且备份更新 -> 覆盖
 * - 本机有且不更新 -> 保持不变
 */
class SourceRepository(initial: List<InstalledSource> = emptyList()) {

    /** 一条已安装的书源。 */
    data class InstalledSource(
        val url: String,
        val name: String,
        /** 分组名；空即归入「未分组」。 */
        val group: String = DEFAULT_GROUP,
        val enabled: Boolean = true,
        /** 0 文本 / 1 音频 / 2 图片 / 3 文件。 */
        val type: Int = 0,
        /** 源本身的版本时间，用于判断是否该升级。 */
        val updatedAt: Long = 0,
        /** 用户是否手工编辑过配置字段；为 true 时导入不覆盖。 */
        val userEdited: Boolean = false,
        val sortOrder: Int = 0,
        /** 原始配置文本，落库时原样保存。 */
        val raw: String = "",
        /** 上一次配置校验的摘要，用于列表页直接展示。 */
        val validateSummary: String = ""
    )

    /** 安装结果。 */
    data class InstallReport(
        val added: List<String> = emptyList(),
        val updated: List<String> = emptyList(),
        val unchanged: List<String> = emptyList(),
        val skippedProtected: List<String> = emptyList(),
        val skippedInvalid: List<String> = emptyList(),
        /** 输入里自身重复的条数。 */
        val dedupedIncoming: Int = 0
    ) {
        val inserted: Int get() = added.size

        val isEmpty: Boolean
            get() = added.isEmpty() && updated.isEmpty() && unchanged.isEmpty() &&
                skippedProtected.isEmpty() && skippedInvalid.isEmpty()

        /** 一句话结论，直接展示在导入结果条上。 */
        fun summary(): String {
            if (isEmpty) return "没有需要安装的书源"
            val parts = ArrayList<String>()
            if (added.isNotEmpty()) parts.add("新增 " + added.size)
            if (updated.isNotEmpty()) parts.add("更新 " + updated.size)
            if (unchanged.isNotEmpty()) parts.add("不变 " + unchanged.size)
            if (skippedProtected.isNotEmpty()) parts.add("保护 " + skippedProtected.size)
            if (skippedInvalid.isNotEmpty()) parts.add("无效 " + skippedInvalid.size)
            return parts.joinToString("，")
        }
    }

    /** 仓库概览，供书源管理页顶部显示。 */
    data class Stats(
        val total: Int,
        val enabled: Int,
        val disabled: Int,
        val groups: Int,
        val userEdited: Int
    )

    /** url -> 记录。用 LinkedHashMap 保留插入顺序。 */
    private val items = LinkedHashMap<String, InstalledSource>()

    init {
        initial.forEach { src ->
            if (src.url.isNotBlank()) items[src.url] = src
        }
    }

    // ============================ 查询 ============================

    /** 全部书源，按「排序值 + 名称」稳定排序。 */
    fun all(): List<InstalledSource> = items.values.sortedWith(
        compareBy({ it.sortOrder }, { it.name }, { it.url })
    )

    fun enabled(): List<InstalledSource> = all().filter { it.enabled }

    fun disabled(): List<InstalledSource> = all().filterNot { it.enabled }

    fun size(): Int = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun find(url: String): InstalledSource? = items[url]

    fun contains(url: String): Boolean = items.containsKey(url)

    /** 按分组归集，分组名有序（首组固定为「全部」之外的字典序）。 */
    fun byGroup(): Map<String, List<InstalledSource>> {
        val map = LinkedHashMap<String, MutableList<InstalledSource>>()
        all().forEach { src ->
            map.getOrPut(src.group.ifBlank { DEFAULT_GROUP }) { ArrayList() }.add(src)
        }
        return map.mapValues { it.value.toList() }
    }

    /** 所有分组名（去重、有序）。 */
    fun groups(): List<String> = byGroup().keys.toList()

    /** 按关键字搜索名称 / 地址 / 分组。 */
    fun search(keyword: String): List<InstalledSource> {
        val k = keyword.trim()
        if (k.isEmpty()) return all()
        val lower = k.lowercase()
        return all().filter {
            it.name.lowercase().contains(lower) ||
                it.url.lowercase().contains(lower) ||
                it.group.lowercase().contains(lower)
        }
    }

    fun stats(): Stats = Stats(
        total = items.size,
        enabled = items.values.count { it.enabled },
        disabled = items.values.count { !it.enabled },
        groups = groups().size,
        userEdited = items.values.count { it.userEdited }
    )

    // ============================ 安装 / 升级 ============================

    /**
     * 批量安装（导入的主入口）。
     *
     * 单条无效不影响其它条目；整个过程不抛异常。
     * 新增条的 sortOrder 接在现有最大值之后，保证导入不会打乱用户已有排序。
     */
    fun install(incoming: List<InstalledSource>): InstallReport {
        val added = ArrayList<String>()
        val updated = ArrayList<String>()
        val unchanged = ArrayList<String>()
        val protectedList = ArrayList<String>()
        val invalid = ArrayList<String>()
        var deduped = 0

        // 输入内部先去重：同一份文件里重复的 url 以后出现的为准
        val dedupedInput = LinkedHashMap<String, InstalledSource>()
        incoming.forEach { src ->
            if (dedupedInput.put(src.url, src) != null) deduped++
        }

        var cursor = (items.values.maxOfOrNull { it.sortOrder } ?: 0) + 1

        dedupedInput.forEach { (url, src) ->
            if (url.isBlank() || !SourceValidator.isHttpUrl(url)) {
                invalid.add(url.ifBlank { "(空地址)" })
                return@forEach
            }
            val existing = items[url]
            if (existing == null) {
                items[url] = src.copy(sortOrder = cursor++, url = url)
                added.add(url)
                return@forEach
            }
            // 用户手工改过的配置受保护，任何升级都不覆盖
            if (existing.userEdited) {
                protectedList.add(url)
                return@forEach
            }
            if (src.updatedAt > existing.updatedAt) {
                items[url] = src.copy(
                    url = url,
                    // 保留用户本地的启用状态与排序，升级只换配置内容
                    enabled = existing.enabled,
                    sortOrder = existing.sortOrder,
                    userEdited = false
                )
                updated.add(url)
            } else {
                unchanged.add(url)
            }
        }

        return InstallReport(added, updated, unchanged, protectedList, invalid, deduped)
    }

    /** 安装单个书源，等价于批量安装一条。 */
    fun installOne(src: InstalledSource): InstallReport = install(listOf(src))

    // ============================ 增删改 ============================

    /** 删除单个。 */
    fun remove(url: String): Boolean = items.remove(url) != null

    /** 删除整组，返回删除条数。 */
    fun removeGroup(group: String): Int {
        val targets = items.values.filter { it.group == group }.map { it.url }
        targets.forEach { items.remove(it) }
        return targets.size
    }

    /** 启用 / 停用。 */
    fun setEnabled(url: String, enabled: Boolean): Boolean {
        val cur = items[url] ?: return false
        if (cur.enabled == enabled) return false
        items[url] = cur.copy(enabled = enabled)
        return true
    }

    /** 批量启用 / 停用，返回受影响条数。 */
    fun setEnabled(urls: Collection<String>, enabled: Boolean): Int =
        urls.count { setEnabled(it, enabled) }

    /** 移动分组。 */
    fun moveToGroup(url: String, group: String): Boolean {
        val cur = items[url] ?: return false
        val target = group.ifBlank { DEFAULT_GROUP }
        if (cur.group == target) return false
        items[url] = cur.copy(group = target)
        return true
    }

    /** 重命名。改名视为用户手工编辑，后续导入不覆盖。 */
    fun rename(url: String, name: String): Boolean {
        val cur = items[url] ?: return false
        val target = name.trim()
        if (target.isEmpty() || target == cur.name) return false
        items[url] = cur.copy(name = target, userEdited = true)
        return true
    }

    /** 显式标记为「用户已编辑」，用于设置页保存后调用。 */
    fun markUserEdited(url: String, edited: Boolean = true): Boolean {
        val cur = items[url] ?: return false
        if (cur.userEdited == edited) return false
        items[url] = cur.copy(userEdited = edited)
        return true
    }

    /** 写入校验摘要（不改动 userEdited 状态）。 */
    fun updateValidateSummary(url: String, summary: String): Boolean {
        val cur = items[url] ?: return false
        if (cur.validateSummary == summary) return false
        items[url] = cur.copy(validateSummary = summary)
        return true
    }

    /**
     * 按给定顺序重排。
     *
     * 只接受已在仓库中的地址，未列出的条目保持原有相对顺序排在后面——
     * 这样界面拖拽某几项时不会意外打乱其它分组。
     */
    fun reorder(urls: List<String>): Boolean {
        val known = urls.filter { items.containsKey(it) }
        if (known.isEmpty()) return false
        var cursor = 0
        known.forEach { url ->
            val cur = items[url] ?: return@forEach
            items[url] = cur.copy(sortOrder = cursor++)
        }
        val rest = items.values.filter { it.url !in known }.sortedBy { it.sortOrder }
        rest.forEach { cur ->
            items[cur.url] = cur.copy(sortOrder = cursor++)
        }
        return true
    }

    /** 导出指定分组（[group] 为 null 时导出全部），按当前排序。 */
    fun export(group: String? = null): List<InstalledSource> =
        if (group == null) all() else all().filter { it.group == group }

    /** 用校验器体检单个书源，返回摘要字符串。 */
    fun validate(url: String): String {
        val cur = items[url] ?: return "书源不存在"
        val report = SourceValidator.validateBook(
            bookSourceUrl = cur.url,
            bookSourceName = cur.name,
            searchUrl = null,
            ruleSearch = null,
            exploreUrl = null,
            enabledExplore = false,
            ruleToc = null,
            ruleContent = null,
            loginUrl = null,
            bookSourceType = cur.type
        )
        return report.summary()
    }

    companion object {
        /** 默认分组名。 */
        const val DEFAULT_GROUP = "未分组"
    }
}
