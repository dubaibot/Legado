package com.lanyue.reader.core.shell

/**
 * 底部导航的三个一级目的地。
 *
 * 为什么用 enum 而不是散落的字符串常量：路由名一旦拼错，Compose 侧的
 * `when` 分支不会报错，只会渲染成空白页——用户在真机上看到的是"点了没反应"。
 * 收成一个枚举后，新增/改名必须过编译器，`ordered` 也保证底栏顺序与
 * 声明顺序一致，不会出现「代码里换了顺序、界面没换」。
 *
 * [titleKey] 是**字符串资源名**而不是中文文案：文案属于资源层，
 * 换语言时只改 values-xx/strings.xml 即可，逻辑层不必跟着改。
 */
enum class AppDestination(val route: String, val titleKey: String) {
    SHELF("shelf", "tab_shelf"),
    STORE("store", "tab_store"),
    PROFILE("profile", "tab_profile");

    companion object {

        /** 底栏从左到右的顺序。改这里就等于改界面顺序，唯一来源。 */
        val ordered: List<AppDestination> = listOf(SHELF, STORE, PROFILE)

        /** 冷启动落点。 */
        val default: AppDestination = SHELF

        /**
         * 路由名 → 目的地。未知路由返回 null 而不是抛异常：
         * 路由可能来自旧版本的持久化状态，崩溃不如回落到默认页。
         */
        fun byRoute(route: String?): AppDestination? =
            ordered.firstOrNull { it.route == route }

        /** 未知/越界索引回落到默认目的地，用于恢复持久化的选中位置。 */
        fun fromIndexOrNull(index: Int): AppDestination? = ordered.getOrNull(index)
    }
}
