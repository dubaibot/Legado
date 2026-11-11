import com.lanyue.reader.core.build.WrapperSpec
import com.lanyue.reader.core.build.WrapperSpecParser
import kotlin.system.exitProcess

private var pass = 0
private val failures = ArrayList<String>()
private val outFile = java.io.File("/mnt/work/lanyue25/out_wrapper.txt")

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
    // ==================== 一、解析：properties 语法 ====================
    run {
        // 用仓库里真实文件的形态构造，而不是手写一个"理想"片段：
        // 注释、反斜杠转义、键序都与实际提交一致，才能测出真实解析结果。
        val text = """
            # Gradle 版本锁定为 8.9
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
            distributionSha256Sum=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
            networkTimeout=10000
            validateDistributionUrl=true
        """.trimIndent()

        val spec = WrapperSpecParser.parse(text)
        eq("反斜杠转义还原为 https", spec.distributionUrl,
            "https://services.gradle.org/distributions/gradle-8.9-bin.zip")
        eq("解析 sha256", spec.distributionSha256Sum,
            "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab")
        eq("解析 validateDistributionUrl", spec.validateDistributionUrl, true)
        eq("解析 networkTimeout", spec.networkTimeout, 10000)
        ok("注释行未被当作键值", !spec.distributionUrl.contains("#"))

        val bang = WrapperSpecParser.parse("! 感叹号注释\ndistributionUrl=https://x/gradle-8.9-bin.zip\n")
        eq("感叹号注释被忽略", bang.distributionUrl, "https://x/gradle-8.9-bin.zip")

        val empty = WrapperSpecParser.parse("")
        eq("空文本 url 为空", empty.distributionUrl, "")
        eq("空文本 sha 为 null", empty.distributionSha256Sum, null)
        eq("空文本 validate 默认 false", empty.validateDistributionUrl, false)
        eq("空文本 timeout 为 null", empty.networkTimeout, null)

        val spaced = WrapperSpecParser.parse("distributionUrl = https://a/gradle-8.9-bin.zip  ")
        eq("等号两侧空格被裁剪", spaced.distributionUrl, "https://a/gradle-8.9-bin.zip")
    }

    // ==================== 二、版本解析 ====================
    run {
        fun spec(url: String) = WrapperSpec(url, "a".repeat(64), true, 10000)

        val s = spec("https://x/gradle-8.9-bin.zip")
        eq("分发名", s.distributionName, "gradle-8.9-bin")
        eq("版本号", s.version, "8.9")
        eq("主版本", s.majorVersion, 8)
        eq("次版本", s.minorVersion, 9)
        ok("精确版本判定", s.isPinnedVersion)

        val three = spec("https://x/gradle-8.10.2-all.zip")
        eq("三段式版本", three.version, "8.10.2")
        eq("三段式次版本（两位数字不粘连）", three.minorVersion, 10)
        ok("三段式视为锁定", three.isPinnedVersion)

        val rc = spec("https://x/gradle-8.9-rc-2-bin.zip")
        eq("rc 版本号取前缀", rc.version, "8.9-rc-2")
        ok("rc 不算锁定", !rc.isPinnedVersion)

        ok("SNAPSHOT 不算锁定", !spec("https://x/gradle-8.9-SNAPSHOT-bin.zip").isPinnedVersion)

        // `gradle-latest-bin.zip` 的版本串是 `latest` 本身：
        // 它必须被判为「未锁定」，但不应被误报成「无法解析版本」——
        // 两种错误的修复方向不同，前者要改配置，后者要改解析。
        val latest = spec("https://x/gradle-latest-bin.zip")
        eq("latest 版本串为 latest", latest.version, "latest")
        ok("latest 不算锁定", !latest.isPinnedVersion)

        val noSuffix = spec("https://x/gradle-8.9.zip")
        eq("无 -bin 后缀仍取版本", noSuffix.version, "8.9")

        eq("非 gradle 前缀无版本", spec("https://x/foo.zip").version, null)
        eq("缺次版本时 minor 为 null", spec("https://x/gradle-9-bin.zip").minorVersion, null)
    }

    // ==================== 三、校验：逐条错误必须被拦住 ====================
    run {
        fun problems(url: String, sha: String? = "a".repeat(64),
                     validate: Boolean = true): List<String> =
            WrapperSpecParser.check(WrapperSpec(url, sha, validate, 10000),
                expectedAgpMajor = 8, minMinor = 7)

        val missing = problems("")
        eq("缺 url 只报一条（不连带噪声）", missing.size, 1)
        ok("缺 url 提示明确", missing[0].contains("distributionUrl"))

        ok("http 明文被拒", problems("http://x/gradle-8.9-bin.zip").any { it.contains("https") })
        ok("rc 被拒", problems("https://x/gradle-8.9-rc-2-bin.zip").any { it.contains("未锁定") })
        ok("大版本不匹配被拒", problems("https://x/gradle-7.6-bin.zip").any { it.contains("不匹配") })
        ok("次版本过低被拒", problems("https://x/gradle-8.6-bin.zip").any { it.contains("低于") })
        ok("缺 sha256 被拒", problems("https://x/gradle-8.9-bin.zip", sha = null)
            .any { it.contains("distributionSha256Sum") })
        ok("过短 sha 被拒", problems("https://x/gradle-8.9-bin.zip", sha = "abc")
            .any { it.contains("十六进制") })
        ok("非十六进制 sha 被拒", problems("https://x/gradle-8.9-bin.zip", sha = "z".repeat(64))
            .any { it.contains("十六进制") })
        ok("大写十六进制允许", problems("https://x/gradle-8.9-bin.zip", sha = "A".repeat(64)).isEmpty())
        ok("关闭 validate 被拒", problems("https://x/gradle-8.9-bin.zip", validate = false)
            .any { it.contains("validateDistributionUrl") })
        ok("无法解析版本被拒", problems("https://x/foo.zip").any { it.contains("无法") })

        // 边界：8.7 恰好等于下限，必须放行（写成 > 会漏）
        ok("次版本恰好等于下限时放行", problems("https://x/gradle-8.7-bin.zip").isEmpty())
    }

    // ==================== 四、仓库真实文件一致性 ====================
    run {
        // 把"测试里的期望"和"仓库里真实的 properties"绑死：
        // 任一侧改动而另一侧没跟上，这里直接失败。
        val props = java.io.File("gradle/wrapper/gradle-wrapper.properties")
        ok("仓库存在 wrapper properties", props.exists())
        if (props.exists()) {
            val real = WrapperSpecParser.parse(props.readText())
            eq("仓库锁定版本为 8.9", real.version, "8.9")
            ok("仓库版本为精确锁定", real.isPinnedVersion)
            eq("仓库 sha256 与官方分发包一致", real.distributionSha256Sum,
                "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab")
            eq("仓库校验问题清单为空",
                WrapperSpecParser.check(real, expectedAgpMajor = 8, minMinor = 7),
                emptyList<String>())
            eq("仓库 url 走 https", real.distributionUrl.startsWith("https://"), true)
        }

        ok("gradlew 存在", java.io.File("gradlew").exists())
        ok("gradlew.bat 存在", java.io.File("gradlew.bat").exists())
        ok("gradle-wrapper.jar 存在", java.io.File("gradle/wrapper/gradle-wrapper.jar").exists())
        ok("gradlew 具备可执行位",
            java.io.File("gradlew").let { it.exists() && it.canExecute() })
        // jar 必须是合法 zip：截断的 jar 在 Linux 上只报 "Invalid or corrupt jarfile"
        val jar = java.io.File("gradle/wrapper/gradle-wrapper.jar")
        if (jar.exists()) {
            val head = jar.inputStream().use { it.readNBytes(2) }
            ok("jar 是 zip 头(PK)", head.size == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())
        }
    }

    // ==================== 五、与 AGP 版本对齐 ====================
    run {
        // wrapper 与 AGP 是联动配置：只改一边就会出现"插件 API 不兼容"。
        // 从根构建脚本读 AGP，避免两边各写一个常量再慢慢漂。
        val root = java.io.File("build.gradle.kts")
        ok("根 build.gradle.kts 存在", root.exists())
        if (root.exists()) {
            val agp = Regex("""id\("com\.android\.application"\)\s+version\s+"([\d.]+)"""")
                .find(root.readText())?.groupValues?.get(1)
            eq("从构建脚本解析出 AGP 版本", agp, "8.5.2")

            val props = java.io.File("gradle/wrapper/gradle-wrapper.properties")
            if (props.exists() && agp != null) {
                val real = WrapperSpecParser.parse(props.readText())
                eq("Gradle 主版本与 AGP 主版本一致",
                    real.majorVersion, agp.substringBefore('.').toIntOrNull())
            }
        }
    }

    report()
}
