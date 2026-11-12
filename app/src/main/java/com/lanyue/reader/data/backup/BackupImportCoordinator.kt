package com.lanyue.reader.data.backup

/**
 * 备份版本迁移器。
 *
 * 当备份的 schemaVersion 低于当前版本时，[BackupImportCoordinator] 会先跑迁移，
 * 再做合并。迁移是纯函数：输入旧版 payload，输出当前版 payload + 迁移说明。
 *
 * 当前 v1 → v2 的实际差异为零（[BackupCodec] 解码时已用默认值补齐缺字段），
 * 但保留这一层是为了将来加字段时有明确的扩展点，而不是散落在解码器各处。
 */
object BackupMigrator {

    /**
     * @return 迁移后的 payload 与执行的迁移步骤描述（供导入报告展示）
     */
    fun migrate(payload: BackupPayload, fromVersion: Int): Pair<BackupPayload, List<String>> {
        if (fromVersion >= BackupFormat.SCHEMA_VERSION) return payload to emptyList()
        val notes = ArrayList<String>()

        // v1 → v2：字段层面无破坏性变更，解码器已补默认值。
        // 此处显式记录"已迁移"，让导入报告说清来源版本。
        if (fromVersion <= 1) {
            notes.add("备份格式从 v$fromVersion 迁移到 v${BackupFormat.SCHEMA_VERSION}（字段补齐）")
        }
        // 将来的 v2 → v3 迁移写在这里：
        // if (fromVersion <= 2) { ... notes.add("...") }

        return payload to notes
    }
}

/**
 * 导入批次类型。顺序就是写入顺序——前一批写完才写后一批，
 * 失败时后一批不写，已写的前一批需要回滚。
 */
enum class ImportBatchType(val displayName: String) {
    BOOK_SOURCES("书源"),
    RSS_SOURCES("订阅源"),
    VIDEO_SITES("影视站点"),
    BOOKS("书架"),
    READ_RECORDS("阅读进度"),
    PLAY_RECORDS("播放进度"),
    RSS_FAVORITES("订阅收藏"),
    VIDEO_FAVORITES("影视收藏");

    companion object {
        /**
         * 按依赖顺序排列的完整批次列表。
         *
         * 依赖关系：
         * - 书源/订阅源不依赖任何东西 → 最先
         * - 影视站点不依赖任何东西 → 与源同层
         * - 书架的 origin 引用书源 → 在书源之后
         * - 阅读进度的 bookUrl 引用书架 → 在书架之后
         * - 播放进度的 videoKey 引用影视站点 → 在站点之后
         * - 订阅收藏的 sourceUrl 引用订阅源 → 在源之后
         * - 影视收藏引用影视站点 → 在站点之后
         *
         * 失败回滚时反向操作：先撤收藏，再撤进度，最后撤书架和源。
         */
        val WRITE_ORDER: List<ImportBatchType> = listOf(
            BOOK_SOURCES,
            RSS_SOURCES,
            VIDEO_SITES,
            BOOKS,
            READ_RECORDS,
            PLAY_RECORDS,
            RSS_FAVORITES,
            VIDEO_FAVORITES
        )
    }
}

/**
 * 一批待写入的记录。
 *
 * @param batchType   批次类型（决定写入顺序）
 * @param items       经合并后需要写入的记录列表
 * @param skippedCount 因冲突/策略被跳过的条数
 */
data class ImportBatch(
    val batchType: ImportBatchType,
    val items: List<Any>,
    val skippedCount: Int
) {
    val name: String get() = batchType.displayName
    val isEmpty: Boolean get() = items.isEmpty()
    val isNotEmpty: Boolean get() = items.isNotEmpty()
    val itemCount: Int get() = items.size
}

/**
 * 导入计划：合并完成后的写入蓝图。
 *
 * 调用方按 [batches] 顺序逐批写入数据库；空批次可跳过。
 */
data class ImportPlan(
    val batches: List<ImportBatch>,
    val mergeReport: MergeReport,
    val migrationNotes: List<String>,
    val warnings: List<String>,
    val sourceVersion: Int
) {
    /** 需要写入的总条目数。 */
    val totalItems: Int get() = batches.sumOf { it.itemCount }

    /** 非空批次列表（空批次在实际导入时可跳过）。 */
    val nonEmptyBatches: List<ImportBatch> get() = batches.filter { it.isNotEmpty }

    /** 是否有数据可导入。 */
    val hasData: Boolean get() = totalItems > 0

    /** 一句话预览文案，供导入确认页展示。 */
    fun previewSummary(): String {
        if (!hasData) return "备份里没有需要导入的数据"
        val parts = nonEmptyBatches.map { "${it.name} ${it.itemCount}" }
        return "将导入：" + parts.joinToString("、")
    }
}

/**
 * 导入会话状态。
 */
enum class ImportState {
    /** 计划已生成，尚未开始写入。 */
    PLANNED,
    /** 正在写入（至少一批已提交）。 */
    IMPORTING,
    /** 全部批次写入成功。 */
    COMMITTED,
    /** 某批失败，已提交的批次需要回滚。 */
    FAILED
}

/**
 * 单批写入结果。
 */
data class BatchOutcome(
    val batchType: ImportBatchType,
    val written: Int,
    val failed: Int,
    val failureReason: String?
) {
    val isSuccess: Boolean get() = failed == 0 && failureReason == null
}

/**
 * 导入最终结果。
 */
data class ImportResult(
    val state: ImportState,
    val outcomes: List<BatchOutcome>,
    val mergeReport: MergeReport,
    val migrationNotes: List<String>,
    val warnings: List<String>,
    val rollbackBatchNames: List<String>
) {
    /** 成功写入的总条目数。 */
    val totalWritten: Int get() = outcomes.sumOf { it.written }

    /** 失败条目数。 */
    val totalFailed: Int get() = outcomes.sumOf { it.failed }

    /** 一句话结果文案，供导入结果页展示。 */
    fun summary(): String = when (state) {
        ImportState.COMMITTED -> {
            if (totalWritten == 0) "备份里没有可导入的数据"
            else "已导入 $totalWritten 项"
        }
        ImportState.FAILED -> {
            val rb = if (rollbackBatchNames.isNotEmpty()) "，需回滚 ${rollbackBatchNames.size} 批" else ""
            "导入失败：" + (outcomes.firstOrNull { !it.isSuccess }?.failureReason ?: "未知错误") + rb
        }
        else -> "导入未完成"
    }
}

/**
 * 备份导入事务协调器。
 *
 * 职责：
 * 1. 接收解码后的 [BackupDocument] + 本机快照，跑合并 + 迁移，产出有序写入计划；
 * 2. 追踪写入进度（[ImportSession]），某批失败时计算需要回滚的已提交批次；
 * 3. 不碰数据库——调用方按计划逐批写入，用 session 标记结果。
 *
 * 这样做的原因：[BackupMerger] 只算"该写什么"，但不关心"写入顺序"和"失败后怎么撤"。
 * 直接把合并结果一次性塞给 DAO，如果中途崩了（比如写了书源但写书架时 OOM），
 * 本机就留下半套数据——源有了但书没有，进退两难。协调器把写入变成有序事务，
 * 失败时明确告诉调用方"这 N 批已经写了，需要逐批删除"。
 */
object BackupImportCoordinator {

    /**
     * 规划导入：迁移 → 合并 → 按依赖顺序排批。
     */
    fun planImport(
        document: BackupDocument,
        local: LocalSnapshot,
        mode: MergeMode = MergeMode.Default
    ): ImportPlan {
        val warnings = ArrayList<String>()

        // 1. 版本迁移
        val (migratedPayload, migrationNotes) = BackupMigrator.migrate(
            document.payload, document.meta.schemaVersion
        )
        if (migrationNotes.isNotEmpty()) {
            warnings.addAll(migrationNotes)
        }

        // 2. 合并
        val report = BackupMerger.mergeAll(migratedPayload, local, mode)

        // 3. 按依赖顺序排批
        val batches = ImportBatchType.WRITE_ORDER.map { type ->
            buildBatch(type, report)
        }

        return ImportPlan(
            batches = batches,
            mergeReport = report,
            migrationNotes = migrationNotes,
            warnings = warnings,
            sourceVersion = document.meta.schemaVersion
        )
    }

    private fun buildBatch(type: ImportBatchType, report: MergeReport): ImportBatch =
        when (type) {
            ImportBatchType.BOOK_SOURCES -> ImportBatch(
                type, report.bookSources.toWrite.toList(), report.bookSources.skipped
            )
            ImportBatchType.RSS_SOURCES -> ImportBatch(
                type, report.rssSources.toWrite.toList(), report.rssSources.skipped
            )
            ImportBatchType.VIDEO_SITES -> ImportBatch(
                type, report.videoSites.toWrite.toList(), report.videoSites.skipped
            )
            ImportBatchType.BOOKS -> ImportBatch(
                type, report.books.toWrite.toList(), report.books.skipped
            )
            ImportBatchType.READ_RECORDS -> ImportBatch(
                type, report.readRecords.toWrite.toList(), report.readRecords.skipped
            )
            ImportBatchType.PLAY_RECORDS -> ImportBatch(
                type, report.playRecords.toWrite.toList(), report.playRecords.skipped
            )
            ImportBatchType.RSS_FAVORITES -> ImportBatch(
                type, report.rssFavorites.toWrite.toList(), report.rssFavorites.skipped
            )
            ImportBatchType.VIDEO_FAVORITES -> ImportBatch(
                type, report.videoFavorites.toWrite.toList(), report.videoFavorites.skipped
            )
        }
}

/**
 * 导入会话：追踪写入进度，计算回滚目标。
 *
 * 用法：
 * ```
 * val plan = BackupImportCoordinator.planImport(doc, local, mode)
 * val session = ImportSession(plan)
 * for (batch in plan.nonEmptyBatches) {
 *     try {
 *         dao.batchWrite(batch.items)
 *         session.markCommitted(batch.batchType, batch.itemCount)
 *     } catch (e: Exception) {
 *         session.markFailed(batch.batchType, batch.itemCount, e.message ?: "写入失败")
 *         val rollback = session.rollbackTargets()
 *         // 逐批删除 rollback 中的记录
 *         break
 *     }
 * }
 * val result = session.result()
 * ```
 */
class ImportSession(private val plan: ImportPlan) {

    private val outcomes = LinkedHashMap<ImportBatchType, BatchOutcome>()
    private val committedOrder = mutableListOf<ImportBatchType>()
    private var state: ImportState = ImportState.PLANNED

    init {
        // 初始化所有非空批次为 PLANNED 状态
        plan.nonEmptyBatches.forEach { batch ->
            outcomes[batch.batchType] = BatchOutcome(batch.batchType, 0, 0, null)
        }
    }

    /** 标记一批写入成功。 */
    fun markCommitted(batchType: ImportBatchType, written: Int) {
        if (state == ImportState.FAILED) return  // 已失败，忽略后续
        outcomes[batchType] = BatchOutcome(batchType, written, 0, null)
        committedOrder.add(batchType)
        state = ImportState.IMPORTING
    }

    /** 标记一批写入失败。自动设置回滚目标（已提交的前序批次）。 */
    fun markFailed(batchType: ImportBatchType, failed: Int, reason: String) {
        if (state == ImportState.FAILED) return  // 已失败，忽略后续
        outcomes[batchType] = BatchOutcome(batchType, 0, failed, reason)
        state = ImportState.FAILED
    }

    /** 标记全部完成。 */
    fun markAllCommitted() {
        if (state == ImportState.FAILED) return
        state = ImportState.COMMITTED
    }

    /**
     * 需要回滚的批次名列表（按写入顺序）。
     *
     * 失败时返回已成功提交的全部批次；未失败时返回空。
     * 回滚操作应**逆序执行**（后写的先撤），但此处返回正序，
     * 调用方可用 `reversed()` 翻转。
     */
    fun rollbackTargets(): List<String> {
        if (state != ImportState.FAILED) return emptyList()
        return committedOrder.map { it.displayName }
    }

    /** 当前是否已完成（成功或失败）。 */
    val isFinished: Boolean
        get() = state == ImportState.COMMITTED || state == ImportState.FAILED

    /** 已提交的批次数。 */
    val committedCount: Int get() = committedOrder.size

    /** 生成最终结果。 */
    fun result(): ImportResult {
        // 如果所有非空批次都已提交且没有失败，自动标记为 COMMITTED
        if (state == ImportState.IMPORTING && committedOrder.size == outcomes.size) {
            state = ImportState.COMMITTED
        }

        return ImportResult(
            state = state,
            outcomes = outcomes.values.toList(),
            mergeReport = plan.mergeReport,
            migrationNotes = plan.migrationNotes,
            warnings = plan.warnings,
            rollbackBatchNames = rollbackTargets()
        )
    }
}
