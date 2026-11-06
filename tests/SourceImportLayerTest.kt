import com.lanyue.reader.source.debug.SourceDebugger
import com.lanyue.reader.source.debug.SourceDebugger.Stage
import com.lanyue.reader.source.debug.SourceDebugger.Status
import com.lanyue.reader.source.import.SourceImportParser
import com.lanyue.reader.source.repo.SourceRepository
import com.lanyue.reader.source.repo.SourceRepository.InstalledSource

private var pass = 0
private var fail = 0
private val failures = ArrayList<String>()

private fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++ else {
        fail++
        failures.add("$name -> actual=$actual expected=$expected")
    }
}

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) pass++ else {
        fail++
        failures.add("$name -> $detail")
    }
}

fun main() {
    // ================= 清洗：注释与尾随逗号 =================
    // 最关键：https:// 里的 // 不能被当注释
    val urlSafe = SourceImportParser.stripCommentsAndTrailingCommas(
        "{\"bookSourceUrl\":\"https://a.com/x\",\"bookSourceName\":\"A\"}"
    )
    check("URL 未被截断", urlSafe.contains("https://a.com/x"), urlSafe)

    val withLineComment = SourceImportParser.stripCommentsAndTrailingCommas(
        "// 顶部注释\n{\"a\":1} // 尾部注释\n"
    )
    eq("去行注释", withLineComment.trim(), "{\"a\":1}")

    val withBlock = SourceImportParser.stripCommentsAndTrailingCommas(
        "{/* 块注释 */\"a\":1}"
    )
    eq("去块注释", withBlock, "{\"a\":1}")

    val trailing = SourceImportParser.stripCommentsAndTrailingCommas(
        "{\"a\":[1,2,],\"b\":2,}"
    )
    eq("去尾随逗号", trailing, "{\"a\":[1,2],\"b\":2}")

    // 转义引号内的 // 也不能被当注释
    val escaped = SourceImportParser.stripCommentsAndTrailingCommas(
        "{\"a\":\"x\\\"//y\"}"
    )
    check("转义引号内 // 保留", escaped.contains("//y"), escaped)

    // 单引号字符串（JSON5）
    val singleQ = SourceImportParser.stripCommentsAndTrailingCommas(
        "{\"a\":'p//q'}"
    )
    check("单引号内 // 保留", singleQ.contains("p//q"), singleQ)

    // ================= 解析：JSON 数组 =================
    val arrJson = """
    [
      {"bookSourceUrl":"https://s1.com","bookSourceName":"源一","bookSourceGroup":"小说","bookSourceType":0},
      {"bookSourceUrl":"https://s2.com","bookSourceName":"源二","bookSourceType":1}
    ]
    """.trimIndent()
    val r1 = SourceImportParser.parse(arrJson)
    eq("数组解析格式", r1.format, "json-array")
    eq("数组解析条数", r1.accepted.size, 2)
    eq("数组无拒绝", r1.rejected.size, 0)
    eq("首条名称", r1.accepted[0].name, "源一")
    eq("首条分组", r1.accepted[0].group, "小说")
    eq("次条类型", r1.accepted[1].type, 1)
    check("raw 保留原文", r1.accepted[0].raw.contains("s1.com"))

    // ================= 解析：单个对象 =================
    val objJson = """{"bookSourceUrl":"https://one.com","bookSourceName":"单源"}"""
    val r2 = SourceImportParser.parse(objJson)
    eq("单对象格式", r2.format, "json-object")
    eq("单对象条数", r2.accepted.size, 1)
    eq("单对象名称", r2.accepted[0].name, "单源")

    // ================= 解析：包裹层 =================
    val wrapped = """{"code":0,"data":[{"bookSourceUrl":"https://w1.com","bookSourceName":"包一"}]}"""
    val r3 = SourceImportParser.parse(wrapped)
    eq("包裹层格式", r3.format, "json-wrapped")
    eq("包裹层条数", r3.accepted.size, 1)
    eq("包裹层名称", r3.accepted[0].name, "包一")

    // 自定义包裹键名（不在已知列表里也要能找到）
    val customWrap = """{"mySources":{"x":[{"bookSourceUrl":"https://c1.com","name":"自定义"}]}}"""
    val r4 = SourceImportParser.parse(customWrap)
    eq("自定义包裹能识别", r4.accepted.size, 1)
    eq("自定义包裹别名 name", r4.accepted[0].name, "自定义")

    // 字段别名 sourceUrl / url
    val alias = """[{"url":"https://alias.com","title":"别名源"}]"""
    val r5 = SourceImportParser.parse(alias)
    eq("url 别名识别", r5.accepted.size, 1)
    eq("title 别名识别", r5.accepted[0].name, "别名源")

    // ================= 解析：容错 =================
    // 缺地址的条目被拒
    val noUrl = """[{"bookSourceName":"无地址"},{"bookSourceUrl":"https://ok.com","bookSourceName":"好"}]"""
    val r6 = SourceImportParser.parse(noUrl)
    eq("缺地址被拒", r6.rejected.size, 1)
    eq("合法条目保留", r6.accepted.size, 1)
    eq("合法条目地址", r6.accepted[0].url, "https://ok.com")

    // 非 http 被拒
    val badScheme = """[{"bookSourceUrl":"ftp://x.com","bookSourceName":"F"}]"""
    val r7 = SourceImportParser.parse(badScheme)
    eq("非 http 被拒", r7.rejected.size, 1)
    eq("非 http 无接受", r7.accepted.size, 0)

    // 文件内重复只留一条
    val dup = """[{"bookSourceUrl":"https://d.com","bookSourceName":"A"},
                 {"bookSourceUrl":"https://d.com","bookSourceName":"B"}]"""
    val r8 = SourceImportParser.parse(dup)
    eq("重复去重后一条", r8.accepted.size, 1)
    eq("重复计数", r8.duplicates, 1)
    eq("保留首次出现", r8.accepted[0].name, "A")

    // 缺名称时用域名兜底
    val noName = """[{"bookSourceUrl":"https://host-only.com/path"}]"""
    val r9 = SourceImportParser.parse(noName)
    eq("无名称用域名", r9.accepted[0].name, "host-only.com")

    // 带注释 + 尾随逗号的真实文件
    val messy = """
    [
      // 主源
      {"bookSourceUrl":"https://m1.com","bookSourceName":"乱一",},
      {"bookSourceUrl":"https://m2.com","bookSourceName":"乱二"}
    ]
    """.trimIndent()
    val r10 = SourceImportParser.parse(messy)
    eq("脏 JSON 仍解析出两条", r10.accepted.size, 2)

    // 类型越界被夹紧
    val badType = """[{"bookSourceUrl":"https://t.com","bookSourceName":"T","bookSourceType":99}]"""
    val r11 = SourceImportParser.parse(badType)
    eq("类型越界夹到 3", r11.accepted[0].type, 3)

    // ================= 解析：纯文本行 =================
    val text = """
    # 注释行
    https://t1.com 源甲
    https://t2.com	源乙	分组二
    not-a-url
    https://t1.com 重复
    """.trimIndent()
    val r12 = SourceImportParser.parse(text)
    eq("文本行格式", r12.format, "text-lines")
    eq("文本行接受数", r12.accepted.size, 2)
    eq("文本行名称", r12.accepted[0].name, "源甲")
    eq("文本行分组", r12.accepted[1].group, "分组二")
    eq("文本行拒绝", r12.rejected.size, 1)
    eq("文本行去重", r12.duplicates, 1)
    check("文本行 raw 含地址", r12.accepted[0].raw.contains("t1.com"))

    // ================= 解析：空与异常 =================
    eq("空串 isEmpty", SourceImportParser.parse("").isEmpty, true)
    eq("null 格式", SourceImportParser.parse(null).format, "empty")
    val onlyComments = SourceImportParser.parse("// 只有注释\n/* 块 */")
    check("只有注释无接受", onlyComments.accepted.isEmpty())
    val badJson = SourceImportParser.parse("{this is broken")
    check("坏 JSON 不崩", badJson.accepted.isEmpty())
    eq("坏 JSON 无接受", badJson.accepted.size, 0)

    // 空 JSON 数组
    val emptyArr = SourceImportParser.parse("[]")
    eq("空数组无接受", emptyArr.accepted.size, 0)
    check("空数组有拒绝说明", emptyArr.rejected.isNotEmpty() || emptyArr.total == 0)

    // 摘要文案
    check("摘要含已识别", r1.summary().contains("已识别"), r1.summary())
    eq("空结果摘要", SourceImportParser.parse("[]").rejected.size >= 1, true)

    // ================= 仓库：基础 =================
    val repo = SourceRepository()
    eq("空仓库", repo.isEmpty(), true)
    eq("空仓库大小", repo.size(), 0)

    val s1 = InstalledSource("https://a.com", "源A", "小说", updatedAt = 100)
    val rep1 = repo.installOne(s1)
    eq("新增一条", rep1.added.size, 1)
    eq("仓库大小 1", repo.size(), 1)
    eq("默认启用", repo.find("https://a.com")!!.enabled, true)

    // 新增排序接在已有之后
    val s2 = InstalledSource("https://b.com", "源B", "小说", updatedAt = 100)
    repo.installOne(s2)
    check("排序递增", repo.find("https://b.com")!!.sortOrder > repo.find("https://a.com")!!.sortOrder)

    // ================= 仓库：升级语义 =================
    // 本机旧 -> 覆盖配置但保留启用状态与排序
    repo.setEnabled("https://a.com", false)
    val orderBefore = repo.find("https://a.com")!!.sortOrder
    val upgrade = repo.installOne(
        InstalledSource("https://a.com", "源A新", "小说", updatedAt = 200, raw = "newcfg")
    )
    eq("更新计数", upgrade.updated.size, 1)
    eq("名称已更新", repo.find("https://a.com")!!.name, "源A新")
    eq("启用状态保留", repo.find("https://a.com")!!.enabled, false)
    eq("排序保留", repo.find("https://a.com")!!.sortOrder, orderBefore)
    eq("配置已更新", repo.find("https://a.com")!!.raw, "newcfg")

    // 备份不更新 -> unchanged
    val noUp = repo.installOne(InstalledSource("https://a.com", "旧版", updatedAt = 50))
    eq("不更新计入 unchanged", noUp.unchanged.size, 1)
    eq("名称未被旧版覆盖", repo.find("https://a.com")!!.name, "源A新")

    // 用户手工改过 -> 受保护，不覆盖
    repo.markUserEdited("https://a.com", true)
    val guarded = repo.installOne(
        InstalledSource("https://a.com", "想覆盖", updatedAt = 999)
    )
    eq("受保护被跳过", guarded.skippedProtected.size, 1)
    eq("受保护名称不变", repo.find("https://a.com")!!.name, "源A新")

    // ================= 仓库：无效输入 =================
    val invalidRep = repo.install(listOf(InstalledSource("ftp://bad.com", "坏")))
    eq("无效地址计入", invalidRep.skippedInvalid.size, 1)
    eq("无效未入库", repo.contains("ftp://bad.com"), false)

    // 输入内部重复
    val dupeIn = repo.install(
        listOf(
            InstalledSource("https://dup2.com", "第一", updatedAt = 1),
            InstalledSource("https://dup2.com", "第二", updatedAt = 2)
        )
    )
    eq("输入去重计数", dupeIn.dedupedIncoming, 1)
    eq("输入去重后一条", dupeIn.added.size, 1)
    eq("后者生效", repo.find("https://dup2.com")!!.name, "第二")

    // ================= 仓库：分组 / 查询 / 统计 =================
    repo.install(listOf(
        InstalledSource("https://g1.com", "组一源", "历史", updatedAt = 1),
        InstalledSource("https://g2.com", "组二源", "玄幻", updatedAt = 1)
    ))
    val byGroup = repo.byGroup()
    check("分组含小说", byGroup.containsKey("小说"))
    check("分组含历史", byGroup.containsKey("历史"))
    check("分组含玄幻", byGroup.containsKey("玄幻"))
    check("分组数正确", repo.groups().size >= 4, repo.groups().joinToString(","))

    eq("搜索按名称", repo.search("组一源").size, 1)
    eq("搜索按地址", repo.search("g2.com").size, 1)
    eq("搜索按分组", repo.search("玄幻").size, 1)
    eq("空关键字返回全部", repo.search("  ").size, repo.size())
    eq("无匹配返回空", repo.search("zzz-not-exist").size, 0)

    val st = repo.stats()
    eq("统计总数", st.total, repo.size())
    eq("统计启用+停用=总数", st.enabled + st.disabled, st.total)
    check("统计有手工编辑", st.userEdited >= 1)

    // ================= 仓库：增删改 =================
    val beforeRemove = repo.size()
    eq("删除成功", repo.remove("https://g2.com"), true)
    eq("删除后减少", repo.size(), beforeRemove - 1)
    eq("重复删除返回 false", repo.remove("https://g2.com"), false)

    val g1Count = repo.export("历史").size
    eq("删组条数", repo.removeGroup("历史"), g1Count)
    eq("删组后无该组", repo.export("历史").size, 0)

    // 前面删组已把 g1 删掉，重新装一条专用于改名/移组测试
    repo.installOne(InstalledSource("https://g1.com", "恢复源", "历史", updatedAt = 9))
    eq("重命名成功", repo.rename("https://g1.com", "改名后"), true)
    eq("重命名已生效", repo.find("https://g1.com")!!.name, "改名后")
    eq("重命名标记手工编辑", repo.find("https://g1.com")!!.userEdited, true)
    eq("重命名同名返回 false", repo.rename("https://g1.com", "改名后"), false)
    eq("空名重命名被拒", repo.rename("https://g1.com", "  "), false)
    eq("重命名不存在返回 false", repo.rename("https://nope.com", "x"), false)

    eq("移动分组成功", repo.moveToGroup("https://g1.com", "武侠"), true)
    eq("移动后分组", repo.find("https://g1.com")!!.group, "武侠")
    eq("移到同名返回 false", repo.moveToGroup("https://g1.com", "武侠"), false)
    eq("空分组回落默认", repo.moveToGroup("https://g1.com", "  "), true)
    eq("回落后为默认分组",
        repo.find("https://g1.com")!!.group, SourceRepository.DEFAULT_GROUP)

    // 批量启停
    val allUrls = repo.all().map { it.url }
    check("批量停用", repo.setEnabled(allUrls, false) > 0)
    eq("全部停用后无启用", repo.enabled().size, 0)
    check("批量启用", repo.setEnabled(allUrls, true) > 0)
    eq("全部启用", repo.enabled().size, repo.size())

    // 重排
    val reversed = repo.all().map { it.url }.reversed()
    eq("重排返回 true", repo.reorder(reversed), true)
    eq("重排后首项", repo.all().first().url, reversed.first())

    // 写校验摘要
    eq("写摘要成功", repo.updateValidateSummary(reversed.first(), "通过"), true)
    eq("摘要已写入", repo.find(reversed.first())!!.validateSummary, "通过")
    eq("重复写同摘要返回 false", repo.updateValidateSummary(reversed.first(), "通过"), false)

    // 摘要文案
    check("安装摘要含新增", rep1.summary().contains("新增"), rep1.summary())
    eq("空安装摘要", SourceRepository.InstallReport().summary(), "没有需要安装的书源")

    // ================= 调试：搜索 =================
    val okSearch = SourceDebugger.checkSearch(listOf("书一", "书二"), "https://a.com")
    eq("搜索通过", okSearch.status, Status.PASS)
    eq("搜索计数", okSearch.count, 2)
    eq("搜索阶段", okSearch.stage, Stage.SEARCH)

    val emptySearch = SourceDebugger.checkSearch(emptyList(), "https://a.com")
    eq("空搜索失败", emptySearch.status, Status.FAIL)
    check("空搜索有提示", emptySearch.message.contains("搜索"))

    val hugeSearch = SourceDebugger.checkSearch(List(600) { "r$it" }, "https://a.com")
    eq("过多结果警告", hugeSearch.status, Status.WARN)
    eq("过多计数", hugeSearch.count, 600)

    val blankSearch = SourceDebugger.checkSearch(listOf("", " ", "ok", ""), "https://a.com")
    eq("大量空白警告", blankSearch.status, Status.WARN)

    // ================= 调试：详情 =================
    val okDetail = SourceDebugger.checkDetail("书名", "cover.jpg", "简介")
    eq("详情全通过", okDetail.status, Status.PASS)

    val noNameDetail = SourceDebugger.checkDetail(null, "c.jpg", "简介")
    eq("缺书名失败", noNameDetail.status, Status.FAIL)

    val partialDetail = SourceDebugger.checkDetail("书名", null, null)
    eq("缺封面简介警告", partialDetail.status, Status.WARN)
    check("警告说明含封面", partialDetail.message.contains("封面"), partialDetail.message)
    check("警告说明含简介", partialDetail.message.contains("简介"), partialDetail.message)

    // ================= 调试：目录 =================
    val okToc = SourceDebugger.checkToc(List(20) { "第${it}章" })
    eq("目录通过", okToc.status, Status.PASS)
    eq("目录章节数", okToc.count, 20)

    eq("空目录失败", SourceDebugger.checkToc(emptyList()).status, Status.FAIL)
    eq("单章警告", SourceDebugger.checkToc(listOf("唯一章")).status, Status.WARN)
    val blankToc = SourceDebugger.checkToc(listOf("a", "", "c"))
    eq("含空标题警告", blankToc.status, Status.WARN)
    check("空标题计数在文案", blankToc.message.contains("1"), blankToc.message)

    // ================= 调试：正文 =================
    eq("空正文失败", SourceDebugger.checkContent(null).status, Status.FAIL)
    eq("空白正文失败", SourceDebugger.checkContent("   ").status, Status.FAIL)
    eq("极短正文失败", SourceDebugger.checkContent("太短了").status, Status.FAIL)

    val shortBody = "字".repeat(100)
    eq("偏短正文警告", SourceDebugger.checkContent(shortBody).status, Status.WARN)

    val goodBody = "正文内容。".repeat(100)
    eq("正常正文通过", SourceDebugger.checkContent(goodBody).status, Status.PASS)
    eq("正文字数", SourceDebugger.checkContent(goodBody).chars, goodBody.length)

    val htmlBody = ("<p>段落</p>").repeat(30) + "字".repeat(300)
    eq("含标签正文警告", SourceDebugger.checkContent(htmlBody).status, Status.WARN)
    check("标签提示在文案", SourceDebugger.checkContent(htmlBody).message.contains("标签"))

    // 样本被截断
    val longBody = "字".repeat(2000)
    check("样本被截断", SourceDebugger.checkContent(longBody).sample.length <= 500)

    // ================= 调试：整链 =================
    val pipeline = SourceDebugger.buildReport(
        "https://a.com",
        okSearch, okDetail, okToc, SourceDebugger.checkContent(goodBody)
    )
    eq("整链可用", pipeline.isUsable, true)
    eq("整链四步", pipeline.steps.size, 4)
    eq("整链通过数", pipeline.passCount, 4)
    eq("整链无失败阶段", pipeline.failedStage, null)
    eq("整链摘要", pipeline.summary(), "四步全部通过，书源可用")

    // 搜索失败 -> 后续 SKIP
    val broken = SourceDebugger.buildReport(
        "https://a.com",
        emptySearch, okDetail, okToc, SourceDebugger.checkContent(goodBody)
    )
    eq("搜索失败整链不可用", broken.isUsable, false)
    eq("首个失败阶段", broken.failedStage, Stage.SEARCH)
    eq("后续被跳过", broken.step(Stage.DETAIL)!!.status, Status.SKIP)
    eq("跳过说明", broken.step(Stage.DETAIL)!!.message.contains("上一步失败"), true)
    check("失败摘要含阶段名", broken.summary().contains("搜索"), broken.summary())

    // 警告不阻断
    val warnChain = SourceDebugger.buildReport(
        "https://a.com", okSearch, partialDetail, okToc, SourceDebugger.checkContent(goodBody)
    )
    eq("警告仍可用", warnChain.isUsable, true)
    eq("警告计数", warnChain.warnCount, 1)
    check("警告摘要含留意", warnChain.summary().contains("留意"), warnChain.summary())

    // 未执行阶段标 SKIP
    val partialReport = SourceDebugger.buildReport("https://a.com", okSearch, null, null, null)
    eq("未执行标跳过", partialReport.step(Stage.DETAIL)!!.status, Status.SKIP)

    // ================= 调试：静态自检 =================
    val staticBad = SourceDebugger.staticCheck(
        "ftp://bad", "源", null, null, null, null
    )
    eq("静态自检失败", staticBad.isUsable, false)
    eq("静态自检单步", staticBad.steps.size, 1)

    val staticOk = SourceDebugger.staticCheck(
        "https://a.com", "源名",
        "https://a.com/s?q={{key}}", "class.s@text",
        "class.toc@tag.a", "id.c@text"
    )
    eq("静态自检可用", staticOk.isUsable, true)
    check("静态自检提示实测", staticOk.steps.first().message.contains("实测"),
        staticOk.steps.first().message)

    // ================= 枚举契约 =================
    eq("阶段 id 解析", Stage.fromId("toc"), Stage.TOC)
    eq("未知阶段", Stage.fromId("nope"), null)
    eq("状态 isBlocking", Status.FAIL.isBlocking, true)
    eq("PASS 不阻断", Status.PASS.isBlocking, false)
    eq("阶段数", Stage.entries.size, 4)
    eq("状态数", Status.entries.size, 4)

    println("================ R7 源导入/仓库/调试 单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
