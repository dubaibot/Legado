package io.legado.app.lib.theme

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter as ComposeColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.graphics.Paint
import io.legado.app.help.config.AppConfig
import kotlin.math.roundToInt

/**
 * 墨水屏渲染的图片灰阶化工具（View 与 Compose 共用）。
 *
 * 墨水屏只能显示灰阶，开启「启用墨水屏渲染」后所有位图（封面、书源/订阅源图标、
 * 底栏自定义图标…）都要以灰阶绘制，否则纯色平面里会散着一堆彩色图片。
 * 这里把它收口成一个可复用的 `ColorFilter`，两个端的取用方式：
 *
 * - View：`imageView.colorFilter = EInkRender.androidFilterOrNull()`，
 *   或包一层带同一张底图的 `BitmapDrawable`（见 `NavigationBarConfig`，避免改动缓存实例）
 * - Compose：`Image(painter, ..., colorFilter = EInkRender.composeFilterOrNull())`
 *
 * 三个刻意的取舍：
 * 1. 作用在**绘图层**而不是解码结果上——同一个 Bitmap 关掉开关后仍是彩色的，
 *    既不用清 Glide 的内存/磁盘缓存，也不会把灰度图写进磁盘缓存
 *    （在 Glide 请求上挂 `BitmapTransformation` 会多出一份缓存副本）。
 * 2. 日间/夜间都用同一套灰阶、不做反相：主流墨水屏设备的图片在前光开启时也仍是灰阶，
 *    反相会把照片类封面变成底片。
 * 3. 饱和度置 0（按感知加权算亮度），比直接取某个通道更接近墨水屏的 16 级灰阶观感。
 *
 * 开关是全局态、且切换会重建界面，调用点不需要自己缓存结果，每次绘制现取即可。
 */
object EInkRender {

    private val androidGrayscale: ColorFilter by lazy {
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    private val composeGrayscale: ComposeColorFilter by lazy {
        ComposeColorFilter.colorMatrix(ComposeColorMatrix().apply { setToSaturation(0f) })
    }

    /** 是否按墨水屏渲染图片 */
    val enabled: Boolean
        get() = AppConfig.isEInkMode

    /** View 侧：开启墨水屏渲染时返回灰阶 ColorFilter，否则返回 null（可直接赋给 `colorFilter`） */
    fun androidFilterOrNull(): ColorFilter? = if (enabled) androidGrayscale else null

    /** Compose 侧：开启墨水屏渲染时返回灰阶 ColorFilter，否则返回 null */
    fun composeFilterOrNull(): ComposeColorFilter? = if (enabled) composeGrayscale else null

    /**
     * View 侧的根层合成 Paint。
     *
     * 只有这一个实例、只设置一次滤镜，所以重复 [applyRootLayer] 不会重建图层
     * （`View.setLayerType` 只在 layerType 变化时才真正重建）。
     */
    private val viewLayerPaint: android.graphics.Paint by lazy {
        android.graphics.Paint().apply { colorFilter = androidGrayscale }
    }

    /**
     * 墨水屏渲染的 View 侧入口：把整个窗口内容按灰阶合成。
     *
     * View 体系没有"逐控件统一滤镜"的位置——位图可以逐个挂 `colorFilter`，
     * 但文字里的彩色表情字形、自绘的正文插图这类内容只能靠一层整窗合成罩住。
     * 挂在窗口的**内容根**（`android.R.id.content` / 弹窗内容）上即可覆盖该窗口的一切内容，
     * 包括后来才加进去的子 View（ComposeView 也在其中）。
     *
     * 用 [View.LAYER_TYPE_HARDWARE]：图层由 RenderNode 承担，内容不变时不重画；
     * 关闭开关时恢复 [View.LAYER_TYPE_NONE]，不会把滤镜留在窗口上。
     *
     * @param view 窗口内容根；为 null（窗口还没建好）时什么都不做
     */
    /**
     * 不做整层灰阶的窗口黑名单。
     *
     * 视频画面走独立的 Surface 输出层，离屏合成会与图层对不上（表现为画面不显示或错位），
     * 而且墨水屏本来就放不了视频，保持彩色不影响观感。
     */
    private val rootLayerExcludedActivities = setOf(
        "io.legado.app.ui.video.VideoPlayerActivity",
    )

    /** Activity 窗口是否允许整层灰阶；见 [rootLayerExcludedActivities] */
    fun supportsRootLayer(activity: Activity): Boolean = activity::class.java.name !in rootLayerExcludedActivities

    fun applyRootLayer(view: View?) {
        if (view == null) return
        if (enabled) {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, viewLayerPaint)
        } else {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }
}

/**
 * Compose 侧：把这段内容整体按墨水屏灰阶绘制。
 *
 * 用于**图片之外**的少量内容——书源名 / 分类名 / 条目信息里带的表情符号（🎇🔥⭐🕐…）
 * 是字体里的彩色字形，`colorFilter` 参数对它们无效（`Text` 也没有滤镜入口），
 * 只能靠一次 `saveLayer` 上色滤镜把整段绘制罩住。
 *
 * 图层面积等于修饰符作用到的区域，所以要贴在**具体的名称文本**上，
 * 不要贴在整页 / 整个列表项上（整页离屏合成会带来 10MB 级的额外缓冲与填充开销）。
 * 关闭墨水屏渲染时直接返回原修饰符，零开销。
 *
 * 两类例外（细则见 `docs/project-rules/compose/theme-styles.md` §7.10）：
 * - **另开窗口的 Compose 弹层**（`AlertDialog` / `DropdownMenu` / `ModalBottomSheet`）不继承根层，
 *   要在弹层内容上再罩一层；
 * - 其中 `ModalBottomSheet` **必须贴在内容根**（`content` 里最外层容器的 modifier），
 *   **不能**贴在 `ModalBottomSheet(modifier = ...)`：sheet 宿主自带 `offset` 平移，图层按节点尺寸
 *   从原点截取，会把平移后的内容裁掉下半截。
 */
fun Modifier.eInkGrayscale(): Modifier {
    val filter = EInkRender.composeFilterOrNull() ?: return this
    return drawWithContent {
        drawContext.canvas.saveLayer(
            Rect(Offset.Zero, size),
            Paint().apply { colorFilter = filter },
        )
        drawContent()
        drawContext.canvas.restore()
    }
}

/**
 * View 侧：把这段文本里的**彩色字形**（表情符号）按墨水屏灰阶绘制。
 *
 * 与 [Modifier.eInkGrayscale] 同一个目的，用在**窗口另开、应用拿不到引用**的 View 文本上。
 * 典型场景是 AppCompat 的菜单弹窗（发现页的分组下拉就是它）：窗口由框架内部创建，
 * 应用连引用都没有，挂不了整层灰阶。标题里的普通文字不用管——灰阶色板已经把它变灰，
 * 只有表情符号是字体里的彩色字形（用户把分组名写成「🎧有声」），不跟色板走。
 *
 * **只在文本含非 BMP 字符（代理对，表情符号基本都是）时才挂 span**——这不是优化而是必须：
 * 换绘制方式的做法只在**非 boring 的布局**下生效，纯 BMP 的短标题会走 `BoringLayout`，
 * 那条路径不调用替换 span 的 `draw`，标题会整条变成空白（2026-10-13 实测：同一个菜单里
 * 带表情的项正常、不带表情的项消失）。只含 BMP 表情（如 ❤️）的标题因此保持彩色，是刻意的稳妥取舍。
 */
fun CharSequence.eInkGrayscale(): CharSequence {
    val filter = EInkRender.androidFilterOrNull() ?: return this
    if (!hasAstralChar(this)) return this
    val spannable = if (this is Spannable) this else SpannableString(this)
    spannable.setSpan(GrayscaleSpan(filter), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return spannable
}

/** 是否含非 BMP 字符（代理对），即表情符号这类字体自带彩色字形的字符 */
private fun hasAstralChar(text: CharSequence): Boolean = text.any { it.isHighSurrogate() || it.isLowSurrogate() }

/**
 * 把 span 覆盖的文本用一层灰阶滤镜重画。
 *
 * 必须是 [ReplacementSpan]：给文本 paint 挂 `colorFilter` 的办法对表情符号无效——
 * 实测同一个菜单里 `paint.colorFilter` 只把汉字变灰，彩色字形照旧是彩色
 * （`updateDrawState` 改的是那次绘制的 paint，表情字形不走它）。
 * 只有把这段绘制整体落到离屏图层上再合成，滤镜才会罩住表情符号——与 Compose 侧
 * [Modifier.eInkGrayscale] 的 `saveLayer` 同理。
 */
private class GrayscaleSpan(private val filter: ColorFilter) : ReplacementSpan() {

    /** 合成用的滤镜 Paint，跟整窗那层一样只建一次，不在绘制路径里分配 */
    private val layerPaint = android.graphics.Paint().apply { colorFilter = filter }

    override fun getSize(
        paint: android.graphics.Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: android.graphics.Paint.FontMetricsInt?,
    ): Int = paint.measureText(text, start, end).roundToInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: android.graphics.Paint,
    ) {
        val width = paint.measureText(text, start, end)
        // 表情字形可能略微超出字体的 top/bottom，图层上下各留一点余量，避免被裁掉
        val pad = (bottom - top) * 0.25f
        val save = canvas.saveLayer(x, top - pad, x + width, bottom + pad, layerPaint)
        // 转成 String 再画：避免 Canvas.drawText(CharSequence…) 又解析一遍 span（会递归回来）
        canvas.drawText(text.subSequence(start, end).toString(), x, y.toFloat(), paint)
        canvas.restoreToCount(save)
    }
}
