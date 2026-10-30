package io.legado.app.ui.main.explore

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.PopupMenu
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ItemExploreSourceBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivity

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
        val firstChar = displayName.firstOrNull()
        binding.tvIcon.text = if (firstChar != null && firstChar.isLetterOrDigit()) {
            firstChar.toString()
        } else {
            "书"
        }
        binding.tvIcon.background = createIconBackground(item.bookSourceUrl)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemExploreSourceBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.openExplore(it)
            }
        }
        holder.itemView.setOnLongClickListener {
            showMenu(binding, holder.bindingAdapterPosition - getHeaderCount())
            true
        }
    }

    private fun showMenu(binding: ItemExploreSourceBinding, position: Int): Boolean {
        val source = getItem(position) ?: return true
        val popupMenu = PopupMenu(context, binding.root)
        popupMenu.inflate(R.menu.explore_item)
        popupMenu.menu.findItem(R.id.menu_login).isVisible = source.hasLoginUrl
        popupMenu.menu.findItem(R.id.menu_refresh).isVisible = false
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_edit -> {
                    callBack.editSource(source.bookSourceUrl)
                }

                R.id.menu_top -> callBack.toTop(source)
                R.id.menu_bottom -> callBack.toBottom(source)
                R.id.menu_search -> callBack.searchBook(source)
                R.id.menu_login -> context.startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", source.bookSourceUrl)
                }
                R.id.menu_del -> callBack.deleteSource(source)
            }
            true
        }
        popupMenu.show()
        return true
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
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            setCornerRadius(12f.dpToPx())
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

        fun editSource(sourceUrl: String)

        fun toTop(source: BookSourcePart)

        fun toBottom(source: BookSourcePart)

        fun deleteSource(source: BookSourcePart)

        fun searchBook(source: BookSourcePart)
    }
}
