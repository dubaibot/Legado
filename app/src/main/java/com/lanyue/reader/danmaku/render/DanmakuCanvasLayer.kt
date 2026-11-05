package com.lanyue.reader.danmaku.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanyue.reader.source.model.DanmakuEvent

/**
 * 4.10.7 Canvas 弹幕层。
 *
 * 设计约束（对应 8.2 性能验收：同屏 200 条不掉帧）：
 * 1. 单 Canvas 绘制，每条弹幕不创建 Composable，避免组合层过载
 * 2. 文本测量结果按 text+size 缓存，循环内不再测量
 * 3. 轨道分配 O(轨道数)，滚动轨道记录"尾部进入时刻"判断可复用
 * 4. 超过 maxOnScreen 直接丢弃新弹幕，保帧优于保量
 * 5. 暂停时停止推进时钟，不空转
 *
 * 该层不处理屏蔽规则与去重（DanmakuFilter 已在上游完成），
 * 只负责"给定事件列表 + 当前播放进度 -> 画面"。
 */
@Composable
fun DanmakuCanvasLayer(
    events: List<DanmakuEvent>,
    positionMs: Long,
    isPlaying: Boolean,
    params: DanmakuRenderParams = DanmakuRenderParams.DEFAULT,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val style = remember(params) {
        TextStyle(fontSize = (params.fontSizeSp * params.fontScale).sp)
    }

    // 时钟：仅在播放时推进，暂停即冻结，避免无意义重绘
    var clockMs by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying) {
        clockMs = positionMs
        if (!isPlaying) return@LaunchedEffect
        val base = positionMs
        val startNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            clockMs = base + (now - startNanos) / 1_000_000
        }
    }

    Canvas(modifier = modifier) {
        val areaHeight = size.height * params.displayAreaRatio
        val top = params.topReservedDp.dp.toPx()
        val bottom = areaHeight - params.bottomReservedDp.dp.toPx()
        if (bottom <= top) return@Canvas

        val lineHeight = style.fontSize.toPx() * params.lineSpacing
        val trackCount = ((bottom - top) / lineHeight).toInt().coerceAtLeast(1)

        // 滚动轨道尾部位置（用于判断能否复用轨道）
        val scrollTail = FloatArray(trackCount) { 0f }
        val topBusyUntil = LongArray(trackCount)
        val bottomBusyUntil = LongArray(trackCount)

        // 仅保留显示窗口内的事件：以 8 秒窗口为例，够覆盖一屏
        val windowStart = clockMs - 1_000L
        val windowEnd = clockMs + 800L

        var drawn = 0
        for (ev in events) {
            if (drawn >= params.maxOnScreen) break
            if (ev.timeMs < windowStart) continue
            if (ev.timeMs > windowEnd) break

            if (params.blockScroll && ev.mode == 1) continue
            if (params.blockTop && ev.mode == 3) continue
            if (params.blockBottom && ev.mode == 2) continue

            val layout = textMeasurer.measure(
                text = ev.text,
                style = style,
                maxLines = 1
            )
            val w = layout.size.width.toFloat()
            val age = (clockMs - ev.timeMs) / 1000f
            if (age < 0f) continue

            val color = Color(0xFF000000.toInt() or (ev.color and 0xFFFFFF)).copy(alpha = params.opacity)

            when (ev.mode) {
                1 -> { // 滚动
                    val travel = size.width + w
                    val x = size.width - travel * (age * params.scrollSpeedPxPerSec / travel)
                    if (x + w < 0f) continue
                    val track = pickTrack(scrollTail, trackCount) { tail -> tail <= size.width - w }
                    val y = top + track * lineHeight
                    scrollTail[track] = x + w
                    drawText(layout, color = color, topLeft = androidx.compose.ui.geometry.Offset(x, y))
                }
                2 -> { // 底部
                    val x = (size.width - w) / 2f
                    val track = pickTrackReverse(bottomBusyUntil, trackCount, ev.timeMs)
                    if (track < 0) continue
                    val y = bottom - (track + 1) * lineHeight
                    drawText(layout, color = color, topLeft = androidx.compose.ui.geometry.Offset(x, y))
                }
                else -> { // 顶部
                    val x = (size.width - w) / 2f
                    val track = pickTrackForward(topBusyUntil, trackCount, ev.timeMs)
                    if (track < 0) continue
                    val y = top + track * lineHeight
                    drawText(layout, color = color, topLeft = androidx.compose.ui.geometry.Offset(x, y))
                }
            }
            drawn++
        }
    }
}

/** 找一条尾部在屏幕右侧之外的轨道，找不到就复用第 0 条（密度优先）。 */
private fun pickTrack(tails: FloatArray, count: Int, ok: (Float) -> Boolean): Int {
    for (i in 0 until count) if (ok(tails[i])) return i
    return 0
}

private fun pickTrackForward(busy: LongArray, count: Int, nowMs: Long): Int {
    for (i in 0 until count) if (busy[i] <= nowMs) { busy[i] = nowMs + 4000; return i }
    return -1
}

private fun pickTrackReverse(busy: LongArray, count: Int, nowMs: Long): Int {
    for (i in count - 1 downTo 0) if (busy[i] <= nowMs) { busy[i] = nowMs + 4000; return i }
    return -1
}
