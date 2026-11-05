package com.lanyue.reader.danmaku.render

/**
 * 4.10.7 渲染参数。
 * 速度、不透明度、字号缩放、显示区域、顶部/底部保留区、同屏密度、合并重复、屏蔽规则。
 */
data class DanmakuRenderParams(
    /** 滚动速度：每秒横穿屏幕的屏幕宽度倍数 */
    val scrollSpeedPxPerSec: Float = 240f,
    val opacity: Float = 0.9f,
    val fontSizeSp: Float = 15f,
    val fontScale: Float = 1f,
    /** 显示区域占屏幕高度的比例（0~1） */
    val displayAreaRatio: Float = 1f,
    val topReservedDp: Int = 0,
    val bottomReservedDp: Int = 40,
    /** 同屏最大条数，超出丢弃（保帧） */
    val maxOnScreen: Int = 200,
    val mergeDuplicates: Boolean = true,
    /** 屏蔽规则：关键词 */
    val blockKeywords: List<String> = emptyList(),
    /** 屏蔽规则：正则 */
    val blockRegexes: List<String> = emptyList(),
    val blockScroll: Boolean = false,
    val blockTop: Boolean = false,
    val blockBottom: Boolean = false,
    /** 轨道行高倍数 */
    val lineSpacing: Float = 1.2f
) {
    companion object {
        /** 8.2 验收：中端机 60fps，同屏 200 条无掉帧。 */
        val DEFAULT = DanmakuRenderParams()
    }
}
