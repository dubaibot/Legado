import com.lanyue.reader.data.backup.BackupCodec
import com.lanyue.reader.data.backup.BackupDocument
import com.lanyue.reader.data.backup.BackupFormat
import com.lanyue.reader.data.backup.BackupImportCoordinator
import com.lanyue.reader.data.backup.BackupMeta
import com.lanyue.reader.data.backup.BackupMigrator
import com.lanyue.reader.data.backup.BackupPayload
import com.lanyue.reader.data.backup.BookBackup
import com.lanyue.reader.data.backup.BookSourceBackup
import com.lanyue.reader.data.backup.ImportBatchType
import com.lanyue.reader.data.backup.ImportSession
import com.lanyue.reader.data.backup.ImportState
import com.lanyue.reader.data.backup.LocalSnapshot
import com.lanyue.reader.data.backup.MergeMode
import com.lanyue.reader.data.backup.PlayRecordBackup
import com.lanyue.reader.data.backup.ReadRecordBackup
import com.lanyue.reader.data.backup.RssFavoriteBackup
import com.lanyue.reader.data.backup.RssSourceBackup
import com.lanyue.reader.data.backup.VideoFavoriteBackup
import com.lanyue.reader.data.backup.VideoSiteBackup
import kotlin.system.exitProcess

private var pass = 0
private val failures = ArrayList<String>()

private fun eq(label: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++ else failures += "$label\n  期望: $expected\n  实际: $actual"
}

private fun ok(label: String, cond: Boolean) {
    if (cond) pass++ else failures += label
}

private fun report() {
    println("PASS = $pass")
    println("FAIL = ${failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEachIndexed { i, f -> println("${i + 1}. $f") }
        exitProcess(1)
    }
}

private fun fullPayload(): BackupPayload = BackupPayload(
    books = listOf(
        BookBackup(bookUrl = "https://a.com/b1", name = "书一", origin = "https://s1.com", lastReadAt = 1000L)
    ),
    bookSources = listOf(
        BookSourceBackup(bookSourceUrl = "https://s1.com", bookSourceName = "源一", lastUpdateTime = 500L)
    ),
    rssSources = listOf(
        RssSourceBackup(sourceUrl = "https://r.com/feed", sourceName = "订阅一")
    ),
    videoSites = listOf(
        VideoSiteBackup(configUrl = "https://v.com/api", siteKey = "k1", name = "站一")
    ),
    readRecords = listOf(
        ReadRecordBackup(bookUrl = "https://a.com/b1", chapterIndex = 5, updatedAt = 3000L)
    ),
    playRecords = listOf(
        PlayRecordBackup(videoKey = "v1", title = "剧一", updatedAt = 4000L)
    ),
    rssFavorites = listOf(
        RssFavoriteBackup(articleUrl = "https://r.com/a1", title = "文章一", savedAt = 5000L)
    ),
    videoFavorites = listOf(
        VideoFavoriteBackup(videoUrl = "https://v.com/d9", title = "影片一", savedAt = 6000L)
    )
)

private fun doc(payload: BackupPayload, version: Int = BackupFormat.SCHEMA_VERSION): BackupDocument =
    BackupDocument(
        meta = BackupMeta(schemaVersion = version, exportedAt = 1700000000000L, appVersion = "0.1.0"),
        payload = payload
    )

fun main() {
    // ==================== 一、版本迁移 ====================
    run {
        val (migrated, notes) = BackupMigrator.migrate(fullPayload(), 1)
        ok("v1 迁移不丢数据", migrated.books.size == 1)
        ok("v1 迁移有说明", notes.isNotEmpty())
        ok("v1 迁移说明含版本号", notes.any { it.contains("v1") && it.contains("v2") })

        val (same, noNotes) = BackupMigrator.migrate(fullPayload(), BackupFormat.SCHEMA_VERSION)
        ok("当前版本不迁移", noNotes.isEmpty())
        eq("当前版本 payload 不变", same.books.first().name, "书一")
    }

    // ==================== 二、计划生成：批次顺序 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), LocalSnapshot())
        eq("批次总数", plan.batches.size, 8)

        // 验证写入顺序
        val order = plan.batches.map { it.batchType }
        eq("第一批书源", order[0], ImportBatchType.BOOK_SOURCES)
        eq("第二批订阅源", order[1], ImportBatchType.RSS_SOURCES)
        eq("第三批影视站点", order[2], ImportBatchType.VIDEO_SITES)
        eq("第四批书架", order[3], ImportBatchType.BOOKS)
        eq("第五批阅读进度", order[4], ImportBatchType.READ_RECORDS)
        eq("第六批播放进度", order[5], ImportBatchType.PLAY_RECORDS)
        eq("第七批订阅收藏", order[6], ImportBatchType.RSS_FAVORITES)
        eq("第八批影视收藏", order[7], ImportBatchType.VIDEO_FAVORITES)
    }

    // ==================== 三、计划生成：内容正确 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), LocalSnapshot())
        ok("有数据", plan.hasData)
        eq("总条目数", plan.totalItems, 8)
        eq("书源批次条数", plan.batches[0].itemCount, 1)
        eq("书架批次条数", plan.batches[3].itemCount, 1)
        eq("阅读进度批次条数", plan.batches[4].itemCount, 1)
        ok("非空批次 8 个", plan.nonEmptyBatches.size == 8)

        // 预览文案
        val preview = plan.previewSummary()
        ok("预览含书源", preview.contains("书源"))
        ok("预览含书架", preview.contains("书架"))
        ok("预览含数字", preview.contains("1"))
    }

    // ==================== 四、空备份计划 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(BackupPayload()), LocalSnapshot())
        ok("空备份无数据", !plan.hasData)
        eq("空备份总条目", plan.totalItems, 0)
        ok("空备份无非空批次", plan.nonEmptyBatches.isEmpty())
        eq("空备份预览", plan.previewSummary(), "备份里没有需要导入的数据")
    }

    // ==================== 五、合并集成：KEEP_NEWER ====================
    run {
        val local = LocalSnapshot(
            books = mapOf("https://a.com/b1" to 2000L)  // 本机比备份新
        )
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), local, MergeMode.KEEP_NEWER)
        // 书一在本机且更新，跳过
        eq("按新策略书架跳过", plan.batches[3].itemCount, 0)
        eq("书架跳过数", plan.batches[3].skippedCount, 1)
        // 书源本机没有，写入
        eq("书源仍写入", plan.batches[0].itemCount, 1)
        ok("合并报告有跳过", plan.mergeReport.totalSkipped > 0)
    }

    // ==================== 六、合并集成：OVERWRITE ====================
    run {
        val local = LocalSnapshot(books = mapOf("https://a.com/b1" to 2000L))
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), local, MergeMode.OVERWRITE)
        eq("覆盖策略书架写入", plan.batches[3].itemCount, 1)
        eq("覆盖无跳过", plan.mergeReport.totalSkipped, 0)
    }

    // ==================== 七、v1 备份导入计划 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload(), version = 1), LocalSnapshot())
        ok("v1 来源版本", plan.sourceVersion == 1)
        ok("v1 有迁移说明", plan.migrationNotes.isNotEmpty())
        ok("v1 迁移说明在告警里", plan.warnings.any { it.contains("迁移") })
        // 数据仍完整
        eq("v1 迁移后书源完整", plan.batches[0].itemCount, 1)
        eq("v1 迁移后书架完整", plan.batches[3].itemCount, 1)
    }

    // ==================== 八、全量成功导入 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), LocalSnapshot())
        val session = ImportSession(plan)

        // 逐批提交
        for (batch in plan.nonEmptyBatches) {
            session.markCommitted(batch.batchType, batch.itemCount)
        }
        ok("未调 markAllCommitted 也能自动完成",
            session.result().state == ImportState.COMMITTED)

        val result = session.result()
        eq("成功状态", result.state, ImportState.COMMITTED)
        eq("成功总写入", result.totalWritten, 8)
        eq("成功无失败", result.totalFailed, 0)
        ok("成功无回滚目标", result.rollbackBatchNames.isEmpty())
        ok("成功文案含已导入", result.summary().contains("已导入"))
        eq("成功文案数字", result.summary(), "已导入 8 项")
    }

    // ==================== 九、显式 markAllCommitted ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), LocalSnapshot())
        val session = ImportSession(plan)
        for (batch in plan.nonEmptyBatches) {
            session.markCommitted(batch.batchType, batch.itemCount)
        }
        session.markAllCommitted()
        eq("显式完成状态", session.result().state, ImportState.COMMITTED)
    }

    // ==================== 十、中途失败与回滚 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), LocalSnapshot())
        val session = ImportSession(plan)

        // 前三批成功
        session.markCommitted(ImportBatchType.BOOK_SOURCES, 1)
        session.markCommitted(ImportBatchType.RSS_SOURCES, 1)
        session.markCommitted(ImportBatchType.VIDEO_SITES, 1)
        // 第四批失败
        session.markFailed(ImportBatchType.BOOKS, 1, "数据库约束冲突")

        ok("失败状态", session.result().state == ImportState.FAILED)
        ok("已结束", session.isFinished)

        val rollback = session.rollbackTargets()
        eq("回滚 3 批", rollback.size, 3)
        eq("回滚第一批书源", rollback[0], "书源")
        eq("回滚第二批订阅源", rollback[1], "订阅源")
        eq("回滚第三批影视站点", rollback[2], "影视站点")
        ok("回滚不含失败批", !rollback.contains("书架"))

        val result = session.result()
        eq("失败总写入", result.totalWritten, 3)  // 前三批
        eq("失败数", result.totalFailed, 1)
        ok("失败文案含原因", result.summary().contains("数据库约束冲突"))
        ok("失败文案含回滚", result.summary().contains("回滚"))
    }

    // ==================== 十一、第一批就失败 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), LocalSnapshot())
        val session = ImportSession(plan)
        session.markFailed(ImportBatchType.BOOK_SOURCES, 1, "磁盘空间不足")

        eq("首批失败无回滚", session.rollbackTargets().size, 0)
        ok("结果失败", session.result().state == ImportState.FAILED)
        ok("文案含原因", session.result().summary().contains("磁盘空间不足"))
        ok("文案无回滚（无已提交）", !session.result().summary().contains("回滚"))
    }

    // ==================== 十二、失败后忽略后续提交 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), LocalSnapshot())
        val session = ImportSession(plan)

        session.markCommitted(ImportBatchType.BOOK_SOURCES, 1)
        session.markFailed(ImportBatchType.RSS_SOURCES, 1, "超时")
        // 失败后继续提交——应被忽略
        session.markCommitted(ImportBatchType.VIDEO_SITES, 1)
        session.markFailed(ImportBatchType.BOOKS, 1, "不该到这")

        eq("忽略后回滚仍 1 批", session.rollbackTargets().size, 1)
        eq("回滚只有书源", session.rollbackTargets()[0], "书源")
        ok("仍是失败态", session.result().state == ImportState.FAILED)
    }

    // ==================== 十三、空备份导入会话 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(BackupPayload()), LocalSnapshot())
        val session = ImportSession(plan)

        // 空计划：没有非空批次，直接完成
        session.markAllCommitted()
        val result = session.result()
        eq("空导入成功", result.state, ImportState.COMMITTED)
        eq("空导入零写入", result.totalWritten, 0)
        eq("空导入文案", result.summary(), "备份里没有可导入的数据")
    }

    // ==================== 十四、部分段有数据 ====================
    run {
        val partial = BackupPayload(
            bookSources = listOf(BookSourceBackup(bookSourceUrl = "s1", bookSourceName = "源一")),
            readRecords = listOf(ReadRecordBackup(bookUrl = "b1", chapterIndex = 3))
        )
        val plan = BackupImportCoordinator.planImport(doc(partial), LocalSnapshot())
        eq("部分数据总条目", plan.totalItems, 2)
        ok("书源非空", plan.batches[0].itemCount == 1)
        ok("订阅源空", plan.batches[1].isEmpty)
        ok("书架空", plan.batches[3].isEmpty)
        ok("阅读进度非空", plan.batches[4].itemCount == 1)
        ok("非空批次 2 个", plan.nonEmptyBatches.size == 2)

        val session = ImportSession(plan)
        session.markCommitted(ImportBatchType.BOOK_SOURCES, 1)
        session.markCommitted(ImportBatchType.READ_RECORDS, 1)
        session.markAllCommitted()
        val result = session.result()
        eq("部分导入成功", result.state, ImportState.COMMITTED)
        eq("部分导入写入", result.totalWritten, 2)
    }

    // ==================== 十五、端到端：解码→迁移→计划→导入 ====================
    run {
        // 先编码一份 v2 备份
        val json = BackupCodec.encode(fullPayload(), exportedAt = 1700000000000L, appVersion = "0.1.0")
        val decoded = BackupCodec.decode(json)
        ok("解码成功", decoded is BackupCodec.DecodeResult.Success)

        if (decoded is BackupCodec.DecodeResult.Success) {
            val document = decoded.document
            val plan = BackupImportCoordinator.planImport(document, LocalSnapshot())
            val session = ImportSession(plan)

            // 模拟逐批写入
            for (batch in plan.nonEmptyBatches) {
                session.markCommitted(batch.batchType, batch.itemCount)
            }
            session.markAllCommitted()

            val result = session.result()
            eq("端到端成功", result.state, ImportState.COMMITTED)
            eq("端到端写入 8", result.totalWritten, 8)
            ok("端到端无迁移（v2）", result.migrationNotes.isEmpty())
            ok("端到端无告警", result.warnings.isEmpty())
        }
    }

    // ==================== 十六、端到端：v1 备份导入 ====================
    run {
        // 手写一份 v1 格式 JSON
        val v1Json = """{"meta":{"format":"lanyue-backup","schemaVersion":1,"exportedAt":1600000000000,"appVersion":"0.0.5","checksum":""},"data":{"books":[{"bookUrl":"old-book","name":"老书","origin":"old-src"}],"bookSources":[{"bookSourceUrl":"old-src","bookSourceName":"老源"}]}}"""
        val decoded = BackupCodec.decode(v1Json)
        ok("v1 解码成功", decoded is BackupCodec.DecodeResult.Success)

        if (decoded is BackupCodec.DecodeResult.Success) {
            val plan = BackupImportCoordinator.planImport(decoded.document, LocalSnapshot())
            ok("v1 来源版本 1", plan.sourceVersion == 1)
            ok("v1 有迁移说明", plan.migrationNotes.isNotEmpty())

            val session = ImportSession(plan)
            session.markCommitted(ImportBatchType.BOOK_SOURCES, 1)
            session.markCommitted(ImportBatchType.BOOKS, 1)
            session.markAllCommitted()

            val result = session.result()
            eq("v1 导入成功", result.state, ImportState.COMMITTED)
            eq("v1 写入 2", result.totalWritten, 2)
            ok("v1 迁移说明在结果", result.migrationNotes.isNotEmpty())
        }
    }

    // ==================== 十七、回滚逆序 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), LocalSnapshot())
        val session = ImportSession(plan)

        // 提交前 5 批，第 6 批失败
        session.markCommitted(ImportBatchType.BOOK_SOURCES, 1)
        session.markCommitted(ImportBatchType.RSS_SOURCES, 1)
        session.markCommitted(ImportBatchType.VIDEO_SITES, 1)
        session.markCommitted(ImportBatchType.BOOKS, 1)
        session.markCommitted(ImportBatchType.READ_RECORDS, 1)
        session.markFailed(ImportBatchType.PLAY_RECORDS, 1, "OOM")

        val rollback = session.rollbackTargets()
        eq("回滚 5 批", rollback.size, 5)
        // 回滚应逆序执行：先撤阅读进度，再撤书架...
        val reversed = rollback.reversed()
        eq("逆序首撤阅读进度", reversed[0], "阅读进度")
        eq("逆序末撤书源", reversed.last(), "书源")
        ok("回滚不含播放进度（失败批）", !rollback.contains("播放进度"))
        ok("回滚不含后续批", !rollback.contains("订阅收藏") && !rollback.contains("影视收藏"))
    }

    // ==================== 十八、合并跳过反映在批次中 ====================
    run {
        val local = LocalSnapshot(
            books = mapOf("https://a.com/b1" to 2000L),
            bookSources = mapOf("https://s1.com" to 500L),
            userEditedBookSources = setOf("https://s1.com")
        )
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), local, MergeMode.OVERWRITE)
        // 书源受保护，跳过
        eq("受保护书源跳过", plan.batches[0].itemCount, 0)
        eq("受保护书源 skip", plan.batches[0].skippedCount, 1)
        // 书架本机更新，OVERWRITE 仍覆盖
        eq("覆盖书架写入", plan.batches[3].itemCount, 1)
        ok("合并报告书源跳过", plan.mergeReport.bookSources.skipped == 1)
    }

    // ==================== 十九、批次项内容正确 ====================
    run {
        val plan = BackupImportCoordinator.planImport(doc(fullPayload()), LocalSnapshot())
        val bookBatch = plan.batches[3]  // BOOKS
        ok("书架批次有项", bookBatch.items.isNotEmpty())
        val item = bookBatch.items.first()
        ok("项是 BookBackup", item is BookBackup)
        eq("项内容正确", (item as BookBackup).name, "书一")

        val srcBatch = plan.batches[0]  // BOOK_SOURCES
        val srcItem = srcBatch.items.first()
        ok("源批次项是 BookSourceBackup", srcItem is BookSourceBackup)
        eq("源项名称", (srcItem as BookSourceBackup).bookSourceName, "源一")
    }

    // ==================== 二十、WRITE_ORDER 完整性 ====================
    run {
        eq("写入顺序 8 步", ImportBatchType.WRITE_ORDER.size, 8)
        ok("书源在订阅源前",
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.BOOK_SOURCES) <
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.RSS_SOURCES))
        ok("书源在书架前",
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.BOOK_SOURCES) <
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.BOOKS))
        ok("书架在阅读进度前",
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.BOOKS) <
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.READ_RECORDS))
        ok("影视站点在播放进度前",
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.VIDEO_SITES) <
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.PLAY_RECORDS))
        ok("订阅源在订阅收藏前",
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.RSS_SOURCES) <
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.RSS_FAVORITES))
        ok("影视站点在影视收藏前",
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.VIDEO_SITES) <
            ImportBatchType.WRITE_ORDER.indexOf(ImportBatchType.VIDEO_FAVORITES))
    }

    report()
}
