package com.lanyue.reader.core.engine

/**
 * 4.5 JsRuntime 门面。
 *
 * 只对外暴露「跑一段 JS 拿结果」，池化、仲裁、清理、限流全部收在实现里，
 * 源适配层不感知内核细节。
 */
interface JsRuntime {
    /**
     * 执行 JS。
     *
     * @param mode 执行环境（MAIN / SANDBOX_REUSE / SANDBOX_ISOLATED）
     * @param tier 池优先级；子池不互借
     * @param dispatch 由具体 WebView 实例负责投递，返回是否投递成功
     * @return JS 结果字符串；超限时返回 JsRunner.OVERSIZE_SENTINEL
     * @throws EngineException
     */
    suspend fun eval(
        script: String,
        mode: JsExecMode = JsExecMode.MAIN,
        tier: PoolTier = PoolTier.NORMAL,
        traceId: String = "",
        dispatch: (taskId: String, script: String) -> Boolean
    ): String

    /** 4.13.5 渲染进程崩溃广播（幂等）。 */
    fun onRenderProcessGone(error: Throwable)

    /** 4.4/4.5 关闭前清空 pending，返回清理条数。 */
    suspend fun drainPending(): Int
}
