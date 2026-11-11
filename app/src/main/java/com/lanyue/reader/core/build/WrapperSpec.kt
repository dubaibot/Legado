package com.lanyue.reader.core.build

/**
 * Gradle Wrapper 配置的解析与校验。
 *
 * 为什么把这件事写成可测代码，而不是"提交时肉眼看一眼 properties"：
 * wrapper 是三处（`gradlew` / `gradle-wrapper.jar` / `gradle-wrapper.properties`）
 * 必须互相一致的组合体，其中 properties 里的 `distributionUrl` 决定所有机器
 * 实际跑哪个 Gradle。它一旦漂到 `-rc`、`-latest` 或与 AGP 不匹配的大版本，
 * 表现是"某台机器能构建、另一台不能"，而且 Gradle 自己的报错在解压前不会出现。
 * 这类配置的正确性只能靠断言锁定，不能靠 review。
 *
 * 校验刻意只做**静态文本层面**的判断：不联网、不启动 Gradle。
 * 这样它能在 `tools/test_jvm.sh` 的离线链里跑，也能在 CI 里当门禁用。
 */
data class WrapperSpec(
    val distributionUrl: String,
    val distributionSha256Sum: String?,
    val validateDistributionUrl: Boolean,
    val networkTimeout: Int?
) {

    /** 从 `distributionUrl` 末尾取出分发标识，例如 `gradle-8.9-bin`。 */
    val distributionName: String
        get() = distributionUrl.substringAfterLast('/').removeSuffix(".zip")

    /**
     * 版本号，例如 `8.9`、`8.9-rc-2`。
     *
     * 必须**先剥掉尾部的 `-bin` / `-all`，再整体取出 `gradle-` 之后的部分**。
     * 若按「第一个 `-` 之前」截取，`gradle-8.9-rc-2-bin` 会被读成 `8.9`，
     * 于是 rc 版被当成正式版而通过 [isPinnedVersion]——门禁形同虚设。
     * 预发布标识必须保留在版本串里，才能被后续校验识别并拒绝。
     */
    val version: String?
        get() {
            val name = distributionName
            if (!name.startsWith("gradle-")) return null
            val rest = name.removePrefix("gradle-")
                .removeSuffix("-bin")
                .removeSuffix("-all")
            if (rest.isEmpty()) return null
            return rest
        }

    /** 是否锁定为精确版本号（`8.9`、`8.10.2`），排除 `-rc` / `-latest` / `-SNAPSHOT`。 */
    val isPinnedVersion: Boolean
        get() {
            val v = version ?: return false
            if (v.isEmpty()) return false
            // 只允许 数字.数字[.数字]，不接受任何预发布/动态后缀
            val parts = v.split('.')
            if (parts.size !in 2..3) return false
            return parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }
        }

    /** 主版本号，用于和 AGP 要求做匹配（AGP 8.x 需要 Gradle 8.x）。 */
    val majorVersion: Int?
        get() = version?.substringBefore('.')?.toIntOrNull()

    /** 次版本号；`8.9` → 9。缺失时为 null。 */
    val minorVersion: Int?
        get() {
            val v = version ?: return null
            val parts = v.split('.')
            if (parts.size < 2) return null
            return parts[1].toIntOrNull()
        }
}

object WrapperSpecParser {

    /** 解析 properties 文本；缺失的关键项保持 null/默认值，由 [check] 统一报错。 */
    fun parse(text: String): WrapperSpec {
        val map = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            // properties 的注释以 # 或 ! 开头；这只收 key=value
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            // `https\://` 里的反斜杠是 properties 的转义，解析时要还原
            value = value.replace("\\:", ":")
            map[key] = value
        }
        return WrapperSpec(
            distributionUrl = map["distributionUrl"].orEmpty(),
            distributionSha256Sum = map["distributionSha256Sum"],
            validateDistributionUrl = map["validateDistributionUrl"]?.equals("true", ignoreCase = true) ?: false,
            networkTimeout = map["networkTimeout"]?.toIntOrNull()
        )
    }

    /**
     * 校验并返回问题清单（空表示通过）。
     *
     * `expectedAgpMajor` 用来锁"Gradle 大版本必须与 AGP 对齐"：
     * AGP 8.5.2 配 Gradle 8.x，跨大版本会有插件 API 不兼容的硬错误。
     */
    fun check(spec: WrapperSpec, expectedAgpMajor: Int = 8, minMinor: Int = 7): List<String> {
        val problems = ArrayList<String>()

        if (spec.distributionUrl.isEmpty()) {
            problems += "缺少 distributionUrl"
            return problems
        }

        // 必须走 https：wrapper 首次构建会下载并执行分发包，明文 http 是供应链风险
        if (!spec.distributionUrl.startsWith("https://")) {
            problems += "distributionUrl 必须使用 https：${spec.distributionUrl}"
        }

        val version = spec.version
        if (version == null) {
            problems += "无法从 distributionUrl 解析版本：${spec.distributionUrl}"
        } else if (!spec.isPinnedVersion) {
            problems += "版本未锁定为精确版本号（不接受 rc/latest/snapshot）：$version"
        } else {
            if (spec.majorVersion != expectedAgpMajor) {
                problems += "Gradle 大版本 ${spec.majorVersion} 与 AGP $expectedAgpMajor 不匹配"
            }
            val minor = spec.minorVersion ?: 0
            if (minor < minMinor) {
                problems += "Gradle 次版本 $minor 低于 AGP 要求的 $minMinor"
            }
        }

        // 官方行为是可选；但既然要防"下载到被中间篡改的包"，就必须要求它存在且合法
        val sha = spec.distributionSha256Sum
        if (sha == null) {
            problems += "缺少 distributionSha256Sum（无法校验分发包完整性）"
        } else if (sha.length != 64 || !sha.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            problems += "distributionSha256Sum 不是合法的 64 位十六进制：$sha"
        }

        if (!spec.validateDistributionUrl) {
            problems += "validateDistributionUrl 应为 true"
        }

        return problems
    }
}
