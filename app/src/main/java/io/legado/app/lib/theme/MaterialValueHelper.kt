@file:Suppress("unused")

package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

/**
 * @author Karim Abou Zeid (kabouzeid)
 */

/**
 * 判断操作栏“实际可见”的背景是否为浅色，用来决定文字/图标取深色还是浅色前景。
 * 沉浸式（透明）操作栏时，看到的是页面内容背景，而非栏自身的主色/底栏色，
 * 因此必须用内容背景判断，否则浅背景配白字会看不清。
 * 返回值直接喂给 [getPrimaryTextColor]（入参即 isLight）。
 */
fun appBarBackgroundIsLight(
    transparentActionBar: Boolean,
    @ColorInt barBackgroundColor: Int,
    @ColorInt contentBackgroundColor: Int,
): Boolean {
    val effectiveBackground = if (transparentActionBar) {
        contentBackgroundColor
    } else {
        barBackgroundColor
    }
    return ColorUtils.isColorLight(effectiveBackground)
}

@ColorInt
fun Context.getPrimaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_primary_text)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getSecondaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_secondary)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getPrimaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    }
}

@ColorInt
fun Context.getSecondaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_light
        )
    } else {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_dark
        )
    }
}

val Context.primaryColor: Int
    get() = ThemeStore.primaryColor(this)

val Context.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(this)

val Context.accentColor: Int
    get() = ThemeStore.accentColor(this)

val Context.backgroundColor: Int
    get() = ThemeStore.backgroundColor(this)

val Context.bottomBackground: Int
    get() = ThemeStore.bottomBackground(this)

val Context.primaryTextColor: Int
    get() = getPrimaryTextColor(isDarkTheme)

/**
 * 工具栏文字色：沉浸式时按页面背景判断明暗，非沉浸时按主色判断。
 * 供位于 TitleBar 内的 SearchView、菜单文字等使用，替代直接取 [primaryTextColor]。
 */
val Context.toolbarTextColor: Int
    get() = getPrimaryTextColor(
        appBarBackgroundIsLight(
            transparentActionBar = AppConfig.isTransparentActionBar,
            barBackgroundColor = primaryColor,
            contentBackgroundColor = backgroundColor
        )
    )

val Context.transparentNavBar: Boolean
    get() = ThemeStore.transparentNavBar(this)

val Context.secondaryTextColor: Int
    get() = getSecondaryTextColor(isDarkTheme)

val Context.primaryDisabledTextColor: Int
    get() = getPrimaryDisabledTextColor(isDarkTheme)

val Context.secondaryDisabledTextColor: Int
    get() = getSecondaryDisabledTextColor(isDarkTheme)

val Fragment.primaryColor: Int
    get() = ThemeStore.primaryColor(requireContext())

val Fragment.toolbarTextColor: Int
    get() = requireContext().toolbarTextColor

val Fragment.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(requireContext())

val Fragment.accentColor: Int
    get() = ThemeStore.accentColor(requireContext())

val Fragment.backgroundColor: Int
    get() = ThemeStore.backgroundColor(requireContext())

val Fragment.bottomBackground: Int
    get() = ThemeStore.bottomBackground(requireContext())

val Fragment.primaryTextColor: Int
    get() = requireContext().getPrimaryTextColor(isDarkTheme)

val Fragment.secondaryTextColor: Int
    get() = requireContext().getSecondaryTextColor(isDarkTheme)

val Fragment.primaryDisabledTextColor: Int
    get() = requireContext().getPrimaryDisabledTextColor(isDarkTheme)

val Fragment.secondaryDisabledTextColor: Int
    get() = requireContext().getSecondaryDisabledTextColor(isDarkTheme)

val Context.buttonDisabledColor: Int
    get() = if (isDarkTheme) {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    }

val Context.isDarkTheme: Boolean
    get() = ColorUtils.isColorLight(ThemeStore.primaryColor(this))

val Fragment.isDarkTheme: Boolean
    get() = requireContext().isDarkTheme

val Context.elevation: Float
    @SuppressLint("PrivateResource")
    get() {
        return if (AppConfig.elevation < 0) {
            ThemeUtils.resolveFloat(
                this,
                android.R.attr.elevation,
                resources.getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
            )
        } else {
            AppConfig.elevation.toFloat().dpToPx()
        }
    }

val Context.filletBackground: GradientDrawable
    get() {
        val background = GradientDrawable()
        background.cornerRadius = 3f.dpToPx()
        background.setColor(backgroundColor)
        return background
    }

// 多源合并：arc 主题兼容符号（标题色暂跟随正文主色，Sigma 无独立标题色配置）
@ColorInt
fun String?.toThemeTextColorOrNull(): Int? {
    val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val withoutPrefix = raw
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    val candidate = if (
        withoutPrefix.length in setOf(6, 8) &&
        withoutPrefix.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    ) {
        "#$withoutPrefix"
    } else {
        raw
    }
    return kotlin.runCatching { candidate.toColorInt() }.getOrNull()
}

val Context.titleTextColor: Int
    get() = primaryTextColor

/**
 * 书架"显示外边框"卡片背景：填充色取主题背景色（保留半透明以透出背景图），
 * 描边色由背景色与其明暗反色混合推导，使边框随主题背景颜色自适应。
 */
val Context.bookBorderBackground: GradientDrawable
    get() {
        val bgColor = backgroundColor
        val contrastColor = if (ColorUtils.isColorLight(bgColor)) Color.BLACK else Color.WHITE
        val strokeColor = ColorUtils.blendColors(bgColor, contrastColor, 0.22f)
        return GradientDrawable().apply {
            cornerRadius = 12f.dpToPx()
            setColor(ColorUtils.adjustAlpha(bgColor, 0.41f))
            setStroke(0.8f.dpToPx().toInt().coerceAtLeast(1), strokeColor)
        }
    }

    /** 浮层菜单容器背景:M 圆角 + surfaceContainer */
    val Context.popupBackground: GradientDrawable
        get() {
            val background = GradientDrawable()
            background.cornerRadius = resources.getDimension(R.dimen.radius_m)
            background.setColor(AppColorScheme.current.surfaceContainer)
            return background
        }