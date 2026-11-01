package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.lib.theme.AppColorScheme
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

/**
 * 编辑页字段快速定位条：胶囊高亮随强调色（运行时取 scheme，换肤即生效），
 * 点击平滑滚动 RecyclerView 到字段、手动滚动反向高亮。
 * 取代书源/自动任务编辑器里逐字节重复的手搭实现。
 */
class FieldNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    private val group = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val pad = 4.dpToPx()
        setPadding(pad, pad, pad, pad)
    }
    private var selectedIndex = 0
    private var clickScrolling = false

    init {
        isHorizontalScrollBarEnabled = false
        addView(group, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setLabels(labels: List<String>) {
        group.removeAllViews()
        labels.forEachIndexed { index, label ->
            group.addView(buildPill(label, index))
        }
        selectedIndex = 0
        applyPillStyles()
        scrollTo(0, 0)
    }

    fun attachToRecyclerView(rv: RecyclerView) {
        // 点击→滚列表:近处(两屏内)平滑保手感;远处直接落位——平滑滚动逐像素刷过
        // 中间字段,途经超长 JS 字段(数万像素)一跳要数秒,锚点导航应当瞬时
        onItemClick = { index ->
            (rv.layoutManager as? LinearLayoutManager)?.let { lm ->
                val targetTop = lm.findViewByPosition(index)?.top
                val near = targetTop != null && rv.height > 0 &&
                        kotlin.math.abs(targetTop) <= rv.height * 2
                if (near) {
                    clickScrolling = true
                    val scroller = object : LinearSmoothScroller(context) {
                        override fun getVerticalSnapPreference() = SNAP_TO_START
                        override fun calculateDtToFit(
                            viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int,
                        ): Int = boxStart - viewStart + 4.dpToPx()
                    }
                    scroller.targetPosition = index
                    lm.startSmoothScroll(scroller)
                } else {
                    // 瞬时落位不产生滚动状态流转,IDLE 回调不会来:布局期的 onScrolled(0,0)
                    // 由 clickScrolling 挡住(保住点击高亮),post 在其后复位
                    clickScrolling = true
                    lm.scrollToPositionWithOffset(index, 4.dpToPx())
                    rv.post { clickScrolling = false }
                }
            }
        }
        // 滚列表→反向高亮
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (clickScrolling) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val first = lm.findFirstVisibleItemPosition()
                if (first != RecyclerView.NO_POSITION) select(first)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) clickScrolling = false
            }
        })
    }

    fun select(index: Int) {
        if (index == selectedIndex || index !in 0 until group.childCount) return
        selectedIndex = index
        applyPillStyles()
        group.getChildAt(index)?.let {
            smoothScrollTo((it.left - 16.dpToPx()).coerceAtLeast(0), 0)
        }
    }

    private var onItemClick: ((Int) -> Unit)? = null

    private fun buildPill(label: String, index: Int) = TextView(context).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        val h = 12.dpToPx()
        val v = 6.dpToPx()
        setPadding(h, v, h, v)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = 6.dpToPx() }
        setOnClickListener {
            // select() 在 index==selectedIndex 时提前返回（不重复应用样式/滚动自身），
            // 但 onItemClick 必须始终单独触发——否则 setLabels 重置为 0 后首次点击第 0 个
            // pill 会因“已选中”提前退出而不回调，导致 RecyclerView 收不到滚动请求。
            select(index)
            onItemClick?.invoke(index)
        }
    }

    private fun applyPillStyles() {
        val scheme = AppColorScheme.current
        val accent = context.accentColor
        val onAccent = if (ColorUtils.isColorLight(accent)) Color.BLACK else Color.WHITE
        for (i in 0 until group.childCount) {
            val pill = group.getChildAt(i) as? TextView ?: continue
            val selected = i == selectedIndex
            pill.setTextColor(if (selected) onAccent else scheme.onSurfaceVariant)
            pill.background = GradientDrawable().apply {
                cornerRadius = pill.resources.getDimension(R.dimen.radius_full)
                setColor(if (selected) accent else scheme.surfaceContainer)
            }
        }
    }
}
