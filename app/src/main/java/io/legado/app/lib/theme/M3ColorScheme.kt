package io.legado.app.lib.theme

import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent

/**
 * 用单个种子色生成 Material 3 调和配色（Content 方案）。
 *
 * Content 方案保持种子的色相；primary 基准 tone 亮色≈40/暗色≈80，但受 ToneDeltaPair
 * 约束向保真容器靠拢——极端明暗种子时会接近源 tone(实测近黑种子 primary tone<20)，
 * 种子的"保真色"由 primaryContainer 承载。同时派生出和谐的 secondary / tertiary / surface 等辅助色。
 *
 * 注意：MaterialDynamicColors 的取色 API 在 material 1.13.0 中为 public。
 */
@Suppress("RestrictedApi")
object M3ColorScheme {

    private val dynamicColors = MaterialDynamicColors()

    /** 从种子色生成一套 M3 颜色（primary=种子的对比度修正 tone，保真色在 primaryContainer） */
    class Scheme internal constructor(seedArgb: Int, isDark: Boolean) {
        private val scheme = SchemeContent(Hct.fromInt(seedArgb), isDark, 0.0)

        val primary: Int get() = dynamicColors.primary().getArgb(scheme)
        val onPrimary: Int get() = dynamicColors.onPrimary().getArgb(scheme)
        val secondary: Int get() = dynamicColors.secondary().getArgb(scheme)
        val tertiary: Int get() = dynamicColors.tertiary().getArgb(scheme)
        val surface: Int get() = dynamicColors.surface().getArgb(scheme)
        val surfaceVariant: Int get() = dynamicColors.surfaceVariant().getArgb(scheme)
        val onSurface: Int get() = dynamicColors.onSurface().getArgb(scheme)
        val background: Int get() = dynamicColors.background().getArgb(scheme)
        val error: Int get() = dynamicColors.error().getArgb(scheme)
        val primaryContainer: Int get() = dynamicColors.primaryContainer().getArgb(scheme)
        val onPrimaryContainer: Int get() = dynamicColors.onPrimaryContainer().getArgb(scheme)
        val onSecondary: Int get() = dynamicColors.onSecondary().getArgb(scheme)
        val secondaryContainer: Int get() = dynamicColors.secondaryContainer().getArgb(scheme)
        val onSecondaryContainer: Int get() = dynamicColors.onSecondaryContainer().getArgb(scheme)
        val onTertiary: Int get() = dynamicColors.onTertiary().getArgb(scheme)
        val tertiaryContainer: Int get() = dynamicColors.tertiaryContainer().getArgb(scheme)
        val onTertiaryContainer: Int get() = dynamicColors.onTertiaryContainer().getArgb(scheme)
        val onError: Int get() = dynamicColors.onError().getArgb(scheme)
        val errorContainer: Int get() = dynamicColors.errorContainer().getArgb(scheme)
        val onErrorContainer: Int get() = dynamicColors.onErrorContainer().getArgb(scheme)
        val outline: Int get() = dynamicColors.outline().getArgb(scheme)
        val outlineVariant: Int get() = dynamicColors.outlineVariant().getArgb(scheme)
        val inversePrimary: Int get() = dynamicColors.inversePrimary().getArgb(scheme)
        val inverseSurface: Int get() = dynamicColors.inverseSurface().getArgb(scheme)
        val inverseOnSurface: Int get() = dynamicColors.inverseOnSurface().getArgb(scheme)
    }

    fun generate(seedArgb: Int, isDark: Boolean): Scheme = Scheme(seedArgb, isDark)
}
