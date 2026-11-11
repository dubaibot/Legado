package com.lanyue.reader.core.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 崩溃兜底：只落本地文件，不上报。
 *
 * 三条设计取舍：
 * 1. **不上报**。仓库立项就是无埋点、无遥测，崩溃栈同样不能出厂。
 *    文件留在应用私有目录，用户要给出时自己走「调试信息导出」。
 * 2. **时间戳统一 UTC**。跨设备、跨时区对日志时，本地时区是最常见的
 *    误判来源；文件名与正文都用同一套 UTC 格式，排序才和真实先后一致。
 * 3. **保留有限份数**。崩溃可能连环发生（每帧都崩），无限追加会把
 *    磁盘吃满，反而让用户连「卸载前导出」都做不到。
 *
 * 处理完仍然把异常交还给原处理器：吞掉异常会让进程半死不活，
 * 用户看到的是「点不动」而不是「崩了」，比崩溃更难判断。
 */
class CrashGuard(
    private val dir: File,
    private val keepFiles: Int = DEFAULT_KEEP,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val DEFAULT_KEEP = 5
        const val PREFIX = "crash-"
        const val SUFFIX = ".txt"
        private const val REPORT_HEADER = "-- lanyue crash report --"
        private const val MAX_CAUSE_DEPTH = 8
        private const val MAX_FRAMES = 64
        private const val MAX_THREAD_TAG = 24

        /**
         * UTC + 毫秒精度。与 `LocalMetrics` 的 `yyyy-MM-dd'T'HH:mm:ss.SSSZ` 同族，
         * 但固定 UTC，避免同一批日志在不同设备上无法按文件名排序。
         */
        fun formatTimestamp(ms: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date(ms))
        }

        /**
         * 线程名会进文件名，而线程名可能带 `/`、空格、`:`（协程默认名就带空格）。
         * 不净化会直接触发「文件名非法」的二次异常——崩溃处理里再崩一次最难查。
         */
        fun sanitizeThreadTag(threadName: String?): String {
            val raw = threadName?.trim().orEmpty()
            if (raw.isEmpty()) return "unknown"
            val cleaned = raw.map { ch ->
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
            }.joinToString("")
            return if (cleaned.length > MAX_THREAD_TAG) cleaned.take(MAX_THREAD_TAG) else cleaned
        }
    }

    fun fileName(timestamp: Long, threadName: String?): String =
        "$PREFIX$timestamp-${sanitizeThreadTag(threadName)}$SUFFIX"

    fun buildReport(threadName: String?, timestamp: Long, error: Throwable): String {
        val sb = StringBuilder()
        sb.append(REPORT_HEADER).append('\n')
        sb.append("时间: ").append(formatTimestamp(timestamp)).append('\n')
        sb.append("线程: ").append(threadName?.takeIf { it.isNotBlank() } ?: "unknown").append('\n')

        // cause 链可能成环（自定义异常覆盖 cause 时会出现），用身份集合截断。
        val seen = HashSet<Throwable>()
        var cursor: Throwable? = error
        var depth = 0
        while (cursor != null && depth < MAX_CAUSE_DEPTH && seen.add(cursor)) {
            sb.append(if (depth == 0) "异常: " else "原因$depth: ")
            sb.append(cursor.javaClass.name)
            cursor.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it) }
            sb.append('\n')
            cursor.stackTrace.take(MAX_FRAMES).forEach { frame ->
                sb.append("    at ").append(frame.toString()).append('\n')
            }
            cursor = cursor.cause
            depth++
        }
        return sb.toString()
    }

    /**
     * 落盘；任何失败都返回 null 而不抛。
     * 崩溃路径上不允许再产生未捕获异常——那会把原始堆栈彻底盖掉。
     */
    fun handle(threadName: String?, error: Throwable): File? = runCatching {
        if (!dir.exists()) dir.mkdirs()
        val now = clock()
        val target = File(dir, fileName(now, threadName))
        target.writeText(buildReport(threadName, now, error), Charsets.UTF_8)
        prune()
        target
    }.getOrNull()

    /** 按文件名里的时间戳升序删旧，只保留最新的 [keepFiles] 份。 */
    fun prune(): List<File> {
        val reports = listReports()
        val drop = reports.size - keepFiles
        if (drop <= 0) return emptyList()
        return reports.take(drop).onEach { runCatching { it.delete() } }
    }

    /** 已存在的报告，按时间戳升序（最旧在前）。 */
    fun listReports(): List<File> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?: return emptyList()
        return files.sortedWith(
            compareBy<File> { sortKey(it) }.thenBy { it.name }
        )
    }

    private fun sortKey(f: File): Long =
        f.name.removePrefix(PREFIX).substringBefore('-').toLongOrNull() ?: 0L

    /**
     * 生成可直接交给 `Thread.setDefaultUncaughtExceptionHandler` 的处理器。
     * 只有 [handle] 成功写出文件时才转交原处理器——写失败也照样转交，
     * 因为「不留痕」不该成为「不让系统收尸」的理由。
     */
    fun createHandler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, throwable ->
            handle(thread?.name, throwable)
            previous?.uncaughtException(thread, throwable)
        }
}
