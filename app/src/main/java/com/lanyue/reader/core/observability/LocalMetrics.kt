package com.lanyue.reader.core.observability

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 4.13.6 / ADR-020 可观测性裁决。
 *
 * 硬约束：本类不得有任何网络出口。埋点仅追加到本地 JSONL，
 * 由「设置 → 高级 → 调试信息」手动导出（CSV/JSON 到 SAF）。
 * CI 门禁 tools/check_no_telemetry.py 会扫描本文件禁止出现网络类调用。
 */
class LocalMetrics(private val logDir: File) {

    companion object {
        private const val TAG = "LocalMetrics"
        const val UPLOAD_ENABLED = false
    }

    private val file = File(logDir, "metrics.jsonl")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    @Synchronized
    fun record(event: String, traceId: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching {
            if (!logDir.exists()) logDir.mkdirs()
            val sb = StringBuilder()
            sb.append('{')
            sb.append("\"ts\":").append(quote(dateFormat.format(Date()))).append(',')
            sb.append("\"event\":").append(quote(event)).append(',')
            sb.append("\"traceId\":").append(quote(traceId))
            fields.forEach { (k, v) ->
                sb.append(',').append(quote(k)).append(':').append(quote(v?.toString() ?: ""))
            }
            sb.append('}')
            file.appendText(sb.toString() + "\n")
        }.onFailure { Log.w(TAG, "metrics write failed: ${it.message}") }
    }

    private fun quote(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- 4.13.6 指标族 ----

    fun channelHit(traceId: String, channel: String, host: String) =
        record("channel_hit", traceId, mapOf("channel" to channel, "host" to host))

    fun degradation(traceId: String, reason: String) =
        record("degrade", traceId, mapOf("reason" to reason))

    fun poolWait(traceId: String, waitMs: Long) =
        record("pool_wait", traceId, mapOf("waitMs" to waitMs))

    fun renderCrash(traceId: String, detail: String) =
        record("render_crash", traceId, mapOf("detail" to detail))

    fun success(traceId: String, op: String, costMs: Long) =
        record("success", traceId, mapOf("op" to op, "costMs" to costMs))

    fun failure(traceId: String, op: String, code: String) =
        record("failure", traceId, mapOf("op" to op, "code" to code))

    /**
     * 设置 → 高级 → 调试信息 导出（唯一出口）。
     * 导出内容不含设备标识；崩溃日志与「纠错」按钮走同一通道。
     */
    fun exportTo(target: File): Boolean = runCatching {
        if (!file.exists()) return false
        target.writeBytes(file.readBytes())
        true
    }.getOrDefault(false)

    fun clear() {
        runCatching { file.delete() }
    }
}
