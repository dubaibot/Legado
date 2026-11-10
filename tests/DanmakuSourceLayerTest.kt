import com.lanyue.reader.danmaku.DanmakuSourceRegistry
import com.lanyue.reader.danmaku.DanmakuSourceValidator
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import kotlin.system.exitProcess

private var pass = 0
private val failures = ArrayList<String>()
private val outFile = java.io.File("/mnt/work/lanyue23/out_danmaku_source.txt")

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
        outFile.writeText(failures.joinToString("\n\n"))
        exitProcess(1)
    }
}

private fun src(
    id: String,
    name: String = id,
    apiBase: String = "https://$id.example.com/api/v2",
    enabled: Boolean = true,
    sortOrder: Int = 0,
    verified: Boolean = false
) = DanmakuSourceEntity(
    id = id,
    name = name,
    apiBase = apiBase,
    enabled = enabled,
    sortOrder = sortOrder,
    verified = verified
)

fun main() {
    // ==================== 一、apiBase 规范化 ====================
    run {
        eq("已带 https 原样保留", DanmakuSourceValidator.normalizeBase("https://api.dandanplay.net/api/v2"),
            "https://api.dandanplay.net/api/v2")
        eq("漏协议头补 https", DanmakuSourceValidator.normalizeBase("danmaku.example.com/api"),
            "https://danmaku.example.com/api")
        eq("http 保留为 http", DanmakuSourceValidator.normalizeBase("http://in.example.com/api"),
            "http://in.example.com/api")
        eq("大写协议归一为小写", DanmakuSourceValidator.normalizeBase("HTTPS://A.example.com/x"),
            "https://A.example.com/x")
        eq("去掉尾部斜杠", DanmakuSourceValidator.normalizeBase("https://a.example.com/api/"),
            "https://a.example.com/api")
        eq("根地址去斜杠", DanmakuSourceValidator.normalizeBase("https://a.example.com/"),
            "https://a.example.com")
        eq("保留端口", DanmakuSourceValidator.normalizeBase("https://a.example.com:8443/api"),
            "https://a.example.com:8443/api")
        eq("localhost 允许", DanmakuSourceValidator.normalizeBase("http://localhost:8080/api"),
            "http://localhost:8080/api")
        eq("IP 地址允许", DanmakuSourceValidator.normalizeBase("http://192.168.1.10:9000/api"),
            "http://192.168.1.10:9000/api")

        eq("空串拒绝", DanmakuSourceValidator.normalizeBase(""), null)
        eq("单段主机名拒绝", DanmakuSourceValidator.normalizeBase("api"), null)
        eq("ftp 协议拒绝", DanmakuSourceValidator.normalizeBase("ftp://a.example.com/x"), null)
        eq("file 协议拒绝", DanmakuSourceValidator.normalizeBase("file:///etc/passwd"), null)
        eq("仅协议无主机拒绝", DanmakuSourceValidator.normalizeBase("https://"), null)
        eq("以点开头拒绝", DanmakuSourceValidator.normalizeBase("https://.example.com/x"), null)
        eq("以点结尾拒绝", DanmakuSourceValidator.normalizeBase("https://example.com./x"), null)
        eq("含空格拒绝", DanmakuSourceValidator.normalizeBase("https://a b.example.com/x"), null)
        eq("端口非数字拒绝", DanmakuSourceValidator.normalizeBase("https://a.example.com:abc/x"), null)
        eq("空端口拒绝", DanmakuSourceValidator.normalizeBase("https://a.example.com:/x"), null)
    }

    // ==================== 二、validate 正常路径 ====================
    run {
        val r = DanmakuSourceValidator.validate(
            src("d1", name = "弹弹play", apiBase = "https://api.dandanplay.net/api/v2"),
            probe = { null }
        )
        ok("探活通过返回 Ok", r is DanmakuSourceValidator.Verdict.Ok)
        val norm = (r as DanmakuSourceValidator.Verdict.Ok).normalized
        eq("名称保留", norm.name, "弹弹play")
        eq("地址保留", norm.apiBase, "https://api.dandanplay.net/api/v2")
    }

    run {
        val r = DanmakuSourceValidator.validate(
            src("d2", name = "  ", apiBase = "  danmaku.example.com/api  "),
            probe = { null }
        )
        ok("名称为空也 Ok", r is DanmakuSourceValidator.Verdict.Ok)
        val norm = (r as DanmakuSourceValidator.Verdict.Ok).normalized
        eq("空白名称回退为主机名", norm.name, "danmaku.example.com")
        eq("地址已裁剪并补协议", norm.apiBase, "https://danmaku.example.com/api")
    }

    // ==================== 三、探活失败可强制保存 ====================
    run {
        val r = DanmakuSourceValidator.validate(
            src("d3", apiBase = "https://down.example.com/api"),
            probe = { "连接超时" }
        )
        ok("探活失败返回 Unverified 而非 Rejected", r is DanmakuSourceValidator.Verdict.Unverified)
        val u = r as DanmakuSourceValidator.Verdict.Unverified
        eq("失败原因透传", u.reason, "连接超时")
        eq("仍给出可保存的实体", u.normalized.apiBase, "https://down.example.com/api")
    }

    run {
        var probed = false
        val r = DanmakuSourceValidator.validate(
            src("d4", enabled = false, apiBase = "https://off.example.com/api"),
            probe = { probed = true; "不该被调用" }
        )
        ok("未启用时仍可保存", r is DanmakuSourceValidator.Verdict.Ok)
        ok("未启用不触发探活", !probed)
    }

    // ==================== 四、拒绝路径 ====================
    run {
        ok("空 apiBase 拒绝",
            DanmakuSourceValidator.validate(src("d5", apiBase = "   ")) is DanmakuSourceValidator.Verdict.Rejected)
        ok("非法主机拒绝",
            DanmakuSourceValidator.validate(src("d6", apiBase = "api")) is DanmakuSourceValidator.Verdict.Rejected)
        val longBase = "https://a.example.com/" + "x".repeat(DanmakuSourceValidator.MAX_API_BASE_CHARS)
        ok("超长 apiBase 拒绝",
            DanmakuSourceValidator.validate(src("d7", apiBase = longBase)) is DanmakuSourceValidator.Verdict.Rejected)
        val longName = "名".repeat(DanmakuSourceValidator.MAX_NAME_CHARS + 1)
        ok("超长名称拒绝",
            DanmakuSourceValidator.validate(src("d8", name = longName)) is DanmakuSourceValidator.Verdict.Rejected)
        // 拒绝时不探活
        var probed = false
        DanmakuSourceValidator.validate(src("d9", apiBase = ""), probe = { probed = true; null })
        ok("拒绝路径不探活", !probed)
    }

    // ==================== 五、播放期重试标记 ====================
    run {
        ok("启用且未验证需重探", DanmakuSourceValidator.shouldRetryProbe(src("a", enabled = true, verified = false)))
        ok("已验证不重探", !DanmakuSourceValidator.shouldRetryProbe(src("b", enabled = true, verified = true)))
        ok("未启用不重探", !DanmakuSourceValidator.shouldRetryProbe(src("c", enabled = false, verified = false)))
    }

    // ==================== 六、顺序规范化 ====================
    run {
        val list = listOf(
            src("s3", sortOrder = 3),
            src("s1", sortOrder = 1),
            src("s2", sortOrder = 2)
        )
        val n = DanmakuSourceRegistry.normalize(list)
        eq("按 sortOrder 排序", n.map { it.id }, listOf("s1", "s2", "s3"))
        eq("重编号为 0..n-1", n.map { it.sortOrder }, listOf(0, 1, 2))
    }

    run {
        // 并列 sortOrder 用 id 兜底，保证稳定
        val list = listOf(src("b", sortOrder = 0), src("a", sortOrder = 0), src("c", sortOrder = 0))
        eq("并列时按 id 稳定", DanmakuSourceRegistry.normalize(list).map { it.id }, listOf("a", "b", "c"))
        eq("并列重编号", DanmakuSourceRegistry.normalize(list).map { it.sortOrder }, listOf(0, 1, 2))
    }

    run {
        eq("空列表规范化仍为空", DanmakuSourceRegistry.normalize(emptyList()).size, 0)
        val already = listOf(src("a", sortOrder = 0), src("b", sortOrder = 1))
        eq("已规范则内容不变", DanmakuSourceRegistry.normalize(already).map { it.sortOrder }, listOf(0, 1))
    }

    // ==================== 七、启用过滤 ====================
    run {
        val list = listOf(
            src("a", sortOrder = 0, enabled = true),
            src("b", sortOrder = 1, enabled = false),
            src("c", sortOrder = 2, enabled = true)
        )
        val en = DanmakuSourceRegistry.enabledOrdered(list)
        eq("只取启用源", en.map { it.id }, listOf("a", "c"))
        ok("全部为启用", en.all { it.enabled })
    }

    // ==================== 八、新增 ====================
    run {
        val base = listOf(src("a", sortOrder = 0), src("b", sortOrder = 1))
        val r = DanmakuSourceRegistry.add(base, src("c", sortOrder = 0))
        ok("新增成功", r is DanmakuSourceRegistry.ReorderResult.Ok)
        val ordered = (r as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        eq("新源排在末尾", ordered.map { it.id }, listOf("a", "b", "c"))
        eq("新源序号为 2", ordered.last().sortOrder, 2)
        eq("重编号连续", ordered.map { it.sortOrder }, listOf(0, 1, 2))
    }

    run {
        val base = listOf(src("a"))
        val r = DanmakuSourceRegistry.add(base, src("a", name = "重复"))
        ok("重复 id 拒绝", r is DanmakuSourceRegistry.ReorderResult.Rejected)
    }

    run {
        val base = (0 until DanmakuSourceRegistry.MAX_SOURCES).map { src("s$it", sortOrder = it) }
        val r = DanmakuSourceRegistry.add(base, src("overflow"))
        ok("超出上限拒绝", r is DanmakuSourceRegistry.ReorderResult.Rejected)
        // 上限内可加
        val under = base.dropLast(1)
        ok("上限内可新增",
            DanmakuSourceRegistry.add(under, src("fit")) is DanmakuSourceRegistry.ReorderResult.Ok)
    }

    // ==================== 九、删除 ====================
    run {
        val base = listOf(src("a", sortOrder = 0), src("b", sortOrder = 1), src("c", sortOrder = 2))
        val r = DanmakuSourceRegistry.remove(base, "b")
        ok("删除成功", r is DanmakuSourceRegistry.ReorderResult.Ok)
        val ordered = (r as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        eq("移除目标", ordered.map { it.id }, listOf("a", "c"))
        eq("删除后重编号消除空洞", ordered.map { it.sortOrder }, listOf(0, 1))
        ok("空洞已修复", ordered.none { it.sortOrder > ordered.size - 1 })
    }

    run {
        val r = DanmakuSourceRegistry.remove(listOf(src("a")), "zzz")
        ok("删除不存在 id 拒绝", r is DanmakuSourceRegistry.ReorderResult.Rejected)
        val empty = DanmakuSourceRegistry.remove(listOf(src("only")), "only")
        ok("删到空允许", empty is DanmakuSourceRegistry.ReorderResult.Ok)
        eq("删空后为空", (empty as DanmakuSourceRegistry.ReorderResult.Ok).ordered.size, 0)
    }

    // ==================== 十、启停 ====================
    run {
        val base = listOf(src("a", sortOrder = 0, enabled = true), src("b", sortOrder = 1, enabled = true))
        val r = DanmakuSourceRegistry.setEnabled(base, "b", false)
        ok("关闭其一成功", r is DanmakuSourceRegistry.ReorderResult.Ok)
        val ordered = (r as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        eq("目标已关闭", ordered.first { it.id == "b" }.enabled, false)
    }

    run {
        // 只剩一个启用源时不允许关闭
        val base = listOf(src("a", sortOrder = 0, enabled = true), src("b", sortOrder = 1, enabled = false))
        val r = DanmakuSourceRegistry.setEnabled(base, "a", false)
        ok("禁止关闭最后一个启用源", r is DanmakuSourceRegistry.ReorderResult.Rejected)
    }

    run {
        // 已关闭的源可以重新打开
        val base = listOf(src("a", sortOrder = 0, enabled = true), src("b", sortOrder = 1, enabled = false))
        val r = DanmakuSourceRegistry.setEnabled(base, "b", true)
        ok("可重新启用", r is DanmakuSourceRegistry.ReorderResult.Ok)
        val ordered = (r as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        eq("已重新启用", ordered.first { it.id == "b" }.enabled, true)
        eq("两个都启用", ordered.count { it.enabled }, 2)
    }

    run {
        val r = DanmakuSourceRegistry.setEnabled(listOf(src("a")), "zzz", false)
        ok("启停不存在 id 拒绝", r is DanmakuSourceRegistry.ReorderResult.Rejected)
        // 目标状态与现状相同：幂等返回
        val same = DanmakuSourceRegistry.setEnabled(listOf(src("a", enabled = true)), "a", true)
        ok("同状态幂等", same is DanmakuSourceRegistry.ReorderResult.Ok)
    }

    // ==================== 十一、移动 ====================
    run {
        val base = listOf(src("a", sortOrder = 0), src("b", sortOrder = 1), src("c", sortOrder = 2))
        val down = DanmakuSourceRegistry.move(base, "a", 1)
        ok("下移成功", down is DanmakuSourceRegistry.ReorderResult.Ok)
        eq("a 下移一位", (down as DanmakuSourceRegistry.ReorderResult.Ok).ordered.map { it.id },
            listOf("b", "a", "c"))
        eq("下移后序号连续", down.ordered.map { it.sortOrder }, listOf(0, 1, 2))
    }

    run {
        val base = listOf(src("a", sortOrder = 0), src("b", sortOrder = 1), src("c", sortOrder = 2))
        val up = DanmakuSourceRegistry.move(base, "c", -1)
        eq("c 上移一位", (up as DanmakuSourceRegistry.ReorderResult.Ok).ordered.map { it.id },
            listOf("a", "c", "b"))
    }

    run {
        // 越界不环绕
        val base = listOf(src("a", sortOrder = 0), src("b", sortOrder = 1))
        val first = DanmakuSourceRegistry.move(base, "a", -1)
        eq("首位上移保持原位", (first as DanmakuSourceRegistry.ReorderResult.Ok).ordered.map { it.id },
            listOf("a", "b"))
        val last = DanmakuSourceRegistry.move(base, "b", 5)
        eq("末位下移不环绕", (last as DanmakuSourceRegistry.ReorderResult.Ok).ordered.map { it.id },
            listOf("a", "b"))
        val missing = DanmakuSourceRegistry.move(base, "zzz", 1)
        ok("移动不存在 id 拒绝", missing is DanmakuSourceRegistry.ReorderResult.Rejected)
    }

    run {
        // 连按两次上移不会跑到末尾（非环绕）
        var cur = listOf(src("a", sortOrder = 0), src("b", sortOrder = 1), src("c", sortOrder = 2))
        repeat(2) {
            val r = DanmakuSourceRegistry.move(cur, "c", -1)
            cur = (r as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        }
        eq("连按上移停在首位", cur.map { it.id }, listOf("c", "a", "b"))
        eq("序号仍连续", cur.map { it.sortOrder }, listOf(0, 1, 2))
    }

    // ==================== 十二、端到端：校验 → 注册 → 启用顺序 ====================
    run {
        var list = emptyList<DanmakuSourceEntity>()
        val inputs = listOf(
            "danmaku-a.example.com/api" to "A源",
            "https://b.example.com/api/" to "B源",
            "http://c.example.com:8080/api" to "C源"
        )
        for ((base, name) in inputs) {
            val v = DanmakuSourceValidator.validate(src("id-$name", name = name, apiBase = base), probe = { null })
            ok("$name 校验通过", v is DanmakuSourceValidator.Verdict.Ok)
            val norm = (v as DanmakuSourceValidator.Verdict.Ok).normalized
            val add = DanmakuSourceRegistry.add(list, norm)
            list = (add as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        }
        eq("三个源都入库", list.size, 3)
        eq("顺序为追加顺序", list.map { it.name }, listOf("A源", "B源", "C源"))
        eq("地址已规范化", list.map { it.apiBase },
            listOf("https://danmaku-a.example.com/api", "https://b.example.com/api", "http://c.example.com:8080/api"))

        // 调整顺序后，引擎取到的启用列表随之变化
        val moved = DanmakuSourceRegistry.move(list, "id-C源", -2)
        val orderedList = (moved as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        eq("C源提到第一位", DanmakuSourceRegistry.enabledOrdered(orderedList).first().name, "C源")

        // 关闭 B源 后不再出现在启用列表
        val off = DanmakuSourceRegistry.setEnabled(orderedList, "id-B源", false)
        val afterOff = (off as DanmakuSourceRegistry.ReorderResult.Ok).ordered
        eq("关闭后启用列表少一个", DanmakuSourceRegistry.enabledOrdered(afterOff).size, 2)
        ok("B源不在启用列表", DanmakuSourceRegistry.enabledOrdered(afterOff).none { it.name == "B源" })
    }

    report()
}
