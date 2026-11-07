package com.lanyue.reader.source.aggregate

import com.lanyue.reader.source.model.SearchBookItem

/**
 * 多源聚合搜索（纯逻辑，无网络 / 无协程）。
 *
 * 多源搜索是这类阅读器的核心体验：用户搜一个书名，几十个源各自返回结果，
 * 同一本书会在不同源里出现多次（书名写法、装饰符号、全半角都可能不同），
 * 直接平铺会让人看到十几条重复。本模块把结果归一化、聚类、排序成
 * 「一本书一行、点开选源」的形态。
 *
 * 设计取舍：
 * - 只做字符串级归一化，不联网补全作者；缺作者的条目用「同名唯一聚类」规则
 *   宽松并入，多个同名聚类时不并入（宁可多一行，不可错误合并）。
 * - 聚类代表从排序最靠前的可用源里取，保持多源结果顺序稳定；
 *   每个聚类保留 origin -> bookUrl 的备选表，直接喂给「换源」菜单。
 * - 源级状态（成功/空结果/失败/超时/隔离）由调用方传入，
 *   本模块只负责汇总成用户能看懂的一句话与统计。
 */
object SearchAggregator {

    // ============================ 归一化 ============================

    /** 装饰符号与空白：聚类时全部剔除。 */
    private val DECORATIONS = charArrayOf(
        '《', '》', '【', '】', '「', '」', '『', '』', '（', '）', '(', ')',
        '[', ']', '{', '}', '·', '•', '・', '*', '~', '～', '-', '—', '―',
        ' ', '\t', '\n', '\r', '\u00A0'
    )

    /** 尾部候选分隔符：剥掉「著」等后缀后残留的斜杠 / 竖线等。 */
    private val SEPARATION = setOf('/', '｜', '|', ',', '，', '、', ':', '：', '-')

    /**
     * 全角转半角 + 去装饰 + 折叠大小写。
     *
     * 真实数据里「斗破苍穹」「斗破苍穹！」「【斗破苍穹】」「ｄｏｕｐｏ」
     * 都是同一本，必须先归一化再谈聚类。
     */
    fun normalizeText(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length)
        raw.forEach { c ->
            when {
                c == '\u3000' -> sb.append(' ')
                c in '\uFF01'..'\uFF5E' -> sb.append(c - 0xFEE0)
                else -> sb.append(c)
            }
        }
        val folded = sb.toString().lowercase()
        val kept = folded.filter { it !in DECORATIONS }
        return kept
    }

    /**
     * 作者归一化：额外剥掉「著」「/著」「撰」等后缀与空格。
     * 书源里常见「天蚕土豆著」「天蚕土豆 / 著」，归一化后才能对上。
     */
    fun normalizeAuthor(raw: String?): String {
        val base = normalizeText(raw)
        if (base.isEmpty()) return ""
        var s = base
        listOf("著", "撰", "编", "作品").forEach { suffix ->
            while (s.endsWith(suffix)) s = s.removeSuffix(suffix)
        }
        // 剥掉后缀后清理残留分隔符（「天蚕土豆/著」->「天蚕土豆」）
        while (s.isNotEmpty() && SEPARATION.contains(s.last())) s = s.substring(0, s.length - 1)
        return s
    }

    /** 聚类键：书名 + 作者。作者缺失用空串，由并入规则兜底。 */
    private fun bookKey(item: SearchBookItem): String =
        normalizeText(item.name) + "|" + normalizeAuthor(item.author)

    // ============================ 单源去重 ============================

    /**
     * 单源内部去重：同 bookUrl 只留首条；同「书名+作者」的多条
     * （常见于源把置顶推荐与正文搜索都搜出来）也只留首条。
     * 返回去重条数，供界面提示「源内去重 N 条」。
     */
    fun dedupPage(items: List<SearchBookItem>): Pair<List<SearchBookItem>, Int> {
        val seenUrl = HashSet<String>()
        val seenKey = HashSet<String>()
        val kept = ArrayList<SearchBookItem>(items.size)
        var removed = 0
        items.forEach { item ->
            if (item.bookUrl.isBlank()) {
                removed++
                return@forEach
            }
            val key = bookKey(item)
            if (!seenUrl.add(item.bookUrl) || !seenKey.add(key)) {
                removed++
                return@forEach
            }
            kept.add(item)
        }
        return kept to removed
    }

    // ============================ 聚类 ============================

    /** 聚类过程中的暂存记录。 */
    private class Draft(
        val key: String,
        val bestOrder: Int,
        val bestSeq: Int,
        val items: MutableList<SearchBookItem>
    )

    /** 一个「同一本书」的聚类。 */
    data class BookCluster(
        /** 界面直接展示的代表条目（来自优先级最高的源）。 */
        val primary: SearchBookItem,
        /** 归一化键，调试与测试用。 */
        val key: String,
        /** origin -> 该源条目。换源菜单直接遍历这张表。 */
        val alternatives: Map<String, SearchBookItem>,
        /** 参与聚类的源数量。 */
        val sourceCount: Int
    )

    /**
     * 单个源的搜索状态。由调用方（SourceRouter 之后）填好再交进来。
     */
    enum class SourceStatus { OK, EMPTY, FAILED, TIMEOUT, ISOLATED }

    data class SourcePage(
        val origin: String,
        val sourceName: String,
        val status: SourceStatus,
        val items: List<SearchBookItem> = emptyList(),
        /** 失败/超时的一句用户可读原因，可空。 */
        val message: String = "",
        /** 源在用户列表里的优先级：越小越靠前。 */
        val order: Int = 0
    )

    /**
     * 聚合结果：聚类列表 + 源级报告。
     */
    data class AggregateResult(
        val clusters: List<BookCluster>,
        val pages: List<SourcePage>,
        /** 源内去重移除的总条数。 */
        val dedupedInSource: Int,
        /** 聚类后「消失」的跨源重复条数。 */
        val mergedAcrossSources: Int
    ) {
        /** 搜索成功的源数（有返回且条数 > 0）。 */
        val okSources: Int get() = pages.count { it.status == SourceStatus.OK }

        /** 一句话结论，用于搜索页底部状态条。 */
        fun summary(): String {
            if (pages.isEmpty()) return "没有可搜索的源"
            val parts = ArrayList<String>()
            parts.add(okSources.toString() + "/" + pages.size + " 个源有结果")
            if (mergedAcrossSources > 0) parts.add("合并重复 " + mergedAcrossSources + " 条")
            if (dedupedInSource > 0) parts.add("源内去重 " + dedupedInSource + " 条")
            val isolated = pages.count { it.status == SourceStatus.ISOLATED }
            if (isolated > 0) parts.add(isolated.toString() + " 个源被隔离")
            return parts.joinToString("，")
        }
    }

    /**
     * 聚合主入口。
     *
     * 排序规则（用户可预期的稳定顺序）：
     * 1. 多源命中的书排前面（越多人收录越可能是「正主」而非蹭名书）；
     * 2. 同命中源数按首个命中源的 order 排，order 相同按页内出现先后；
     * 3. 关键字完全相等的归一化书名再提前一位（精确命中优先于部分命中）。
     */
    fun aggregate(
        pages: List<SourcePage>,
        /** 用户搜索关键字，用于「精确命中」加权；可空。 */
        keyword: String = ""
    ): AggregateResult {
        val normalizedKeyword = normalizeText(keyword)

        // 源内先去重
        var deduped = 0
        val cleanedPages = pages.map { page ->
            val (kept, removed) = dedupPage(page.items)
            deduped += removed
            page.copy(items = kept)
        }

        val drafts = LinkedHashMap<String, Draft>()
        // 书名键 -> 已建聚类键列表：供「缺作者宽松并入」查重
        val nameIndex = LinkedHashMap<String, MutableList<String>>()

        var seq = 0
        cleanedPages.forEach { page ->
            page.items.forEach { item ->
                val nameKey = normalizeText(item.name)
                val authorKey = normalizeAuthor(item.author)
                val key = nameKey + "|" + authorKey
                val existing = drafts[key]
                if (existing != null) {
                    existing.items.add(item)
                    return@forEach
                }
                // 作者缺失时，若同名聚类恰好只有一个，则宽松并入
                if (authorKey.isEmpty() && nameKey.isNotEmpty()) {
                    val candidates = nameIndex[nameKey].orEmpty().filter { drafts.containsKey(it) }
                    if (candidates.size == 1) {
                        drafts[candidates[0]]!!.items.add(item)
                        return@forEach
                    }
                }
                drafts[key] = Draft(key, page.order, seq++, mutableListOf(item))
                if (nameKey.isNotEmpty()) {
                    nameIndex.getOrPut(nameKey) { ArrayList() }.add(key)
                }
            }
        }

        val clusters = drafts.values.map { draft ->
            val sortedItems = draft.items.sortedWith(
                compareBy({ it.origin }, { it.bookUrl })
            )
            val primary = sortedItems.firstOrNull { item ->
                val page = cleanedPages.firstOrNull { it.origin == item.origin }
                page?.order == draft.bestOrder
            } ?: sortedItems.first()
            BookCluster(
                primary = primary,
                key = draft.key,
                alternatives = sortedItems.associateBy { it.origin },
                sourceCount = sortedItems.map { it.origin }.distinct().size
            )
        }.sortedWith(
            compareByDescending<BookCluster> { it.sourceCount }
                .thenBy { exactHitRank(it, normalizedKeyword) }
                .thenBy { drafts[it.key]!!.bestOrder }
                .thenBy { drafts[it.key]!!.bestSeq }
        )

        val totalItems = drafts.values.sumOf { it.items.size }
        return AggregateResult(
            clusters = clusters,
            pages = cleanedPages,
            dedupedInSource = deduped,
            mergedAcrossSources = totalItems - clusters.size
        )
    }

    /** 精确命中排 0，部分命中排 1，未命中排 2。 */
    private fun exactHitRank(cluster: BookCluster, normalizedKeyword: String): Int {
        if (normalizedKeyword.isEmpty()) return 2
        val name = normalizeText(cluster.primary.name)
        return when {
            name == normalizedKeyword -> 0
            name.contains(normalizedKeyword) -> 1
            else -> 2
        }
    }
}
