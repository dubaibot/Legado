package com.lanyue.reader.core.render

/**
 * 排版度量。
 *
 * 这里只保存"无单位"的排版意图（Sp / Em / Dp 数值），
 * 不引用 android.* 任何类型，便于在 JVM 单测里直接验证边界。
 * 真正落到画布时由房型层乘 density / scaledDensity。
 */
data class TypographySpec(
    /** 正文字号，单位 sp。 */
    val fontSizeSp: Float = DEFAULT_FONT_SP,
    /** 行距附加倍数：0 表示单倍行距。 */
    val lineSpacingAdd: Float = 0.35f,
    /** 字距，单位 em。 */
    val letterSpacingEm: Float = 0f,
    /** 首行缩进，单位 em。 */
    val indentEm: Float = 2f,
    /** 段间距，单位 em。 */
    val paragraphSpacingEm: Float = 0.8f,
    /** 左右留白，单位 dp。 */
    val marginSideDp: Float = 20f,
    /** 上下留白，单位 dp。 */
    val marginTopDp: Float = 16f,
    val marginBottomDp: Float = 16f
) {
    init {
        require(fontSizeSp in MIN_FONT_SP..MAX_FONT_SP) {
            "fontSizeSp out of range: $fontSizeSp"
        }
        require(lineSpacingAdd in 0f..MAX_LINE_SPACING_ADD) {
            "lineSpacingAdd out of range: $lineSpacingAdd"
        }
        require(letterSpacingEm in MIN_LETTER_SPACING_EM..MAX_LETTER_SPACING_EM) {
            "letterSpacingEm out of range: $letterSpacingEm"
        }
        require(indentEm in 0f..MAX_INDENT_EM) { "indentEm out of range: $indentEm" }
        require(paragraphSpacingEm in 0f..MAX_PARAGRAPH_SPACING_EM) {
            "paragraphSpacingEm out of range: $paragraphSpacingEm"
        }
        require(marginSideDp >= 0f && marginTopDp >= 0f && marginBottomDp >= 0f) {
            "margins must not be negative"
        }
    }

    /** 行高倍数：1 + lineSpacingAdd。 */
    val lineHeightMultiplier: Float get() = 1f + lineSpacingAdd

    /**
     * 按可用的正文宽度算每行容纳的 em 数。
     * 留白过大导致宽度为 0 时返回安全下限，避免上层除以 0。
     */
    fun columnsForWidth(widthDp: Float): Float {
        val usable = widthDp - marginSideDp * 2f
        if (usable <= 0f) return MIN_COLUMNS
        // 1 em ≈ fontSizeSp 像素（sp 与 dp 在无系统字体缩放下等价）
        return (usable / fontSizeSp).coerceAtLeast(MIN_COLUMNS)
    }

    fun withFontSize(sp: Float): TypographySpec =
        copy(fontSizeSp = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP))

    fun withLineSpacing(add: Float): TypographySpec =
        copy(lineSpacingAdd = add.coerceIn(0f, MAX_LINE_SPACING_ADD))

    companion object {
        const val MIN_FONT_SP = 12f
        const val MAX_FONT_SP = 34f
        const val DEFAULT_FONT_SP = 18f
        const val MAX_LINE_SPACING_ADD = 1.5f
        const val MIN_LETTER_SPACING_EM = -0.05f
        const val MAX_LETTER_SPACING_EM = 0.3f
        const val MAX_INDENT_EM = 4f
        const val MAX_PARAGRAPH_SPACING_EM = 3f
        const val MIN_COLUMNS = 1f

        /** 默认阅读排版。 */
        val Default = TypographySpec()
    }
}

/**
 * 主题配色。颜色统一用 ARGB Int 表示，避免依赖 Android Color。
 */
data class ThemePalette(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val backgroundArgb: Int,
    val textArgb: Int,
    val secondaryTextArgb: Int,
    val accentArgb: Int,
    /** 是否叠加纸张纹理（仅对浅色主题有意义）。 */
    val paperGrain: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "theme id must not be blank" }
        require(name.isNotBlank()) { "theme name must not be blank" }
    }

    /** 与本主题搭配的深色/浅色切换目标。 */
    fun toggled(registry: ThemeRegistry = ThemeRegistry): ThemePalette =
        if (isDark) registry.byId(DayId) ?: this else registry.byId(NightId) ?: this

    companion object {
        const val DayId = "day"
        const val NightId = "night"
    }
}

/**
 * 内置主题集合。主题是纯数据，运行时可被用户覆盖（用户主题由上层持久化）。
 */
object ThemeRegistry {

    private val builtIn: List<ThemePalette> = listOf(
        ThemePalette(
            id = ThemePalette.DayId,
            name = "纸质",
            isDark = false,
            backgroundArgb = 0xFFF5F0E6.toInt(),
            textArgb = 0xFF2B2620.toInt(),
            secondaryTextArgb = 0xFF7A7267.toInt(),
            accentArgb = 0xFF8C6A3F.toInt(),
            paperGrain = true
        ),
        ThemePalette(
            id = ThemePalette.NightId,
            name = "夜间",
            isDark = true,
            backgroundArgb = 0xFF16181C.toInt(),
            textArgb = 0xFFC9CDD4.toInt(),
            secondaryTextArgb = 0xFF7E848D.toInt(),
            accentArgb = 0xFF6E9BD1.toInt()
        ),
        ThemePalette(
            id = "green",
            name = "护眼绿",
            isDark = false,
            backgroundArgb = 0xFFCCE8CF.toInt(),
            textArgb = 0xFF23301F.toInt(),
            secondaryTextArgb = 0xFF5C6B58.toInt(),
            accentArgb = 0xFF3F7A46.toInt()
        ),
        ThemePalette(
            id = "oled",
            name = "纯黑",
            isDark = true,
            backgroundArgb = 0xFF000000.toInt(),
            textArgb = 0xFFE6E6E6.toInt(),
            secondaryTextArgb = 0xFF8A8A8A.toInt(),
            accentArgb = 0xFF4C9AFF.toInt()
        )
    )

    val all: List<ThemePalette> get() = builtIn

    fun byId(id: String?): ThemePalette? = builtIn.firstOrNull { it.id == id }

    /** 未知 id 回落到浅色纸质主题，不让界面因为坏配置变黑。 */
    fun byIdOrDefault(id: String?): ThemePalette = byId(id) ?: builtIn.first()
}
