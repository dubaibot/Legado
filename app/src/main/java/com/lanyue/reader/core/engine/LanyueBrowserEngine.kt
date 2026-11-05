package com.lanyue.reader.core.engine

import com.lanyue.reader.core.engine.pool.JsRuntimePool
import com.lanyue.reader.core.engine.pool.JsRunner
import com.lanyue.reader.core.engine.pool.RunnerInstance
import com.lanyue.reader.core.observability.LocalMetrics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 4.4 BrowserEngine 实现。
 *
 * 生命周期契约：
 * - warmUp()：初始化、预建池、加载 runner；至少 1 实例就绪才返回
 * - shutdown()：幂等；先 poison 再 drain(SHUTDOWN)；关闭态不可重启
 * - ADR-022：懒预热。纯 API 请求与本地列表不触发 warmUp，由调用方决定
 */
class LanyueBrowserEngine(
    override val net: NetworkDispatcher,
    override val dom: DomParser,
    override val sniffer: Sniffer,
    private val pool: JsRuntimePool,
    private val metrics: LocalMetrics,
    private val runnerLoader: suspend () -> Boolean
) : BrowserEngine {

    private val shutdownFlag = AtomicBoolean(false)
    private val poisonFlag = AtomicBoolean(false)
    private val warmMutex = Mutex()
    @Volatile private var warmed = false

    private val runners = mutableMapOf<String, JsRunner>()

    /** 4.4 / 4.5 JsRuntime 门面。 */
    override val js: JsRuntime = object : JsRuntime {
        override suspend fun eval(
            script: String,
            mode: JsExecMode,
            tier: PoolTier,
            traceId: String,
            dispatch: (taskId: String, script: String) -> Boolean
        ): String {
            checkedEntry()
            val instance: RunnerInstance = pool.acquire(tier, traceId)
            val runner = runners.getOrPut(instance.id) { JsRunner(instance) }
            val start = System.currentTimeMillis()
            try {
                instance.markRunning()
                val result = runner.execute(script, mode, dispatch)
                metrics.success(traceId, "js_eval", System.currentTimeMillis() - start)
                return result
            } catch (e: EngineException) {
                if (!e.silent) metrics.failure(traceId, "js_eval", e.code.name)
                throw e
            } finally {
                pool.reap(instance, /* taskTimeoutMs */ 20_000L, System.currentTimeMillis() - start)
                pool.release(instance)
            }
        }

        override fun onRenderProcessGone(error: Throwable) {
            // 4.13.5 池级批量重建：多 WebView 共享渲染进程，崩溃后全部实例受影响
            runners.values.forEach { it.onRenderProcessGone(error) }
            metrics.renderCrash("engine", error.message ?: "unknown")
        }

        override suspend fun drainPending(): Int =
            runners.values.sumOf { it.drainPending() }
    }

    /** 4.4 入口检查：先 SHUTDOWN 再 POISONED。 */
    private fun checkedEntry() {
        if (shutdownFlag.get()) throw EngineException.shutdown()
        if (poisonFlag.get()) throw EngineException.poisoned()
    }

    override suspend fun warmUp() {
        warmMutex.withLock {
            if (warmed || shutdownFlag.get()) return
            checkedEntry()
            // 4.4 加载 runner（WebViewAssetLoader + Polyfill）；至少 1 实例就绪
            val loaded = runnerLoader()
            if (!loaded) {
                throw EngineException(
                    EngineErrorCode.SCRIPT_EXCEPTION,
                    "runner load failed"
                )
            }
            warmed = true
        }
    }

    override suspend fun shutdown() {
        if (!shutdownFlag.compareAndSet(false, true)) return // 幂等
        // 4.4 先 poison 再 drain(SHUTDOWN)
        poisonFlag.set(true)
        js.drainPending()
        runners.values.forEach { runner ->
            val empty = runner.assertEmptyAfterDrain()
            if (!empty) metrics.failure("shutdown", "drain_residual", "PENDING_LEFT")
        }
        // 关闭态 discard 只销毁不重建
        pool.onLowMemory()
    }

    val isWarm: Boolean get() = warmed
}
