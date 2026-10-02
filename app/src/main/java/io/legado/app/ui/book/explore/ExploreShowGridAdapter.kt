package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemExploreGridBinding
import io.legado.app.help.config.AppConfig

/**
 * 发现列表三列网格样式
 */
class ExploreShowGridAdapter(context: Context, val callBack: ExploreShowAdapter.CallBack) :
    RecyclerAdapter<SearchBook, ItemExploreGridBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemExploreGridBinding {
        return ItemExploreGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemExploreGridBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            bind(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bindChange(binding, item, bundle)
            }
        }
    }

    private fun bind(binding: ItemExploreGridBinding, item: SearchBook) {
        binding.run {
            tvName.text = item.name
            tvAuthor.text = item.author
            tvAuthor.isVisible = item.author.isNotBlank()
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            ivCover.load(
                item,
                AppConfig.loadCoverOnlyWifi
            )
        }
    }

    private fun bindChange(binding: ItemExploreGridBinding, item: SearchBook, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "isInBookshelf" -> ivInBookshelf.isVisible =
                        callBack.isInBookshelf(item)
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemExploreGridBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.showBookInfo(it)
            }
        }
    }
}
