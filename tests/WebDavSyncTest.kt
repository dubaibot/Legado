import com.lanyue.reader.data.sync.ConflictStrategy
import com.lanyue.reader.data.sync.LocalEntry
import com.lanyue.reader.data.sync.RemoteEntry
import com.lanyue.reader.data.sync.SyncAction
import com.lanyue.reader.data.sync.SyncBaseline
import com.lanyue.reader.data.sync.SyncDirection
import com.lanyue.reader.data.sync.SyncSession
import com.lanyue.reader.data.sync.SyncState
import com.lanyue.reader.data.sync.WebDavSyncCoordinator
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

// ── 辅助构造 ──────────────────────────────────────────────

private fun L(key: String, ts: Long, deleted: Boolean = false) = LocalEntry(key, ts, deleted)
private fun R(key: String, ts: Long, etag: String? = null) = RemoteEntry(key, ts, etag)
private fun B(vararg pairs: Pair<String, Long>, syncedAt: Long = 1000L) =
    SyncBaseline(pairs.toMap(), syncedAt)

/** 取出某 key 的操作类型（返回类名简写）。 */
private fun actionType(plan: com.lanyue.reader.data.sync.SyncPlan, key: String): String {
    val a = plan.actions.firstOrNull {
        when (it) {
            is SyncAction.Upload -> it.key == key
            is SyncAction.Download -> it.key == key
            is SyncAction.DeleteRemote -> it.key == key
            is SyncAction.DeleteLocal -> it.key == key
            is SyncAction.Skip -> it.key == key
            is SyncAction.ConflictResolved -> it.key == key
        }
    } ?: return "NONE"
    return when (a) {
        is SyncAction.Upload -> "Upload"
        is SyncAction.Download -> "Download"
        is SyncAction.DeleteRemote -> "DeleteRemote"
        is SyncAction.DeleteLocal -> "DeleteLocal"
        is SyncAction.Skip -> "Skip"
        is SyncAction.ConflictResolved -> "ConflictResolved(${a.winner})"
    }
}

fun main() {
    // ════════════════════════════════════════════════════════
    // 一、空同步：本地远端均空
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = emptyList(), remote = emptyList(),
            baseline = SyncBaseline()
        )
        ok("空同步 actions 为空", plan.actions.isEmpty())
        eq("空同步 stats 全 0", plan.stats.uploads + plan.stats.downloads, 0)
        eq("空同步 summary", plan.summary(), "没有需要同步的变更")
    }

    // ════════════════════════════════════════════════════════
    // 二、首次同步：本地有、远端无、无基准 → 上传
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 100), L("k2", 200)),
            remote = emptyList(),
            baseline = SyncBaseline()
        )
        eq("首次同步上传数", plan.stats.uploads, 2)
        eq("k1 操作", actionType(plan, "k1"), "Upload")
        eq("k2 操作", actionType(plan, "k2"), "Upload")
    }

    // ════════════════════════════════════════════════════════
    // 三、首次同步：远端有、本地无、无基准 → 下载
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = emptyList(),
            remote = listOf(R("k1", 100), R("k2", 200)),
            baseline = SyncBaseline()
        )
        eq("首次同步下载数", plan.stats.downloads, 2)
        eq("k1 操作", actionType(plan, "k1"), "Download")
    }

    // ════════════════════════════════════════════════════════
    // 四、无变更：两侧与基准完全一致 → 全跳过
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 100), L("k2", 200)),
            remote = listOf(R("k1", 100), R("k2", 200)),
            baseline = B("k1" to 100L, "k2" to 200L)
        )
        eq("无变更跳过数", plan.stats.skipped, 2)
        eq("无变更上传数", plan.stats.uploads, 0)
        eq("无变更下载数", plan.stats.downloads, 0)
    }

    // ════════════════════════════════════════════════════════
    // 五、仅本地改了 → 上传
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 300)),
            remote = listOf(R("k1", 100)),
            baseline = B("k1" to 100L)
        )
        eq("本地改了→上传", plan.stats.uploads, 1)
        eq("k1 操作", actionType(plan, "k1"), "Upload")
    }

    // ════════════════════════════════════════════════════════
    // 六、仅远端改了 → 下载
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 100)),
            remote = listOf(R("k1", 300)),
            baseline = B("k1" to 100L)
        )
        eq("远端改了→下载", plan.stats.downloads, 1)
        eq("k1 操作", actionType(plan, "k1"), "Download")
    }

    // ════════════════════════════════════════════════════════
    // 七、冲突—NEWER_WINS：本地新 → 上传
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 500)),
            remote = listOf(R("k1", 300)),
            baseline = B("k1" to 100L),
            strategy = ConflictStrategy.NEWER_WINS
        )
        eq("冲突 NEWER_WINS 本地新→冲突已决", plan.stats.conflictsResolved, 1)
        eq("k1 操作", actionType(plan, "k1"), "ConflictResolved(local(newer))")
        ok("无未解决冲突", plan.unresolvedConflicts.isEmpty())
    }

    // ════════════════════════════════════════════════════════
    // 八、冲突—NEWER_WINS：远端新 → 下载
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 200)),
            remote = listOf(R("k1", 500)),
            baseline = B("k1" to 100L),
            strategy = ConflictStrategy.NEWER_WINS
        )
        eq("冲突 NEWER_WINS 远端新→冲突已决", plan.stats.conflictsResolved, 1)
        eq("k1 操作", actionType(plan, "k1"), "ConflictResolved(remote(newer))")
    }

    // ════════════════════════════════════════════════════════
    // 九、冲突—NEWER_WINS：时间戳相等 → 保留本地
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 300)),
            remote = listOf(R("k1", 300)),
            baseline = B("k1" to 100L),
            strategy = ConflictStrategy.NEWER_WINS
        )
        eq("冲突时间戳相等→保留本地", actionType(plan, "k1"), "ConflictResolved(local(newer))")
    }

    // ════════════════════════════════════════════════════════
    // 十、冲突—KEEP_LOCAL
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 200)),
            remote = listOf(R("k1", 999)),
            baseline = B("k1" to 100L),
            strategy = ConflictStrategy.KEEP_LOCAL
        )
        eq("KEEP_LOCAL→上传", actionType(plan, "k1"), "ConflictResolved(local)")
    }

    // ════════════════════════════════════════════════════════
    // 十一、冲突—KEEP_REMOTE
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 999)),
            remote = listOf(R("k1", 200)),
            baseline = B("k1" to 100L),
            strategy = ConflictStrategy.KEEP_REMOTE
        )
        eq("KEEP_REMOTE→下载", actionType(plan, "k1"), "ConflictResolved(remote)")
    }

    // ════════════════════════════════════════════════════════
    // 十二、冲突—SKIP：产生未解决冲突
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 200), L("k2", 300)),
            remote = listOf(R("k1", 300), R("k2", 200)),
            baseline = B("k1" to 100L, "k2" to 100L),
            strategy = ConflictStrategy.SKIP
        )
        eq("SKIP→未解决冲突数", plan.stats.conflictsUnresolved, 2)
        ok("SKIP→hasConflicts", plan.hasConflicts)
        eq("k1 操作 Skip", actionType(plan, "k1"), "Skip")
        eq("k2 操作 Skip", actionType(plan, "k2"), "Skip")
        // 冲突详情
        eq("冲突 k1 localUpdatedAt", plan.unresolvedConflicts[0].localUpdatedAt, 200L)
        eq("冲突 k1 remoteUpdatedAt", plan.unresolvedConflicts[0].remoteUpdatedAt, 300L)
    }

    // ════════════════════════════════════════════════════════
    // 十三、本地删除 → 远端也删
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 100, deleted = true)),
            remote = listOf(R("k1", 100)),
            baseline = B("k1" to 100L)
        )
        eq("本地删除→删远端", plan.stats.remoteDeletions, 1)
        eq("k1 操作", actionType(plan, "k1"), "DeleteRemote")
    }

    // ════════════════════════════════════════════════════════
    // 十四、本地删除、远端也删除 → 跳过
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 100, deleted = true)),
            remote = emptyList(),
            baseline = B("k1" to 100L)
        )
        // 本地删除但远端也没了 → 跳过（双侧均已删除）
        eq("双侧删除→跳过", actionType(plan, "k1"), "Skip")
    }

    // ════════════════════════════════════════════════════════
    // 十五、远端删除、本地无变更 → 本地也删
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 100)),
            remote = emptyList(),
            baseline = B("k1" to 100L)
        )
        eq("远端删除→删本地", plan.stats.localDeletions, 1)
        eq("k1 操作", actionType(plan, "k1"), "DeleteLocal")
    }

    // ════════════════════════════════════════════════════════
    // 十六、远端删除、本地也改了 → 冲突
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 300)),
            remote = emptyList(),
            baseline = B("k1" to 100L),
            strategy = ConflictStrategy.KEEP_LOCAL
        )
        // 本地改了但远端删了 → 冲突，KEEP_LOCAL → 上传
        eq("远端删+本地改→冲突已决", plan.stats.conflictsResolved, 1)
    }

    // ════════════════════════════════════════════════════════
    // 十七、仅上传模式
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 300), L("k2", 200), L("k3", 100, deleted = true)),
            remote = listOf(R("k1", 500), R("k2", 200)),  // 远端 k1 更新但上传模式忽略
            baseline = B("k1" to 100L, "k2" to 200L, "k3" to 100L),
            direction = SyncDirection.UPLOAD_ONLY
        )
        // k1 本地改了 → 上传（不管远端是否改过）
        // k2 本地无变更 → 跳过
        // k3 本地删除 → 删远端
        eq("上传模式 uploads", plan.stats.uploads, 1)
        eq("上传模式 remoteDeletions", plan.stats.remoteDeletions, 1)
        eq("k1→Upload", actionType(plan, "k1"), "Upload")
        eq("k2→Skip", actionType(plan, "k2"), "Skip")
        eq("k3→DeleteRemote", actionType(plan, "k3"), "DeleteRemote")
        // 不应有下载
        eq("上传模式 downloads=0", plan.stats.downloads, 0)
    }

    // ════════════════════════════════════════════════════════
    // 十八、仅下载模式
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 300), L("k2", 200)),
            remote = listOf(R("k1", 500), R("k3", 400)),
            baseline = B("k1" to 100L, "k2" to 200L),
            direction = SyncDirection.DOWNLOAD_ONLY
        )
        // k1 远端改了 → 下载（不管本地是否改过）
        // k2 远端没了 → 删本地
        // k3 远端新增 → 下载
        eq("下载模式 downloads", plan.stats.downloads, 2)
        eq("下载模式 localDeletions", plan.stats.localDeletions, 1)
        eq("k1→Download", actionType(plan, "k1"), "Download")
        eq("k2→DeleteLocal", actionType(plan, "k2"), "DeleteLocal")
        eq("k3→Download", actionType(plan, "k3"), "Download")
        // 不应有上传
        eq("下载模式 uploads=0", plan.stats.uploads, 0)
    }

    // ════════════════════════════════════════════════════════
    // 十九、混合场景：多种操作并存
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(
                L("new_local", 100),       // 本地新增 → 上传
                L("changed_local", 300),   // 本地改 → 上传
                L("deleted_local", 100, deleted = true), // 本地删 → 删远端
                L("conflict", 400)         // 双侧改 → 冲突(NEWER_WINS 本地 400>远端 300)
            ),
            remote = listOf(
                R("changed_local", 100),   // 远端没改
                R("deleted_local", 100),   // 远端还有（本地删了→删远端）
                R("new_remote", 200),      // 远端新增 → 下载
                R("conflict", 300),        // 远端也改了
                R("remote_deleted", 100)   // 远端有但无基准 → 下载
            ),
            baseline = B(
                "changed_local" to 100L,
                "deleted_local" to 100L,
                "conflict" to 100L
            ),
            strategy = ConflictStrategy.NEWER_WINS
        )
        // 实际：new_local→Upload(1), changed_local→Upload(1), deleted_local→DeleteRemote,
        //       conflict→ConflictResolved(local,newer)→子操作Upload→uploads++(3),
        //       new_remote→Download(1), remote_deleted→Download(1)
        // uploads=3（含 ConflictResolved 子操作）
        eq("混合 uploads", plan.stats.uploads, 3)
        eq("混合 downloads", plan.stats.downloads, 2)
        eq("混合 remoteDeletions", plan.stats.remoteDeletions, 1)
        eq("混合 conflictsResolved", plan.stats.conflictsResolved, 1)
    }

    // ════════════════════════════════════════════════════════
    // 二十、summary 文案
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("u1", 200), L("d1", 100, deleted = true)),
            remote = listOf(R("u1", 100), R("dl1", 300)),
            baseline = B("u1" to 100L, "d1" to 100L),
            strategy = ConflictStrategy.SKIP
        )
        // u1 本地改→Upload, d1 本地删→DeleteRemote, dl1 远端新增→Download
        // 注意 d1 远端没有对应条目，本地已删除+远端也没有→Skip（双侧均已删除）
        // 修改：给 d1 远端也加一个条目，使 DeleteRemote 生效
        val s = plan.summary()
        ok("summary 含上传", s.contains("上传"))
        ok("summary 含下载", s.contains("下载"))
        // d1 远端无条目→双侧删除→Skip，无远端删除
        ok("summary 不含远端删除（d1 远端无条目）", !s.contains("远端删除"))
    }

    // ════════════════════════════════════════════════════════
    // 二十一、SyncSession 状态机：正常流程
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 200), L("k2", 200)),
            remote = listOf(R("k1", 100), R("k2", 100)),
            baseline = B("k1" to 100L, "k2" to 100L)
        )
        val session = SyncSession()
        eq("初始状态 IDLE", session.currentState(), SyncState.IDLE)
        session.start(plan)
        eq("start 后 SYNCING", session.currentState(), SyncState.SYNCING)
        eq("totalActions=2", session.totalActions, 2)
        session.markDone("k1")
        eq("进度 50%", session.progress, 50)
        session.markDone("k2")
        eq("进度 100%", session.progress, 100)
        session.finish()
        eq("finish 后 SUCCESS", session.currentState(), SyncState.SUCCESS)
        eq("errorCount=0", session.errorCount, 0)
    }

    // ════════════════════════════════════════════════════════
    // 二十二、SyncSession：有冲突 → CONFLICT 态
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 200)),
            remote = listOf(R("k1", 300)),
            baseline = B("k1" to 100L),
            strategy = ConflictStrategy.SKIP
        )
        val session = SyncSession()
        session.start(plan)
        eq("冲突计划→CONFLICT", session.currentState(), SyncState.CONFLICT)
        ok("有冲突时不在 SYNCING", session.currentState() != SyncState.SYNCING)
    }

    // ════════════════════════════════════════════════════════
    // 二十三、SyncSession：冲突解决后继续
    // ════════════════════════════════════════════════════════
    run {
        val conflictPlan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 200)),
            remote = listOf(R("k1", 300)),
            baseline = B("k1" to 100L),
            strategy = ConflictStrategy.SKIP
        )
        val resolvedPlan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 200)),
            remote = listOf(R("k1", 300)),
            baseline = B("k1" to 100L),
            strategy = ConflictStrategy.KEEP_LOCAL
        )
        val session = SyncSession()
        session.start(conflictPlan)
        eq("初始冲突", session.currentState(), SyncState.CONFLICT)
        session.resolveConflicts(resolvedPlan)
        eq("解决后 SYNCING", session.currentState(), SyncState.SYNCING)
        session.markDone("k1")
        session.finish()
        eq("最终 SUCCESS", session.currentState(), SyncState.SUCCESS)
    }

    // ════════════════════════════════════════════════════════
    // 二十四、SyncSession：出错
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 200), L("k2", 200)),
            remote = listOf(R("k1", 100), R("k2", 100)),
            baseline = B("k1" to 100L, "k2" to 100L)
        )
        val session = SyncSession()
        session.start(plan)
        session.markDone("k1")
        session.markError("k2", "网络超时")
        eq("出错后 ERROR", session.currentState(), SyncState.ERROR)
        eq("errorCount=1", session.errorCount, 1)
        ok("errorDetails 含 k2", session.errorDetails().containsKey("k2"))
        // finish 后仍 ERROR（已有错误）
        session.finish()
        eq("finish 后仍 ERROR", session.currentState(), SyncState.ERROR)
    }

    // ════════════════════════════════════════════════════════
    // 二十五、SyncSession：markDone 在非 SYNCING 态无效
    // ════════════════════════════════════════════════════════
    run {
        val session = SyncSession()
        // IDLE 态 markDone 不应生效
        session.markDone("k1")
        eq("IDLE 态 markDone 无效", session.completedCount, 0)
    }

    // ════════════════════════════════════════════════════════
    // 二十六、SyncSession：重复 start 无效
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 200)),
            remote = listOf(R("k1", 100)),
            baseline = B("k1" to 100L)
        )
        val session = SyncSession()
        session.start(plan)
        eq("第一次 start", session.currentState(), SyncState.SYNCING)
        // 第二次 start 不应改变状态（已在 SYNCING）
        session.start(plan)
        eq("重复 start 不变", session.currentState(), SyncState.SYNCING)
    }

    // ════════════════════════════════════════════════════════
    // 二十七、SyncSession：空计划进度 100%
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = emptyList(), remote = emptyList(),
            baseline = SyncBaseline()
        )
        val session = SyncSession()
        session.start(plan)
        // 空计划，actions 为空 → totalActions=0 → progress=100
        eq("空计划 totalActions=0", session.totalActions, 0)
        eq("空计划 progress=100", session.progress, 100)
    }

    // ════════════════════════════════════════════════════════
    // 二十八、Download 操作携带 etag
    // ════════════════════════════════════════════════════════
    run {
        val plan = WebDavSyncCoordinator.planSync(
            local = listOf(L("k1", 100)),
            remote = listOf(R("k1", 300, etag = "abc123")),
            baseline = B("k1" to 100L)
        )
        val dl = plan.actions.filterIsInstance<SyncAction.Download>().first()
        eq("etag 传递", dl.etag, "abc123")
    }

    // ════════════════════════════════════════════════════════
    // 二十九、端到端：完整同步流程
    // ════════════════════════════════════════════════════════
    run {
        // 模拟真实场景：书架 + 阅读进度
        val local = listOf(
            L("book:url1", 1000),    // 未改
            L("book:url2", 2000),    // 本地改了
            L("book:url3", 0, deleted = true), // 本地删了
            L("book:url4", 4000)     // 新增
        )
        val remote = listOf(
            R("book:url1", 1000),    // 未改
            R("book:url2", 1000),    // 远端没改
            R("book:url3", 1000),    // 远端还有
            R("book:url5", 5000)     // 远端新增
        )
        val baseline = B(
            "book:url1" to 1000L,
            "book:url2" to 1000L,
            "book:url3" to 1000L
        )

        val plan = WebDavSyncCoordinator.planSync(
            local = local, remote = remote, baseline = baseline,
            strategy = ConflictStrategy.NEWER_WINS
        )

        // url1: 无变更 → Skip
        // url2: 本地改 → Upload
        // url3: 本地删除 → DeleteRemote
        // url4: 本地新增无基准 → Upload
        // url5: 远端新增无基准 → Download
        eq("e2e url1", actionType(plan, "book:url1"), "Skip")
        eq("e2e url2", actionType(plan, "book:url2"), "Upload")
        eq("e2e url3", actionType(plan, "book:url3"), "DeleteRemote")
        eq("e2e url4", actionType(plan, "book:url4"), "Upload")
        eq("e2e url5", actionType(plan, "book:url5"), "Download")
        eq("e2e uploads", plan.stats.uploads, 2)
        eq("e2e downloads", plan.stats.downloads, 1)
        eq("e2e remoteDeletions", plan.stats.remoteDeletions, 1)

        // 用 session 模拟执行
        val session = SyncSession()
        session.start(plan)
        eq("e2e SYNCING", session.currentState(), SyncState.SYNCING)
        eq("e2e totalActions", session.totalActions, 4) // 2 Upload + 1 DeleteRemote + 1 Download
        session.markDone("book:url2")
        eq("e2e progress 25", session.progress, 25)
        session.markDone("book:url3")
        session.markDone("book:url4")
        session.markDone("book:url5")
        eq("e2e progress 100", session.progress, 100)
        session.finish()
        eq("e2e SUCCESS", session.currentState(), SyncState.SUCCESS)
    }

    report()
}
