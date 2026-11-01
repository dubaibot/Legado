package io.legado.app.ui.widget.recycler

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setEdgeEffectColor

/**
 * 管理页 recycler 初始化样板（R1c）：边缘光效着色 + adapter 装配 +
 * 滑动多选（页面打开即处于选择模式）+ 拖拽排序。
 * layoutManager/分割线/isCanDrag 初值等页面差异由调用方在调用前自行设置。
 *
 * 注意顺序:DragSelect 必须先于 ItemTouchHelper attach（后者需要让前者先判定选择手势）。
 */
fun RecyclerView.setupManagePage(
    adapter: RecyclerView.Adapter<*>,
    itemTouchCallback: ItemTouchCallback,
    dragSelectCallback: DragSelectTouchHelper.Callback? = null,
) {
    setEdgeEffectColor(context.primaryColor)
    this.adapter = adapter
    dragSelectCallback?.let {
        val dragSelectTouchHelper = DragSelectTouchHelper(it).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(this)
        dragSelectTouchHelper.activeSlideSelect()
    }
    ItemTouchHelper(itemTouchCallback).attachToRecyclerView(this)
}
