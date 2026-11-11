package com.lanyue.reader.core.diagnostics

/**
 * 构建类型在这里只用来决定「日志下限」，不承载任何业务分支。
 *
 * 为什么不用 `BuildConfig.DEBUG`：它由 AGP 代码生成，而 `tools/test_jvm.sh`
 * 用 kotlinc 直编源码、不跑 AGP。诊断层一旦引用 BuildConfig 就无法离线单测，
 * 只能等到真机才能发现判断写反了。改成从 `ApplicationInfo.FLAG_DEBUGGABLE`
 * 推导后，判断退化成纯函数，编译期与单测阶段都能锁定。
 */
enum class BuildType { DEBUG, RELEASE }

enum class LogLevel(val priority: Int) {
    VERBOSE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4)
}

object LogPolicy {

    fun fromDebuggable(debuggable: Boolean): BuildType =
        if (debuggable) BuildType.DEBUG else BuildType.RELEASE

    /**
     * release 只保留 WARN 及以上。
     *
     * 省的不是「输出看起来干不干净」，而是冷启动与翻页高频路径上的字符串拼接：
     * debug 级日志即使被过滤，参数表达式也已经在调用点求值过了，
     * 所以真正有用的开关是「调用点先问一句能不能记」。
     */
    fun minLevel(type: BuildType): LogLevel =
        if (type == BuildType.DEBUG) LogLevel.VERBOSE else LogLevel.WARN

    fun isEnabled(level: LogLevel, type: BuildType): Boolean =
        level.priority >= minLevel(type).priority

    fun verboseEnabled(type: BuildType): Boolean = isEnabled(LogLevel.VERBOSE, type)

    fun debugEnabled(type: BuildType): Boolean = isEnabled(LogLevel.DEBUG, type)
}
