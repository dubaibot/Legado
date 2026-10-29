package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemPopupActionBinding
import io.legado.app.databinding.PopupActionBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.resolveDropDownYOffset
import io.legado.app.utils.setTintMutate
import splitties.systemservices.layoutInflater

class PopupAction(private val context: Context) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    val binding = PopupActionBinding.inflate(context.layoutInflater)
    val adapter by lazy {
        Adapter(context).apply {
            setHasStableIds(true)
        }
    }
    var onActionClick: ((action: String) -> Unit)? = null
    private var isVertical = false
    private var dangerValues: Set<String> = emptySet()

    /** 竖向菜单内任意项带 leading 图标时,所有行都保留图标列,让文字纵向对齐。 */
    private var reserveIconColumn = false

    /** 竖向菜单内任意项处于勾选状态时,所有行都保留 trailing 勾号列,避免未勾选项左挤。 */
    private var reserveCheckColumn = false

    /** 用真实 TextView 测量,保证用上主题字体(包括用户自定义 CJK 字体)。 */
    private val measureRow by lazy {
        ItemPopupActionBinding.inflate(context.layoutInflater).apply {
            textView.setPadding(0, 0, 0, 0)
            textView.minWidth = 0
            textView.minHeight = 0
        }
    }

    init {
        contentView = binding.root
        applyMd3PopupStyle()

        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true

        binding.recyclerView.adapter = adapter
    }

    /**
     * 重写下拉显示:先测量菜单真实高度,再决定显示在锚点下方还是上方。
     *
     * 系统的 [showAsDropDown] 在 PopupWindow 高度为 WRAP_CONTENT 时不会自动翻转到锚点上方,
     * 导致贴近屏幕底部的锚点(如底部操作栏的"更多"按钮、列表底部卡片的菜单按钮)弹出的菜单
     * 落到屏幕外不可见。这里用测量高度配合可视区域计算偏移,空间不足时翻转到上方。
     */
    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        if (anchor == null) {
            super.showAsDropDown(anchor, xoff, yoff, gravity)
            return
        }
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.AT_MOST)
        contentView.measure(widthSpec, heightSpec)
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val resolvedYOff = resolveDropDownYOffset(
            anchorTop = location[1],
            anchorHeight = anchor.height,
            popupHeight = contentView.measuredHeight,
            frameTop = frame.top,
            frameBottom = frame.bottom,
            gap = yoff
        )
        super.showAsDropDown(anchor, xoff, resolvedYOff, gravity)
    }

    fun setVertical(vertical: Boolean) {
        if (isVertical == vertical && binding.recyclerView.layoutManager != null) {
            return
        }
        isVertical = vertical
        binding.recyclerView.layoutManager = if (vertical) {
            LinearLayoutManager(context)
        } else {
            FlexboxLayoutManager(context).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
            }
        }
        if (adapter.itemCount > 0) {
            adapter.notifyDataSetChanged()
        }
    }

    fun setDangerValues(values: Set<String>) {
        if (dangerValues == values) {
            return
        }
        dangerValues = values
        if (adapter.itemCount > 0) {
            adapter.notifyDataSetChanged()
        }
    }

    fun setItems(items: List<SelectItem<String>>) {
        setActionItems(
            items.map { item ->
                PopupActionItem(
                    title = item.title,
                    value = item.value
                )
            }
        )
    }

    fun setActionItems(items: List<PopupActionItem>) {
        reserveIconColumn = isVertical && items.any { it.icon != null }
        reserveCheckColumn = isVertical && items.any { it.checked }
        adapter.setItems(items)
        updateMenuWidth(items)
    }

    /**
     * 竖向菜单按最长一项统一宽度(像原生下拉菜单),所有行等宽。
     * 横向 chips 仍按内容自适应。
     */
    private fun updateMenuWidth(items: List<PopupActionItem>) {
        binding.recyclerView.updateLayoutParams {
            width = if (isVertical) {
                measureVerticalMenuWidth(items)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun measureVerticalMenuWidth(items: List<PopupActionItem>): Int {
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val tv = measureRow.textView
        var maxText = 0
        for (item in items) {
            tv.text = item.title
            tv.measure(unspec, unspec)
            if (tv.measuredWidth > maxText) {
                maxText = tv.measuredWidth
            }
        }
        val horizontalPadding = 16.dpToPx() * 2
        val leadingCol = if (reserveIconColumn) 24.dpToPx() + 12.dpToPx() else 0
        val trailingCol = if (reserveCheckColumn) 24.dpToPx() + 8.dpToPx() else 0
        val content = horizontalPadding + leadingCol + trailingCol + maxText
        return content.coerceIn(112.dpToPx(), 280.dpToPx())
    }

    data class PopupActionItem(
        val title: String,
        val value: String,
        val icon: Drawable? = null,
        val enabled: Boolean = true,
        val checked: Boolean = false
    )

    inner class Adapter(context: Context) :
        RecyclerAdapter<PopupActionItem, ItemPopupActionBinding>(context) {

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getViewBinding(parent: ViewGroup): ItemPopupActionBinding {
            return ItemPopupActionBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemPopupActionBinding,
            item: PopupActionItem,
            payloads: MutableList<Any>
        ) {
            with(binding) {
                root.isEnabled = item.enabled
                root.alpha = if (item.enabled) 1f else 0.38f
                textView.text = item.title
                if (isVertical) {
                    root.updateLayoutParams { width = ViewGroup.LayoutParams.MATCH_PARENT }
                    root.minimumHeight = 45.dpToPx()
                    root.setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                    root.background = ContextCompat.getDrawable(context, R.drawable.selector_menu_item_bg)
                    textView.updateLayoutParams<LinearLayout.LayoutParams> {
                        width = 0
                        weight = 1f
                    }
                    textView.minHeight = 45.dpToPx()
                    textView.minWidth = 0
                    textView.gravity = Gravity.CENTER_VERTICAL
                    textView.setPadding(0, 0, 0, 0)
                    textView.background = null
                } else {
                    root.updateLayoutParams { width = ViewGroup.LayoutParams.WRAP_CONTENT }
                    root.minimumHeight = 0
                    root.setPadding(0, 0, 0, 0)
                    root.background = null
                    textView.updateLayoutParams<LinearLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                        weight = 0f
                    }
                    textView.minHeight = 0
                    textView.minWidth = 0
                    textView.gravity = Gravity.CENTER
                    textView.setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())
                    textView.setBackgroundResource(selectableItemBackgroundResId())
                }
                val textColor = if (item.value in dangerValues) {
                    context.getCompatColor(R.color.error)
                } else {
                    context.getCompatColor(R.color.primaryText)
                }
                textView.setTextColor(textColor)
                bindLeadingIcon(ivIcon, item, textColor)
                bindTrailingCheck(ivCheckEnd, item, textColor)
            }
        }

        private fun bindLeadingIcon(
            ivIcon: android.widget.ImageView,
            item: PopupActionItem,
            textColor: Int
        ) {
            when {
                item.icon != null -> {
                    ivIcon.setImageDrawable(item.icon)
                    item.icon.setTintMutate(textColor)
                    ivIcon.visibility = View.VISIBLE
                }

                isVertical && reserveIconColumn -> {
                    ivIcon.setImageDrawable(null)
                    ivIcon.visibility = View.INVISIBLE
                }

                else -> {
                    ivIcon.setImageDrawable(null)
                    ivIcon.visibility = View.GONE
                }
            }
        }

        private fun bindTrailingCheck(
            ivCheckEnd: android.widget.ImageView,
            item: PopupActionItem,
            textColor: Int
        ) {
            when {
                item.checked -> {
                    ivCheckEnd.setImageResource(R.drawable.ic_check)
                    ivCheckEnd.drawable?.setTintMutate(textColor)
                    ivCheckEnd.visibility = View.VISIBLE
                }

                isVertical && reserveCheckColumn -> {
                    ivCheckEnd.setImageDrawable(null)
                    ivCheckEnd.visibility = View.INVISIBLE
                }

                else -> {
                    ivCheckEnd.setImageDrawable(null)
                    ivCheckEnd.visibility = View.GONE
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemPopupActionBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.takeIf { it.enabled }?.let { item ->
                    onActionClick?.invoke(item.value)
                }
            }
        }

        private fun selectableItemBackgroundResId(): Int {
            val value = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            return value.resourceId
        }
    }

}
