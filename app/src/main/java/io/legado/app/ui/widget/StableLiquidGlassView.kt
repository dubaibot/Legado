package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StableLiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var sourceView: ViewGroup? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private var blurRadius: Float = 24f * resources.displayMetrics.density
    private var tintAlpha: Float = 0f
    private var tintRed: Float = 0f
    private var tintGreen: Float = 0f
    private var tintBlue: Float = 0f
    private var cornerRadius: Float = 0f

    fun beginBatchUpdate() {}

    fun endBatchUpdate() {
        applyBlurEffect()
    }

    fun bind(source: ViewGroup?) {
        sourceView = source
        startCapture()
    }

    fun release() {
        captureJob?.cancel()
        sourceView = null
    }

    fun isReleased(): Boolean = sourceView == null

    fun setCornerRadius(value: Float) {
        cornerRadius = value
    }

    fun setRefractionHeight(value: Float) {}

    fun setRefractionOffset(value: Float) {}

    fun setTintAlpha(value: Float) {
        tintAlpha = value
    }

    fun setTintColorRed(value: Float) {
        tintRed = value
    }

    fun setTintColorGreen(value: Float) {
        tintGreen = value
    }

    fun setTintColorBlue(value: Float) {
        tintBlue = value
    }

    fun setDispersion(value: Float) {}

    fun setBlurRadius(value: Float) {
        blurRadius = value
    }

    fun setDraggableEnabled(enabled: Boolean) {}

    fun setElasticEnabled(enabled: Boolean) {}

    fun setTouchEffectEnabled(enabled: Boolean) {}

    fun refresh() {
        applyBlurEffect()
        startCapture()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyBlurEffect()
        startCapture()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    private fun applyBlurEffect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = blurRadius.coerceAtLeast(1f)
        setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }

    private fun startCapture() {
        val source = sourceView ?: return
        if (width <= 0 || height <= 0) return
        captureJob?.cancel()
        captureJob = scope.launch {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val glassLoc = IntArray(2)
            getLocationOnScreen(glassLoc)
            val sourceLoc = IntArray(2)
            source.getLocationOnScreen(sourceLoc)
            val rect = Rect(
                glassLoc[0] - sourceLoc[0],
                glassLoc[1] - sourceLoc[1],
                glassLoc[0] - sourceLoc[0] + width,
                glassLoc[1] - sourceLoc[1] + height
            )
            val canvas = Canvas(bitmap)
            canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
            source.draw(canvas)
            // 应用 tint 叠加色
            if (tintAlpha > 0f) {
                canvas.drawColor(
                    Color.argb(
                        (tintAlpha * 255).toInt().coerceIn(0, 255),
                        (tintRed * 255).toInt().coerceIn(0, 255),
                        (tintGreen * 255).toInt().coerceIn(0, 255),
                        (tintBlue * 255).toInt().coerceIn(0, 255)
                    ),
                    PorterDuff.Mode.SRC_IN
                )
            }
            background = BitmapDrawable(resources, bitmap)
        }
    }
}
