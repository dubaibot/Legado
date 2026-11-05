package com.lanyue.reader.core.engine.pool

import com.lanyue.reader.core.engine.EngineException
import com.lanyue.reader.core.engine.PoolTier
import com.lanyue.reader.core.observability.LocalMetrics

/**
 * 4.5 池化：高优/普通各 1；低端单池；子池不互借；单实例单任务。
 *
 * Reaper 超 2× 超时丢弃。池耗尽抛 POOL_EXHAUSTED。
 */
class JsRuntimePool(
    private val metrics: LocalMetrics,
    private val lowEndDevice: Boolean = false,
    private val acquireTimeoutMs: Long = 10_000L
) {
    private val high = mutableListOf<RunnerInstance>().apply { add(newInstance(PoolTier.HIGH)) }
    private val normal = if (lowEndDevice) emptyList() else listOf(newInstance(PoolTier.NORMAL))

    private val all: List<RunnerInstance> = high + normal

    private fun newInstance(tier: PoolTier) = RunnerInstance(
        id = "${tier.name}-${System.nanoTime()}",
        tier = tier.ordinal
    )

    /** 按优先级取实例；子池不互借（高优池空不回落到普通池）。 */
    suspend fun acquire(tier: PoolTier, traceId: String): RunnerInstance {
        val pool = when (tier) {
            PoolTier.HIGH -> high
            PoolTier.NORMAL -> if (lowEndDevice) high else normal
        }
        val start = System.currentTimeMillis()
        val deadline = start + acquireTimeoutMs

        while (System.currentTimeMillis() < deadline) {
            // 4.5 污染实例不回池：取用时直接抛错，由调用方重路由
            val candidate = pool.firstOrNull { it.reusable }
            if (candidate != null && candidate.casIdleToAcquired()) {
                metrics.poolWait(traceId, System.currentTimeMillis() - start)
                return candidate
            }
            kotlinx.coroutines.delay(25L)
        }
        throw EngineException.poolExhausted("no instance in ${tier.name} pool")
    }

    fun release(instance: RunnerInstance) {
        if (instance.state == com.lanyue.reader.core.engine.EngineState.POISONED) {
            // 4.5 丢弃不回池，调用方负责重建
            instance.discard()
            return
        }
        instance.backToIdle()
    }

    /** 4.5 Reaper：超 2× 任务超时的实例丢弃并幂等重建。 */
    fun reap(instance: RunnerInstance, taskTimeoutMs: Long, elapsedMs: Long) {
        if (elapsedMs > 2 * taskTimeoutMs) {
            instance.poison()
            instance.discard()
            instance.bumpGeneration()
        }
    }

    /** 4.13.4 后台缩容：退后台释放空闲实例，仅保留 1 个。 */
    fun shrinkOnBackground() {
        (high + normal).drop(1).forEach {
            if (it.reusable) it.discard()
        }
    }

    /** 4.13.4 低内存：自动全量回收，按需重建。 */
    fun onLowMemory() {
        all.forEach { it.discard() }
    }
}
