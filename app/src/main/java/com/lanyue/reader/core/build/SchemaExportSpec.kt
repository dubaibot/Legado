package com.lanyue.reader.core.build

/**
 * Room schema 导出的配置校验（T05）。
 *
 * 为什么这件事需要可测代码：schema 导出是\"三处必须同时为真\"的组合——
 *
 * 1. `@Database(exportSchema = true)`：数据库类同意导出；
 * 2. `ksp { arg("room.schemaLocation", ...) }`：告诉处理器往哪写；
 * 3. `app/schemas/<version>.json`：真的被写出来并提交进版本库。
 *
 * 缺任意一处的表现都**不是构建失败**，而是构建成功但没有任何 schema 落地。
 * 等到某天加了字段才想起来，就没有基线可以 diff，迁移测试无从写起，
 * 只能靠 `fallbackToDestructiveMigration()` 掩盖——用户一升级，数据全丢。
 *
 * 这类\"静默失效\"的配置错误不能靠 review 发现，只能靠断言锁定。
 * 因此这里做纯文本解析：不启动 Gradle、不联网，能在离线测试链里跑。
 */
data class SchemaExportSpec(
    val exportSchemaEnabled: Boolean,
    val schemaLocation: String?,
    val databaseVersion: Int?,
    val entityCount: Int
) {

    /** schemaLocation 是否指向模块内的 `schemas` 目录（`$projectDir/schemas`）。 */
    val isLocationUnderProjectDir: Boolean
        get() {
            val loc = schemaLocation ?: return false
            if (!loc.contains("schemas")) return false
            // 接受 $projectDir/schemas 与 ${'$'}{projectDir}/schemas 两种写法
            return loc.contains("projectDir")
        }

    /** 目录名是否恰好是 `schemas`（不是 `schema` / `roomSchemas` 之类）。 */
    val isSchemasDirName: Boolean
        get() {
            val loc = schemaLocation ?: return false
            val tail = loc.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
            return tail == "schemas"
        }
}

object SchemaExportSpecParser {

    /**
     * 从 `@Database(...)` 注解文本里取出三项关键信息。
     *
     * `text` 传整份数据库源码即可，不必精确切片：注解里 `entities`、
     * `version`、`exportSchema` 三个键在整个文件中唯一，误判风险很低。
     */
    fun parseDatabase(text: String): SchemaExportSpec {
        // exportSchema 缺省值是 false，必须显式写 true 才算开启
        val exportSchema = Regex("""exportSchema\s*=\s*true""").containsMatchIn(text)

        val version = Regex("""version\s*=\s*(\d+)""").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()

        // entities 列表里每个元素形如 `XxxEntity::class`
        val entityBlock = Regex(
            """entities\s*=\s*\[([^\]]*)]""", RegexOption.DOT_MATCHES_ALL
        ).find(text)?.groupValues?.get(1)
        val entityCount = entityBlock
            ?.let { Regex("""::class""").findAll(it).count() }
            ?: 0

        return SchemaExportSpec(
            exportSchemaEnabled = exportSchema,
            schemaLocation = null,
            databaseVersion = version,
            entityCount = entityCount
        )
    }

    /**
     * 从模块 `build.gradle.kts` 文本里取出 `room.schemaLocation` 的值。
     *
     * 只认 `arg("room.schemaLocation", "...")` 这一种写法：KSP 的参数名是
     * 大小写敏感的固定字符串，写成 `room.schemaLocation` 之外的任何变体
     * （`roomSchemaLocation`、`ROOM_SCHEMA_LOCATION`）都会被处理器忽略，
     * 且不报错。
     */
    fun parseSchemaLocation(buildScript: String): String? =
        Regex(
            """arg\s*\(\s*"room\.schemaLocation"\s*,\s*"([^"]*)"\s*\)"""
        ).find(buildScript)?.groupValues?.get(1)

    /** 合并两份文本，得到完整判定所需的规格。 */
    fun parse(databaseSource: String, buildScript: String): SchemaExportSpec {
        val base = parseDatabase(databaseSource)
        return base.copy(schemaLocation = parseSchemaLocation(buildScript))
    }

    /**
     * 校验并返回问题清单（空表示通过）。
     *
     * [schemaFiles] 传 `app/schemas/` 下实际存在的 JSON 文件名，
     * 用来确认\"配置对了\"之外，产物也真的在版本库里。
     */
    fun check(spec: SchemaExportSpec, schemaFiles: List<String>): List<String> {
        val problems = ArrayList<String>()

        if (!spec.exportSchemaEnabled) {
            problems += "@Database 未声明 exportSchema = true（缺省为 false，schema 不会导出）"
        }

        val loc = spec.schemaLocation
        if (loc == null) {
            problems += "build.gradle.kts 缺少 ksp { arg(\"room.schemaLocation\", ...) }"
        } else {
            if (!spec.isLocationUnderProjectDir) {
                problems += "room.schemaLocation 未使用 \$projectDir 相对路径：$loc"
            }
            if (!spec.isSchemasDirName) {
                problems += "room.schemaLocation 目录名不是 schemas：$loc"
            }
        }

        val version = spec.databaseVersion
        if (version == null || version <= 0) {
            problems += "@Database 缺少合法 version"
        }

        if (spec.entityCount <= 0) {
            problems += "@Database 的 entities 列表为空或无法解析"
        }

        if (schemaFiles.none { it.endsWith(".json") }) {
            problems += "app/schemas/ 下没有 schema JSON（配置对了但产物未提交）"
        } else if (version != null) {
            // 版本号与文件名必须对应，否则迁移测试会找错基线
            val expected = "$version.json"
            if (schemaFiles.none { it == expected }) {
                problems += "缺少与 version=$version 对应的 $expected"
            }
        }

        return problems
    }

    /**
     * 校验一份 schema JSON 的骨架。
     *
     * 只查\"结构与数量\"这类能廉价判伪的部分：`format` 必须是 Room 的
     * `1`，`version` 必须与 `@Database.version` 一致，`entities` 条数必须与
     * 注解里声明的实体数一致，且带 32 位十六进制的 `identityHash`。
     * 不解析每条字段：那是 Room 自己的事，重复实现只会引入分歧。
     */
    fun checkSchemaJson(
        json: String,
        expectedVersion: Int,
        expectedEntityCount: Int
    ): List<String> {
        val problems = ArrayList<String>()

        val format = Regex(""""format"\s*:\s*(\d+)""").find(json)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (format != 1) {
            problems += "schema format 应为 1，实际 $format"
        }

        val version = Regex(""""version"\s*:\s*(\d+)""").find(json)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (version != expectedVersion) {
            problems += "schema version 应为 $expectedVersion，实际 $version"
        }

        // entities 是对象数组，元素里带 tableName
        val tables = Regex(""""tableName"\s*:\s*"([^"]+)"""")
            .findAll(json).map { it.groupValues[1] }.toList()
        if (tables.size != expectedEntityCount) {
            problems += "schema 中表数量应为 $expectedEntityCount，实际 ${tables.size}"
        }
        if (tables.toSet().size != tables.size) {
            problems += "schema 中存在重复表名：${tables.groupBy { it }.filter { it.value.size > 1 }.keys}"
        }

        val hash = Regex(""""identityHash"\s*:\s*"([^"]+)"""")
            .find(json)?.groupValues?.get(1)
        if (hash == null || !hash.matches(Regex("[0-9a-f]{32}"))) {
            problems += "identityHash 缺失或不是 32 位小写十六进制：$hash"
        }

        return problems
    }
}
