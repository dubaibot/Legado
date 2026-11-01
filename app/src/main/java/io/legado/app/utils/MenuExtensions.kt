@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.view.menu.SubMenuBuilder
import androidx.core.graphics.ColorUtils
import androidx.core.view.forEach
import io.legado.app.R
import io.legado.app.constant.Theme
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.SearchView
import io.legado.app.ui.widget.TitleBar
import io.legado.app.ui.widget.topBarContentColor
import java.lang.reflect.Method

/**
 * 顶栏菜单着色：图标与 actionView 形式的搜索框都用宿主页面 [TitleBar] 的内容色（与标题同源）。
 *
 * 页面/Fragment 给顶栏菜单 inflate 之后应当用它，而不是 [applyTint]——后者按"主题主色 /
 * 背景色"取色，与标题"按顶栏实际底色取"不同源。设置类页面正是这种结构（标题挂在 Activity 的
 * TitleBar 上、菜单在 Fragment 的 MenuProvider 里），于是会出现"标题深色、图标与搜索框浅色"。
 *
 * 拿不到 TitleBar 时（对话框等没有页面顶栏的场景）回退到 [applyTint] 的默认取色。
 */
fun Menu.applyTopBarTint(context: Context): Menu {
    val titleBar = context.findTitleBar()
    if (titleBar == null) {
        return applyTint(context)
    }
    val contentColor = titleBar.topBarContentColor()
    applyTint(context, titleBar.topBarTheme, contentColor)
    // actionView 形式的菜单项不在 item.icon 上，需要单独着色
    forEach { item ->
        (item.actionView as? SearchView)?.setContentTint(contentColor)
    }
    return this
}

/** 从 Context（可能是被包装的）里找宿主 Activity 的页面顶栏；没有则返回 null */
private fun Context.findTitleBar(): TitleBar? {
    var ctx: Context? = this
    while (ctx is ContextWrapper && ctx !is Activity) {
        ctx = ctx.baseContext
    }
    return (ctx as? Activity)?.findViewById(R.id.title_bar)
}

@SuppressLint("RestrictedApi")
@Suppress("UsePropertyAccessSyntax")
fun Menu.applyTint(
    context: Context,
    theme: Theme = Theme.Auto,
    tintColor: Int? = null
): Menu = this.let { menu ->
    if (menu is MenuBuilder) {
        menu.setOptionalIconsVisible(true)
    }
    val defaultTextColor = context.getCompatColor(R.color.primaryText)
    val tint = tintColor ?: MenuExtensions.getMenuColor(context, theme)
    menu.forEach { item ->
        (item as? MenuItemImpl)?.let { impl ->
            //overflow：展开的item
            impl.icon?.setTintMutate(
                if (impl.requiresOverflow()) defaultTextColor else tint
            )
            // actionView 形式的项（iconItemOnLongClick 把图标塞进 ImageButton）不吃 icon 上的 tint：
            // setTintMutate 内部会 mutate 出新的 Drawable，而 ImageButton 还拿着旧实例，
            // 于是这些图标会停在图标自带颜色上（深色图标压在深色顶栏上就是"看不清"）
            impl.actionView?.findViewById<ImageButton>(R.id.item)?.setImageDrawable(impl.icon)
        }
    }
    return menu
}

@SuppressLint("RestrictedApi")
fun Menu.applyOpenTint(context: Context, showIcon: Boolean = true) {
    //展开菜单显示图标
    if (this.javaClass.simpleName.equals("MenuBuilder", ignoreCase = true)) {
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
        kotlin.runCatching {
            var method: Method =
                this.javaClass.getDeclaredMethod("setOptionalIconsVisible", java.lang.Boolean.TYPE)
            method.isAccessible = true
            method.invoke(this, showIcon)
            if (showIcon) {
                method = this.javaClass.getDeclaredMethod("getNonActionItems")
                val menuItems = method.invoke(this)
                if (menuItems is ArrayList<*>) {
                    for (menuItem in menuItems) {
                        if (menuItem is MenuItem) {
                            menuItem.icon?.setTintMutate(defaultTextColor)
                        }
                    }
                }
            }
        }
    } else if (this.javaClass.simpleName.equals("SubMenuBuilder", ignoreCase = true)) {
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
        (this as? SubMenuBuilder)?.forEach { item: MenuItem ->
            item.icon?.setTintMutate(defaultTextColor)
        }
    }
}

fun Menu.iconItemOnLongClick(id: Int, function: (view: View) -> Unit) {
    findItem(id)?.let { item ->
        item.setActionView(R.layout.view_action_button)
        item.actionView?.run {
            contentDescription = item.title
            findViewById<ImageButton>(R.id.item).setImageDrawable(item.icon)
            setOnLongClickListener {
                function.invoke(this)
                true
            }
            setOnClickListener {
                performIdentifierAction(id, 0)
            }
        }
    }
}

@SuppressLint("RestrictedApi")
inline fun Menu.transaction(block: (Menu) -> Unit) {
    val menuBuilder = this as? MenuBuilder
    menuBuilder?.stopDispatchingItemsChanged()
    try {
        block(this)
    } finally {
        menuBuilder?.startDispatchingItemsChanged()
    }
}

object MenuExtensions {

    fun getMenuColor(
        context: Context,
        theme: Theme = Theme.Auto
    ): Int {
        if (context.transparentNavBar) {
            val isBackgroundLight = ColorUtils.calculateLuminance(context.backgroundColor) > 0.25
            return context.getPrimaryTextColor(isBackgroundLight)
        }
        val primaryTextColor = context.primaryTextColor
        return when (theme) {
            Theme.Dark -> context.getCompatColor(R.color.md_white_1000)
            Theme.Light -> context.getCompatColor(R.color.md_black_1000)
            else -> primaryTextColor
        }
    }

}
