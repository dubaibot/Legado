package io.legado.app.ui.theme

import io.legado.app.lib.theme.ThemeStateStore

/**
 * 主题全局同步信号（主题架构 v2，借鉴 MoRealm/Archive 的全局主题状态思路）。
 *
 * 问题背景：Compose 迁移后 LegadoTheme 只在组合时一次性读 ThemeStore，
 * 主题设置变更（EventBus.RECREATE）仅 MainActivity/ConfigActivity 订阅重建，
 * 其余已组合的 Compose 页面永不刷新 —— 表现为「设置后不起作用」。
 *
 * 机制：[io.legado.app.help.config.ThemeConfig.applyTheme] 更新色板后调用
 * [ThemeStateStore.notifyThemeChanged]，所有在组合中读取了 [version] 的
 * Composable（LegadoTheme、直接读 ThemeStore 的 GlassTopAppBar 等）立即失效重组，
 * 重新读取 ThemeStore 最新值 —— 无需依赖 Activity 重建，栈内后台页面同样生效。
 *
 * 历史（勿改回独立计数）：ThemeSync 曾持有自己的 version 计数，但 ThemeConfig
 * 实际发的是 ThemeStateStore 信号，ThemeSync.version 恒为 0 成为死信号，
 * 主题变更后已组合的 Compose 页面不再重组。现统一委托给 [ThemeStateStore]，
 * 色板版本保持单一来源。
 */
object ThemeSync {

    /** 当前色板版本；读取它会建立 Compose 重组依赖。 */
    val version: Int
        get() = ThemeStateStore.version
}
