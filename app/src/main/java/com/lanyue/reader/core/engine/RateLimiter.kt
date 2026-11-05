package com.lanyue.reader.core.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 4.6 限速：全局并发上限 + 源级 concurrentRate 独立令牌桶。
 * 令牌桶按 key 懒创建，避免源数量多时预分配。
 */
class TokenBucket(
    private val permits: Int,
    private val periodMs: Long
) {
    private var tokens: Double = permits.toDouble()
    private var lastRefill: Long = System.currentTimeMillis()

    @Synchronized
    fun tryAcquire(): Boolean {
        refill()
        if (tokens >= 1.0) {
            tokens -= 1.0
            return true
        }
        return false
    }

    @Synchronized
    fun nextWaitMs(): Long {
        refill()
        if (tokens >= 1.0) return 0L
        val ratePerMs = permits.toDouble() / periodMs.toDouble()
        val need = 1.0 - tokens
        return maxOf(1L, (need / ratePerMs).toLong())
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefill
        if (elapsed <= 0) return
        val ratePerMs = permits.toDouble() / periodMs.toDouble()
        tokens = minOf(permits.toDouble(), tokens + elapsed * ratePerMs)
        lastRefill = now
    }
}

/**
 * 4.6：源级独立令牌桶 + 登录态源串行化。
 * 登录态源（带 loginUrl 的源）同一时刻只允许一个在途请求，避免 Cookie 串号。
 */
class SourceRateLimiter(
    private val defaultPermits: Int = 10,
    private val defaultPeriodMs: Long = 1000L
) {
    private val buckets = ConcurrentHashMap<String, TokenBucket>()
    private val serialLocks = ConcurrentHashMap<String, Mutex>()

    fun bucketFor(sourceId: String, limit: RateLimit?): TokenBucket =
        buckets.getOrPut(sourceId) {
            TokenBucket(limit?.permits ?: defaultPermits, limit?.periodMs ?: defaultPeriodMs)
        }

    /** 阻塞式取令牌；超时返回 false，由调用方转 RATE_LIMITED。 */
    suspend fun acquire(sourceId: String, limit: RateLimit?, timeoutMs: Long = 5_000L): Boolean {
        val bucket = bucketFor(sourceId, limit)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (bucket.tryAcquire()) return true
            kotlinx.coroutines.delay(minOf(bucket.nextWaitMs(), 50L))
        }
        return false
    }

    fun tryAcquire(sourceId: String, limit: RateLimit?): Boolean =
        bucketFor(sourceId, limit).tryAcquire()

    /** 登录态源串行化：对该 sourceId 持有独占锁执行。 */
    suspend fun <T> serialized(sourceId: String, block: suspend () -> T): T {
        val mutex = serialLocks.getOrPut(sourceId) { Mutex() }
        return mutex.withLock { block() }
    }

    fun clear(sourceId: String) {
        buckets.remove(sourceId)
        serialLocks.remove(sourceId)
    }
}

/** 4.6 全局并发闸：搜索页「全局 ≤10 源并发」的口径由它兜底。 */
class GlobalConcurrencyGate(private val maxConcurrent: Int = 10) {
    private val semaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrent)

    suspend fun <T> withPermit(block: suspend () -> T): T =
        semaphore.withPermit { block() }
}
