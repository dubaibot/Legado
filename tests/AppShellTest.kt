import com.lanyue.reader.core.diagnostics.AppGraph
import com.lanyue.reader.core.diagnostics.BuildType
import com.lanyue.reader.core.diagnostics.CrashGuard
import com.lanyue.reader.core.diagnostics.LogLevel
import com.lanyue.reader.core.diagnostics.LogPolicy
import java.io.File
import kotlin.system.exitProcess

private var pass = 0
private val failures = ArrayList<String>()
private val outFile = java.io.File("/mnt/work/lanyue24/out_app_shell.txt")

private fun eq(label: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++ else failures += "$label\n  期望: $expected\n  实际: $actual"
}

private fun ok(label: String, cond: Boolean) {
    if (cond) pass++ else failures += label
}

private fun report() {
    println("PASS = $pass")
    println("FAIL = ${failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEachIndexed { i, f -> println("${i + 1}. $f") }
        outFile.parentFile?.mkdirs()
        outFile.writeText(failures.joinToString("\n\n"))
        exitProcess(1)
    }
}

private class Boom(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun tempDir(name: String): File {
    val d = File(System.getProperty("java.io.tmpdir"), "lanyue24-$name-${System.nanoTime()}")
    d.mkdirs()
    return d
}

fun main() {
    // ==================== 一、日志策略 ====================
    run {
        eq("debuggable 映射 DEBUG", LogPolicy.fromDebuggable(true), BuildType.DEBUG)
        eq("不可调试映射 RELEASE", LogPolicy.fromDebuggable(false), BuildType.RELEASE)

        eq("debug 构建下限 VERBOSE", LogPolicy.minLevel(BuildType.DEBUG), LogLevel.VERBOSE)
        eq("release 构建下限 WARN", LogPolicy.minLevel(BuildType.RELEASE), LogLevel.WARN)

        ok("debug 构建允许 verbose", LogPolicy.verboseEnabled(BuildType.DEBUG))
        ok("release 构建禁止 verbose", !LogPolicy.verboseEnabled(BuildType.RELEASE))
        ok("release 构建禁止 debug", !LogPolicy.debugEnabled(BuildType.RELEASE))
        ok("release 构建允许 WARN", LogPolicy.isEnabled(LogLevel.WARN, BuildType.RELEASE))
        ok("release 构建允许 ERROR", LogPolicy.isEnabled(LogLevel.ERROR, BuildType.RELEASE))
        ok("release 构建拒绝 INFO", !LogPolicy.isEnabled(LogLevel.INFO, BuildType.RELEASE))

        // 边界：WARN 恰好等于下限，必须算「允许」，不能因为比较写成 > 而漏掉
        ok("WARN 与下限恰好相等时放行", LogPolicy.isEnabled(LogLevel.WARN, BuildType.RELEASE))
        // 边界：level 优先级单调，逐级比较不出现回绕
        ok("优先级严格递增", LogLevel.VERBOSE.priority < LogLevel.DEBUG.priority &&
            LogLevel.DEBUG.priority < LogLevel.INFO.priority &&
            LogLevel.INFO.priority < LogLevel.WARN.priority &&
            LogLevel.WARN.priority < LogLevel.ERROR.priority)
    }

    // ==================== 二、惰性单例 ====================
    run {
        var built = 0
        val graph = AppGraph { built++; StringBuilder("db") }

        ok("首次访问前未创建", !graph.isCreated)
        eq("首次访问前 factory 未调用", built, 0)

        val first = graph.get()
        ok("取用后标记已创建", graph.isCreated)
        eq("factory 只调用一次", built, 1)
        eq("拿到构造结果", first.toString(), "db")

        val second = graph.get()
        ok("重复取用返回同一实例", first === second)
        eq("重复取用不再构造", built, 1)
    }

    // 构造失败不缓存：一次偶发失败不应把进程钉死在坏状态
    run {
        var attempts = 0
        val graph = AppGraph<String> {
            attempts++
            if (attempts == 1) throw IllegalStateException("首次失败") else "ok"
        }
        val firstError = runCatching { graph.get() }.exceptionOrNull()
        ok("首次构造抛出原始异常", firstError is IllegalStateException)
        ok("失败后未标记已创建", !graph.isCreated)
        eq("第二次取用重试成功", graph.get(), "ok")
        eq("总计构造两次", attempts, 2)
        ok("成功后标记已创建", graph.isCreated)
    }

    // ==================== 三、崩溃兜底：文件名与净化 ====================
    run {
        eq("空线程名回落 unknown", CrashGuard.sanitizeThreadTag(null), "unknown")
        eq("空白线程名回落 unknown", CrashGuard.sanitizeThreadTag("   "), "unknown")
        eq("常规线程名原样保留", CrashGuard.sanitizeThreadTag("main"), "main")
        // 协程默认线程名带空格与斜杠，直接进文件名会非法。
        // 净化后 "DefaultDispatcher-worker_1" 共 25 字符，会被 24 上限截掉末位——
        // 两条规则叠加时以截断为准，这里把顺序固定下来，避免以后调上限时静默改变行为。
        eq("空格与斜杠替换为下划线后再按上限截断", CrashGuard.sanitizeThreadTag("DefaultDispatcher-worker/1"),
            "DefaultDispatcher-worker")
        eq("截断结果恰为 24 字符", CrashGuard.sanitizeThreadTag("DefaultDispatcher-worker/1").length, 24)
        // 未触及上限时只做替换，不截断
        eq("短名不截断", CrashGuard.sanitizeThreadTag("worker/1"), "worker_1")
        eq("冒号与点号替换", CrashGuard.sanitizeThreadTag("pool:1.thread"), "pool_1_thread")
        eq("超长名截断到 24", CrashGuard.sanitizeThreadTag("a".repeat(40)).length, 24)
        eq("恰好 24 保留", CrashGuard.sanitizeThreadTag("b".repeat(24)).length, 24)

        eq("时间戳为 UTC ISO 形态", CrashGuard.formatTimestamp(0L), "1970-01-01T00:00:00.000Z")
        eq("时间戳毫秒位保留", CrashGuard.formatTimestamp(86_400_123L), "1970-01-02T00:00:00.123Z")

        val guard = CrashGuard(tempDir("name"))
        eq("文件名拼接", guard.fileName(1234L, "main"), "crash-1234-main.txt")
        eq("无线程名文件名", guard.fileName(99L, null), "crash-99-unknown.txt")
    }

    // ==================== 四、崩溃兜底：报告正文 ====================
    run {
        val guard = CrashGuard(tempDir("body"))
        val root = Boom("顶层炸了")
        val report = guard.buildReport("main", 0L, root)

        ok("含报告头", report.startsWith("-- lanyue crash report --"))
        ok("含 UTC 时间", report.contains("时间: 1970-01-01T00:00:00.000Z"))
        ok("含线程名", report.contains("线程: main"))
        ok("含异常类全名", report.contains("异常: Boom: 顶层炸了"))
        ok("含堆栈帧", report.contains("    at "))

        val chained = guard.buildReport("worker", 0L, Boom("外层", Boom("内层")))
        ok("外层异常在首行", chained.contains("异常: Boom: 外层"))
        ok("原因链标注序号", chained.contains("原因1: Boom: 内层"))

        // 自引用 cause 会成环，必须截断而不是无限展开
        val cyclic = Boom("自环")
        val selfRef = object : RuntimeException("循环") {
            override val cause: Throwable get() = this
        }
        val cyclicReport = guard.buildReport("t", 0L, selfRef)
        ok("环状 cause 不无限展开", cyclicReport.length < 4000)
        ok("环状 cause 正常成文", cyclicReport.contains("异常: "))

        // 无 message 的异常：不能出现「null」字样污染报告
        val noMsg = object : RuntimeException() {}
        val nm = guard.buildReport("t", 0L, noMsg)
        ok("无 message 不写 null", !nm.contains("null"))
    }

    // ==================== 五、崩溃兜底：落盘与保留份数 ====================
    run {
        val dir = tempDir("save")
        var now = 1000L
        val guard = CrashGuard(dir, keepFiles = 3, clock = { now })

        val f1 = guard.handle("main", Boom("一"))
        ok("首次落盘成功", f1 != null && f1.exists())
        ok("文件名含时间戳", f1!!.name.startsWith("crash-1000-"))
        ok("正文可回读", f1.readText(Charsets.UTF_8).contains("异常: Boom: 一"))

        // 四个线程名相同会让文件名撞车，用不同线程名模拟连环崩溃
        now = 2000L; guard.handle("t1", Boom("二"))
        now = 3000L; guard.handle("t2", Boom("三"))
        eq("三份报告都在", guard.listReports().size, 3)

        now = 4000L; guard.handle("t3", Boom("四"))
        eq("超过上限后仍只留 3 份", guard.listReports().size, 3)

        val names = guard.listReports().map { it.name }
        ok("最旧的一份被删除", !f1.exists())
        ok("保留的是最新三份", names == listOf("crash-2000-t1.txt", "crash-3000-t2.txt", "crash-4000-t3.txt"))

        // 目录不存在时应自动创建，而不是抛异常
        val fresh = tempDir("guard-dir").resolve("nested/deep")
        val g2 = CrashGuard(fresh, keepFiles = 1)
        val written = g2.handle("main", Boom("深层"))
        ok("目录缺失时自动创建", written != null && written.exists())
    }

    // ==================== 六、崩溃兜底：处理器转交 ====================
    run {
        val guard = CrashGuard(tempDir("handler"))
        var forwarded: Throwable? = null
        var forwardedThread: Thread? = null

        val prev = Thread.UncaughtExceptionHandler { t, e -> forwardedThread = t; forwarded = e }
        val handler = guard.createHandler(prev)
        val err = Boom("交给原处理器")
        val thread = Thread { }
        handler.uncaughtException(thread, err)

        ok("原处理器收到异常", forwarded === err)
        ok("原处理器收到线程", forwardedThread === thread)
        eq("崩溃文件同时落盘", guard.listReports().size, 1)

        // 没有原处理器时不能因空指针二次崩溃
        val solo = guard.createHandler(null)
        val soloResult = runCatching { solo.uncaughtException(Thread { }, Boom("无前驱")) }
        ok("无原处理器时不抛", soloResult.isSuccess)
        eq("无前驱时同样落盘", guard.listReports().size, 2)
    }

    // ==================== 七、冷启动约束：不提前建库 ====================
    run {
        // T01 的硬约束是「onCreate 不做重活」，这里用可观测的行为锁定它：
        // 构造图之后、真正取用之前，工厂必须一次都没跑。
        var built = 0
        val graph = AppGraph { built++; "db" }
        ok("持有图不等于建库", !graph.isCreated)
        eq("建库次数为 0", built, 0)
        graph.get()
        eq("取用后建库一次", built, 1)
    }

    report()
}
