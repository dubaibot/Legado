package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.dpToPx
import java.io.File

private fun Context.baseSystemTypeface(): Typeface {
    return when (AppConfig.systemTypefaces) {
        1 -> Typeface.SERIF
        2 -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
}

/** 返回 UI 正文字体：优先加载自定义字体，未设置或加载失败时按系统字体设置返回对应 Typeface */
fun Context.uiTypeface(): Typeface {
    val fontPath = AppConfig.uiFontPath
    if (fontPath.isNotBlank()) {
        loadUiTypeface(fontPath)?.let {
            return it
        }
    }
    return baseSystemTypeface()
}

/** 返回标题字体：优先加载标题自定义字体，未设置或加载失败时按系统字体设置返回对应 Typeface */
fun Context.titleTypeface(): Typeface {
    val fontPath = AppConfig.titleFontPath
    if (fontPath.isNotBlank()) {
        loadUiTypeface(fontPath)?.let {
            return it
        }
    }
    return baseSystemTypeface()
}

/** 从字体路径加载 Typeface，支持 content:// URI 与本地文件，结果带缓存 */
fun Context.loadUiTypeface(fontPath: String): Typeface? {
    if (fontPath.isBlank()) {
        return null
    }
    return UiTypefaceCache.get(this, fontPath)
}

private object UiTypefaceCache {

    private val cache = linkedMapOf<String, Typeface>()

    fun get(context: Context, fontPath: String): Typeface? {
        cache[fontPath]?.let { return it }
        return runCatching {
            when {
                fontPath.startsWith("content://", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    context.contentResolver.openFileDescriptor(android.net.Uri.parse(fontPath), "r")?.use {
                        Typeface.Builder(it.fileDescriptor).build()
                    }
                }
                fontPath.startsWith("content://", ignoreCase = true) -> {
                    RealPathUtil.getPath(context, android.net.Uri.parse(fontPath))?.let {
                        Typeface.createFromFile(it)
                    }
                }
                File(fontPath).exists() -> Typeface.createFromFile(fontPath)
                else -> null
            }
        }.getOrNull().also {
            if (it != null) {
                cache[fontPath] = it
                if (cache.size > 8) {
                    cache.remove(cache.keys.first())
                }
            }
        }
    }
}

/** 递归设置 View 树的字体 */
fun View.applyUiTypefaceDeep(typeface: Typeface) {
    when (this) {
        is TextView -> {
            if (this.typeface != typeface) {
                this.typeface = typeface
            }
        }
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiTypefaceDeep(typeface)
            }
        }
    }
}

/**
 * 递归设置 View 树的字体。
 * 跳过 TitleBar 和标记了 ui_title_typeface_role 的 View（如标题标签）。
 */
fun View.applyUiBodyTypefaceDeep(typeface: Typeface) {
    when (this) {
        is TitleBar -> return
        is TextView -> {
            if (getTag(R.id.ui_title_typeface_role) == true) return
            if (this.typeface != typeface) {
                this.typeface = typeface
            }
        }
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiBodyTypefaceDeep(typeface)
            }
        }
    }
}

/** 应用标题字体与标题色，仅当当前文字色为默认正文色时才覆盖文字色 */
fun TextView.applyUiTitleTypeface(context: Context) {
    setTag(R.id.ui_title_typeface_role, true)
    val colorBeforeTypeface = currentTextColor
    typeface = context.titleTypeface()
    val titleColor = context.titleTextColor
    val defaultTitleColors = setOf(
        context.primaryTextColor,
        context.getPrimaryTextColor(!AppConfig.isNightTheme),
        ThemeStore.textColorPrimary(context)
    )
    if (colorBeforeTypeface in defaultTitleColors) {
        setTextColor(titleColor)
    }
}

/** 应用菜单项字体 */
fun TextView.applyUiMenuItemTypeface(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
}

/** 递归设置 View 树的菜单项字体 */
fun View.applyUiMenuTypefaceDeep(context: Context) {
    when (this) {
        is TextView -> applyUiMenuItemTypeface(context)
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiMenuTypefaceDeep(context)
            }
        }
    }
}

/** 应用标签样式：小字号、正文主色 */
fun TextView.applyUiLabelStyle(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 14f
    setTextColor(context.primaryTextColor)
}

/** 应用区块标题样式：标题字体、中字号、标题色 */
fun TextView.applyUiSectionTitleStyle(context: Context) {
    applyUiTitleTypeface(context)
    textSize = 15f
    setTextColor(context.titleTextColor)
}

/** 应用次级按钮样式：小字号、固定最小高度、正文主色 */
fun TextView.applyUiSubtleButtonStyle(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 14f
    minHeight = 40.dpToPx()
    setTextColor(context.primaryTextColor)
}

/** 应用输入框样式：自动设置字号、行数限制、内边距 */
fun EditText.applyUiInputStyle(context: Context, minLines: Int = 1) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 15f
    this.minLines = minLines
    maxLines = if (minLines > 1) 8 else 2
    setSingleLine(minLines == 1)
    minHeight = if (minLines > 1) 92.dpToPx() else 44.dpToPx()
    val horizontal = 12.dpToPx()
    val vertical = if (minLines > 1) 10.dpToPx() else 8.dpToPx()
    setPadding(horizontal, vertical, horizontal, vertical)
}
