import com.lanyue.reader.core.shell.PlaceholderModel
import com.lanyue.reader.core.shell.ShellSpec
import kotlin.system.exitProcess

private var pass = 0
private val failures = ArrayList<String>()
private val outFile = java.io.File("/mnt/work/lanyue26/out_mainshell.txt")

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

fun main() {
    // ==================== 一、标题恢复校验 ====================
    run {
        eq("正常标题通过", ShellSpec.sanitizeRestoredTitle("览阅"), "览阅")
        eq("首尾空白被裁掉", ShellSpec.sanitizeRestoredTitle("  览阅  "), "览阅")

        // 空/空白必须回落到资源值，否则界面出现空标题
        eq("null 被拒", ShellSpec.sanitizeRestoredTitle(null), null)
        eq("空串被拒", ShellSpec.sanitizeRestoredTitle(""), null)
        eq("纯空白被拒", ShellSpec.sanitizeRestoredTitle("   "), null)
        eq("纯换行被拒", ShellSpec.sanitizeRestoredTitle("\n"), null)

        // 控制字符会破坏单行排版
        eq("含换行被拒", ShellSpec.sanitizeRestoredTitle("览\n阅"), null)
        eq("含制表符被拒", ShellSpec.sanitizeRestoredTitle("览\t阅"), null)

        // 长度边界：等于上限可用，超过上限整条拒绝（不做截断，避免"少几个字"无从追溯）
        val exact = "a".repeat(ShellSpec.MAX_TITLE_LENGTH)
        eq("恰好等于上限通过", ShellSpec.sanitizeRestoredTitle(exact), exact)
        val over = "a".repeat(ShellSpec.MAX_TITLE_LENGTH + 1)
        eq("超过上限被拒", ShellSpec.sanitizeRestoredTitle(over), null)

        eq("上限常量为 32", ShellSpec.MAX_TITLE_LENGTH, 32)
    }

    // ==================== 二、版本标签 ====================
    run {
        eq("常规版本加 v", ShellSpec.versionLabel("0.1.0"), "v0.1.0")
        eq("已带 v 不重复加", ShellSpec.versionLabel("v0.1.0"), "v0.1.0")
        eq("大写 V 不重复加", ShellSpec.versionLabel("V0.1.0"), "V0.1.0")
        eq("首尾空白裁剪后加 v", ShellSpec.versionLabel(" 0.1.0 "), "v0.1.0")

        // 缺失时不能出现 "v" 或 "vnull"
        eq("null 回落 v?", ShellSpec.versionLabel(null), ShellSpec.UNKNOWN_VERSION)
        eq("空串回落 v?", ShellSpec.versionLabel(""), ShellSpec.UNKNOWN_VERSION)
        eq("空白回落 v?", ShellSpec.versionLabel("   "), ShellSpec.UNKNOWN_VERSION)
        eq("字面 null 回落 v?", ShellSpec.versionLabel("null"), ShellSpec.UNKNOWN_VERSION)
        eq("未知版本常量为 v?", ShellSpec.UNKNOWN_VERSION, "v?")
    }

    // ==================== 三、模型组装 ====================
    run {
        val m = ShellSpec.buildPlaceholder(
            appName = "览阅",
            notice = "本应用不含任何内置源与内容入口。",
            versionName = "0.1.0"
        )
        eq("标题取应用名", m.title, "览阅")
        eq("说明原样保留", m.notice, "本应用不含任何内置源与内容入口。")
        eq("版本标签", m.versionLabel, "v0.1.0")

        // 说明为空白时应归一成空串，由 UI 决定不渲染该行
        eq("说明 null 归一空串",
            ShellSpec.buildPlaceholder("览阅", null, "0.1.0").notice, "")
        eq("说明空白归一空串",
            ShellSpec.buildPlaceholder("览阅", "   ", "0.1.0").notice, "")

        // 应用名缺失时有兜底，绝不出现空标题
        eq("应用名为 null 时兜底",
            ShellSpec.buildPlaceholder(null, "x", "0.1.0").title, "览阅")
        eq("应用名为空白时兜底",
            ShellSpec.buildPlaceholder("  ", "x", "0.1.0").title, "览阅")

        // 合法恢复值优先于资源值：保证旋转后标题不回退
        eq("合法恢复值覆盖应用名",
            ShellSpec.buildPlaceholder("览阅", "x", "0.1.0", restoredTitle = "我的书架").title, "我的书架")
        // 非法恢复值必须回落到应用名
        eq("非法恢复值回落到应用名",
            ShellSpec.buildPlaceholder("览阅", "x", "0.1.0", restoredTitle = "   ").title, "览阅")
        eq("超长恢复值回落到应用名",
            ShellSpec.buildPlaceholder("览阅", "x", "0.1.0",
                restoredTitle = "a".repeat(ShellSpec.MAX_TITLE_LENGTH + 5)).title, "览阅")

        // 默认参数：不传恢复值时行为等价于「无恢复」
        eq("省略恢复值等同无恢复",
            ShellSpec.buildPlaceholder("览阅", "x", "0.1.0").title, "览阅")
    }

    // ==================== 四、占位壳判据 ====================
    run {
        // 启用了业务数据字段后，这个判据应随之演进——
        // 当前它只要求标题与版本非空，正是「此阶段不得引入业务 UI」的机器可读表达。
        ok("正常模型通过占位判据",
            ShellSpec.isPlaceholderOnly(PlaceholderModel("览阅", "说明", "v0.1.0")))
        ok("无说明仍算占位页",
            ShellSpec.isPlaceholderOnly(PlaceholderModel("览阅", "", "v0.1.0")))
        ok("空标题不算合法占位页",
            !ShellSpec.isPlaceholderOnly(PlaceholderModel("", "说明", "v0.1.0")))
        ok("空版本不算合法占位页",
            !ShellSpec.isPlaceholderOnly(PlaceholderModel("览阅", "说明", "")))
    }

    // ==================== 五、仓库一致性：清单与实现 ====================
    run {
        // 清单声明 android:name=".ui.MainActivity"，本类必须真实存在于对应包路径下，
        // 否则 assembleDebug 会在 aapt 阶段报「class not found」。
        val manifest = java.io.File("app/src/main/AndroidManifest.xml")
        ok("AndroidManifest.xml 存在", manifest.exists())
        if (manifest.exists()) {
            val text = manifest.readText()
            ok("清单声明了 .ui.MainActivity", text.contains("android:name=\".ui.MainActivity\""))
            ok("该 activity 导出", text.contains("android:exported=\"true\""))
            ok("声明了 MAIN/LAUNCHER", text.contains("android.intent.action.MAIN") &&
                text.contains("android.intent.category.LAUNCHER"))
        }

        val activity = java.io.File("app/src/main/java/com/lanyue/reader/ui/MainActivity.kt")
        ok("MainActivity.kt 存在", activity.exists())
        if (activity.exists()) {
            val src = activity.readText()
            ok("包名与清单一致（ui）", src.contains("package com.lanyue.reader.ui"))
            ok("继承 ComponentActivity", src.contains(": ComponentActivity()"))
            ok("重写 onCreate", src.contains("override fun onCreate"))
            ok("使用 setContent", src.contains("setContent {"))
            // 占位壳不得引入业务入口：出现这些关键词说明超范围
            val forbidden = listOf("ShelfScreen", "DiscoverScreen", "PlayerActivity", "BookDetail")
            ok("未引入业务界面", forbidden.none { src.contains(it) })
            // 判断逻辑应委托给 ShellSpec，而不是散落在 Composable 里
            ok("委托 ShellSpec 组装模型", src.contains("ShellSpec.buildPlaceholder"))
        }

        val spec = java.io.File("app/src/main/java/com/lanyue/reader/core/shell/ShellSpec.kt")
        ok("ShellSpec.kt 存在", spec.exists())

        // 清单里声明的两个 service 也在 T03 之外，不应在本轮被伪造实现
        val manifestText = if (manifest.exists()) manifest.readText() else ""
        ok("清单仍声明 MediaDownloadService",
            manifestText.contains(".player.MediaDownloadService"))
        ok("清单仍声明 AudioPlaybackService",
            manifestText.contains(".player.AudioPlaybackService"))
    }

    report()
}
