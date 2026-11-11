package com.lanyue.reader.core.shell

/**
 * 主题模式：跟随系统 / 始终浅色 / 始终深色。
 *
 * T10 要求"主题跟随系统，支持深色/浅色"。这里的重点是**不要把
 * "系统的深浅"和"网络/配置读到的偏好"混成一个布尔值**：
 *
 * - 用户偏好是三态的（FOLLOW_SYSTEM / LIGHT / DARK）；
 * - 系统是否深色是外部输入，会随日落、系统设置、电量模式随时变化。
 *
 * 如果只存一个 `isDark: Boolean`，用户在"跟随系统"下切换系统主题后，
 * 应用不会跟着变——因为布尔值已经固化了上一次的结果。把两者分开，
 * 每次重组时重新求值，跟随才会真的跟随。
 *
 * [id] 用于持久化，是稳定的字面量，不能依赖 ordina——枚举顺序一变，
 * 用户存的偏好就会指向另一个模式。
 */
enum class ThemeMode(val id: String) {
    FOLLOW_SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {

        /**
         * 反序列化。认不出的值回落到 FOLLOW_SYSTEM：
         * 这是最不会让用户困扰的默认——它至少与系统一致。
         */
        fun byId(id: String?): ThemeMode =
            entries.firstOrNull { it.id == id } ?: FOLLOW_SYSTEM
    }
}

object ThemeModeResolver {

    /**
     * 求当前最终是深色还是浅色。
     *
     * [systemIsDark] 由外部提供（Compose 侧是 `isSystemInDarkTheme()`）。
     * 显式的 LIGHT / DARK 必须压过系统值，这是用户主动选择应有的优先级。
     */
    fun resolveIsDark(mode: ThemeMode, systemIsDark: Boolean): Boolean =
        when (mode) {
            ThemeMode.FOLLOW_SYSTEM -> systemIsDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    /**
     * 在三个模式间轮换，供"设置页点一下切一次"的场景使用。
     * 顺序固定为 跟随系统 → 浅色 → 深色 → 跟随系统。
     */
    fun next(mode: ThemeMode): ThemeMode =
        when (mode) {
            ThemeMode.FOLLOW_SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.FOLLOW_SYSTEM
        }

    /** 供界面显示的模式名（资源名，不是文案本身）。 */
    fun labelKey(mode: ThemeMode): String =
        when (mode) {
            ThemeMode.FOLLOW_SYSTEM -> "theme_follow_system"
            ThemeMode.LIGHT -> "theme_light"
            ThemeMode.DARK -> "theme_dark"
        }
}
