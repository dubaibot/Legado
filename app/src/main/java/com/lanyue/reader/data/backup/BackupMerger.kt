package com.lanyue.reader.data.backup

/**
 * 导入时的冲突处理策略。
 *
 * 三个选项对应设置页里的三条文案，用户不需要理解数据库：
 * - [KEEP_LOCAL]    "保留本机，只补缺"：本机没有的才写入。
 * - [OVERWRITE]     "以备份为准"：备份有的全部覆盖。
 * - [KEEP_NEWER]    "按时间取新"：比较 updatedAt，谁新用谁（默认，最不容易丢进度）。
 */
enum class MergeMode(val id: String, val label: String) {
    KEEP_LOCAL("keep_local", "保留本机，只补缺"),
    OVERWRITE("overwrite", "以备份为准"),
    KEEP_NEWER("keep_newer", "按时间取新");

    companion object {
        val Default = KEEP_NEWER

        fun fromId(id: String?): MergeMode =
            entries.firstOrNull { it.id == id } ?: Default
    }
}

/** 合并结果。 */
data class MergeOutcome<T>(
    /** 需要写入数据库的记录（已按主键去重）。 */
    val toWrite: List<T>,
    /** 因冲突/策略被跳过的条数。 */
    val skipped: Int,
    /** 备份内自身重复而去重的条数。 */
    val deduped: Int
) {
    val written: Int get() = toWrite.size
    val total: Int get() = toWrite.size + skipped
}

/** 各段合并统计，供导入结果页展示。 */
data class MergeReport(
    val books: MergeOutcome<BookBackup> = MergeOutcome(emptyList(), 0, 0),
    val bookSources: MergeOutcome<BookSourceBackup> = MergeOutcome(emptyList(), 0, 0),
    val rssSources: MergeOutcome<RssSourceBackup> = MergeOutcome(emptyList(), 0, 0),
    val videoSites: MergeOutcome<VideoSiteBackup> = MergeOutcome(emptyList(), 0, 0),
    val readRecords: MergeOutcome<ReadRecordBackup> = MergeOutcome(emptyList(), 0, 0),
    val playRecords: MergeOutcome<PlayRecordBackup> = MergeOutcome(emptyList(), 0, 0),
    val rssFavorites: MergeOutcome<RssFavoriteBackup> = MergeOutcome(emptyList(), 0, 0),
    val videoFavorites: MergeOutcome<VideoFavoriteBackup> = MergeOutcome(emptyList(), 0, 0)
) {
    val totalWritten: Int
        get() = books.written + bookSources.written + rssSources.written + videoSites.written +
            readRecords.written + playRecords.written + rssFavorites.written +
            videoFavorites.written

    val totalSkipped: Int
        get() = books.skipped + bookSources.skipped + rssSources.skipped + videoSites.skipped +
            readRecords.skipped + playRecords.skipped + rssFavorites.skipped +
            videoFavorites.skipped

    /** 一句话结论，直接放到结果页顶部。 */
    fun summary(): String = when {
        totalWritten == 0 && totalSkipped == 0 -> "备份里没有可导入的数据"
        totalWritten == 0 -> "全部 $totalSkipped 项被跳过，本机数据未变"
        totalSkipped == 0 -> "已导入 $totalWritten 项"
        else -> "已导入 $totalWritten 项，跳过 $totalSkipped 项"
    }
}

/**
 * 备份合并器。
 *
 * 全部是纯函数：不碰数据库、不碰文件，输入"本机有什么"和"备份有什么"，
 * 输出"应该写什么"。这样导入逻辑可以在 JVM 单测里跑完整分支，
 * 也便于将来把"导入预览"做成一屏可勾选的差异列表。
 */
object BackupMerger {

    /**
     * 通用合并。
     *
     * @param localKeys        本机已有主键 -> 本机记录的 updatedAt（用于比新旧）
     * @param incoming         备份里的记录
     * @param keyOf            取主键
     * @param updatedAtOf      取更新时间；不参与比较的段落传常量 0
     * @param mode             冲突策略
     * @param protectedKeys    不允许被覆盖的本机主键（例如用户手工改过的书源）
     */
    fun <T> merge(
        localKeys: Map<String, Long>,
        incoming: List<T>,
        keyOf: (T) -> String,
        updatedAtOf: (T) -> Long,
        mode: MergeMode = MergeMode.Default,
        protectedKeys: Set<String> = emptySet()
    ): MergeOutcome<T> {
        // 备份内部先按主键去重：同一份文件里重复的条目以后出现的为准
        val deduped = LinkedHashMap<String, T>()
        var dupCount = 0
        incoming.forEach { item ->
            val key = keyOf(item)
            if (key.isBlank()) {
                dupCount++
                return@forEach
            }
            if (deduped.put(key, item) != null) dupCount++
        }

        val toWrite = ArrayList<T>()
        var skipped = 0
        deduped.forEach { (key, item) ->
            val localUpdatedAt = localKeys[key]
            if (localUpdatedAt == null) {
                // 本机没有：任何策略下都写入
                toWrite.add(item)
                return@forEach
            }
            // 本机已有，进入冲突处理
            if (key in protectedKeys) {
                skipped++
                return@forEach
            }
            when (mode) {
                MergeMode.KEEP_LOCAL -> skipped++
                MergeMode.OVERWRITE -> toWrite.add(item)
                MergeMode.KEEP_NEWER -> {
                    if (updatedAtOf(item) > localUpdatedAt) toWrite.add(item) else skipped++
                }
            }
        }
        return MergeOutcome(toWrite, skipped, dupCount)
    }

    /** 合并整份备份。参数是本机现状的轻量快照，避免把 DAO 传进来。 */
    fun mergeAll(
        payload: BackupPayload,
        local: LocalSnapshot,
        mode: MergeMode = MergeMode.Default
    ): MergeReport = MergeReport(
        books = merge(
            localKeys = local.books,
            incoming = payload.books,
            keyOf = { it.bookUrl },
            updatedAtOf = { it.lastReadAt },
            mode = mode
        ),
        bookSources = merge(
            localKeys = local.bookSources,
            incoming = payload.bookSources,
            keyOf = { it.bookSourceUrl },
            updatedAtOf = { it.lastUpdateTime },
            mode = mode,
            // 用户手工改过的书源不允许被备份覆盖，否则调好的规则会丢
            protectedKeys = local.userEditedBookSources
        ),
        rssSources = merge(
            localKeys = local.rssSources,
            incoming = payload.rssSources,
            keyOf = { it.sourceUrl },
            updatedAtOf = { it.lastUpdateTime },
            mode = mode
        ),
        videoSites = merge(
            localKeys = local.videoSites,
            incoming = payload.videoSites,
            keyOf = { siteKeyOf(it.configUrl, it.siteKey) },
            updatedAtOf = { 0L },
            mode = mode
        ),
        readRecords = merge(
            localKeys = local.readRecords,
            incoming = payload.readRecords,
            keyOf = { it.bookUrl },
            updatedAtOf = { it.updatedAt },
            mode = mode
        ),
        playRecords = merge(
            localKeys = local.playRecords,
            incoming = payload.playRecords,
            keyOf = { it.videoKey },
            updatedAtOf = { it.updatedAt },
            mode = mode
        ),
        rssFavorites = merge(
            localKeys = local.rssFavorites,
            incoming = payload.rssFavorites,
            keyOf = { it.articleUrl },
            updatedAtOf = { it.savedAt },
            mode = mode
        ),
        videoFavorites = merge(
            localKeys = local.videoFavorites,
            incoming = payload.videoFavorites,
            keyOf = { it.videoUrl },
            updatedAtOf = { it.savedAt },
            mode = mode
        )
    )

    /** 影视站点主键是 (configUrl, siteKey) 复合键，用不可见分隔符拼接。 */
    fun siteKeyOf(configUrl: String, siteKey: String): String = "$configUrl\u0001$siteKey"
}

/**
 * 本机数据快照：只保留主键与用于比新的时间戳。
 *
 * 专门做成轻量结构而不是直接传实体列表，是为了让合并在纯 JVM 下可测，
 * 也顺手避免了把整个数据库读进内存。
 */
data class LocalSnapshot(
    val books: Map<String, Long> = emptyMap(),
    val bookSources: Map<String, Long> = emptyMap(),
    val rssSources: Map<String, Long> = emptyMap(),
    val videoSites: Map<String, Long> = emptyMap(),
    val readRecords: Map<String, Long> = emptyMap(),
    val playRecords: Map<String, Long> = emptyMap(),
    val rssFavorites: Map<String, Long> = emptyMap(),
    val videoFavorites: Map<String, Long> = emptyMap(),
    /** 用户手工编辑过的书源 URL，导入时受保护。 */
    val userEditedBookSources: Set<String> = emptySet()
) {
    val isEmpty: Boolean
        get() = books.isEmpty() && bookSources.isEmpty() && rssSources.isEmpty() &&
            videoSites.isEmpty() && readRecords.isEmpty() && playRecords.isEmpty() &&
            rssFavorites.isEmpty() && videoFavorites.isEmpty()
}
