package io.legado.app.ui.main.explore

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ItemExploreSourceBinding
import io.legado.app.help.config.LocalConfig

/**
 * 新版发现书签样式书源网格
 */
class ExploreNewSourceAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<BookSourcePart, ItemExploreSourceBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemExploreSourceBinding {
        return ItemExploreSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemExploreSourceBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        val displayName = getDisplayName(item)
        binding.tvName.text = displayName
        binding.tvIcon.text = displayName.firstOrNull()?.toString() ?: ""
        binding.tvIcon.background = createIconBackground(item.bookSourceUrl)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemExploreSourceBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.openExplore(it)
            }
        }
        holder.itemView.setOnLongClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.editBookmark(it)
            } ?: false
        }
    }

    private fun getDisplayName(source: BookSourcePart): String {
        return LocalConfig.getExploreSourceName(source.bookSourceUrl)
            .ifBlank { source.bookSourceName }
    }

    private fun createIconBackground(key: String): GradientDrawable {
        val color = iconColors[
            (key.hashCode() and Int.MAX_VALUE) % iconColors.size
        ]
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    companion object {

        private val iconColors = intArrayOf(
            0xFFF44336.toInt(),
            0xFFE91E63.toInt(),
            0xFF9C27B0.toInt(),
            0xFF673AB7.toInt(),
            0xFF3F51B5.toInt(),
            0xFF2196F3.toInt(),
            0xFF00BCD4.toInt(),
            0xFF009688.toInt(),
            0xFF4CAF50.toInt(),
            0xFF8BC34A.toInt(),
            0xFFFF9800.toInt(),
            0xFFFF5722.toInt(),
            0xFF795548.toInt(),
            0xFF607D8B.toInt()
        )

    }

    interface CallBack {
        fun openExplore(source: BookSourcePart)

        /**
         * 长按编辑书签显示名
         */
        fun editBookmark(source: BookSourcePart): Boolean
    }
}
