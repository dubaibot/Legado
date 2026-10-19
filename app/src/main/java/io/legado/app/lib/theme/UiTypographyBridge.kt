package io.legado.app.lib.theme

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.graphics.toColorInt

/**
 * D9 桥接：m 版 MaterialValueHelper 的字体颜色派生层（AppConfig.uiFontColor/titleFontColor
 * → ThemeConfig.effective*FontColor 治理面）属后置批次，本桥接按基座行为提供最小取值。
 * 字体颜色治理批次落位时删除本文件，以 m 原版实现覆盖。
 */
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
    @ColorInt
    get() = getPrimaryTextColor(isDarkTheme)
