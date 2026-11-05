package com.lanyue.reader.source.rule

import com.lanyue.reader.core.engine.JsExecMode
import com.lanyue.reader.core.engine.JsRuntime
import com.lanyue.reader.core.engine.PoolTier
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.source.model.SortRule

/**
 * 1.4.4 sortUrl 动态生成。
 *
 * - 静态多行：按「分类名::url」解析，行分隔符支持换行与 &&
 * - 含 @js: / <js></js>：走 JsRuntime 主上下文 IIFE 生成
 * - 失败降级为「无分类」，只显示列表态
 * - 分类数量上限 100，超出截断并埋点
 */
class SortUrlParser(
    private val js: JsRuntime,
    private val metrics: LocalMetrics
) {

    companion object {
        const val MAX_SORTS = 100
        private const val LINE_SEP = "\n"
        private const val ALT_SEP = "&&"
    }

    data class Result(
        val sorts: List<SortRule>,
        val truncated: Boolean,
        val degraded: Boolean
    )

    suspend fun parse(sortUrl: String?, sourceId: String, traceId: String): Result {
        if (sortUrl.isNullOrBlank()) return Result(emptyList(), truncated = false, degraded = false)

        // 1.4.4 动态生成：@js: 或 <js></js>
        val isDynamic = sortUrl.contains("@js:") || sortUrl.contains("<js>")
        if (isDynamic) {
            val script = if (sortUrl.contains("@js:")) {
                sortUrl.substringAfter("@js:")
            } else {
                sortUrl.substringAfter("<js>").substringBefore("</js>")
            }
            val raw = runCatching {
                js.eval(
                    script = "(function(){ " + script + " })()",
                    mode = JsExecMode.MAIN,
                    tier = PoolTier.NORMAL,
                    traceId = traceId
                ) { _, _ -> true }
            }.getOrNull()

            if (raw.isNullOrBlank()) {
                // 1.4.4 失败降级为「无分类」
                metrics.degradation(traceId, "sort_url_js_failed")
                return Result(emptyList(), truncated = false, degraded = true)
            }
            return clamp(parseStatic(raw), traceId)
        }

        return clamp(parseStatic(sortUrl), traceId)
    }

    private fun clamp(parsed: List<SortRule>, traceId: String): Result {
        val truncated = parsed.size > MAX_SORTS
        if (truncated) {
            metrics.degradation(traceId, "sort_url_truncated_at_" + MAX_SORTS)
        }
        return Result(parsed.take(MAX_SORTS), truncated, degraded = false)
    }

    /** 静态解析：「名::url」，一行一个，也支持 && 串联。 */
    fun parseStatic(raw: String): List<SortRule> {
        val lines = raw.split(LINE_SEP, ALT_SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return lines.mapNotNull { line ->
            val idx = line.indexOf("::")
            if (idx <= 0) return@mapNotNull null
            val name = line.substring(0, idx).trim()
            val url = line.substring(idx + 2).trim()
            if (name.isEmpty() || url.isEmpty()) null else SortRule(name, url)
        }
    }
}
