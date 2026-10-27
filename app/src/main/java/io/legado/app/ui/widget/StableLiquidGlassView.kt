package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
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

    fun beginBatchUpdate() {}

    fun endBatchUpdate() {}

    fun bind(source: ViewGroup?) {
        sourceView = source
        startCapture()
    }

    fun release() {
        captureJob?.cancel()
        sourceView = null
    }

    fun isReleased(): Boolean = sourceView == null

    fun setCornerRadius(value: Float) {}

    fun setRefractionHeight(value: Float) {}

    fun setRefractionOffset(value: Float) {}

    fun setTintAlpha(value: Float) {}

    fun setTintColorRed(value: Float) {}

    fun setTintColorGreen(value: Float) {}

    fun setTintColorBlue(value: Float) {}

    fun setDispersion(value: Float) {}

    fun setBlurRadius(value: Float) {}

    fun setDraggableEnabled(enabled: Boolean) {}

    fun setElasticEnabled(enabled: Boolean) {}

    fun setTouchEffectEnabled(enabled: Boolean) {}

    fun refresh() {
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
        val density = resources.displayMetrics.density
        val radius = 24f * density
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
            background = BitmapDrawable(resources, bitmap)
        }
    }
}
