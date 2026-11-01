package io.legado.app.utils

/**
 * 计算下拉菜单(PopupWindow)的纵向偏移量。
 *
 * 系统的 [android.widget.PopupWindow.showAsDropDown] 只有在 PopupWindow 设置了确定高度时
 * 才会在下方空间不足时自动翻转到锚点上方;当高度为 WRAP_CONTENT 时该翻转逻辑失效,菜单会被放在
 * 锚点下方而落到屏幕外。这里用测量得到的真实高度自行决定:
 *
 * - 默认显示在锚点下方,偏移为 [gap]。
 * - 当下方放不下且上方空间更充足时,翻转到锚点上方,偏移为 `-(popupHeight + anchorHeight + gap)`,
 *   使菜单底部距锚点顶部留出 [gap] 间距。
 *
 * 所有坐标均为屏幕坐标(像素)。
 *
 * @param anchorTop    锚点在屏幕上的顶部 Y 坐标
 * @param anchorHeight 锚点高度
 * @param popupHeight  菜单测量后的高度
 * @param frameTop     可视区域(去除状态栏/导航栏)顶部 Y 坐标
 * @param frameBottom  可视区域底部 Y 坐标
 * @param gap          菜单与锚点之间的期望间距
 */
fun resolveDropDownYOffset(
    anchorTop: Int,
    anchorHeight: Int,
    popupHeight: Int,
    frameTop: Int,
    frameBottom: Int,
    gap: Int
): Int {
    val anchorBottom = anchorTop + anchorHeight
    val spaceBelow = frameBottom - anchorBottom
    val spaceAbove = anchorTop - frameTop
    val fitsBelow = popupHeight + gap <= spaceBelow
    val showAbove = !fitsBelow && spaceAbove > spaceBelow
    return if (showAbove) {
        -(popupHeight + anchorHeight + gap)
    } else {
        gap
    }
}
