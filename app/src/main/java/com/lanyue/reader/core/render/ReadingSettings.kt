package com.lanyue.reader.core.render

/**
 * 翻页方式。
 *
 * 只有 [SLIDE]、[COVER]、[NONE] 是"分页阅读"下的动画差异，
 * [SCROLL] 是连续滚动阅读，语义上不产生页的概念，
 * 上层据 [isPaged] 决定走分页器还是滚动容器。
 */
enum class PageTurnMode(val id: String, val label: String) {
    SLIDE("slide", "平移"),
    COVER("cover", "覆盖"),
    NONE("none", "无动画"),
    SCROLL("scroll", "滚动");

    /** 是否为分页阅读（需要分页器）。 */
    val isPaged: Boolean get() = this != SCROLL

    companion object {
        val Default = SLIDE

        /** 未知 id 回落到默认，避免坏配置导致白屏。 */
        fun fromId(id: String?): PageTurnMode =
            entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * 用户可调的阅读设置。
 *
 * 全部字段都带上下界，构造即校验：设置页拖动滑杆不会把界面拖进非法状态。
 * 与 [TypographySpec] 的分工是：本类负责"用户意图 + 持久化"，
 * [TypographySpec] 负责"排版度量计算"，两者通过 [toTypography] 单向转换。
 */
data class ReadingSettings(
    val fontSizeSp: Float = TypographySpec.DEFAULT_FONT_SP,
    val lineSpacingAdd: Float = 0.35f,
    val letterSpacingEm: Float = 0f,
    val themeId: String = ThemePalette.DayId,
    val pageTurnMode: PageTurnMode = PageTurnMode.Default,
    /** 阅读时保持屏幕常亮。 */
    val keepScreenOn: Boolean = true,
    /** 全屏（隐藏状态栏）。 */
    val fullScreen: Boolean = true,
    /** 跟随系统时间自动切换日夜主题。 */
    val autoNightByClock: Boolean = false
) {
    init {
        require(fontSizeSp in TypographySpec.MIN_FONT_SP..TypographySpec.MAX_FONT_SP) {
            "fontSizeSp out of range: $fontSizeSp"
        }
        require(lineSpacingAdd in 0f..TypographySpec.MAX_LINE_SPACING_ADD) {
            "lineSpacingAdd out of range: $lineSpacingAdd"
        }
        require(
            letterSpacingEm in TypographySpec.MIN_LETTER_SPACING_EM..
                TypographySpec.MAX_LETTER_SPACING_EM
        ) { "letterSpacingEm out of range: $letterSpacingEm" }
        require(themeId.isNotBlank()) { "themeId must not be blank" }
    }

    /** 转成排版度量。缩进/段距沿用 [TypographySpec] 默认值。 */
    fun toTypography(): TypographySpec = TypographySpec(
        fontSizeSp = fontSizeSp,
        lineSpacingAdd = lineSpacingAdd,
        letterSpacingEm = letterSpacingEm
    )

    fun withFontSize(sp: Float): ReadingSettings = copy(
        fontSizeSp = sp.coerceIn(TypographySpec.MIN_FONT_SP, TypographySpec.MAX_FONT_SP)
    )

    /** 字号步进，供 +/- 按钮使用，结果仍被夹在合法区间。 */
    fun stepFontSize(delta: Float): ReadingSettings = withFontSize(fontSizeSp + delta)

    fun withLineSpacing(add: Float): ReadingSettings = copy(
        lineSpacingAdd = add.coerceIn(0f, TypographySpec.MAX_LINE_SPACING_ADD)
    )

    fun withLetterSpacing(em: Float): ReadingSettings = copy(
        letterSpacingEm = em.coerceIn(
            TypographySpec.MIN_LETTER_SPACING_EM,
            TypographySpec.MAX_LETTER_SPACING_EM
        )
    )

    fun withTheme(id: String): ReadingSettings = copy(themeId = id)

    /** 当前主题；未知 id 由 [ThemeRegistry] 回落，不会抛异常。 */
    fun theme(): ThemePalette = ThemeRegistry.byIdOrDefault(themeId)

    /**
     * 按时间解析实际生效主题。
     * [hourOfDay] 取 0..23；开启自动夜间时 19 点后到早上 6 点前用夜间主题。
     */
    fun resolveTheme(hourOfDay: Int): ThemePalette {
        if (!autoNightByClock) return theme()
        val h = hourOfDay.coerceIn(0, 23)
        val night = h >= 19 || h < 6
        return if (night) {
            ThemeRegistry.byId(ThemePalette.NightId) ?: theme()
        } else {
            val day = ThemeRegistry.byId(ThemePalette.DayId) ?: theme()
            // 白天向夜间切换时保留用户原本的浅色主题
            if (theme().isDark) day else theme()
        }
    }

    /**
     * 序列化为稳定的 key=value 串，便于存 SharedPreferences / DataStore。
     * 顺序固定，方便测试与 diff。
     */
    fun serialize(): String = listOf(
        "fontSizeSp=${trimFloat(fontSizeSp)}",
        "lineSpacingAdd=${trimFloat(lineSpacingAdd)}",
        "letterSpacingEm=${trimFloat(letterSpacingEm)}",
        "themeId=$themeId",
        "pageTurnMode=${pageTurnMode.id}",
        "keepScreenOn=$keepScreenOn",
        "fullScreen=$fullScreen",
        "autoNightByClock=$autoNightByClock"
    ).joinToString(";")

    companion object {
        val Default = ReadingSettings()

        /**
         * 从序列化串恢复设置。
         * 任何字段缺失或非法都单独回落到默认值，不整体丢弃——
         * 升级后新增字段时老配置仍然可用。
         */
        fun deserialize(raw: String?): ReadingSettings {
            if (raw.isNullOrBlank()) return Default
            val map = HashMap<String, String>()
            raw.split(';').forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) map[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
            }
            val base = Default
            return ReadingSettings(
                fontSizeSp = map["fontSizeSp"]?.toFloatOrNull()
                    ?.coerceIn(TypographySpec.MIN_FONT_SP, TypographySpec.MAX_FONT_SP)
                    ?: base.fontSizeSp,
                lineSpacingAdd = map["lineSpacingAdd"]?.toFloatOrNull()
                    ?.coerceIn(0f, TypographySpec.MAX_LINE_SPACING_ADD)
                    ?: base.lineSpacingAdd,
                letterSpacingEm = map["letterSpacingEm"]?.toFloatOrNull()
                    ?.coerceIn(
                        TypographySpec.MIN_LETTER_SPACING_EM,
                        TypographySpec.MAX_LETTER_SPACING_EM
                    )
                    ?: base.letterSpacingEm,
                themeId = map["themeId"]?.takeIf { it.isNotBlank() } ?: base.themeId,
                pageTurnMode = PageTurnMode.fromId(map["pageTurnMode"]),
                keepScreenOn = map["keepScreenOn"]?.toBooleanStrictOrNull() ?: base.keepScreenOn,
                fullScreen = map["fullScreen"]?.toBooleanStrictOrNull() ?: base.fullScreen,
                autoNightByClock = map["autoNightByClock"]?.toBooleanStrictOrNull()
                    ?: base.autoNightByClock
            )
        }

        /** 去掉浮点尾巴（18.0 -> 18，18.35 -> 18.35），让配置串可读。 */
        private fun trimFloat(v: Float): String {
            val asInt = v.toInt()
            return if (v == asInt.toFloat()) asInt.toString() else v.toString()
        }
    }
}
