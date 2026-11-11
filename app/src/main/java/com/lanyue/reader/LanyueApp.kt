package com.lanyue.reader

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.lanyue.reader.core.diagnostics.AppGraph
import com.lanyue.reader.core.diagnostics.CrashGuard
import com.lanyue.reader.core.diagnostics.LogPolicy
import com.lanyue.reader.data.db.LanyueDatabase
import java.io.File

/**
 * 应用入口。只做三件事：日志下限、崩溃兜底、数据库单例的持有者。
 *
 * 刻意不做的事：网络预连、书源预加载、弹幕探活、数据库预热。
 * 它们都在首帧之前引入 I/O 与超时风险；一旦崩在这里，堆栈里同时混着
 * 「初始化」和「界面」两段代码，定位成本远高于省下的那点耗时。
 * 数据库因此只登记为惰性图（[AppGraph]），第一次取用才真正 `build`。
 */
class LanyueApp : Application() {

    /** 供后续日志门面判断是否需要拼参数，构造后不再变更。 */
    @Volatile
    var verboseLogging: Boolean = false
        private set

    @Volatile
    var crashGuard: CrashGuard? = null
        private set

    private lateinit var databaseGraph: AppGraph<LanyueDatabase>

    override fun onCreate() {
        super.onCreate()

        // BuildConfig.DEBUG 在真机可用，但诊断层要保持可离线单测，
        // 所以这里只负责把「是否可调试」这个事实翻译成日志策略。
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val buildType = LogPolicy.fromDebuggable(debuggable)
        verboseLogging = LogPolicy.verboseEnabled(buildType)

        // 崩溃文件放私有目录，卸载即随应用清除；不落外部存储，避免多一份裸露的调用栈。
        val guard = CrashGuard(File(filesDir, CRASH_DIR))
        crashGuard = guard
        Thread.setDefaultUncaughtExceptionHandler(
            guard.createHandler(Thread.getDefaultUncaughtExceptionHandler())
        )

        databaseGraph = AppGraph { LanyueDatabase.build(applicationContext) }

        // 打一行即可：日志系统自身出问题时，这行能区分「没进 onCreate」和「进了但后面挂掉」。
        Log.i(TAG, "LanyueApp.onCreate build=$buildType")
    }

    /** 惰性取数据库；第一次调用才会真正创建 Room 实例。 */
    fun database(): LanyueDatabase = databaseGraph.get()

    /** 冷启动阶段是否已经把数据库拉起来——T01 约束的可见断言。 */
    fun isDatabaseCreated(): Boolean = databaseGraph.isCreated

    companion object {
        private const val TAG = "LanyueApp"
        private const val CRASH_DIR = "crash"

        /**
         * 从任意 Context 反查应用实例。
         * 用 `as?` 而不是强转：单测里跑的是 Application 替身，不该因为类型不符直接崩。
         */
        fun from(context: Context): LanyueApp? =
            context.applicationContext as? LanyueApp
    }
}
