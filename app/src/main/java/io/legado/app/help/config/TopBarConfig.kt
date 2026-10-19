package io.legado.app.help.config

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import io.legado.app.R
import io.legado.app.lib.theme.primaryColor
import splitties.init.appCtx
import io.legado.app.utils.ColorUtils

/**
 * D9 桥接：TopBarConfig（m 顶栏包契约，695 行原版）依赖 AppCloudStorage/MainLayoutPresetConfig
 * 等 max 云存储与顶栏包治理面（D23 范围），本桥接仅覆盖 D9 拉入 compose 组件
 * （GlassTopAppBar/AppMenuSheet/AppSettingComponents/AiProviderEditScreen）消费的最小成员面。
 * 顶栏包批次落位时删除本文件，以 m 原版实现覆盖。
 */
object TopBarConfig {

    const val STYLE_DEFAULT = "default"
    const val STYLE_REGULAR = "regular"

    /**
     * 顶栏资产契约（门禁 20：实现内禁止写死 R.drawable.ic_*，一律经本契约取）。
     */
    object Icons {

        /** 返回键。 */
        @DrawableRes
        val navBack: Int = R.drawable.ic_back

        /** 更多（溢出菜单入口图标）。 */
        @DrawableRes
        val more: Int = R.drawable.ic_more_vert
    }

    data class Config(
        var style: String = STYLE_DEFAULT,
        var wallpaperPath: String? = null,
        var wallpaperAlpha: Int = 100,
    )

    fun currentSignature(isNight: Boolean): String {
        return if (isNight) "shim-night" else "shim-day"
    }

    fun currentConfig(context: Context, isNight: Boolean): Config {
        return Config(style = STYLE_DEFAULT)
    }

    fun actionContainerSize(context: Context): Float {
        return 34f * context.resources.configuration.fontScale
    }

    fun actionIconSize(context: Context): Float {
        return actionContainerSize(context) - 2 * 6f * context.resources.configuration.fontScale
    }

    fun currentWallpaperFile(context: Context, isNight: Boolean): java.io.File? {
        return null
    }

    @ColorInt
    fun resolvePageBarColorWithAlpha(context: Context, config: Config): Int {
        return context.primaryColor
    }

    @ColorInt
    fun resolveBackgroundColor(config: Config): Int {
        return appCtx.primaryColor
    }

    @ColorInt
    fun withOpacity(@ColorInt color: Int, alpha: Int): Int {
        return ColorUtils.withAlpha(color, alpha.coerceIn(0, 100) / 100f)
    }

    fun cornerRadius(context: Context, config: Config): Float {
        return context.resources.getDimension(R.dimen.ui_panel_radius)
    }
}
