package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout

class StableLiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    fun beginBatchUpdate() {}

    fun endBatchUpdate() {}

    fun bind(source: ViewGroup?) {}

    fun release() {}

    fun isReleased(): Boolean = false

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
}
