package com.lanyue.reader.core.engine.pool

import com.lanyue.reader.core.engine.EngineState
import java.util.concurrent.atomic.AtomicReference

/**
 * 4.5 单实例状态。CAS 状态机，单实例单任务。
 *
 * 状态迁移：
 *   IDLE -> ACQUIRED -> RUNNING -> IDLE
 *   RUNNING -> POISONED / DISCARDED（终态，不回池）
 */
class RunnerInstance(
    val id: String,
    val tier: Int
) {
    private val stateRef = AtomicReference(EngineState.IDLE)

    /** 4.5 实例代数：重建后自增，用于拒绝旧实例的迟到回调。 */
    @Volatile var generation: Long = 1L
        private set

    val state: EngineState get() = stateRef.get()

    fun casIdleToAcquired(): Boolean = stateRef.compareAndSet(EngineState.IDLE, EngineState.ACQUIRED)

    fun markRunning() {
        stateRef.compareAndSet(EngineState.ACQUIRED, EngineState.RUNNING)
    }

    fun backToIdle(): Boolean =
        stateRef.compareAndSet(EngineState.RUNNING, EngineState.IDLE) ||
            stateRef.compareAndSet(EngineState.ACQUIRED, EngineState.IDLE)

    /** 4.5 污染标记：丢弃后不允许回池。 */
    fun poison() {
        stateRef.compareAndSet(EngineState.RUNNING, EngineState.POISONED)
        stateRef.compareAndSet(EngineState.ACQUIRED, EngineState.POISONED)
    }

    fun discard() {
        stateRef.set(EngineState.DISCARDED)
    }

    fun shutdown() {
        stateRef.set(EngineState.SHUTDOWN)
    }

    fun bumpGeneration() {
        generation += 1
    }

    val reusable: Boolean
        get() = state == EngineState.IDLE
}
