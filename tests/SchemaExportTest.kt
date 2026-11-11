import com.lanyue.reader.core.build.SchemaExportSpec
import com.lanyue.reader.core.build.SchemaExportSpecParser
import kotlin.system.exitProcess

private var pass = 0
private val failures = ArrayList<String>()

private fun eq(label: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        pass++
    } else {
        failures += label + "\n  期望: " + expected + "\n  实际: " + actual
    }
}

private fun ok(label: String, cond: Boolean) {
    if (cond) pass++ else failures += label
}

private fun report() {
    println("PASS = $pass")
    println("FAIL = ${failures.size}")
    if (failures.isNotEmpty()) {
        for ((i, f) in failures.withIndex()) {
            println("${i + 1}. $f")
        }
        exitProcess(1)
    }
}

fun main() {
    // ==================== 一、解析 @Database 注解 ====================
    run {
        // 用仓库里真实的注解形态：多行 entities、version、exportSchema 三键齐全。
        // 只测一行理想片段的话，真实文件里的换行与缩进就从未被覆盖过。
        val text = """
            @Database(
                entities = [
                    BookSourceEntity::class,
                    RssSourceEntity::class,
                    VideoApiEntity::class
                ],
                version = 1,
                exportSchema = true
            )
            abstract class LanyueDatabase : RoomDatabase() {
        """.trimIndent()
        val spec = SchemaExportSpecParser.parseDatabase(text)
        ok("exportSchema = true 应被识别为已开启", spec.exportSchemaEnabled)
        eq("version 应解析为 1", spec.databaseVersion, 1)
        eq("entity 数量应为 3", spec.entityCount, 3)
    }

    run {
        // exportSchema 缺省为 false。缺省若被当成已开启，门禁就形同虚设。
        val text = """
            @Database(entities = [BookEntity::class], version = 2)
            abstract class LanyueDatabase : RoomDatabase()
        """.trimIndent()
        val spec = SchemaExportSpecParser.parseDatabase(text)
        ok("缺省 exportSchema 应判为未开启", !spec.exportSchemaEnabled)
        eq("version 应解析为 2", spec.databaseVersion, 2)
        eq("单个 entity 应为 1", spec.entityCount, 1)
    }

    run {
        // 显式 false 是关闭，不能因为"出现过 exportSchema 字样"就判真
        val text = "@Database(entities = [A::class], version = 1, exportSchema = false)"
        ok(
            "exportSchema = false 应判为未开启",
            !SchemaExportSpecParser.parseDatabase(text).exportSchemaEnabled
        )
    }

    run {
        // 实体列表写漏必须能被检出
        val text = "@Database(entities = [], version = 1, exportSchema = true)"
        eq("空 entities 应为 0", SchemaExportSpecParser.parseDatabase(text).entityCount, 0)
    }

    run {
        // 跨行且带尾随逗号的真实写法
        val text = """
            @Database(
                entities = [
                    AEntity::class,
                    BEntity::class,
                ],
                version = 3,
                exportSchema = true
            )
        """.trimIndent()
        eq(
            "带尾随逗号的 entities 应解析为 2",
            SchemaExportSpecParser.parseDatabase(text).entityCount,
            2
        )
    }

    // ==================== 二、解析 ksp 参数 ====================
    run {
        val script = """
            ksp {
                arg("room.schemaLocation", "${'$'}projectDir/schemas")
            }
        """.trimIndent()
        eq(
            "应取到 \$projectDir/schemas",
            SchemaExportSpecParser.parseSchemaLocation(script),
            "${'$'}projectDir/schemas"
        )
    }

    run {
        // 参数名大小写敏感：写成别的变体 KSP 会静默忽略，且不报错
        val script = """ksp { arg("roomSchemaLocation", "x/schemas") }"""
        eq(
            "错误参数名不应被识别",
            SchemaExportSpecParser.parseSchemaLocation(script),
            null
        )
    }

    run {
        // 绝对路径会把本机目录写死，换机器即失效
        val script = """ksp { arg("room.schemaLocation", "/home/ci/schemas") }"""
        val loc = SchemaExportSpecParser.parseSchemaLocation(script)
        eq("绝对路径应能被解析", loc, "/home/ci/schemas")
        val spec = SchemaExportSpec(true, loc, 1, 1)
        ok("绝对路径应判为不在 projectDir 下", !spec.isLocationUnderProjectDir)
    }

    run {
        // 花括号插值写法也应被接受
        val script = """ksp { arg("room.schemaLocation", "${'$'}{projectDir}/schemas") }"""
        val spec = SchemaExportSpecParser.parse(
            "@Database(entities = [A::class], version = 1, exportSchema = true)",
            script
        )
        eq(
            "花括号写法也应取到值",
            spec.schemaLocation,
            "${'$'}{projectDir}/schemas"
        )
        ok("花括号写法应判为在 projectDir 下", spec.isLocationUnderProjectDir)
    }

    // ==================== 三、目录名判定 ====================
    run {
        ok(
            "schemas 目录名应为真",
            SchemaExportSpec(true, "${'$'}projectDir/schemas", 1, 1).isSchemasDirName
        )
        // 尾随斜杠是常见写法，不能被判成目录名为空
        ok(
            "带尾随斜杠也应为真",
            SchemaExportSpec(true, "${'$'}projectDir/schemas/", 1, 1).isSchemasDirName
        )
        ok(
            "单数 schema 应判假",
            !SchemaExportSpec(true, "${'$'}projectDir/schema", 1, 1).isSchemasDirName
        )
        ok(
            "roomSchemas 应判假",
            !SchemaExportSpec(true, "${'$'}projectDir/roomSchemas", 1, 1).isSchemasDirName
        )
    }

    // ==================== 四、check：配置组合 ====================
    run {
        val spec = SchemaExportSpec(
            exportSchemaEnabled = true,
            schemaLocation = "${'$'}projectDir/schemas",
            databaseVersion = 1,
            entityCount = 17
        )
        eq(
            "完整正确配置 + 1.json 应无问题",
            SchemaExportSpecParser.check(spec, listOf("1.json")),
            emptyList<String>()
        )
    }

    run {
        // 缺 exportSchema：即使 ksp 配好，数据库类也不产 schema
        val spec = SchemaExportSpec(false, "${'$'}projectDir/schemas", 1, 17)
        val problems = SchemaExportSpecParser.check(spec, listOf("1.json"))
        ok("缺 exportSchema 应报问题", problems.any { it.contains("exportSchema") })
    }

    run {
        // 缺 ksp 参数：exportSchema=true 但无处可写，Room 只打一条警告
        val spec = SchemaExportSpec(true, null, 1, 17)
        val problems = SchemaExportSpecParser.check(spec, listOf("1.json"))
        ok("缺 room.schemaLocation 应报问题", problems.any { it.contains("schemaLocation") })
    }

    run {
        // 配置全对但产物没提交：最隐蔽，构建永远是绿的
        val spec = SchemaExportSpec(true, "${'$'}projectDir/schemas", 1, 17)
        val problems = SchemaExportSpecParser.check(spec, emptyList())
        ok("无 schema 文件应报问题", problems.any { it.contains("没有 schema JSON") })
    }

    run {
        // version 与文件名不匹配：迁移测试会找错基线
        val spec = SchemaExportSpec(true, "${'$'}projectDir/schemas", 2, 17)
        val problems = SchemaExportSpecParser.check(spec, listOf("1.json"))
        ok("缺少 2.json 应报问题", problems.any { it.contains("2.json") })
    }

    run {
        val spec = SchemaExportSpec(true, "${'$'}projectDir/schemas", 0, 17)
        ok(
            "version 为 0 应报问题",
            SchemaExportSpecParser.check(spec, listOf("1.json")).any { it.contains("version") }
        )
    }

    run {
        val spec = SchemaExportSpec(true, "${'$'}projectDir/schemas", 1, 0)
        ok(
            "entity 数为 0 应报问题",
            SchemaExportSpecParser.check(spec, listOf("1.json")).any { it.contains("entities") }
        )
    }

    // ==================== 五、schema JSON 骨架校验 ====================
    run {
        val json = """
            {
              "format": 1,
              "database": {
                "version": 1,
                "identityHash": "1a2b3c4d5e6f708192a3b4c5d6e7f809",
                "entities": [
                  { "tableName": "book_source" },
                  { "tableName": "rss_source" }
                ]
              }
            }
        """.trimIndent()
        eq(
            "合法骨架应无问题",
            SchemaExportSpecParser.checkSchemaJson(json, 1, 2),
            emptyList<String>()
        )
    }

    run {
        val json = """{"format": 2, "database": {"version": 1, "identityHash": "0123456789abcdef0123456789abcdef", "entities": [{"tableName": "a"}]}}"""
        ok(
            "format != 1 应报问题",
            SchemaExportSpecParser.checkSchemaJson(json, 1, 1).any { it.contains("format") }
        )
    }

    run {
        // 改了 @Database 忘重新导出，是最容易发生的错配
        val json = """{"format": 1, "database": {"version": 1, "identityHash": "0123456789abcdef0123456789abcdef", "entities": [{"tableName": "a"}]}}"""
        ok(
            "version 不匹配应报问题",
            SchemaExportSpecParser.checkSchemaJson(json, 2, 1).any { it.contains("version") }
        )
    }

    run {
        // 表数量不等：schema 是旧的，或实体没全纳入
        val json = """{"format": 1, "database": {"version": 1, "identityHash": "0123456789abcdef0123456789abcdef", "entities": [{"tableName": "a"}, {"tableName": "b"}]}}"""
        ok(
            "表数量不符应报问题",
            SchemaExportSpecParser.checkSchemaJson(json, 1, 3).any { it.contains("表数量") }
        )
    }

    run {
        // identityHash 是 Room 判断结构是否变化的唯一依据
        val json = """{"format": 1, "database": {"version": 1, "entities": [{"tableName": "a"}]}}"""
        ok(
            "缺 identityHash 应报问题",
            SchemaExportSpecParser.checkSchemaJson(json, 1, 1).any { it.contains("identityHash") }
        )
    }

    run {
        // Room 产出的必是小写 32 位；大写与长度不符都应拒绝
        val json = """{"format": 1, "database": {"version": 1, "identityHash": "0123456789ABCDEF0123456789ABCDEF", "entities": [{"tableName": "a"}]}}"""
        ok(
            "大写 identityHash 应报问题",
            SchemaExportSpecParser.checkSchemaJson(json, 1, 1).any { it.contains("identityHash") }
        )
    }

    run {
        // 重复表名：通常意味着两个实体映射到了同一张表
        val json = """{"format": 1, "database": {"version": 1, "identityHash": "0123456789abcdef0123456789abcdef", "entities": [{"tableName": "a"}, {"tableName": "a"}]}}"""
        ok(
            "重复表名应报问题",
            SchemaExportSpecParser.checkSchemaJson(json, 1, 2).any { it.contains("重复表名") }
        )
    }

    report()
}
