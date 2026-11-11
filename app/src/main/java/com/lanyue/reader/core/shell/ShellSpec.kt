package com.lanyue.reader.core.shell

/**
 * 启动页（占位壳）的纯逻辑层。
 *
 * 为什么这些判断不写在 `MainActivity` 里：Activity 继承 Android 基类，
 * `tools/test_jvm.sh` 编不过它，一旦把「标题该显示什么」「恢复的状态是否可信」
 * 塞进 `setContent {}`，就只能等真机点一遍才知道对不对。抽成纯函数后，
 * 恢复逻辑可以在离线单测里逐条断言。
 */

/** 占位页要展示的内容。刻意只含文本，不含任何业务入口。 */
data class PlaceholderModel(
    /** 主标题：应用名（或可信的恢复值）。 */
    val title: String,
    /** 说明行：本应用不含内置源与内容入口。 */
    val notice: String,
    /** 版本标签，形如 `v0.1.0`；版本缺失时为 `v?`。 */
    val versionLabel: String
)

object ShellSpec {

    /** 标题上限。超过视为脏数据：极长的旧值会把标题挤出屏幕。 */
    const val MAX_TITLE_LENGTH = 32

    /** 版本号缺失时的兜底标签。用 `?` 而不是编一个假版本号。 */
    const val UNKNOWN_VERSION = "v?"

    /**
     * 校验「上次保存的标题」是否还能用；不可用返回 null，由调用方回落到资源值。
     *
     * 为什么要校验：`rememberSaveable` 的值来自上一个进程/上一个版本，
     * 可能为空串、被裁剪成空白、含换行与制表符，或来自更早的版本而超长。
     * 直接展示会出现空标题或标题撑破布局——这正是「切后台回来界面错乱」的成因。
     * 恢复路径必须容忍脏数据，而不是假设它一定合法。
     */
    fun sanitizeRestoredTitle(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_TITLE_LENGTH) return null
        // 控制字符（换行/制表/不可见）会破坏单行标题排版，整条拒绝而非静默清洗：
        // 静默清洗会让「为什么标题少了几个字」无从追溯。
        if (trimmed.any { it.isISOControl() }) return null
        return trimmed
    }

    /** 版本标签：空值回落到 [UNKNOWN_VERSION]，避免出现 `v` 或 `vnull`。 */
    fun versionLabel(versionName: String?): String {
        val v = versionName?.trim().orEmpty()
        if (v.isEmpty() || v.equals("null", ignoreCase = true)) return UNKNOWN_VERSION
        return if (v.startsWith("v") || v.startsWith("V")) v else "v$v"
    }

    /**
     * 组装占位页模型。
     *
     * [restoredTitle] 来自 `rememberSaveable`：合法则沿用（保证旋转后标题不回退），
     * 非法则回落到 [appName]。
     */
    fun buildPlaceholder(
        appName: String?,
        notice: String?,
        versionName: String?,
        restoredTitle: String? = null
    ): PlaceholderModel {
        val fallback = appName?.trim()?.takeIf { it.isNotEmpty() } ?: "览阅"
        val title = sanitizeRestoredTitle(restoredTitle) ?: fallback
        return PlaceholderModel(
            title = title,
            notice = notice?.trim().orEmpty(),
            versionLabel = versionLabel(versionName)
        )
    }

    /**
     * 占位页是否「只剩壳」——即没有任何真实内容入口。
     *
     * 这条断言的意义：T03 明确要求此阶段不得引入业务 UI。把它写成可测的判据，
     * 后续轮次误把书架/发现页塞进启动壳时，测试会先失败，而不是等到发版前才发现。
     */
    fun isPlaceholderOnly(model: PlaceholderModel): Boolean =
        model.title.isNotEmpty() && model.versionLabel.isNotEmpty()
}
