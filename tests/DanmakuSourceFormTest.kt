import com.lanyue.reader.danmaku.DanmakuSourceFormModel
import com.lanyue.reader.danmaku.DanmakuSourceFormModel.Form
import com.lanyue.reader.danmaku.DanmakuSourceFormModel.SubmitResult
import com.lanyue.reader.danmaku.DanmakuSourceRegistry
import com.lanyue.reader.danmaku.DanmakuSourceValidator
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import kotlin.system.exitProcess

private var pass = 0
private val failures = ArrayList<String>()

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
        exitProcess(1)
    }
}

private fun src(
    id: String,
    name: String = id,
    apiBase: String = "https://$id.example.com/api",
    enabled: Boolean = true,
    sortOrder: Int = 0,
    verified: Boolean = false
) = DanmakuSourceEntity(
    id = id, name = name, apiBase = apiBase,
    enabled = enabled, sortOrder = sortOrder, verified = verified
)

fun main() {
    // ==================== 一、新建：校验通过 ====================
    run {
        val form = Form(apiBase = "https://new.example.com/api", name = "新源")
        val r = DanmakuSourceFormModel.submit(form, emptyList(), probe = { null })
        ok("新建成功", r is SubmitResult.Created)
        val c = r as SubmitResult.Created
        eq("名称保留", c.entity.name, "新源")
        eq("地址保留", c.entity.apiBase, "https://new.example.com/api")
        ok("已验证", c.verified)
        ok("无告警", c.warning == null)
    }

    // ==================== 二、新建：名称为空自动兜底 ====================
    run {
        val form = Form(apiBase = "auto.example.com/api", name = "")
        val r = DanmakuSourceFormModel.submit(form, emptyList(), probe = { null })
        ok("空名称可创建", r is SubmitResult.Created)
        val c = r as SubmitResult.Created
        eq("名称兜底为主机名", c.entity.name, "auto.example.com")
        eq("地址补协议", c.entity.apiBase, "https://auto.example.com/api")
    }

    // ==================== 三、新建：探活失败强制保存为未验证 ====================
    run {
        val form = Form(apiBase = "https://down.example.com/api", name = "离线源")
        val r = DanmakuSourceFormModel.submit(form, emptyList(), probe = { "连接超时" })
        ok("探活失败仍创建", r is SubmitResult.Created)
        val c = r as SubmitResult.Created
        ok("标记未验证", !c.verified)
        ok("告警含原因", c.warning?.contains("连接超时") == true)
        eq("地址仍保存", c.entity.apiBase, "https://down.example.com/api")
    }

    // ==================== 四、新建：探活失败且不强制保存 → 拒绝 ====================
    run {
        val form = Form(apiBase = "https://down2.example.com/api", name = "离线源2")
        val r = DanmakuSourceFormModel.submit(form, emptyList(), probe = { "403" }, forceSave = false)
        ok("不强制保存时拒绝", r is SubmitResult.Rejected)
        val rej = r as SubmitResult.Rejected
        ok("拒绝原因含探活失败", rej.reason.contains("探活失败"))
        ok("拒绝原因含强制保存提示", rej.reason.contains("强制保存"))
    }

    // ==================== 五、新建：空 apiBase 拒绝 ====================
    run {
        val form = Form(apiBase = "   ", name = "空的")
        val r = DanmakuSourceFormModel.submit(form, emptyList())
        ok("空 apiBase 拒绝", r is SubmitResult.Rejected)
        ok("原因含 apiBase", (r as SubmitResult.Rejected).reason.contains("apiBase"))
    }

    // ==================== 六、新建：格式无效拒绝 ====================
    run {
        val form = Form(apiBase = "not-a-url", name = "坏的")
        val r = DanmakuSourceFormModel.submit(form, emptyList())
        ok("无效格式拒绝", r is SubmitResult.Rejected)
        ok("原因含格式", (r as SubmitResult.Rejected).reason.contains("格式"))
    }

    // ==================== 七、重复检测：相同地址 ====================
    run {
        val existing = listOf(src("a", apiBase = "https://dup.example.com/api"))
        val form = Form(apiBase = "https://dup.example.com/api", name = "重复源")
        val r = DanmakuSourceFormModel.submit(form, existing, probe = { null })
        ok("重复地址被拦", r is SubmitResult.Duplicate)
        val d = r as SubmitResult.Duplicate
        eq("指出重复 ID", d.existingId, "a")
        eq("指出重复名称", d.existingName, "a")
    }

    // ==================== 八、重复检测：大小写与斜杠差异 ====================
    run {
        val existing = listOf(src("orig", apiBase = "https://Dup.Example.com/api/"))
        val form = Form(apiBase = "https://dup.example.com/api", name = "看起来不同")
        val r = DanmakuSourceFormModel.submit(form, existing, probe = { null })
        ok("大小写差异仍判重复", r is SubmitResult.Duplicate)
    }

    // ==================== 九、编辑自己不算重复 ====================
    run {
        val existing = listOf(src("self", apiBase = "https://self.example.com/api"))
        val form = DanmakuSourceFormModel.editForm(existing.first())
        val r = DanmakuSourceFormModel.submit(form, existing, probe = { null })
        ok("编辑自己不判重复", r is SubmitResult.Updated)
    }

    // ==================== 十、编辑：改名改地址 ====================
    run {
        val existing = listOf(src("edit1", name = "原名", apiBase = "https://old.example.com/api"))
        val form = Form(
            apiBase = "https://new.example.com/api", name = "新名",
            enabled = true, editingId = "edit1"
        )
        val r = DanmakuSourceFormModel.submit(form, existing, probe = { null })
        ok("编辑成功", r is SubmitResult.Updated)
        val u = r as SubmitResult.Updated
        eq("新名称", u.entity.name, "新名")
        eq("新地址", u.entity.apiBase, "https://new.example.com/api")
        ok("编辑后已验证", u.verified)
    }

    // ==================== 十一、编辑：保留 sortOrder ====================
    run {
        val existing = listOf(
            src("a", sortOrder = 0),
            src("b", sortOrder = 1),
            src("c", sortOrder = 2, apiBase = "https://c.example.com/api")
        )
        val form = Form(
            apiBase = "https://c.example.com/api/v2", name = "C改",
            editingId = "c"
        )
        val r = DanmakuSourceFormModel.submit(form, existing, probe = { null })
        ok("编辑成功", r is SubmitResult.Updated)
        val u = r as SubmitResult.Updated
        eq("sortOrder 保留", u.entity.sortOrder, 2)
    }

    // ==================== 十二、ID 生成 ====================
    run {
        val id1 = DanmakuSourceFormModel.generateId("https://api.dandanplay.net/v2", emptyList())
        eq("ID 用主机名", id1, "api.dandanplay.net")

        val existing = listOf(src("api.dandanplay.net"))
        val id2 = DanmakuSourceFormModel.generateId("https://api.dandanplay.net/v2", existing)
        ok("ID 重复时加后缀", id2.startsWith("api.dandanplay.net-"))
        ok("ID 不与现有冲突", id2 != "api.dandanplay.net")

        // 多次重复
        val existing2 = listOf(
            src("api.dandanplay.net"),
            src("api.dandanplay.net-1"),
            src("api.dandanplay.net-2")
        )
        val id3 = DanmakuSourceFormModel.generateId("https://api.dandanplay.net/v2", existing2)
        eq("第三次重复加 -2", id3, "api.dandanplay.net-3")
    }

    // ==================== 十三、表单校验状态 ====================
    run {
        eq("空输入", DanmakuSourceFormModel.formValidationState(""), "empty")
        eq("空白输入", DanmakuSourceFormModel.formValidationState("   "), "empty")
        eq("有效地址", DanmakuSourceFormModel.formValidationState("https://a.example.com/api"), "ok")
        eq("漏协议补全后有效", DanmakuSourceFormModel.formValidationState("a.example.com/api"), "ok")
        eq("无效地址", DanmakuSourceFormModel.formValidationState("not-a-url"), "invalid")
        eq("单段主机名无效", DanmakuSourceFormModel.formValidationState("api"), "invalid")
    }

    // ==================== 十四、editForm 构造 ====================
    run {
        val entity = src("x", name = "测试源", apiBase = "https://x.example.com/api", enabled = false)
        val form = DanmakuSourceFormModel.editForm(entity)
        eq("editForm apiBase", form.apiBase, "https://x.example.com/api")
        eq("editForm name", form.name, "测试源")
        eq("editForm enabled", form.enabled, false)
        eq("editForm editingId", form.editingId, "x")
        ok("editForm isEditing", form.isEditing)
    }

    // ==================== 十五、toRegistryOp：新建 ====================
    run {
        val existing = listOf(src("a"), src("b"))
        val form = Form(apiBase = "https://c.example.com/api", name = "C源")
        val r = DanmakuSourceFormModel.submit(form, existing, probe = { null })
        val op = DanmakuSourceFormModel.toRegistryOp(r, existing)
        ok("新建 registry 成功", op is DanmakuSourceRegistry.ReorderResult.Ok)
        val ordered = (op as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        eq("三个源", ordered.size, 3)
        eq("新源在末尾", ordered.last().name, "C源")
        eq("新源 sortOrder=2", ordered.last().sortOrder, 2)
    }

    // ==================== 十六、toRegistryOp：编辑 ====================
    run {
        val existing = listOf(
            src("a", sortOrder = 0),
            src("b", name = "原名", sortOrder = 1, apiBase = "https://b.example.com/api")
        )
        val form = Form(apiBase = "https://b.example.com/api/v2", name = "改名", editingId = "b")
        val r = DanmakuSourceFormModel.submit(form, existing, probe = { null })
        val op = DanmakuSourceFormModel.toRegistryOp(r, existing)
        ok("编辑 registry 成功", op is DanmakuSourceRegistry.ReorderResult.Ok)
        val ordered = (op as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        eq("数量不变", ordered.size, 2)
        eq("b 已改名", ordered.first { it.id == "b" }.name, "改名")
        eq("b 新地址", ordered.first { it.id == "b" }.apiBase, "https://b.example.com/api/v2")
        eq("sortOrder 保留", ordered.first { it.id == "b" }.sortOrder, 1)
    }

    // ==================== 十七、toRegistryOp：拒绝时不操作 ====================
    run {
        val existing = listOf(src("a"))
        val form = Form(apiBase = "", name = "空的")
        val r = DanmakuSourceFormModel.submit(form, existing)
        val op = DanmakuSourceFormModel.toRegistryOp(r, existing)
        ok("拒绝时 registry 也拒绝", op is DanmakuSourceRegistry.ReorderResult.Rejected)
    }

    // ==================== 十八、toRegistryOp：超上限 ====================
    run {
        val existing = (0 until DanmakuSourceRegistry.MAX_SOURCES).map {
            src("s$it", sortOrder = it)
        }
        val form = Form(apiBase = "https://overflow.example.com/api", name = "超限")
        val r = DanmakuSourceFormModel.submit(form, existing, probe = { null })
        ok("表单提交成功（无重复）", r is SubmitResult.Created)
        val op = DanmakuSourceFormModel.toRegistryOp(r, existing)
        ok("registry 拒绝超限", op is DanmakuSourceRegistry.ReorderResult.Rejected)
    }

    // ==================== 十九、端到端：新建→编辑→启停→移动 ====================
    run {
        var list = emptyList<DanmakuSourceEntity>()

        // 新建 3 个源
        val names = listOf("alpha.example.com/api" to "A源",
            "beta.example.com/api" to "B源",
            "gamma.example.com/api" to "C源")
        for ((base, name) in names) {
            val form = Form(apiBase = base, name = name)
            val r = DanmakuSourceFormModel.submit(form, list, probe = { null })
            ok("$name 创建成功", r is SubmitResult.Created)
            val op = DanmakuSourceFormModel.toRegistryOp(r, list)
            list = (op as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        }
        eq("三个源", list.size, 3)
        eq("顺序追加", list.map { it.name }, listOf("A源", "B源", "C源"))

        // 编辑 B 源改名
        val editForm = Form(apiBase = "https://beta.example.com/api", name = "B改", editingId = list[1].id)
        val editR = DanmakuSourceFormModel.submit(editForm, list, probe = { null })
        ok("B 编辑成功", editR is SubmitResult.Updated)
        val editOp = DanmakuSourceFormModel.toRegistryOp(editR, list)
        list = (editOp as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        eq("B 已改名", list.first { it.id.contains("beta") }.name, "B改")

        // 尝试新建一个与 B 地址重复的
        val dupForm = Form(apiBase = "https://beta.example.com/api", name = "重复")
        val dupR = DanmakuSourceFormModel.submit(dupForm, list, probe = { null })
        ok("重复被拦", dupR is SubmitResult.Duplicate)
    }

    // ==================== 二十、新建未验证源的 ID 与 verified 状态 ====================
    run {
        val form = Form(apiBase = "https://unverified.example.com/api", name = "未验证源")
        val r = DanmakuSourceFormModel.submit(form, emptyList(), probe = { "DNS 解析失败" })
        ok("创建成功", r is SubmitResult.Created)
        val c = r as SubmitResult.Created
        ok("verified=false", !c.verified)
        ok("ID 基于主机名", c.entity.id == "unverified.example.com")
        ok("告警含 DNS", c.warning?.contains("DNS") == true)

        // 播放期应重试探活
        ok("shouldRetryProbe 为 true", DanmakuSourceValidator.shouldRetryProbe(c.entity))
    }

    report()
}
