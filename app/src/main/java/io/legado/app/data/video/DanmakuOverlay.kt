package io.legado.app.data.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 弹幕叠加层视图：Canvas 渲染，叠加在播放器上方。
 *
 * 用法：
 * - `setDanmaku(items)` 设置弹幕数据（按时间排序）
 * - `updateTime(timeMs)` 每帧/每秒由播放器进度回调驱动
 * - `seekTo(timeMs)` 拖动进度条时清空进行中的弹幕
 * - `pause()/resume()` 暂停恢复动画
 *
 * 渲染规则：
 * - 滚动弹幕：从右向左匀速移动（约 6.5s 横穿屏幕），多车道分配
 * - 顶部/底部弹幕：固定位置显示 5s，最多 3 条堆叠
 */
class DanmakuOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class ActiveDanmaku(
        val item: DanmakuItem,
        val spawnAtMs: Float,
        val lane: Int,
        val widthPx: Float,
    )

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 1f, 1f, Color.argb(160, 0, 0, 0))
    }
    private val laneHeights = mutableListOf<Float>()

    private val scrollItems = mutableListOf<ActiveDanmaku>()
    private val fixedItems = mutableListOf<ActiveDanmaku>()

    private var allItems: List<DanmakuItem> = emptyList()
    private var cursorIndex = 0
    private var lastTimeMs = 0f

    var renderEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var show: Boolean = true
    var speedFactor: Float = 1.0f
    var textSizeFactor: Float = 1.0f
    var maxLanes: Int = 8

    private val scrollCrossMs: Float get() = 6500f / speedFactor
    private val fixedShowMs = 5000f

    /** 上次 updateTime 的时间戳（纳秒），用于帧间插值 */
    private var lastUpdateNanos: Long = System.nanoTime()

    fun setDanmaku(items: List<DanmakuItem>) {
        allItems = items.sortedBy { it.time }
        cursorIndex = 0
        lastTimeMs = 0f
        scrollItems.clear()
        fixedItems.clear()
        invalidate()
    }

    fun clear() {
        allItems = emptyList()
        cursorIndex = 0
        scrollItems.clear()
        fixedItems.clear()
        invalidate()
    }

    fun seekTo(timeMs: Long) {
        lastTimeMs = timeMs.toFloat()
        scrollItems.clear()
        fixedItems.clear()
        // 二分定位到新时间点
        cursorIndex = findIndexAt(timeMs / 1000f)
        invalidate()
    }

    fun pause() {
        // 保留当前状态即可，暂停期间不消费时间
    }

    fun resume() {
        // 重新开始动画循环
        postInvalidateOnAnimation()
    }

    /** 播放进度回调：timeMs 为当前播放位置 */
    fun updateTime(timeMs: Long) {
        if (!show || !renderEnabled) return
        val t = timeMs.toFloat()
        // 时间倒退（seek）时重置
        if (t < lastTimeMs - 500f) {
            seekTo(timeMs)
            return
        }
        lastTimeMs = t
        lastUpdateNanos = System.nanoTime()
        spawnUpTo(t / 1000f)
        if (scrollItems.isNotEmpty() || fixedItems.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    private fun findIndexAt(timeSec: Float): Int {
        var lo = 0
        var hi = allItems.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (allItems[mid].time <= timeSec) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun spawnUpTo(timeSec: Float) {
        if (width == 0) return
        while (cursorIndex < allItems.size && allItems[cursorIndex].time <= timeSec) {
            val item = allItems[cursorIndex]
            cursorIndex++
            textPaint.textSize = 42f * textSizeFactor
            val w = textPaint.measureText(item.text)
            when (item.mode) {
                0 -> {
                    val lane = allocateScrollLane(w, item.time)
                    if (lane in 0 until maxLanes) {
                        scrollItems.add(ActiveDanmaku(item, item.time, lane, w))
                    }
                }
                else -> {
                    val lane = allocateFixedLane()
                    if (lane >= 0) {
                        fixedItems.add(ActiveDanmaku(item, item.time, lane, w))
                    }
                }
            }
        }
    }

    private fun allocateScrollLane(textWidth: Float, timeSec: Float): Int {
        for (lane in 0 until maxLanes) {
            val last = scrollItems.lastOrNull { it.lane == lane } ?: return lane
            // 上一条弹幕尾端已离开右边缘一定距离即可复用车道
            val elapsed = (timeSec - last.spawnAtMs) * 1000f
            val laneSpeed = (width + last.widthPx) / scrollCrossMs
            val moved = elapsed * laneSpeed
            if (moved > last.widthPx + textWidth * 0.5f + 40f) return lane
        }
        return -1
    }

    private fun allocateFixedLane(): Int {
        val now = lastTimeMs / 1000f
        for (lane in 0 until 3) {
            val last = fixedItems.lastOrNull { it.lane == lane }
            if (last == null || (now - last.spawnAtMs) * 1000f > fixedShowMs) return lane
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!show || !renderEnabled) return
        computeLaneHeight()
        // 帧间插值：lastTimeMs + 距上次 updateTime 的实际流逝时间，保证滚动平滑
        val elapsedSinceUpdate = (System.nanoTime() - lastUpdateNanos) / 1_000_000f
        val nowMs = lastTimeMs + elapsedSinceUpdate

        // 滚动弹幕
        val scrollIter = scrollItems.iterator()
        while (scrollIter.hasNext()) {
            val d = scrollIter.next()
            val elapsed = (nowMs / 1000f - d.spawnAtMs) * 1000f
            val speed = (width + d.widthPx) / scrollCrossMs
            val x = width - elapsed * speed
            if (x < -d.widthPx) {
                scrollIter.remove()
                continue
            }
            drawItem(canvas, d.item, x, laneTop(d.lane))
        }

        // 顶部/底部弹幕
        val fixedIter = fixedItems.iterator()
        while (fixedIter.hasNext()) {
            val d = fixedIter.next()
            val elapsed = (nowMs / 1000f - d.spawnAtMs) * 1000f
            if (elapsed > fixedShowMs) {
                fixedIter.remove()
                continue
            }
            val x = (width - d.widthPx) / 2f
            val y = if (d.item.mode == 1) {
                laneTop(d.lane)
            } else {
                height - (d.lane + 1) * laneHeight() - 8f
            }
            drawItem(canvas, d.item, x, y)
        }

        if (scrollItems.isNotEmpty() || fixedItems.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawItem(canvas: Canvas, item: DanmakuItem, x: Float, y: Float) {
        textPaint.textSize = 42f * textSizeFactor
        textPaint.color = item.color
        canvas.drawText(item.text, x, y + textPaint.textSize, textPaint)
    }

    private fun computeLaneHeight() {
        textPaint.textSize = 42f * textSizeFactor
        val h = textPaint.textSize + 12f
        if (laneHeights.isEmpty() || abs(laneHeights[0] - h) > 0.5f) {
            laneHeights.clear()
            repeat(maxLanes + 3) { laneHeights.add(h) }
        }
    }

    private fun laneHeight(): Float = laneHeights.firstOrNull() ?: 54f

    private fun laneTop(lane: Int): Float = 8f + lane * laneHeight()
}
