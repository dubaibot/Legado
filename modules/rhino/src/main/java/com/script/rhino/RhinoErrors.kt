package com.script.rhino

import java.util.concurrent.CancellationException

/**
 * JS 执行期间协程被取消（如分类切换取消加载协程）时抛出。
 *
 * 必须继承 [CancellationException]：协程机制依据异常类型识别取消，
 * 继承 Error 会被当作未捕获异常导致崩溃。
 * Android SDK 的 CancellationException 仅暴露 (String) 构造器，原始异常经 cause 属性保留。
 */
class RhinoInterruptError(override val cause: Throwable) : CancellationException(cause.message ?: cause.toString())

class RhinoRecursionError(): Error("Maximum recursion depth exceeded.")
