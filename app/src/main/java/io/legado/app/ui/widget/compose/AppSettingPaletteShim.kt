package io.legado.app.ui.widget.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.lib.theme.toThemeTextColorOrNull

/**
 * D9 桥接：AppSettingPalette / AppManagementPalette 与两个 remember 工厂，从 m 版
 * AppSettingComponents.kt 原文提取（该文件其余 700+ 行为设置页组件，属 D10 组件库范围，
 * 会拖出 LazyColumnScrollbar 等 jitpack 依赖）。本文件在 D10 拉入 AppSettingComponents
 * 全量时删除，与原文件合并。
 */
@Immutable
data class AppSettingPalette(
    val page: Color,
    val row: Int,
    val rowPressed: Int,
    val divider: Color,
    val bottomBar: Int,
    val bottomBarText: Color,
    val border: Int?,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val danger: Color,
    val disabledText: Color,
    val onAccent: Color,
    val panelRadiusPx: Float,
    val bodyFontFamily: androidx.compose.ui.text.font.FontFamily,
    val titleFontFamily: androidx.compose.ui.text.font.FontFamily,
    val themeSignature: String
)

@Immutable
data class AppManagementPalette(
    val settings: AppSettingPalette,
    val miuix: LegadoMiuixPalette
)

@Composable
fun rememberAppSettingPalette(): AppSettingPalette {
    val context = LocalContext.current
    val dialogStyle = rememberAppDialogStyle()
    val themeUiPalette = rememberThemeUiPalette()
    val rowBaseColor = themeUiPalette.cardColor
    // 文字/强调前景色按实际背景明暗推导，避免随 night 标志产生深底深字/浅底白字
    val customUiText = AppConfig.uiFontColor.toThemeTextColorOrNull()
    val customTitleText = AppConfig.titleFontColor.toThemeTextColorOrNull()
    val secondaryText = if (customUiText != null) {
        Color(customUiText).copy(alpha = 0.72f)
    } else {
        Color(context.secondaryTextColor)
    }
    // followup F4 v3：透明度语义（0=不透明原状，>0 半透明叠加透出底图；红队 R5-1 预混废弃）
    val page = if (AppConfig.manageBgAlphaFraction > 0f) {
        Color(context.backgroundColor).copy(alpha = AppConfig.manageBgAlphaFraction)
    } else {
        Color(context.backgroundColor)
    }
    val row = UiCorner.surfaceColor(rowBaseColor)
    val rowPressed = UiCorner.surfaceColor(rowBaseColor, pressed = true)
    val divider = Color(themeUiPalette.dividerColor)
    val bottomBarBase = if (themeUiPalette.hasCustomCardColor || themeUiPalette.hasCustomMutedColor) {
        themeUiPalette.mutedColor
    } else {
        context.bottomBackground
    }
    val bottomBarText = Color(customUiText ?: context.primaryTextColor)
    val border = UiCorner.panelBorderColor(context)
    val primaryText = Color(customTitleText ?: customUiText ?: context.titleTextColor)
    val accentArgb = context.accentColor
    val accent = Color(accentArgb)
    // onAccent 单源（AD-15）：统一走 AppUiTokens.onAccent → ThemeSpec.contrastOn，禁止在此重复推导
    val onAccent = AppUiTokens.onAccent(accent)
    val panelRadiusPx = UiCorner.panelRadius(context)
    return remember(
        page,
        row,
        rowPressed,
        divider,
        bottomBarBase,
        bottomBarText,
        border,
        primaryText,
        secondaryText,
        accent,
        onAccent,
        dialogStyle.danger,
        panelRadiusPx,
        dialogStyle.bodyFontFamily,
        dialogStyle.titleFontFamily,
        themeUiPalette.signature
    ) {
        AppSettingPalette(
            page = page,
            row = row,
            rowPressed = rowPressed,
            divider = divider,
            bottomBar = bottomBarBase,
            bottomBarText = bottomBarText,
            border = border,
            primaryText = primaryText,
            secondaryText = secondaryText,
            accent = accent,
            danger = dialogStyle.danger,
            disabledText = secondaryText.copy(alpha = 0.48f),
            onAccent = onAccent,
            panelRadiusPx = panelRadiusPx,
            bodyFontFamily = dialogStyle.bodyFontFamily,
            titleFontFamily = dialogStyle.titleFontFamily,
            themeSignature = themeUiPalette.signature
        )
    }
}

@Composable
fun rememberAppManagementPalette(): AppManagementPalette {
    val context = LocalContext.current
    val colors = rememberAppSettingPalette()
    val panelRadius = context.composePanelRadius()
    val actionRadius = context.composeActionRadius()
    return remember(colors, panelRadius, actionRadius) {
        AppManagementPalette(
            settings = colors,
            miuix = LegadoMiuixPalette(
                accent = colors.accent,
                surface = colors.page,
                surfaceVariant = Color(colors.row),
                primaryText = colors.primaryText,
                secondaryText = colors.secondaryText,
                danger = colors.danger,
                onAccent = colors.onAccent,
                panelRadius = panelRadius,
                actionRadius = actionRadius
            )
        )
    }
}
