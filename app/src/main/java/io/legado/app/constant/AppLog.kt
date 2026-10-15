package io.legado.app.constant

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object AppLog {

    private val mLogs = arrayListOf<Triple<Long, String, Throwable?>>()

    val logs get() = mLogs.toList()

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    @Synchronized
    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (AppConfig.recordLog) {
            put(message, throwable)
        }
    }

    // ===================== [D5] 支撑性追加（清单 2.1.3 视频管线） =====================
    // m AppLog.kt:29/112-126/278 薄适配：m 为 putEntry(message, throwable, toast, Level) 分级体系，
    // 工作区暂无该体系，三级变体统一委托 put()（单级记录语义）；putDebugWithTag 对齐 m
    // 「tag 定向 logcat + recordLog 门禁 + 入列」语义，存储复用工作区 mLogs。
    // 完整 Level 体系升级登记于 D5 设计文档依赖台账（低优先级，阶段 1 回归前决策）。

    /** 日志级别（m AppLog 同名枚举，供视频栈分级埋点使用） */
    enum class Level { ERROR, WARN, INFO, DEBUG }

    /** 订阅源/视频链路日志 Tag（m AppLog.kt:29 逐字） */
    const val TAG_RSS = "Rss"

    @Synchronized
    fun putError(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        put(message, throwable, toast)
    }

    @Synchronized
    fun putWarn(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        put(message, throwable, toast)
    }

    @Synchronized
    fun putInfo(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        put(message, throwable, toast)
    }

    fun putDebugWithTag(
        tag: String,
        message: String?,
        throwable: Throwable? = null,
        level: Level = Level.ERROR
    ) {
        message ?: return
        val safeMsg = truncateForLog(message)
        val fileMsg = if (throwable == null) safeMsg else "$safeMsg\n${throwable.stackTraceToString()}"
        // V-004-P0 语义对齐（m AppLog.kt:285-291）：recordLog 关闭时 ERROR/WARN/INFO 仍输出到 logcat
        if (!AppConfig.recordLog) {
            if (level != Level.DEBUG) {
                Log.e(tag, fileMsg)
            }
            return
        }
        LogUtils.d(tag, fileMsg)
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), safeMsg, throwable))
        if (BuildConfig.DEBUG || level != Level.DEBUG) {
            Log.e(tag, safeMsg, throwable)
        }
    }

    /** m AppLog.truncateSafely 等价实现（通用截断分支，data URI 专项分支 m 内部使用，此处无消费方） */
    private fun truncateForLog(msg: String, maxLen: Int = 2000): String {
        if (msg.length <= maxLen) return msg
        val codePointCount = msg.codePointCount(0, msg.length)
        val safeEnd = if (codePointCount <= maxLen) msg.length else msg.offsetByCodePoints(0, maxLen)
        return "${msg.substring(0, safeEnd)}...(已截断，总长${msg.length}字符)"
    }

    // ===================== [D5] 追加结束 =====================

}