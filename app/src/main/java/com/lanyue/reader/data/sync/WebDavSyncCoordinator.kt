package com.lanyue.reader.data.sync

/**
 * WebDAV 同步协调器。
 *
 * 职责：把"本地有什么变更"和"远端有什么变更"变成一份同步计划，
 * 标出冲突项并按解决策略裁决，产出上传/下载/跳过三类操作清单。
 *
 * 不碰 HTTP / OkHttp / WebDAV PROPFIND-COPY——调用方负责：
 * 1. 从 WebDAV 服务器拉取远端文件列表（`RemoteEntry`）；
 * 2. 从本地数据库读取本地条目（`LocalEntry`）；
 * 3. 把协调器产出的 `SyncPlan` 逐项执行（上传/下载/删除）。
 *
 * 为什么不在协调器里直接发 HTTP：
 * - 纯 JVM 测试无法 mock OkHttp 的 TLS/认证/超时；
 * - 同步逻辑（谁该传、谁冲突、谁更新）与传输无关，分开测试更可靠；
 * - 调用方可能用 Retrofit、也可能用原生 HttpURLConnection，不应绑定。
 */

// ── 数据模型 ──────────────────────────────────────────────

/**
 * 本地条目快照。
 *
 * @param key       唯一标识（书架条目的 bookUrl、书源的 bookSourceUrl 等）
 * @param updatedAt 最后修改时间（毫秒时间戳）
 * @param deleted   是否已被本地删除（软删除，尚未同步到远端）
 */
data class LocalEntry(
    val key: String,
    val updatedAt: Long,
    val deleted: Boolean = false
)

/**
 * 远端条目快照（WebDAV 服务器上的文件/记录）。
 *
 * @param key       唯一标识，与 LocalEntry.key 对齐
 * @param updatedAt 远端最后修改时间（从 WebDAV `getlastmodified` 解析）
 * @param etag      远端 ETag（用于后续条件请求，可为空）
 */
data class RemoteEntry(
    val key: String,
    val updatedAt: Long,
    val etag: String? = null
)

/**
 * 上次同步的基准快照：记录"上次同步完时双方的状态"。
 *
 * 用途：判断一个条目是"本侧改了"还是"对侧改了"还是"双侧都改了"。
 * 如果本地与基准相同但远端不同 → 远端改了（下载）；
 * 如果远端与基准相同但本地不同 → 本地改了（上传）；
 * 如果两侧都与基准不同 → 冲突。
 *
 * @param entries   上次同步后的条目快照（key → updatedAt）
 * @param syncedAt  上次同步完成时间
 */
data class SyncBaseline(
    val entries: Map<String, Long> = emptyMap(),
    val syncedAt: Long = 0
)

// ── 同步方向与冲突策略 ────────────────────────────────────

/**
 * 同步方向。
 */
enum class SyncDirection {
    /** 双向：上传本地变更 + 下载远端变更。默认行为。 */
    BIDIRECTIONAL,
    /** 仅上传：把本地推到远端，不拉远端变更。用于"我知道本地是最新的"场景。 */
    UPLOAD_ONLY,
    /** 仅下载：把远端拉到本地，不上传本地变更。用于"以远端为准"场景。 */
    DOWNLOAD_ONLY
}

/**
 * 冲突解决策略（双侧都改了同一项时怎么办）。
 */
enum class ConflictStrategy {
    /** 保留本地版本（上传本地覆盖远端）。 */
    KEEP_LOCAL,
    /** 保留远端版本（下载远端覆盖本地）。 */
    KEEP_REMOTE,
    /** 时间戳新的赢（比较两侧 updatedAt）。相等时默认保留本地。 */
    NEWER_WINS,
    /** 跳过冲突项，不自动裁决，留待用户手动处理。 */
    SKIP
}

// ── 同步操作 ──────────────────────────────────────────────

/**
 * 单个条目的同步操作。
 */
sealed class SyncAction {
    /** 上传本地到远端。 */
    data class Upload(val key: String, val localUpdatedAt: Long) : SyncAction()
    /** 下载远端到本地。 */
    data class Download(val key: String, val remoteUpdatedAt: Long, val etag: String?) : SyncAction()
    /** 远端有但本地已删除 → 在远端也删除。 */
    data class DeleteRemote(val key: String) : SyncAction()
    /** 本地有但远端已删除 → 在本地也删除。 */
    data class DeleteLocal(val key: String) : SyncAction()
    /** 跳过（无变更或冲突被跳过）。 */
    data class Skip(val key: String, val reason: String) : SyncAction()
    /** 冲突已自动裁决。 */
    data class ConflictResolved(val key: String, val winner: String, val action: SyncAction) : SyncAction()
}

// ── 同步计划 ──────────────────────────────────────────────

/**
 * 未解决的冲突项（策略为 SKIP 时产生）。
 */
data class UnresolvedConflict(
    val key: String,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long,
    val baselineUpdatedAt: Long
)

/**
 * 同步计划：协调器产出的操作清单。
 *
 * 调用方按 `actions` 逐项执行；`unresolvedConflicts` 中的条目需要用户裁决。
 */
data class SyncPlan(
    val actions: List<SyncAction>,
    val unresolvedConflicts: List<UnresolvedConflict>,
    val stats: SyncStats
) {
    /** 是否有需要用户手动解决的冲突。 */
    val hasConflicts: Boolean get() = unresolvedConflicts.isNotEmpty()

    /** 一句话摘要文案，供同步界面展示。 */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (stats.uploads > 0) parts.add("上传 ${stats.uploads}")
        if (stats.downloads > 0) parts.add("下载 ${stats.downloads}")
        if (stats.remoteDeletions > 0) parts.add("远端删除 ${stats.remoteDeletions}")
        if (stats.localDeletions > 0) parts.add("本地删除 ${stats.localDeletions}")
        if (stats.conflictsResolved > 0) parts.add("冲突已决 ${stats.conflictsResolved}")
        if (stats.skipped > 0) parts.add("跳过 ${stats.skipped}")
        if (stats.conflictsUnresolved > 0) parts.add("待解决冲突 ${stats.conflictsUnresolved}")
        return if (parts.isEmpty()) "没有需要同步的变更" else parts.joinToString("、")
    }
}

/**
 * 同步统计。
 */
data class SyncStats(
    val uploads: Int = 0,
    val downloads: Int = 0,
    val remoteDeletions: Int = 0,
    val localDeletions: Int = 0,
    val conflictsResolved: Int = 0,
    val conflictsUnresolved: Int = 0,
    val skipped: Int = 0
)

// ── 同步状态机 ────────────────────────────────────────────

/**
 * 同步会话状态。
 */
enum class SyncState {
    IDLE,       // 空闲
    PLANNING,   // 正在生成同步计划
    SYNCING,    // 正在执行同步操作
    SUCCESS,    // 全部成功
    CONFLICT,   // 有未解决冲突，等用户裁决
    ERROR       // 出错（某步失败）
}

/**
 * 同步会话：追踪执行进度。
 */
class SyncSession {
    private var state: SyncState = SyncState.IDLE
    private var plan: SyncPlan? = null
    private val completed = mutableSetOf<String>()
    private val errors = mutableMapOf<String, String>()

    /** 开始同步：传入计划，进入 SYNCING。 */
    fun start(plan: SyncPlan) {
        if (state != SyncState.IDLE) return
        this.plan = plan
        state = if (plan.hasConflicts) SyncState.CONFLICT else SyncState.SYNCING
    }

    /** 标记一个操作完成。 */
    fun markDone(key: String) {
        if (state == SyncState.SYNCING || state == SyncState.CONFLICT) {
            completed.add(key)
        }
    }

    /** 标记一个操作失败。 */
    fun markError(key: String, reason: String) {
        if (state == SyncState.SYNCING) {
            errors[key] = reason
            state = SyncState.ERROR
        }
    }

    /** 用户解决了冲突，传入新的计划。 */
    fun resolveConflicts(resolvedPlan: SyncPlan) {
        if (state != SyncState.CONFLICT) return
        this.plan = resolvedPlan
        state = SyncState.SYNCING
    }

    /** 全部操作完成。 */
    fun finish() {
        if (state == SyncState.SYNCING) {
            state = if (errors.isEmpty()) SyncState.SUCCESS else SyncState.ERROR
        }
    }

    /** 当前状态。 */
    fun currentState(): SyncState = state

    /** 已完成的操作数。 */
    val completedCount: Int get() = completed.size

    /** 错误数。 */
    val errorCount: Int get() = errors.size

    /** 总操作数（不含冲突跳过）。 */
    val totalActions: Int get() = plan?.actions?.count { it !is SyncAction.Skip } ?: 0

    /** 进度百分比（0-100）。 */
    val progress: Int
        get() = if (totalActions == 0) 100 else (completed.size * 100 / totalActions)

    /** 错误详情。 */
    fun errorDetails(): Map<String, String> = errors.toMap()
}

// ── 协调器 ────────────────────────────────────────────────

/**
 * WebDAV 同步协调器。
 *
 * 用法：
 * ```
 * val plan = WebDavSyncCoordinator.planSync(
 *     local = localEntries,
 *     remote = remoteEntries,
 *     baseline = lastSyncBaseline,
 *     direction = SyncDirection.BIDIRECTIONAL,
 *     strategy = ConflictStrategy.NEWER_WINS
 * )
 * val session = SyncSession()
 * session.start(plan)
 * for (action in plan.actions) {
 *     try {
 *         executeAction(action)  // 调用方接线 HTTP
 *         session.markDone(actionKey(action))
 *     } catch (e: Exception) {
 *         session.markError(actionKey(action), e.message ?: "失败")
 *         break
 *     }
 * }
 * session.finish()
 * ```
 */
object WebDavSyncCoordinator {

    /**
     * 规划同步：对比本地、远端、基准三方，产出操作清单。
     */
    fun planSync(
        local: List<LocalEntry>,
        remote: List<RemoteEntry>,
        baseline: SyncBaseline,
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        strategy: ConflictStrategy = ConflictStrategy.NEWER_WINS
    ): SyncPlan {
        val localMap = local.associateBy { it.key }
        val remoteMap = remote.associateBy { it.key }
        val baseMap = baseline.entries

        val allKeys = (localMap.keys + remoteMap.keys + baseMap.keys)

        val actions = mutableListOf<SyncAction>()
        val conflicts = mutableListOf<UnresolvedConflict>()
        var uploads = 0; var downloads = 0
        var remoteDels = 0; var localDels = 0
        var resolved = 0; var skipped = 0

        for (key in allKeys) {
            val l = localMap[key]
            val r = remoteMap[key]
            val b = baseMap[key]

            val action = classify(key, l, r, b, direction, strategy)
            when (action) {
                is SyncAction.Upload -> uploads++
                is SyncAction.Download -> downloads++
                is SyncAction.DeleteRemote -> remoteDels++
                is SyncAction.DeleteLocal -> localDels++
                is SyncAction.Skip -> skipped++
                is SyncAction.ConflictResolved -> {
                    resolved++
                    // 内部递归产生的子操作也要计入统计
                    when (action.action) {
                        is SyncAction.Upload -> uploads++
                        is SyncAction.Download -> downloads++
                        is SyncAction.DeleteRemote -> remoteDels++
                        is SyncAction.DeleteLocal -> localDels++
                        is SyncAction.Skip -> skipped++
                        else -> {}
                    }
                }
            }
            actions.add(action)

            // 收集未解决冲突
            if (action is SyncAction.Skip && action.reason.startsWith("冲突")) {
                conflicts.add(UnresolvedConflict(
                    key = key,
                    localUpdatedAt = l?.updatedAt ?: 0,
                    remoteUpdatedAt = r?.updatedAt ?: 0,
                    baselineUpdatedAt = b ?: 0
                ))
            }
        }

        // 统计未解决冲突数
        val unresolvedCount = conflicts.size

        return SyncPlan(
            actions = actions,
            unresolvedConflicts = conflicts,
            stats = SyncStats(
                uploads = uploads,
                downloads = downloads,
                remoteDeletions = remoteDels,
                localDeletions = localDels,
                conflictsResolved = resolved,
                conflictsUnresolved = unresolvedCount,
                skipped = skipped
            )
        )
    }

    /**
     * 分类单个条目的同步操作。
     */
    private fun classify(
        key: String,
        local: LocalEntry?,
        remote: RemoteEntry?,
        baseline: Long?,
        direction: SyncDirection,
        strategy: ConflictStrategy
    ): SyncAction {
        // ── 仅上传模式 ──
        if (direction == SyncDirection.UPLOAD_ONLY) {
            if (local == null) return SyncAction.Skip(key, "无本地数据")
            if (local.deleted) return SyncAction.DeleteRemote(key)
            // 只上传有变更的
            return if (baseline == null || local.updatedAt > baseline) {
                SyncAction.Upload(key, local.updatedAt)
            } else {
                SyncAction.Skip(key, "本地无变更")
            }
        }

        // ── 仅下载模式 ──
        if (direction == SyncDirection.DOWNLOAD_ONLY) {
            if (remote == null) {
                // 远端没了，本地也删
                return if (local != null && !local.deleted) {
                    SyncAction.DeleteLocal(key)
                } else {
                    SyncAction.Skip(key, "远端和本地均无数据")
                }
            }
            return if (baseline == null || remote.updatedAt > baseline) {
                SyncAction.Download(key, remote.updatedAt, remote.etag)
            } else {
                SyncAction.Skip(key, "远端无变更")
            }
        }

        // ── 双向模式 ──
        val localChanged = local != null && (baseline == null || local.updatedAt > baseline)
        val remoteChanged = remote != null && (baseline == null || remote.updatedAt > baseline)
        val localDeleted = local?.deleted == true
        val remoteDeleted = remote == null && baseline != null

        // 本地已删除
        if (localDeleted) {
            return if (remote != null) {
                SyncAction.DeleteRemote(key)
            } else {
                SyncAction.Skip(key, "双侧均已删除")
            }
        }

        // 远端已删除（远端有基准但远端现在没有）
        if (remoteDeleted && local != null) {
            return if (localChanged) {
                // 本地改了又远端删了 → 冲突
                resolveConflict(key, local, remote, baseline, strategy)
            } else {
                SyncAction.DeleteLocal(key)
            }
        }

        // 本地有、远端无、无基准 → 新条目，上传
        if (local != null && remote == null && baseline == null) {
            return SyncAction.Upload(key, local.updatedAt)
        }

        // 远端有、本地无、无基准 → 新条目，下载
        if (remote != null && local == null && baseline == null) {
            return SyncAction.Download(key, remote.updatedAt, remote.etag)
        }

        // 本地有、远端无、有基准 → 远端删了
        if (local != null && remote == null && baseline != null) {
            return if (localChanged) {
                resolveConflict(key, local, remote, baseline, strategy)
            } else {
                SyncAction.DeleteLocal(key)
            }
        }

        // 远端有、本地无、有基准 → 本地删了
        if (remote != null && local == null && baseline != null) {
            return SyncAction.DeleteRemote(key)
        }

        // 两侧都有
        if (local != null && remote != null) {
            return when {
                localChanged && remoteChanged -> {
                    // 双侧都改了 → 冲突
                    resolveConflict(key, local, remote, baseline, strategy)
                }
                localChanged -> SyncAction.Upload(key, local.updatedAt)
                remoteChanged -> SyncAction.Download(key, remote.updatedAt, remote.etag)
                else -> SyncAction.Skip(key, "无变更")
            }
        }

        // 两侧都没有
        return SyncAction.Skip(key, "无数据")
    }

    /**
     * 冲突裁决。
     */
    private fun resolveConflict(
        key: String,
        local: LocalEntry?,
        remote: RemoteEntry?,
        baseline: Long?,
        strategy: ConflictStrategy
    ): SyncAction {
        return when (strategy) {
            ConflictStrategy.KEEP_LOCAL -> {
                if (local != null && !local.deleted) {
                    SyncAction.ConflictResolved(key, "local", SyncAction.Upload(key, local.updatedAt))
                } else {
                    SyncAction.Skip(key, "冲突：本地已删除")
                }
            }
            ConflictStrategy.KEEP_REMOTE -> {
                if (remote != null) {
                    SyncAction.ConflictResolved(key, "remote", SyncAction.Download(key, remote.updatedAt, remote.etag))
                } else {
                    SyncAction.Skip(key, "冲突：远端已删除")
                }
            }
            ConflictStrategy.NEWER_WINS -> {
                val lt = local?.updatedAt ?: 0
                val rt = remote?.updatedAt ?: 0
                if (lt >= rt) {
                    if (local != null && !local.deleted) {
                        SyncAction.ConflictResolved(key, "local(newer)", SyncAction.Upload(key, lt))
                    } else {
                        SyncAction.Skip(key, "冲突：本地已删除，远端较新但策略跳过")
                    }
                } else {
                    if (remote != null) {
                        SyncAction.ConflictResolved(key, "remote(newer)", SyncAction.Download(key, rt, remote.etag))
                    } else {
                        SyncAction.Skip(key, "冲突：远端已删除")
                    }
                }
            }
            ConflictStrategy.SKIP -> {
                SyncAction.Skip(key, "冲突：双侧均变更，策略为跳过")
            }
        }
    }

    /**
     * 从 SyncAction 提取关联的 key（供 SyncSession 使用）。
     */
    private fun actionKey(action: SyncAction): String = when (action) {
        is SyncAction.Upload -> action.key
        is SyncAction.Download -> action.key
        is SyncAction.DeleteRemote -> action.key
        is SyncAction.DeleteLocal -> action.key
        is SyncAction.Skip -> action.key
        is SyncAction.ConflictResolved -> action.key
    }
}
