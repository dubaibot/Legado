package io.legado.app.ui.main.explore

import android.content.Context
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ItemSourceIconGridBinding

class ExploreNewSourceAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<BookSourcePart, ItemSourceIconGridBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemSourceIconGridBinding {
        return ItemSourceIconGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSourceIconGridBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        binding.tvName.text = item.bookSourceName
        binding.ivIcon.load(name = item.bookSourceName)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSourceIconGridBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.openExplore(it)
            }
        }
    }

    interface CallBack {
        fun openExplore(source: BookSourcePart)
    }
}
