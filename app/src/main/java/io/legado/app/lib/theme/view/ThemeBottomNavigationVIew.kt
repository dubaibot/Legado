package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.NavigationBarConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.lib.theme.elevation

/**
 * 主题化底部导航栏。
 *
 * 继承 BottomNavigationView，在初始化时根据当前主题设置背景色、透明度、
 * 图标/文字着色，并支持应用 NavigationBarConfig 配置与 badge 视图添加。
 */
class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    init {
        val transparentNavBar = context.transparentNavBar
        val bgColor = context.bottomBackground
        // 背景设为透明，由 StableLiquidGlassView 提供毛玻璃效果
        setBackgroundColor(Color.TRANSPARENT)
        val colorStateList = createThemeColorStateList()
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
        // itemBackground 设为透明，避免默认 ripple 覆盖毛玻璃效果
        itemBackground = Color.TRANSPARENT.toDrawable()
        if (AppConfig.isEInkMode || transparentNavBar) {
            isItemHorizontalTranslationEnabled = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    fun createThemeColorStateList(bgColor: Int = context.bottomBackground): ColorStateList {
        val textIsDark = ColorUtils.isColorLight(bgColor)
        val textColor = context.getSecondaryTextColor(textIsDark)
        return Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(ThemeStore.accentColor(context))
            .create()
    }

    fun restoreThemeIconTint(bgColor: Int = context.bottomBackground) {
        val colorStateList = createThemeColorStateList(bgColor)
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
    }

    /**
     * 应用底栏导航栏配置（NavigationBarConfig）。
     *
     * 根据当前激活的配置项设置底栏背景色/透明度、图标和文字颜色。
     * E-Ink 模式下跳过，保持 E-Ink 专用背景。
     */
    fun applyNavigationBarConfig() {
        if (AppConfig.isEInkMode) return
        val config = NavigationBarConfig.activeConfig(context, AppConfig.isNightTheme)
        val baseColor = context.bottomBackground
        val bgColor = NavigationBarConfig.resolveBottomColor(baseColor, config)
        if (context.transparentNavBar) {
            setBackgroundColor(Color.TRANSPARENT)
        } else {
            setBackgroundColor(bgColor)
            elevation = context.elevation
        }
        restoreThemeIconTint(bgColor)
    }

    fun addBadgeView(index: Int): BadgeView {
        //获取底部菜单view
        val menuView = getChildAt(0) as ViewGroup
        // 跳过隐藏(GONE)的 child，按可见 child 计数定位
        var itemIndex = 0
        var itemView: ViewGroup? = null
        for (i in 0 until menuView.childCount) {
            val child = menuView.getChildAt(i)
            if (child.visibility != View.GONE) {
                if (itemIndex == index) {
                    itemView = child as ViewGroup
                    break
                }
                itemIndex++
            }
        }
        itemView ?: return BadgeView(context)
        val badgeBinding = ViewNavigationBadgeBinding.inflate(LayoutInflater.from(context))
        itemView.addView(badgeBinding.root)
        return badgeBinding.viewBadge
    }

}
