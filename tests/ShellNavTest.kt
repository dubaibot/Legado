import com.lanyue.reader.core.shell.AppDestination
import com.lanyue.reader.core.shell.BackResult
import com.lanyue.reader.core.shell.BottomNavPolicy
import com.lanyue.reader.core.shell.ThemeMode
import com.lanyue.reader.core.shell.ThemeModeResolver

private var pass = 0
private val failures = ArrayList<String>()

private fun eq(label: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++
    else failures += "$label\n  期望: $expected\n  实际: $actual"
}

private fun ok(label: String, cond: Boolean) {
    if (cond) pass++ else failures += label
}

private fun report() {
    println("PASS = $pass")
    println("FAIL = ${failures.size}")
    if (failures.isNotEmpty()) {
        for ((i, f) in failures.withIndex()) println("${i + 1}. $f")
        kotlin.system.exitProcess(1)
    }
}

fun main() {
    // ==================== 目的地定义 ====================
    run {
        // 三个一级页，顺序即底栏顺序
        eq("一级页数量应为 3", AppDestination.ordered.size, 3)
        eq("第一个是书架", AppDestination.ordered[0], AppDestination.SHELF)
        eq("最后一个是我的", AppDestination.ordered.last(), AppDestination.PROFILE)
        eq("冷启动落点是书架", AppDestination.default, AppDestination.SHELF)
    }

    run {
        // 路由名必须互不相同，重复会让 byRoute 只命中第一个
        val routes = AppDestination.ordered.map { it.route }
        eq("路由名应无重复", routes.toSet().size, routes.size)
        // 路由名不能为空，空串会让"未知路由"与某个真实页混淆
        ok("路由名均非空", routes.all { it.isNotBlank() })
        // 标题是资源名而非文案：必须是 tab_ 前缀的 key 形式
        val keys = AppDestination.ordered.map { it.titleKey }
        ok("标题键应以 tab_ 开头", keys.all { it.startsWith("tab_") })
        eq("标题键应无重复", keys.toSet().size, keys.size)
    }

    run {
        eq("byRoute 能认出 store", AppDestination.byRoute("store"), AppDestination.STORE)
        // 未知路由返回 null，由调用方决定回落，而不是在这里抛异常
        eq("未知路由返回 null", AppDestination.byRoute("nope"), null)
        eq("null 路由返回 null", AppDestination.byRoute(null), null)
        // 大小写敏感：路由是标识符，不该宽容匹配
        eq("路由大小写敏感", AppDestination.byRoute("STORE"), null)
    }

    run {
        eq("索引 0 是书架", AppDestination.fromIndexOrNull(0), AppDestination.SHELF)
        eq("索引越界返回 null", AppDestination.fromIndexOrNull(9), null)
        eq("负索引返回 null", AppDestination.fromIndexOrNull(-1), null)
    }

    // ==================== 平级切换 ====================
    run {
        // 核心规则：选中谁就是谁，不叠加历史
        eq("选中书城", BottomNavPolicy.select(AppDestination.STORE), AppDestination.STORE)
        eq("选中我的", BottomNavPolicy.select(AppDestination.PROFILE), AppDestination.PROFILE)
    }

    run {
        ok(
            "重复点击当前页应识别为重选",
            BottomNavPolicy.isReselect(AppDestination.SHELF, AppDestination.SHELF)
        )
        ok(
            "点击其它页不是重选",
            !BottomNavPolicy.isReselect(AppDestination.SHELF, AppDestination.STORE)
        )
    }

    // ==================== 返回键 ====================
    run {
        // 非默认页按返回：回默认页，不退出
        val r = BottomNavPolicy.onBack(AppDestination.STORE)
        eq("书城按返回应回书架", r, BackResult.NavigateTo(AppDestination.SHELF))
        val r2 = BottomNavPolicy.onBack(AppDestination.PROFILE)
        eq("我的按返回应回书架", r2, BackResult.NavigateTo(AppDestination.SHELF))
    }

    run {
        // 已在默认页：交还系统，否则应用退不出去
        eq(
            "书架按返回应交还系统",
            BottomNavPolicy.onBack(AppDestination.SHELF),
            BackResult.ExitApp
        )
    }

    run {
        // 页面内部还有层级时，导航层不该抢返回事件
        eq(
            "内部有层级时先消耗",
            BottomNavPolicy.onBack(AppDestination.SHELF, innerDepth = 1),
            BackResult.Consumed
        )
        eq(
            "内部有层级时即便非默认页也不跳转",
            BottomNavPolicy.onBack(AppDestination.STORE, innerDepth = 2),
            BackResult.Consumed
        )
        // 层级为 0 时才轮到导航层处理
        eq(
            "层级为 0 时正常判断",
            BottomNavPolicy.onBack(AppDestination.STORE, innerDepth = 0),
            BackResult.NavigateTo(AppDestination.SHELF)
        )
    }

    // ==================== 恢复选中页 ====================
    run {
        eq("能恢复已存在的路由", BottomNavPolicy.restore("profile"), AppDestination.PROFILE)
        // 旧版本的持久化数据可能含已删除的路由，必须回落而不是崩
        eq("未知路由回落到默认页", BottomNavPolicy.restore("removed_tab"), AppDestination.SHELF)
        eq("null 回落到默认页", BottomNavPolicy.restore(null), AppDestination.SHELF)
    }

    // ==================== 主题模式 ====================
    run {
        eq("模式数量应为 3", ThemeMode.entries.size, 3)
        // id 是持久化字面量，必须稳定且唯一
        val ids = ThemeMode.entries.map { it.id }
        eq("id 应无重复", ids.toSet().size, ids.size)
        eq("byId 能认出 dark", ThemeMode.byId("dark"), ThemeMode.DARK)
        eq("未知 id 回落到跟随系统", ThemeMode.byId("purple"), ThemeMode.FOLLOW_SYSTEM)
        eq("null 回落到跟随系统", ThemeMode.byId(null), ThemeMode.FOLLOW_SYSTEM)
    }

    run {
        // 跟随系统：结果完全由系统决定
        ok(
            "跟随系统+系统深色 → 深色",
            ThemeModeResolver.resolveIsDark(ThemeMode.FOLLOW_SYSTEM, systemIsDark = true)
        )
        ok(
            "跟随系统+系统浅色 → 浅色",
            !ThemeModeResolver.resolveIsDark(ThemeMode.FOLLOW_SYSTEM, systemIsDark = false)
        )
        // 显式选择必须压过系统值
        ok(
            "显式浅色压过系统深色",
            !ThemeModeResolver.resolveIsDark(ThemeMode.LIGHT, systemIsDark = true)
        )
        ok(
            "显式深色压过系统浅色",
            ThemeModeResolver.resolveIsDark(ThemeMode.DARK, systemIsDark = false)
        )
    }

    run {
        // 轮换一圈回到原点，不能出现死循环或跳档
        val a = ThemeModeResolver.next(ThemeMode.FOLLOW_SYSTEM)
        eq("跟随系统 → 浅色", a, ThemeMode.LIGHT)
        val b = ThemeModeResolver.next(a)
        eq("浅色 → 深色", b, ThemeMode.DARK)
        val c = ThemeModeResolver.next(b)
        eq("深色 → 跟随系统", c, ThemeMode.FOLLOW_SYSTEM)
    }

    run {
        val keys = ThemeMode.entries.map { ThemeModeResolver.labelKey(it) }
        ok("模式文案键应以 theme_ 开头", keys.all { it.startsWith("theme_") })
        eq("文案键应无重复", keys.toSet().size, keys.size)
    }

    report()
}
