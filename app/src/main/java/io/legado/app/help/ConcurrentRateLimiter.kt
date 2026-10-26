package io.legado.app.help

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.ConcurrentException
import io.legado.app.model.analyzeRule.AnalyzeUrl.ConcurrentRecord
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class ConcurrentRateLimiter(source: BaseSource?) {

    companion object {
        val concurrentRecordMap = ConcurrentHashMap<String, ConcurrentRecord>()
        /**
         * 更新并发率
         */
        fun updateConcurrentRate(key: String, concurrentRate: String) {
            concurrentRecordMap.compute(key) { _, record ->
                try {
                    val rateIndex = concurrentRate.indexOf("/")
                    when {
                        rateIndex > 0 -> {
                            val accessLimit = concurrentRate.take(rateIndex).toInt()
                            val interval = concurrentRate.substring(rateIndex + 1).toInt()
                            if (accessLimit <= 0 || interval <= 0) throw NumberFormatException()
                            ConcurrentRecord(
                                record?.time ?: System.currentTimeMillis(),
                                accessLimit,
                                interval,
                                record?.frequency ?: 0
                            )
                        }
                        concurrentRate.toInt() > 0 -> {
                            ConcurrentRecord(
                                record?.time ?: System.currentTimeMillis(),
                                1,
                                concurrentRate.toInt(),
                                record?.frequency ?: 0
                            )
                        }
                        else -> record
                    }
                } catch (_: NumberFormatException) {
                    record
                }
            }
        }

        /**
         * 取两个并发率中限制更严格（吞吐量更低）者
         * null/空/"0" 表示无限制，不参与比较
         */
        fun effectiveRate(rate1: String?, rate2: String?): String? {
            val t1 = throughput(rate1)
            val t2 = throughput(rate2)
            return if (t1 <= t2) rate1 else rate2
        }

        /**
         * 删除指定源的并发限流记录
         * 调用方：SourceHelp.deleteBookSourceInternal / deleteRssSourceInternal（删源时清理，避免内存泄漏）
         */
        fun clearRecord(key: String) {
            concurrentRecordMap.remove(key)
        }

        /**
         * 计算并发率的吞吐量（请求/秒），用于比较限制严格程度
         * null/空/"0" 返回正无穷（无限制，不参与比较）
         */
        private fun throughput(rate: String?): Double {
            if (rate.isNullOrBlank() || rate == "0") return Double.POSITIVE_INFINITY
            return try {
                val idx = rate.indexOf("/")
                if (idx > 0) {
                    val limit = rate.take(idx).toDouble()
                    val ms = rate.substring(idx + 1).toDouble()
                    if (limit <= 0 || ms <= 0) Double.POSITIVE_INFINITY
                    else limit * 1000.0 / ms
                } else {
                    val ms = rate.toDouble()
                    if (ms <= 0) Double.POSITIVE_INFINITY
                    else 1000.0 / ms
                }
            } catch (_: NumberFormatException) {
                Double.POSITIVE_INFINITY
            }
        }
    }

    private val concurrentRate = source?.concurrentRate
    private val key = source?.getKey()
    /**
     * 开始访问,并发判断
     */
    @Throws(ConcurrentException::class)
    private fun fetchStart(): ConcurrentRecord? {
        if (concurrentRate.isNullOrEmpty() || concurrentRate == "0") {
            return null
        }
        val key = key ?: return null
        var isNewRecord = false
        val fetchRecord = concurrentRecordMap.computeIfAbsent(key) {
            isNewRecord = true
            val rateIndex = concurrentRate.indexOf("/")
            if (rateIndex > 0) {
                val accessLimit = concurrentRate.take(rateIndex).toIntOrNull() ?: 1
                val interval = concurrentRate.substring(rateIndex + 1).toIntOrNull() ?: 0
                ConcurrentRecord(System.currentTimeMillis(), accessLimit, interval, 1)
            }
            else {
                ConcurrentRecord(System.currentTimeMillis(),1,concurrentRate.toIntOrNull() ?: 0, 1)
            }
        }
        if (isNewRecord) return fetchRecord
        val waitTime: Long = synchronized(fetchRecord) {
            //并发控制为 次数/毫秒 , 非并发实际为1/毫秒
            val nextTime = fetchRecord.time + fetchRecord.interval.toLong()
            val nowTime = System.currentTimeMillis()
            if (nowTime >= nextTime) {
                //已经过了限制时间,重置开始时间
                fetchRecord.time = nowTime
                fetchRecord.frequency = 1
                return@synchronized 0
            }
            if (fetchRecord.frequency < fetchRecord.accessLimit) {
                fetchRecord.frequency ++
                return@synchronized 0
            } else {
                return@synchronized nextTime - nowTime
            }
        }
        if (waitTime > 0) {
            throw ConcurrentException(
                "根据并发率还需等待${waitTime}毫秒才可以访问",
                waitTime = waitTime
            )
        }
        return fetchRecord
    }

    /**
     * 获取并发记录，若处于并发限制状态下则会等待
     */
    suspend fun getConcurrentRecord(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                delay(e.waitTime)
            }
        }
    }

    fun getConcurrentRecordBlocking(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                Thread.sleep(e.waitTime)
            }
        }
    }

    suspend inline fun <T> withLimit(block: () -> T): T {
        getConcurrentRecord()
        return block()
    }

    inline fun <T> withLimitBlocking(block: () -> T): T {
        getConcurrentRecordBlocking()
        return block()
    }

}
