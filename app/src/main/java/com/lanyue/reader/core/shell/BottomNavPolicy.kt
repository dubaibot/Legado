package com.lanyue.reader.core.shell

/**
 * 返回键的处置结果。
 *
 * 用密封类型而不是返回 Boolean：调用方（Compose 的 BackHandler 或 Activity）
 * 必须显式处理"回默认页"和"交还系统"两种情况，漏掉一种编译就过不去。
 * 如果只返回 true/false，很容易写成"永远吞掉返回键"——用户会发现
 * 在书架页按返回退不出应用，这是最常见的 Android 体验问题之一。
 */
sealed interface BackResult {

    /** 不退出，切到某个一级页。 */
    data class NavigateTo(val destination: AppDestination) : BackResult

    /** 一级页上按返回：交还给系统（结束 Activity）。 */
    data object ExitApp : BackResult

    /** 消费但不出栈（例如关掉一个浮层），由上层决定。 */
    data object Consumed : BackResult
}

/**
 * 底部导航的返回栈规则。
 *
 * 这里刻意**不实现返回栈容器**。底部 tab 的正确行为是"平级切换"：
 * 从书架点书城再点我的，返回栈深度始终是 1，按返回应直接回书架，
 * 而不是沿"我的 → 书城 → 书架"逐个回退。很多应用在这里写成
 * navController.navigate(route) 而不带 popUpTo，于是返回键要按 N 次
 * 才退得出去。把规则抽成纯函数后，这类错误可以由单测拦住，
 * 不必等到真机上按返回键才发现。
 */
object BottomNavPolicy {

    /**
     * 选中某个 tab 之后应当展示的目的地。
     *
     * 永远是目标任务本身，不叠加历史——这就是"平级切换"的定义。
     */
    fun select(target: AppDestination): AppDestination = target

    /**
     * 重复点击当前已选中的 tab。
     *
     * 返回 true 表示不该产生任何状态变化。真实场景里这里通常还会
     * "滚回顶部"或"二次点击刷新列表"，但那些属于页面内部行为，
     * 导航层只需要保证：不重建页面、不叠加栈。
     */
    fun isReselect(current: AppDestination, target: AppDestination): Boolean =
        current == target

    /**
     * 在一级页上按返回键。
     *
     * - 当前不是默认页 → 回默认页（用户预期"退一层"到主页面）
     * - 当前就是默认页 → 交还系统（否则应用退不出去）
     *
     * [innerDepth] 是页面内部还有多少层可退（例如书城进了分类页）。
     * 大于 0 时先消耗内部层级，导航层不该抢这个返回事件。
     */
    fun onBack(current: AppDestination, innerDepth: Int = 0): BackResult =
        when {
            innerDepth > 0 -> BackResult.Consumed
            current == AppDestination.default -> BackResult.ExitApp
            else -> BackResult.NavigateTo(AppDestination.default)
        }

    /**
     * 恢复上次选中的页面。
     *
     * 路由可能来自旧版本的持久化数据，认不出来就落回默认页，
     * 而不是把一个不存在的路由塞进界面。
     */
    fun restore(savedRoute: String?): AppDestination =
        AppDestination.byRoute(savedRoute) ?: AppDestination.default
}
