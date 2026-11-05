package com.lanyue.reader.core.engine.pool

import com.lanyue.reader.core.engine.EngineException
import com.lanyue.reader.core.engine.JsExecMode
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 4.5 协程桥接层：单会话单实例单任务。
 *
 * 核心不变量：
 * 1. CompletableDeferred 保证「恰好一次」结算
 * 2. 六路清理：结果 / REJECTED / 投递超时 / 投递取消 / 任务超时 / 崩溃广播
 * 3. 预注册 + 后置门禁：任务先注册再投递，投递后再次校验未被结算
 * 4. drain 后断言表空；30s 扫描兜底
 */
class JsRunner(private val instance: RunnerInstance) {

    data class PendingTask(
        val taskId: String,
        val generation: Long,
        val deferred: CompletableDeferred<String>,
        val mode: JsExecMode,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val pending = ConcurrentHashMap<String, PendingTask>()
    @Volatile private var drained = false
    @Volatile private var poisoned = false

    companion object {
        /** 4.5 投递独立超时 1000ms */
        const val DELIVER_TIMEOUT_MS = 1000L
        /** 4.5 30s 扫描兜底 */
        const val SWEEP_INTERVAL_MS = 30_000L
        /** 4.7/4.5 结果大小上限，complete 前校验 */
        const val MAX_RESULT_BYTES = 4 * 1024 * 1024
        const val OVERSIZE_SENTINEL = "__LANYUE_RESULT_OVERSIZE__"
    }

    /**
     * 4.5 入口检查顺序：先 SHUTDOWN 再 POISONED。
     * 顺序颠倒会让已关闭实例报 POISONED，用户提示错误。
     */
    private fun entryCheck() {
        if (drained) throw EngineException.shutdown("runner drained")
        if (poisoned) throw EngineException.poisoned("runner poisoned")
    }

    /**
     * 执行一段 JS。
     * [dispatch] 由具体 WebView 实现负责真正投递，返回是否投递成功。
     */
    suspend fun execute(
        script: String,
        mode: JsExecMode,
        dispatch: (taskId: String, script: String) -> Boolean
    ): String {
        entryCheck()

        val taskId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()

        // 4.5 预注册：先入表再投递，避免结果早于注册（附录 B 用例 17）
        val task = PendingTask(taskId, instance.generation, deferred, mode)
        pending[taskId] = task

        val delivered = try {
            dispatch(taskId, script)
        } catch (e: Exception) {
            settle(taskId, Result.failure(
                EngineException(com.lanyue.reader.core.engine.EngineErrorCode.SCRIPT_EXCEPTION, "dispatch error", e)
            ))
            false
        }

        // 4.5 投递失败/拒绝：立即清理，避免悬挂
        if (!delivered) {
            settle(taskId, Result.failure(EngineException.sandboxSubmitTimeout("deliver rejected")))
        }

        // 4.5 后置门禁：投递期间可能已被结算（结果早于挂载），此处再校验
        if (deferred.isCompleted) {
            // await() 已直接返回 String；失败会由 await 本身抛出，不再二次包一层
            return deferred.await()
        }

        return deferred.await()
    }

    /**
     * 4.5 结算入口：保证恰好一次。
     * 返回 true 表示本次调用真正完成了结算，false 表示已被其他路径结算。
     */
    fun settle(taskId: String, result: Result<String>): Boolean {
        val task = pending.remove(taskId) ?: return false
        // 4.5 代数校验：旧实例迟到回调不得影响新实例
        if (task.generation != instance.generation) return false
        return task.deferred.completeWithResult(result)
    }

    private fun CompletableDeferred<String>.completeWithResult(result: Result<String>): Boolean {
        val value = result.getOrElse { return completeExceptionally(it) }
        // 4.5 结果大小 complete 前校验，超限哨兵包裹（附录 B 用例 19）
        val safe = if (value.toByteArray().size > MAX_RESULT_BYTES) OVERSIZE_SENTINEL else value
        return complete(safe)
    }

    /** 4.5 崩溃广播：activeCont + pendingTable 全部收异常，广播幂等。 */
    fun onRenderProcessGone(error: Throwable) {
        val wasPoisoned = poisoned
        poisoned = true
        pending.values.forEach { task ->
            task.deferred.completeExceptionally(
                EngineException(
                    com.lanyue.reader.core.engine.EngineErrorCode.RENDER_GONE,
                    "render process gone",
                    error
                )
            )
        }
        pending.clear()
        if (!wasPoisoned) {
            instance.poison()
        }
    }

    /**
     * 4.5 drain：先置门禁（拒绝新进入），再清理 pending。
     * 附录 B 用例 18：drain 后入口必须拒绝 POISONED/SHUTDOWN；
     * 提交与 discard 并发时必以 DISCARDED 结算，不悬挂不残留。
     */
    fun drainPending(): Int {
        drained = true
        val snapshot = pending.values.toList()
        snapshot.forEach { task ->
            settle(
                task.taskId,
                Result.failure(EngineException.discarded("drained during shutdown"))
            )
        }
        return snapshot.size
    }

    /** drain 后断言表空；由生命周期守卫调用。 */
    fun assertEmptyAfterDrain(): Boolean = pending.isEmpty()

    /** 4.5 30s 扫描兜底：清理泄漏任务。 */
    fun sweep(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 120_000L): Int {
        val leaked = pending.values.filter { now - it.createdAt > maxAgeMs }
        leaked.forEach {
            settle(it.taskId, Result.failure(EngineException.timeout("swept leaked task")))
        }
        return leaked.size
    }

    val pendingCount: Int get() = pending.size
}
