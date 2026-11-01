package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.StateSet
import android.view.View
import androidx.collection.LruCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.MenuExtensions
import io.legado.app.utils.applyTint
import java.io.File

/**
 * TitleBar 顶栏配置应用扩展函数集。
 *
 * 将 [TopBarConfig] 的配置应用到 TitleBar 控件及其子 View（TabLayout、SearchView）。
 * 根据配置样式（默认/常规）、E-Ink 模式、透明导航栏等条件，
 * 分别设置背景色/壁纸/圆角、文字颜色、标签栏颜色和搜索框样式。
 */

fun TitleBar.applyTopBarConfig() {
    if (skipTopBarConfig) return
    if (AppConfig.isEInkMode) {
        applyEInkTopBarConfig()
        return
    }
    if (!opaque && context.transparentNavBar) {
        setBackgroundColor(Color.TRANSPARENT)
        applyTopBarContentColor()
        applyTransparentTopBarChildConfig()
        return
    }
    if (ignoreTopBarOpacity) {
        setBackgroundColor(context.primaryColor)
        applyTopBarContentColor()
        elevation = context.elevation
        applyTopBarChildConfig()
        return
    }
    val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    applyTopBarConfig(config)
}

/**
 * 默认顶部背景图：在顶栏自身背景之上叠加壁纸图层。
 *
 * 与 [applyTopBarConfig] 的区别：本函数**不改写顶栏底色**（保留各页面原有的
 * primaryColor / 透明 / 墨水屏落地），只在其上叠一层壁纸——对应「仅顶部栏有背景图片」。
 * 未配置壁纸时直接返回：零开销、零视觉变化。
 *
 * 对两种样式都生效：
 * - STYLE_DEFAULT：在顶栏自身背景之上叠加壁纸图层（圆角为 0，与顶栏底色无缝衔接）
 * - STYLE_REGULAR：委托 [applyTopBarConfig] 完整落地（圆角底色 + 壁纸两层）
 */
fun TitleBar.applyTopBarWallpaper() {
    if (skipTopBarConfig) return
    val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    if (config.style != TopBarConfig.STYLE_DEFAULT) {
        applyTopBarConfig()
        return
    }
    val file = TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme) ?: return
    val wallpaper = bitmapLayer(file, config.wallpaperAlpha, 0f) ?: return
    val base = background ?: ColorDrawable(context.primaryColor)
    background = LayerDrawable(arrayOf(base, wallpaper))
    // 壁纸铺上后按合成底色重取标题/图标颜色，避免深色壁纸配深色文字看不清
    applyTopBarContentColor()
}

/**
 * 墨水屏渲染下的顶栏落地。
 *
 * 墨水屏只该把彩色换成灰阶，不该改写用户配好的顶栏外观——用户设了「顶栏颜色透明」
 * 或顶栏壁纸时照旧生效（此前一律换成不透明色带，表现为"顶栏透明没起作用"）；
 * 只有配置本身就是不透明纯色时，才换成墨水屏描边变体（纸白/墨黑平面 + 1dp 分隔线）。
 */
private fun TitleBar.applyEInkTopBarConfig() {
    val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    val backgroundAlpha = if (config.style == TopBarConfig.STYLE_REGULAR) {
        config.wallpaperAlpha
    } else {
        config.tagBarAlpha
    }
    val hasWallpaper = config.style == TopBarConfig.STYLE_REGULAR &&
        TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme) != null
    if (hasWallpaper || (!opaque && !ignoreTopBarOpacity && backgroundAlpha < 100)) {
        // 透明/半透明或带壁纸：交给通用落地，保留用户配置（其中的阴影已按墨水屏去掉）
        applyTopBarConfig(config)
        return
    }
    setBackgroundResource(R.drawable.bg_eink_border_bottom)
    elevation = 0f
    applyTopBarContentColor()
    applyTopBarChildConfig()
}

private fun TitleBar.applyTopBarConfig(config: TopBarConfig.Config) {
    val backgroundColor = if (config.style == TopBarConfig.STYLE_REGULAR) {
        TopBarConfig.resolveBackgroundColor(config)
    } else {
        config.tagBarColor ?: context.primaryColor
    }
    val radius = if (config.style == TopBarConfig.STYLE_REGULAR) {
        context.resources.getDimension(R.dimen.ui_panel_radius) *
            TopBarConfig.resolveCornerScale(config).coerceIn(0f, 3f)
    } else {
        0f
    }
    val backgroundAlpha = if (config.style == TopBarConfig.STYLE_REGULAR) {
        config.wallpaperAlpha
    } else {
        config.tagBarAlpha
    }
    val shape = regularBackground(
        backgroundColor,
        radius,
        backgroundAlpha,
    )
    val wallpaper = TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme)
        ?.takeIf { config.style == TopBarConfig.STYLE_REGULAR }
        ?.let { file -> bitmapLayer(file, backgroundAlpha, radius) }
    background = if (wallpaper == null) {
        shape
    } else {
        LayerDrawable(arrayOf(shape, wallpaper))
    }
    // 墨水屏渲染去掉阴影：半透明顶栏原本靠 0.1dp 阴影勾边，墨水屏不需要这层
    elevation = if (AppConfig.isEInkMode) {
        0f
    } else {
        when {
            backgroundAlpha < 100 -> 0.1f
            else -> context.elevation
        }
    }
    val contentColor = topBarContentColor()
    applyTopBarContentColor(contentColor)
    applyTopBarChildConfig(config, contentColor)
}

/** 应用顶栏内容颜色（文字、图标着色） */
private fun TitleBar.applyTopBarContentColor(contentColor: Int = topBarContentColor()) {
    setTextColor(contentColor)
    setColorFilter(contentColor)
    toolbar.menu.applyTint(context, topBarTheme, contentColor)
}

/**
 * 顶栏内容色（标题、标签栏、搜索框、返回与菜单图标）的统一取色入口。
 *
 * View 侧 [TitleBar] 与 Compose 侧顶栏（[io.legado.app.ui.theme.pageTopBarColors]）都走这里：
 * 同一根顶栏的前景必须同色，否则会出现"标题深色、图标浅色"这类不一致；取色依据又必须落在
 * 顶栏实际压着的底色上，否则会与底色撞色（历史实现里图标按"主题主色"取、文字按"顶栏背景"取，
 * 于是自定义顶栏背景色与"透明顶栏压壁纸"这两种场景都会翻车）。
 *
 * 规则（按可靠性排序）：
 * 1. 顶栏底层（底色 + 可选的顶栏壁纸，两层各带透明度）合成出来不是全透明 → 按合成结果的亮度
 *    取深浅。这条**优先于**页面写死的 [topBarTheme]：顶栏底色是主题/用户配出来的，写死深浅会
 *    配出"纸白顶栏压白图标"。合成必须逐层做：只拿壁纸本身的深浅判定，会把"半透明壁纸压在不透明
 *    浅底色上"判成深底而配上白字（2026-10-12 反馈的"顶栏文字看不清"）。
 * 2. 顶栏完全没有底色（"顶栏颜色透明" / 未配顶栏背景）→ 页面显式声明的 [topBarTheme] 说了算
 *    （书籍详情页这类透明顶栏底下是封面图，亮度无从判定）。
 * 3. 顶栏没底色、页面也没声明 → 按顶栏背后实际显示的窗口背景取色：有主界面壁纸时用壁纸的
 *    平均亮度，没有壁纸时用主题背景色。
 *
 * @param topBarBackground 顶栏自身底色（含 alpha）；顶栏完全透明或底色解析不出来时传 null
 * @param topBarWallpaperColor 顶栏自带壁纸的平均色（**已按 wallpaperAlpha 带 alpha**），
 *   用 `TopBarConfig.wallpaperAverageColor(file)?.let { TopBarConfig.withOpacity(it, alpha) }` 取得；
 *   没叠壁纸时传 null。壁纸压在底色之上，必须与底色合成后再判深浅。
 */
fun resolveTopBarContentColor(
    context: Context,
    topBarTheme: Theme,
    topBarBackground: Int?,
    topBarWallpaperColor: Int? = null,
): Int {
    val solid = resolveTopBarSurfaceColor(context, topBarBackground, topBarWallpaperColor)
    if (solid != null) {
        // 背景越亮文字越深：亮背景(>0.5)用深色文字，暗背景用浅色文字。
        // 与 MenuExtensions.getMenuColor 透明导航栏分支的取色约定保持一致。
        return context.getPrimaryTextColor(ColorUtils.calculateLuminance(solid) > 0.25)
    }
    if (topBarTheme != Theme.Auto) {
        return MenuExtensions.getMenuColor(context, topBarTheme)
    }
    return context.getPrimaryTextColor(windowBackgroundLuminance(context) > 0.25f)
}

/**
 * 顶栏前景实际压着的那块颜色：底色与壁纸层依次压在窗口背景之上，逐层合成。
 *
 * 顶栏底色与顶栏壁纸都是**半透明**的（用户会把壁纸透明度调低），只取其中一层判深浅都不成立——
 * 必须像真正绘制那样一层层压上去（LayerDrawable 的绘制顺序即此顺序）。
 * 两层都不可用（全透明或解析不出）时返回 null，交给调用方按页面声明 / 窗口背景回退。
 */
private fun resolveTopBarSurfaceColor(
    context: Context,
    topBarBackground: Int?,
    topBarWallpaperColor: Int?,
): Int? {
    var surface: Int? = null
    if (topBarBackground != null && Color.alpha(topBarBackground) > 0) {
        surface = ColorUtils.compositeColors(topBarBackground, windowBackgroundColor(context))
    }
    if (topBarWallpaperColor != null && Color.alpha(topBarWallpaperColor) > 0) {
        surface = ColorUtils.compositeColors(
            topBarWallpaperColor,
            surface ?: windowBackgroundColor(context),
        )
    }
    return surface
}

/**
 * 顶栏背后窗口背景的代表色：有主界面壁纸时按**壁纸平均色**，否则用主题背景色。
 *
 * 取真实平均色而不是"按亮度取黑白"：顶栏底色与壁纸是半透明压在它上面的，只有同色系合成
 * 才接近实际渲染出来的颜色；黑白近似会把中性色调的壁纸推成纯黑/纯白，贴着 0.5 阈值就判反。
 */
private fun windowBackgroundColor(context: Context): Int {
    val path = ThemeConfig.resolveBgImagePath(context) ?: return context.backgroundColor
    return ThemeConfig.getImageAverageColor(path) ?: context.backgroundColor
}

/**
 * 顶栏背后窗口背景的亮度：有主界面壁纸时按壁纸平均亮度，否则按主题背景色。
 *
 * 只按主题背景色判断深浅，会在"浅色背景色 + 深色壁纸"（或反之）这类组合里取反——
 * 主界面与"我的"子界面的顶栏都压在壁纸上，这正是顶栏文字/图标撞色的主要来源。
 */
private fun windowBackgroundLuminance(context: Context): Float = ThemeConfig.getBgImageLuminance(context)
    ?: ColorUtils.calculateLuminance(context.backgroundColor).toFloat()

/**
 * 顶栏内容颜色：按顶栏自身底色取色（见 [resolveTopBarContentColor]），
 * 顶栏透明时回落到窗口背景（壁纸）亮度。
 *
 * 取色依据直接来自 [background] 这块 Drawable——底色与壁纸各自带透明度、以 LayerDrawable
 * 逐层叠好，由 [resolveSolidColor] 逐层合成回一块代表色（见该函数说明）。这样"顶栏背景被谁
 * 改过"（透明导航栏、墨水屏纸白、页面自己置透明）都能自动跟着走，不必在这里再认一遍分支。
 *
 * internal 而非 private：`TitleBar.attachToActivity` 在窗口附加后还要给返回/菜单图标上色，
 * 书架的顶栏分组选择器等自定义子视图也要按同一规则取色，它们都必须复用这个函数，
 * 否则会与标题各按一套规则取色。
 */
internal fun TitleBar.topBarContentColor(): Int = resolveTopBarContentColor(
    context = context,
    topBarTheme = topBarTheme,
    topBarBackground = background.resolveSolidColor(),
)

/**
 * 只重算顶栏前景色，不改背景。
 *
 * 供"顶栏背景被调用方另行改掉"的页面使用：书籍详情页与音频播放页会把顶栏改成透明以露出
 * 封面/封面模糊底图，而 `applyTopBarConfig` 早已按"不透明底"算好了内容色——背景一改，
 * 按底色取的深浅就过期了，标题会停在旧配色上、与随后重算的图标不同色。
 */
internal fun TitleBar.refreshTopBarContentColor() {
    applyTopBarContentColor()
    applyTopBarChildConfig()
}

/**
 * 解析背景代表的纯色（含 alpha）：底色/壁纸这些图层自底向顶逐个合成。
 *
 * 顶栏背景是「圆角底色 + 顶栏壁纸」两层叠出来的 LayerDrawable，两层都带透明度；只认"整块是
 * 不是纯色"会直接判不出颜色，退到按页面声明/窗口背景取色，于是半透明壁纸那一层被彻底忽略
 * （2026-10-12 的顶栏白字压浅底）。逐层合成后，两层各自的不透明度与颜色都会算进去。
 * 解析不出的图层（自定义 Drawable、API < 24 的渐变）跳过不参与合成；全都解析不出时返回 null。
 */
private fun Drawable?.resolveSolidColor(): Int? {
    if (this == null) return null
    return when (this) {
        is ColorDrawable -> color
        is GradientDrawable -> resolveGradientSolidColor()
        is TopBarWallpaperDrawable -> solidColor
        is LayerDrawable -> {
            var resolved: Int? = null
            for (i in 0 until numberOfLayers) {
                val layer = getDrawable(i).resolveSolidColor() ?: continue
                resolved = resolved?.let { ColorUtils.compositeColors(layer, it) } ?: layer
            }
            resolved
        }
        else -> null
    }
}

/**
 * GradientDrawable.getColor() 为 API 24 新增，函数级守卫满足 lint NewApi 检查；
 * 低版本读不出纯色，回退 null 交给 [resolveTopBarContentColor] 按页面声明 / 窗口背景取色
 */
private fun GradientDrawable.resolveGradientSolidColor(): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
    return color?.defaultColor?.takeIf { it != Color.TRANSPARENT }
}

/**
 * 应用顶栏子 View 配置（TabLayout、SearchView），使用当前 TopBarConfig。
 *
 * 取色与标题、图标同源（[TitleBar.topBarContentColor]）：此前这里按"透明底栏 / 主题主色"
 * 单独取色，于是标签栏文字与标题会不一致，顶栏背景不是主题主色时还会与底色撞色。
 */
private fun TitleBar.applyTopBarChildConfig() {
    applyTopBarChildConfig(
        TopBarConfig.currentConfig(context, AppConfig.isNightTheme),
        topBarContentColor(),
    )
}

/** 生成常规样式的圆角背景 Drawable */
private fun regularBackground(color: Int, radius: Float, alphaPercent: Int): Drawable = GradientDrawable().apply {
    setColor(TopBarConfig.withOpacity(color, alphaPercent))
    cornerRadii = if (radius > 0f) {
        floatArrayOf(
            0f,
            0f,
            0f,
            0f,
            radius,
            radius,
            radius,
            radius,
        )
    } else {
        null
    }
}

private fun TitleBar.applyTopBarChildConfig(
    config: TopBarConfig.Config,
    contentColor: Int = topBarContentColor(),
) {
    findViewById<TabLayout?>(R.id.tab_layout)?.applyTopBarChildConfig(config, contentColor)
    findViewById<View?>(R.id.search_view)?.applyTopBarChildConfig(config, contentColor)
}

private fun TitleBar.applyTransparentTopBarChildConfig() {
    val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    val contentColor = topBarContentColor()
    findViewById<TabLayout?>(R.id.tab_layout)?.apply {
        setBackgroundColor(Color.TRANSPARENT)
        setTabTextColors(tabTextColorStateList(contentColor))
        setSelectedTabIndicatorColor(
            resolveTabIndicatorColor(context, config, Color.TRANSPARENT),
        )
    }
    findViewById<View?>(R.id.search_view)?.applyTopBarChildConfig(config, contentColor)
}

private fun View.applyTopBarChildConfig(config: TopBarConfig.Config, contentColor: Int) {
    if (this !is TabLayout && id != R.id.search_view) return
    val tagBarColor = config.tagBarColor
        ?: ContextCompat.getColor(context, R.color.background_menu)
    if (this is TabLayout && id == R.id.tab_layout) {
        val tagBarAlpha = if (context.transparentNavBar) 0 else config.tagBarAlpha
        val barColor = TopBarConfig.withOpacity(tagBarColor, tagBarAlpha)
        setBackgroundColor(barColor)
        setTabTextColors(tabTextColorStateList(contentColor))
        setSelectedTabIndicatorColor(
            resolveTabIndicatorColor(context, config, barColor),
        )
    }
    if (id == R.id.search_view) {
        background = searchViewBackground()
        applyTint(contentColor)
        (this as? SearchView)?.setContentTint(contentColor)
    }
}

/**
 * 解析分组 TabLayout 的指示器（当前分组下划线）颜色。
 * 标签栏背景完全透明时（开启"顶栏颜色透明"或标签栏透明度为 0），
 * 指示器直接叠在页面背景上，而默认选中色是主题主色，常与页面背景同色而看不清，
 * 因此这种场景始终使用全局强调色；
 * 背景不透明时，若"选中标签颜色"与标签栏背景色同为 primaryColor（默认顶栏配置）
 * 会融为一体而不可见，此时同样回退为全局强调色，选中色透明度为 0 时也回退。
 */
private fun resolveTabIndicatorColor(
    context: Context,
    config: TopBarConfig.Config,
    barColor: Int,
): Int {
    if (Color.alpha(barColor) == 0) return context.accentColor
    val selectedColor = config.tagSelectedColor
        ?.let { TopBarConfig.withOpacity(it, config.tagSelectedAlpha) }
        ?: context.primaryColor
    val visible = TopBarConfig.opacityToAlpha(config.tagSelectedAlpha) > 0 &&
        selectedColor != barColor
    return if (visible) selectedColor else context.accentColor
}

private fun View.searchViewBackground(): Drawable {
    val radius = 35f * resources.displayMetrics.density
    val strokeWidth = (0.5f * resources.displayMetrics.density).coerceAtLeast(1f).toInt()
    val color = ContextCompat.getColor(context, R.color.transparent10)
    return GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        setStroke(strokeWidth, color)
    }
}

private fun tabTextColorStateList(contentColor: Int): ColorStateList {
    val normalColor = ColorUtils.setAlphaComponent(contentColor, 200)
    return ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_selected),
            StateSet.WILD_CARD,
        ),
        intArrayOf(contentColor, normalColor),
    )
}

/**
 * 顶栏壁纸解码结果缓存。
 *
 * 壁纸是整屏宽的大图，解码一次要几十毫秒；而 [applyTopBarConfig] 会在每个页面的 TitleBar
 * 创建、附加到窗口、顶栏配置变更时各跑一次——复杂主题下切页/返回时反复解码同一张图。
 * key 含文件时间与大小、目标尺寸，配置包里换了壁纸自然失效。
 * 容量按日/夜两套主题各留一份余量。
 */
private val topBarWallpaperCache = LruCache<String, Bitmap>(4)

private fun TitleBar.bitmapLayer(file: File, alphaPercent: Int, radius: Float): Drawable? {
    val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val targetHeight = height.takeIf { it > 0 } ?: (56 * resources.displayMetrics.density).toInt()
    val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}|$targetWidth|$targetHeight"
    val bitmap = topBarWallpaperCache[key]
        ?: kotlin.runCatching {
            BitmapUtils.decodeBitmap(file.absolutePath, targetWidth, targetHeight)
        }.getOrNull()?.also { topBarWallpaperCache.put(key, it) }
        ?: return null
    return TopBarWallpaperDrawable(
        bitmap = bitmap,
        radius = radius,
        alphaPercent = alphaPercent,
        // 前景取色要把壁纸平均色与底色合成后再判深浅（见 resolveTopBarContentColor），
        // 这里顺带把平均色带上，取色时不必再按文件路径查一次
        averageColor = TopBarConfig.wallpaperAverageColor(file),
    )
}

/**
 * 顶栏壁纸 Drawable。
 * 使用 BitmapShader 平铺壁纸图片，支持圆角裁剪和透明度。
 * 图片以 center-crop 方式缩放填充。
 */
private class TopBarWallpaperDrawable(
    private val bitmap: Bitmap,
    private val radius: Float,
    alphaPercent: Int,
    /** 壁纸平均色（不含 alpha），供取色方与底色合成；解不出来时为 null */
    private val averageColor: Int?,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        alpha = TopBarConfig.opacityToAlpha(alphaPercent)
    }
    private val rect = RectF()
    private val matrix = Matrix()
    private val path = Path()

    /**
     * 代表色（含 alpha）：平均色按**当前**透明度合成。
     *
     * 取色方（[resolveSolidColor]）据此把壁纸层与底色层叠起来判深浅；用当前 alpha 而非构造时
     * 记下的值，是为了之后有人对此 Drawable 调 [setAlpha] 时取色不会停在旧透明度上。
     * 解不出平均色时为 null，解析方会跳过这一层（回退顶栏底色）。
     */
    val solidColor: Int?
        get() = averageColor?.let {
            Color.argb(paint.alpha, Color.red(it), Color.green(it), Color.blue(it))
        }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
        rect.set(bounds)
        val scale = maxOf(
            bounds.width() / bitmap.width.toFloat(),
            bounds.height() / bitmap.height.toFloat(),
        )
        val dx = bounds.left + (bounds.width() - bitmap.width * scale) / 2f
        val dy = bounds.top + (bounds.height() - bitmap.height * scale) / 2f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        paint.shader?.setLocalMatrix(matrix)
        path.reset()
        path.addRoundRect(
            rect,
            floatArrayOf(
                0f,
                0f,
                0f,
                0f,
                radius,
                radius,
                radius,
                radius,
            ),
            Path.Direction.CW,
        )
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android SDK")
    override fun getOpacity(): Int = if (paint.alpha >= 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = -1

    override fun getIntrinsicHeight(): Int = -1
}
