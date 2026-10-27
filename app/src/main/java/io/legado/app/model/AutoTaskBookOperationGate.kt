package io.legado.app.model

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes task operations for one book while allowing unrelated books to
 * proceed independently.  The gate is deliberately small; callers own the
 * actual refresh/cache work and cancellation remains structured.
 *
 * Lock entries are reference counted and recycled once no caller needs them,
 * so the map cannot grow unbounded over a long-running session.
 */
object AutoTaskBookOperationGate {
    private val locks = ConcurrentHashMap<String, LockEntry>()

    private class LockEntry {
        val mutex = Mutex()
        val holders = AtomicInteger(0)
    }

    suspend fun <T> withBook(bookUrl: String, block: suspend () -> T): T {
        val normalized = bookUrl.trim()
        require(normalized.isNotEmpty()) { "bookUrl is blank" }
        val entry = locks.compute(normalized) { _, old ->
            val existing = old ?: LockEntry()
            existing.holders.incrementAndGet()
            existing
        }!!
        try {
            entry.mutex.lock()
            try {
                return block()
            } finally {
                entry.mutex.unlock()
            }
        } finally {
            locks.compute(normalized) { _, current ->
                if (current === entry && entry.holders.decrementAndGet() == 0) null else current
            }
        }
    }
}
