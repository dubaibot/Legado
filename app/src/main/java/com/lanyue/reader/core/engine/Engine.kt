package com.lanyue.reader.core.engine

import com.lanyue.reader.core.security.SourcePermissionLevel

/**
 * 4.4 BrowserEngine 抽象层。
 * UI/源调度层只依赖本文件，不直接接触 WebView / Cronet / OkHttp。
 */
interface BrowserEngine {
    val js: JsRuntime
    val net: NetworkDispatcher
    val dom: DomParser
    val sniffer: Sniffer

    /** 4.4：初始化、预建池、加载 runner；至少 1 实例就绪才返回。 */
    suspend fun warmUp()

    /** 4.4：幂等；先 poison 再 drain(SHUTDOWN)；关闭态不可重启。 */
    suspend fun shutdown()
}

/** 4.5 三种 JS 执行环境。 */
enum class JsExecMode { MAIN, SANDBOX_REUSE, SANDBOX_ISOLATED }

/** 4.3 内核错误码。 */
enum class EngineErrorCode {
    TIMEOUT,
    SCRIPT_EXCEPTION,
    POISONED,
    DISCARDED,
    SHUTDOWN,
    RENDER_GONE,
    POOL_EXHAUSTED,
    RATE_LIMITED,
    SANDBOX_SUBMIT_TIMEOUT,
    CANCELLED
}

/**
 * 4.3 强制约束：EngineException 禁止继承 CancellationException。
 * 否则协程取消语义会被内核异常污染，导致上游误判取消来源。
 */
class EngineException(
    val code: EngineErrorCode,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /** 4.3：CANCELLED 仅埋点，不提示用户。 */
    val silent: Boolean get() = code == EngineErrorCode.CANCELLED

    companion object {
        fun timeout(msg: String = "engine timeout") =
            EngineException(EngineErrorCode.TIMEOUT, msg)

        fun poisoned(msg: String = "instance poisoned") =
            EngineException(EngineErrorCode.POISONED, msg)

        fun discarded(msg: String = "instance discarded") =
            EngineException(EngineErrorCode.DISCARDED, msg)

        fun shutdown(msg: String = "engine shutdown") =
            EngineException(EngineErrorCode.SHUTDOWN, msg)

        fun poolExhausted(msg: String = "pool exhausted") =
            EngineException(EngineErrorCode.POOL_EXHAUSTED, msg)

        fun sandboxSubmitTimeout(msg: String = "sandbox submit timeout") =
            EngineException(EngineErrorCode.SANDBOX_SUBMIT_TIMEOUT, msg)
    }
}

/** 4.3 产品级错误码（UI 层使用，与内核错误码分离）。 */
enum class ProductErrorCode {
    CONTENT_EMPTY,
    PARSE_FAILED,
    DECODE_ERROR,
    DANMAKU_NOT_MATCHED,
    LOGIN_REQUIRED,
    SOURCE_INVALID
}

class ProductException(
    val code: ProductErrorCode,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)

/**
 * 4.6 网络层统一异常。所有通道（OkHttp/Cronet/WebView）失败都包装成它，
 * 上层不需要区分通道来源。
 */
class NetworkException(
    override val message: String,
    val httpCode: Int? = null,
    override val cause: Throwable? = null
) : Exception(message, cause)

/** 4.5 池内实例等级。子池不互借。 */
enum class PoolTier { HIGH, NORMAL }

/** 4.5 实例状态机（CAS 状态机）。 */
enum class EngineState { IDLE, ACQUIRED, RUNNING, POISONED, DISCARDED, SHUTDOWN }

/** 4.5 单源/单域名限速声明，来自源 JSON 的 concurrentRate 字段。 */
data class RateLimit(val permits: Int, val periodMs: Long)

/** 4.5 沙箱权限分级（5.5 源权限分级表的运行时映射）。 */
data class SandboxProfile(
    val mode: JsExecMode,
    val level: SourcePermissionLevel,
    val allowDom: Boolean,
    val allowPost: Boolean
)
