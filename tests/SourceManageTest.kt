import com.lanyue.reader.source.manage.SourceIntake
import com.lanyue.reader.source.manage.SourceIntake.FailureKind
import com.lanyue.reader.source.manage.SourceIntake.Origin
import com.lanyue.reader.source.manage.SourceListModel
import com.lanyue.reader.source.manage.SourceListModel.Filter
import com.lanyue.reader.source.manage.SourceListModel.Query
import com.lanyue.reader.source.manage.SourceListModel.Sort
import com.lanyue.reader.source.repo.SourceRepository.InstalledSource
import java.nio.charset.Charset

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

private fun src(
    url: String,
    name: String = url,
    group: String = "",
    enabled: Boolean = true,
    type: Int = 0,
    sortOrder: Int = 0,
) = InstalledSource(
    url = url,
    name = name,
    group = group,
    enabled = enabled,
    type = type,
    sortOrder = sortOrder,
)

private val GOOD_JSON = """
[{"bookSourceUrl":"https://a.com","bookSourceName":"甲"},
 {"bookSourceUrl":"https://b.com","bookSourceName":"乙"}]
""".trimIndent()

fun main() {
    // ================= 一、文件入口：大小 / 空 / 编码 =================
    val emptyBytes = SourceIntake.fromBytes(ByteArray(0))
    eq("空文件判空", emptyBytes.failure, FailureKind.EMPTY)
    eq("空文件来源", emptyBytes.origin, Origin.FILE)
    eq("空文件不可用", emptyBytes.ok, false)

    val nullBytes = SourceIntake.fromBytes(null)
    eq("null 字节判空", nullBytes.failure, FailureKind.EMPTY)

    // 超限要在编码判定之前拦下：16 MiB 乱码应报「过大」而不是「编码错」
    val over = ByteArray(SourceIntake.MAX_BYTES + 1) { 0x80.toByte() }
    val overRes = SourceIntake.fromBytes(over)
    eq("超限文件", overRes.failure, FailureKind.TOO_LARGE)
    check("超限提示含上限", overRes.hint().contains("16 MiB"), overRes.hint())
    check("超限明细含尺寸", overRes.detail.contains("MiB"), overRes.detail)

    // 恰好等于上限不算超限（边界）
    val atLimit = ByteArray(SourceIntake.MAX_BYTES) { ' '.code.toByte() }
    check("等于上限不判过大", SourceIntake.fromBytes(atLimit).failure != FailureKind.TOO_LARGE)

    // ================= 二、编码判定 =================
    val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
        GOOD_JSON.toByteArray(Charsets.UTF_8)
    val bomRes = SourceIntake.decode(utf8Bom)
    eq("UTF-8 BOM 编码", bomRes.encoding, "UTF-8")
    eq("UTF-8 BOM 去 BOM", bomRes.text.startsWith("["), true)
    check("UTF-8 BOM 可解", bomRes.ok)

    val gbkJson = GOodJsonGbk()
    val gbkRes = SourceIntake.fromBytes(gbkJson)
    eq("GBK 自动识别", gbkRes.encoding, "GBK")
    eq("GBK 导入成功", gbkRes.failure, FailureKind.NONE)
    eq("GBK 识别 2 条", gbkRes.accepted.size, 2)
    check("GBK 中文未乱", gbkRes.accepted.any { it.name.contains("甲") }, gbkRes.accepted.map { it.name }.toString())

    // 既非 UTF-8 也非 GBK：严格解码必须失败，而不是把非法字节换成问号蒙混过关
    val garbage = byteArrayOf(0x80.toByte(), 0x81.toByte(), 0xFF.toByte(), 0xFE.toByte())
    val gRes = SourceIntake.decode(garbage)
    eq("乱码判为不可解", gRes.ok, false)
    eq("乱码编码 unknown", gRes.encoding, "unknown")
    eq("乱码走编码失败", SourceIntake.fromBytes(garbage).failure, FailureKind.ENCODING)

    // UTF-16LE BOM
    val u16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
        "abc".toByteArray(Charset.forName("UTF-16LE"))
    val u16Res = SourceIntake.decode(u16)
    eq("UTF-16LE 识别", u16Res.encoding, "UTF-16LE")
    eq("UTF-16LE 内容", u16Res.text, "abc")

    // ================= 三、文本入口与失败分类 =================
    val okText = SourceIntake.fromText(GOOD_JSON)
    eq("粘贴成功", okText.failure, FailureKind.NONE)
    eq("粘贴来源", okText.origin, Origin.PASTE)
    eq("粘贴成功条数", okText.accepted.size, 2)
    check("成功提示含已识别", okText.hint().contains("已识别 2 条"), okText.hint())
    eq("成功无明细", okText.detail, "")

    eq("空文本判空", SourceIntake.fromText("").failure, FailureKind.EMPTY)
    eq("纯空白判空", SourceIntake.fromText("   \n\t  ").failure, FailureKind.EMPTY)
    eq("null 文本判空", SourceIntake.fromText(null).failure, FailureKind.EMPTY)

    // 语法错：能拿到内容但读不懂
    val broken = SourceIntake.fromText("[{\"bookSourceUrl\":")
    eq("坏 JSON 判语法错", broken.failure, FailureKind.SYNTAX)
    check("语法错提示指向 JSON", broken.hint().contains("JSON"), broken.hint())

    // 字段缺失：读懂了但不可用——必须与语法错分开
    val missing = SourceIntake.fromText("""[{"bookSourceName":"只有名字"}]""")
    eq("缺地址判字段缺失", missing.failure, FailureKind.NO_FIELDS)
    check("字段缺失提示指向必填", missing.hint().contains("必填"), missing.hint())
    check("字段缺失带原因", missing.detail.isNotBlank(), missing.detail)
    check("两类失败提示不同", broken.hint() != missing.hint())

    // 空数组：能解析但没有书源
    eq("空数组未识别到书源", SourceIntake.fromText("[]").failure, FailureKind.NOT_SOURCES)

    // 文本行格式仍可用
    val lines = SourceIntake.fromText("https://c.com\t丙组源\n这行不是链接\n")
    eq("文本行成功", lines.failure, FailureKind.NONE)
    eq("文本行收 1 条", lines.accepted.size, 1)
    eq("文本行拒 1 条", lines.rejectedCount, 1)

    // ================= 四、可操作性标记 =================
    eq("语法错可操作", FailureKind.SYNTAX.actionable, true)
    eq("字段缺失可操作", FailureKind.NO_FIELDS.actionable, true)
    eq("空不可操作", FailureKind.EMPTY.actionable, false)
    eq("过大不可操作", FailureKind.TOO_LARGE.actionable, false)
    eq("编码不可操作", FailureKind.ENCODING.actionable, false)
    eq("无失败不可操作", FailureKind.NONE.actionable, false)

    val textOver = SourceIntake.fromText("a".repeat(SourceIntake.MAX_BYTES + 8))
    eq("超长文本判过大", textOver.failure, FailureKind.TOO_LARGE)

    // ================= 五、列表：字段标签 =================
    eq("空分组归未分组", SourceListModel.groupLabelOf(src("u", group = "")), "未分组")
    eq("空白分组归未分组", SourceListModel.groupLabelOf(src("u", group = "   ")), "未分组")
    eq("有分组原样", SourceListModel.groupLabelOf(src("u", group = "小说")), "小说")
    eq("类型 0", SourceListModel.typeLabel(0), "文本")
    eq("类型 1", SourceListModel.typeLabel(1), "音频")
    eq("类型 2", SourceListModel.typeLabel(2), "图片")
    eq("类型 3", SourceListModel.typeLabel(3), "文件")
    eq("类型未知不崩", SourceListModel.typeLabel(9), "未知(9)")
    eq("状态开", SourceListModel.statusLabel(true), "已启用")
    eq("状态关", SourceListModel.statusLabel(false), "已停用")

    // ================= 六、列表：筛选 =================
    val list = listOf(
        src("https://a.com", "甲书源", "小说", true),
        src("https://b.com", "乙书源", "漫画", false),
        src("https://c.com", "丙书源", "", true),
    )

    eq("默认全部", SourceListModel.query(list, Query()).size, 3)
    eq("仅启用", SourceListModel.query(list, Query(filter = Filter.ENABLED)).size, 2)
    eq("仅停用", SourceListModel.query(list, Query(filter = Filter.DISABLED)).size, 1)
    eq("停用命中乙", SourceListModel.query(list, Query(filter = Filter.DISABLED)).first().name, "乙书源")

    eq("按名称搜", SourceListModel.query(list, Query(keyword = "甲")).size, 1)
    eq("按地址搜", SourceListModel.query(list, Query(keyword = "b.com")).size, 1)
    eq("按分组搜", SourceListModel.query(list, Query(keyword = "漫画")).size, 1)
    eq("大小写不敏感", SourceListModel.query(list, Query(keyword = "B.COM")).size, 1)
    eq("关键词首尾空格忽略", SourceListModel.query(list, Query(keyword = "  甲  ")).size, 1)
    eq("搜不到为空", SourceListModel.query(list, Query(keyword = "丁")).size, 0)

    // 分组筛选：空串等价于未分组
    eq("筛未分组(空串)", SourceListModel.query(list, Query(group = "")).size, 1)
    eq("筛未分组(空白)", SourceListModel.query(list, Query(group = " ")).size, 1)
    eq("筛小说组", SourceListModel.query(list, Query(group = "小说")).size, 1)
    eq("组不存在为空", SourceListModel.query(list, Query(group = "科幻")).size, 0)

    // 条件组合：分组 + 关键词 + 状态
    val combo = SourceListModel.query(
        list,
        Query(keyword = "书源", filter = Filter.ENABLED, group = "小说"),
    )
    eq("组合筛选", combo.size, 1)
    eq("组合筛选命中", combo.first().name, "甲书源")

    // ================= 七、列表：排序 =================
    val unsorted = listOf(
        src("https://x.com", "丙", "b组", true, 0, 3),
        src("https://y.com", "甲", "a组", false, 1, 1),
        src("https://z.com", "乙", "c组", true, 2, 2),
    )
    eq("默认按 sortOrder", SourceListModel.query(unsorted, Query(sort = Sort.DEFAULT)).map { it.name }, listOf("甲", "乙", "丙"))
    eq("按名称", SourceListModel.query(unsorted, Query(sort = Sort.NAME)).map { it.name }, listOf("丙", "乙", "甲").sorted())
    eq("按分组", SourceListModel.query(unsorted, Query(sort = Sort.GROUP)).map { it.group }, listOf("a组", "b组", "c组"))
    eq("按状态启用优先", SourceListModel.query(unsorted, Query(sort = Sort.STATUS)).first().enabled, true)
    eq("按状态停用垫底", SourceListModel.query(unsorted, Query(sort = Sort.STATUS)).last().enabled, false)

    // 排序必须稳定可复现：同一输入两次结果一致
    eq(
        "排序稳定",
        SourceListModel.query(unsorted, Query(sort = Sort.STATUS)).map { it.url },
        SourceListModel.query(unsorted, Query(sort = Sort.STATUS)).map { it.url },
    )

    // ================= 八、行模型 =================
    val rows = SourceListModel.rows(list)
    eq("行数", rows.size, 3)
    // 默认排序是 sortOrder 再按名称，不保证插入顺序，故按地址取行
    fun rowOf(url: String) = rows.first { it.source.url == url }
    eq("行分组标签", rowOf("https://c.com").groupLabel, "未分组")
    eq("行类型标签", rowOf("https://a.com").typeLabel, "文本")
    eq("行状态标签", rowOf("https://b.com").statusLabel, "已停用")
    check("行携带原对象", rowOf("https://a.com").source.name == "甲书源", rowOf("https://a.com").source.name)

    // 分组下拉：未分组排最前
    val opts = SourceListModel.groupOptions(list)
    eq("分组选项数", opts.size, 3)
    eq("未分组排首", opts.first(), "未分组")
    eq("分组选项有序", opts.drop(1), listOf("小说", "漫画"))

    // ================= 九、空态 =================
    eq("有内容无空态", SourceListModel.emptyHint(list), null)
    check("全空提示引导导入", SourceListModel.emptyHint(emptyList())!!.contains("导入"), "引导")
    check("搜不到提示含关键词", SourceListModel.emptyHint(list, Query(keyword = "丁"))!!.contains("丁"), "关键词")
    check("筛出空提示分组", SourceListModel.emptyHint(list, Query(group = "科幻"))!!.contains("科幻"), "分组")
    check("仅启用为空", SourceListModel.emptyHint(listOf(src("u", enabled = false)), Query(filter = Filter.ENABLED))!!.contains("启用"), "启用")
    check("仅停用为空", SourceListModel.emptyHint(listOf(src("u", enabled = true)), Query(filter = Filter.DISABLED))!!.contains("停用"), "停用")

    // ================= 十、启停 =================
    val toggled = SourceListModel.withEnabled(list, "https://b.com", true)
    eq("切换后乙启用", toggled.first { it.url == "https://b.com" }.enabled, true)
    eq("切换不动其他", toggled.first { it.url == "https://a.com" }.enabled, true)
    eq("切换条数不变", toggled.size, 3)
    check("切换返回新列表", toggled !== list)

    // 状态已一致时不产生改动，返回原列表（避免无谓重组）
    val same = SourceListModel.withEnabled(list, "https://a.com", true)
    check("无变化返回原列表", same === list)

    // 地址不存在：静默不动，不抛错
    val missingUrl = SourceListModel.withEnabled(list, "https://nope.com", false)
    check("未知地址不动", missingUrl === list)

    val allOff = SourceListModel.withAllEnabled(list, false)
    eq("全部停用", allOff.count { !it.enabled }, 3)
    check("已全停用返回原列表", SourceListModel.withAllEnabled(allOff, false) === allOff)
    eq("全部启用", SourceListModel.withAllEnabled(list, true).count { it.enabled }, 3)

    // 原列表不可被就地修改
    eq("原列表未被改", list[1].enabled, false)

    // ================= 十一、计数汇总 =================
    val c = SourceListModel.counts(list)
    eq("总数", c.total, 3)
    eq("启用数", c.enabled, 2)
    eq("停用数", c.disabled, 1)
    eq("分组数", c.groups, 3)
    check("汇总串含总数", c.summary.contains("共 3 个书源"), c.summary)
    eq("空列表分组数", SourceListModel.counts(emptyList()).groups, 0)

    println("================ T11 书源管理页 单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}

/** 用 GBK 编码一份含中文的合法 JSON，验证自动识别。 */
private fun GOodJsonGbk(): ByteArray {
    val json = """[{"bookSourceUrl":"https://a.com","bookSourceName":"甲"},
                   {"bookSourceUrl":"https://b.com","bookSourceName":"乙"}]"""
    return json.toByteArray(Charset.forName("GBK"))
}
