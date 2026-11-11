import com.lanyue.reader.core.build.BuildScriptAudit
import com.lanyue.reader.core.build.PluginDecl
import kotlin.system.exitProcess

private var pass = 0
private val failures = ArrayList<String>()
private val outFile = java.io.File("/mnt/work/lanyue27/out_buildscript.txt")

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
    // ==================== 一、plugins 块解析 ====================
    run {
        val block = """
            id("com.android.application") version "8.5.2" apply false
            id("org.jetbrains.kotlin.android") version "2.0.20" apply false
            id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
        """.trimIndent()
        val parsed = BuildScriptAudit.parsePlugins(block)
        eq("解析出三条插件", parsed.size, 3)
        eq("首个插件 id", parsed[0].id, "com.android.application")
        eq("首个插件版本", parsed[0].version, "8.5.2")
        ok("首个插件 apply false", parsed[0].applyFalse)
        eq("KSP 版本串保留全部三段", parsed[2].version, "2.0.20-1.0.25")

        // 无版本的声明：version 应为 null，而不是空串
        val noVersion = BuildScriptAudit.parsePlugins("""id("org.jetbrains.kotlin.plugin.compose")""")
        eq("无版本时 version 为 null", noVersion[0].version, null)
        ok("无版本时 applyFalse 为 false", !noVersion[0].applyFalse)

        // 带空格与换行的写法也要认
        val spaced = BuildScriptAudit.parsePlugins("id( \"x.y\" )  version  \"1.0\"   apply false")
        eq("容忍多余空格", spaced[0].id, "x.y")
        eq("容忍多余空格的版本", spaced[0].version, "1.0")
        ok("容忍多余空格的 apply false", spaced[0].applyFalse)

        // 不认识别名写法是刻意的：本仓库统一写全名
        eq("别名写法不识别", BuildScriptAudit.parsePlugins("""kotlin("android")""").size, 0)
    }

    // ==================== 二、Compose 插件一致性 ====================
    run {
        val kotlinDecl = PluginDecl("org.jetbrains.kotlin.android", "2.0.20", true)

        // 开启 compose 但未应用插件 —— 必须报错（Kotlin 2.0+ 的配置期硬失败）
        val missing = BuildScriptAudit.composeProblems(
            listOf(kotlinDecl), listOf(PluginDecl("org.jetbrains.kotlin.android", null, false)),
            composeEnabled = true)
        eq("缺失 Compose 插件时报一条", missing.size, 1)
        ok("提示指明插件 id", missing[0].contains("org.jetbrains.kotlin.plugin.compose"))
        ok("提示指明配置阶段失败", missing[0].contains("配置阶段"))

        // 未开启 compose 时不要求插件：允许未来关掉 Compose 而不必改插件块
        eq("未开启 compose 不报错", BuildScriptAudit.composeProblems(
            listOf(kotlinDecl), emptyList(), composeEnabled = false), emptyList<String>())

        // 模块应用了插件，但根脚本未声明版本
        val noRoot = BuildScriptAudit.composeProblems(
            listOf(kotlinDecl),
            listOf(PluginDecl("org.jetbrains.kotlin.plugin.compose", null, false)),
            composeEnabled = true)
        ok("根脚本缺声明时报错", noRoot.any { it.contains("apply false") })

        // 完整正确配置：应无问题
        val goodRoot = listOf(kotlinDecl,
            PluginDecl("org.jetbrains.kotlin.plugin.compose", "2.0.20", true))
        val goodApp = listOf(PluginDecl("org.jetbrains.kotlin.plugin.compose", null, false))
        eq("完整正确配置无问题",
            BuildScriptAudit.composeProblems(goodRoot, goodApp, composeEnabled = true),
            emptyList<String>())

        // 版本不一致必须报错：这是最容易被复制粘贴带进来的错误
        val mismatchRoot = listOf(kotlinDecl,
            PluginDecl("org.jetbrains.kotlin.plugin.compose", "2.0.0", true))
        val mismatch = BuildScriptAudit.composeProblems(mismatchRoot, goodApp, composeEnabled = true)
        ok("版本不一致报错", mismatch.any { it.contains("不一致") })

        // 补丁号差一位也算不一致（不做模糊匹配）
        val patchRoot = listOf(kotlinDecl,
            PluginDecl("org.jetbrains.kotlin.plugin.compose", "2.0.21", true))
        ok("补丁号不同也报错",
            BuildScriptAudit.composeProblems(patchRoot, goodApp, composeEnabled = true)
                .any { it.contains("不一致") })
    }

    // ==================== 三、AndroidX 开关 ====================
    run {
        eq("有 androidx 依赖且开关为 true：通过",
            BuildScriptAudit.requireAndroidXEnabled("android.useAndroidX=true", true), emptyList<String>())

        val bad = BuildScriptAudit.requireAndroidXEnabled("org.gradle.jvmargs=-Xmx4g", true)
        eq("有 androidx 依赖但未开开关：报一条", bad.size, 1)
        ok("提示指明 useAndroidX", bad[0].contains("android.useAndroidX"))

        // 显式 false 同样应报错（写了 ≠ 开对了）
        ok("写成 false 也报错",
            BuildScriptAudit.requireAndroidXEnabled("android.useAndroidX=false", true).isNotEmpty())

        // 没有 androidx 依赖时不要求该开关
        eq("无 androidx 依赖不报错",
            BuildScriptAudit.requireAndroidXEnabled("", false), emptyList<String>())

        // 值两侧有空格也要认
        eq("容忍值两侧空格",
            BuildScriptAudit.requireAndroidXEnabled("android.useAndroidX = true", true),
            emptyList<String>())
    }

    // ==================== 四、BuildConfig 开关 ====================
    run {
        eq("引用 BuildConfig 且已开启：通过",
            BuildScriptAudit.buildConfigProblems("val v = BuildConfig.VERSION_NAME", true),
            emptyList<String>())

        val bad = BuildScriptAudit.buildConfigProblems("val d = BuildConfig.DEBUG", false)
        eq("引用但未开启：报一条", bad.size, 1)
        ok("提示指明 buildConfig", bad[0].contains("buildConfig"))

        eq("未引用时不报错", BuildScriptAudit.buildConfigProblems("val x = 1", false),
            emptyList<String>())
    }

    // ==================== 五、仓库真实文件一致性 ====================
    run {
        val rootFile = java.io.File("build.gradle.kts")
        val appFile = java.io.File("app/build.gradle.kts")
        val propsFile = java.io.File("gradle.properties")
        ok("根构建脚本存在", rootFile.exists())
        ok("模块构建脚本存在", appFile.exists())
        ok("gradle.properties 存在", propsFile.exists())

        if (rootFile.exists() && appFile.exists()) {
            val rootText = rootFile.readText()
            val appText = appFile.readText()

            val rootPlugins = BuildScriptAudit.parsePlugins(
                rootText.substringAfter("plugins {").substringBefore("}"))
            val appPlugins = BuildScriptAudit.parsePlugins(
                appText.substringAfter("plugins {").substringBefore("}"))

            // 这条是本轮的验收点：模块必须真的应用了 Compose 插件
            ok("模块应用了 Compose 插件",
                appPlugins.any { it.id == BuildScriptAudit.COMPOSE_PLUGIN })
            ok("根脚本声明了 Compose 插件版本",
                rootPlugins.any { it.id == BuildScriptAudit.COMPOSE_PLUGIN && it.version != null })

            val composeEnabled = Regex("""compose\s*=\s*true""").containsMatchIn(appText)
            ok("模块确实开启 compose", composeEnabled)

            eq("仓库配置的 Compose 一致性问题为空",
                BuildScriptAudit.composeProblems(rootPlugins, appPlugins, composeEnabled),
                emptyList<String>())

            // buildConfig 开关：源码用到了 BuildConfig，模块必须显式开启
            val bcEnabled = Regex("""buildConfig\s*=\s*true""").containsMatchIn(appText)
            ok("模块开启了 buildConfig", bcEnabled)

            // namespace 与清单里的 application 类包名应同族，否则资源引用会错位
            val ns = Regex("""namespace\s*=\s*"([^"]+)"""")
                .find(appText)?.groupValues?.get(1)
            eq("namespace 为 com.lanyue.reader", ns, "com.lanyue.reader")
        }

        if (propsFile.exists()) {
            val props = propsFile.readText()
            ok("已启用 AndroidX", BuildScriptAudit
                .requireAndroidXEnabled(props, true).isEmpty())
            ok("gradle.properties 含 useAndroidX=true", props.contains("android.useAndroidX=true"))
        }
    }

    // ==================== 六、清单组件与实现对齐 ====================
    run {
        // 清单声明的四个组件必须都有对应源文件，否则 aapt/manifest 合并阶段失败。
        val manifest = java.io.File("app/src/main/AndroidManifest.xml")
        ok("清单存在", manifest.exists())
        if (manifest.exists()) {
            val text = manifest.readText()
            val expect = mapOf(
                ".LanyueApp" to "app/src/main/java/com/lanyue/reader/LanyueApp.kt",
                ".ui.MainActivity" to "app/src/main/java/com/lanyue/reader/ui/MainActivity.kt",
                ".player.MediaDownloadService" to
                    "app/src/main/java/com/lanyue/reader/player/MediaDownloadService.kt",
                ".player.AudioPlaybackService" to
                    "app/src/main/java/com/lanyue/reader/player/AudioPlaybackService.kt"
            )
            expect.forEach { (decl, path) ->
                ok("清单声明 $decl", text.contains("android:name=\"$decl\""))
                ok("$decl 有对应实现", java.io.File(path).exists())
            }
        }
    }

    report()
}
