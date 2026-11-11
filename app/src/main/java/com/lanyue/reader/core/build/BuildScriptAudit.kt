package com.lanyue.reader.core.build

/**
 * `plugins {}` 里的一条声明。
 *
 * `version` 为 null 表示该块没有写版本——在 Gradle 的 plugins DSL 里这通常意味着
 * 版本由根项目的 `apply false` 声明统一管理，本身不一定是错误。
 */
data class PluginDecl(
    val id: String,
    val version: String?,
    val applyFalse: Boolean
)

/**
 * 构建脚本的静态审查。
 *
 * 为什么把这件事做成可测代码：`assembleDebug` 的失败分两类——「代码错」和「配置错」。
 * 配置错的典型形态是**配置阶段就崩**，报错信息离根因很远（例如只说某个 task 失败），
 * 而真正原因是少了一个插件或一个属性。把这几条规则写成纯函数后，它们能在
 * `tools/test_jvm.sh` 的离线链里跑，不必等一台装了 Android SDK 的机器才发现。
 *
 * 这里只做**文本层面的静态判断**：不解析 Gradle 模型、不联网、不启动构建。
 * 代价是不理解 DSL 的全部语义，收益是能在任何环境稳定复现。
 */
object BuildScriptAudit {

    const val COMPOSE_PLUGIN = "org.jetbrains.kotlin.plugin.compose"
    const val KOTLIN_ANDROID_PLUGIN = "org.jetbrains.kotlin.android"

    /**
     * 解析 `plugins { ... }` 块文本。
     *
     * 只识别 `id("...")`（以及可选的 `version "..."` / `apply false`）。
     * 刻意不认识 `kotlin("android")` 这类别名写法——本仓库统一写全名，
     * 支持别名只会让「同一件事两种写法」扩散，反而更难审查。
     */
    fun parsePlugins(blockText: String): List<PluginDecl> {
        val out = ArrayList<PluginDecl>()
        val regex = Regex("""id\(\s*"([^"]+)"\s*\)(\s+version\s+"([^"]+)")?(\s+apply\s+false)?""")
        regex.findAll(blockText).forEach { m ->
            val id = m.groupValues[1]
            val version = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
            val applyFalse = m.groupValues.getOrNull(4)?.contains("false") == true
            out += PluginDecl(id, version, applyFalse)
        }
        return out
    }

    /**
     * Compose 相关配置的一致性检查。
     *
     * 为什么单独成函数：Kotlin 2.0 起 Compose Compiler 从 Kotlin 编译器内部拆成独立
     * Gradle 插件，**开启 `compose = true` 却不应用该插件会在配置阶段直接失败**。
     * 这条约束的报错信息很不直观，而检查它只需要看两段文本。
     */
    fun composeProblems(
        rootPlugins: List<PluginDecl>,
        appPlugins: List<PluginDecl>,
        composeEnabled: Boolean
    ): List<String> {
        val problems = ArrayList<String>()
        val appHasCompose = appPlugins.any { it.id == COMPOSE_PLUGIN }
        val rootCompose = rootPlugins.firstOrNull { it.id == COMPOSE_PLUGIN }

        if (composeEnabled && !appHasCompose) {
            problems += "开启了 compose 但模块未应用 $COMPOSE_PLUGIN" +
                "（Kotlin 2.0+ 起该插件必需，缺失会在配置阶段失败）"
        }
        if (appHasCompose && rootCompose == null) {
            problems += "模块应用了 $COMPOSE_PLUGIN，但根脚本未声明其版本（apply false）"
        }
        if (appHasCompose && rootCompose != null) {
            val kotlinVersion = rootPlugins.firstOrNull { it.id == KOTLIN_ANDROID_PLUGIN }?.version
            val composeVersion = rootCompose.version
            // 该插件由 Kotlin 发布，版本必须与 Kotlin 完全一致；
            // 差一个补丁号都可能拿到不匹配的编译器桥接，报错发生在编译期而非配置期。
            if (kotlinVersion != null && composeVersion != null && kotlinVersion != composeVersion) {
                problems += "Compose 插件版本 $composeVersion 与 Kotlin $kotlinVersion 不一致" +
                    "（该插件必须与 Kotlin 同版本）"
            }
        }
        return problems
    }

    /**
     * AndroidX 开关检查。
     *
     * 只要模块依赖里有 `androidx.*`，AGP 就要求 `android.useAndroidX=true`，
     * 否则在配置阶段以「This project uses AndroidX dependencies」直接终止。
     */
    fun requireAndroidXEnabled(propertiesText: String, hasAndroidxDependency: Boolean): List<String> {
        if (!hasAndroidxDependency) return emptyList()
        val enabled = propertiesText.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("android.useAndroidX") }
            .any { line -> line.substringAfter('=', "").trim().equals("true", ignoreCase = true) }
        return if (enabled) emptyList()
        else listOf("存在 androidx 依赖但 gradle.properties 未设 android.useAndroidX=true（AGP 会拒绝构建）")
    }

    /**
     * BuildConfig 生成开关检查。
     *
     * AGP 8 起 `buildFeatures.buildConfig` **默认关闭**，代码里一旦引用
     * `BuildConfig.VERSION_NAME` 就会编译失败。仓库同时用到了 `BuildConfig.DEBUG`
     * 与 `VERSION_NAME`，所以这个开关必须显式为 true。
     */
    fun buildConfigProblems(sourcesText: String, buildConfigEnabled: Boolean): List<String> {
        val usesBuildConfig = sourcesText.contains("BuildConfig.")
        return if (usesBuildConfig && !buildConfigEnabled)
            listOf("源码引用 BuildConfig 但未开启 buildFeatures.buildConfig（AGP 8 默认关闭，会编译失败）")
        else emptyList()
    }
}
