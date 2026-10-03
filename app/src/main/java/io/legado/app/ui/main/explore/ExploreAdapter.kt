package io.legado.app.ui.main.explore

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.collection.LruCache
import androidx.core.view.isGone
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ItemFindBookBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.InfoMap
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import kotlinx.coroutines.CoroutineScope
import splitties.views.onLongClick

class ExploreAdapter(
    context: Context,
    val callBack: CallBack,
    private val headerGone: Boolean = false
) : RecyclerAdapter<BookSourcePart, ItemFindBookBinding>(context) {
    companion object {
        val exploreInfoMapList = LruCache<String, InfoMap>(99)
    }

    private val renderer = ExploreKindRenderer(
        context, callBack.scope,
        object : ExploreKindRenderer.Callback {
            override fun onKindClick(view: View, sourceUrl: String, kind: ExploreKind) {
                callBack.openExplore(sourceUrl, kind.title, kind.url)
            }

            override fun onSelectOptionClick(
                view: View, sourceUrl: String, kind: ExploreKind, option: String
            ) {
            }

            override fun onRequestRefresh(sourceUrl: String) {
                val pos = exIndex
                if (pos < 0) return
                val item = getItem(pos) ?: return
                if (item.bookSourceUrl != sourceUrl) return
                Coroutine.async(callBack.scope) {
                    item.clearExploreKindsCache()
                    sourceKinds[sourceUrl] = item.exploreKinds()
                }.onSuccess {
                    notifyItemChanged(pos, false)
                }
            }
        }
    )

    private var exIndex = -1
    private var scrollTo = -1
    private val sourceKinds = HashMap<String, List<ExploreKind>>()

    override fun getViewBinding(parent: ViewGroup): ItemFindBookBinding {
        return ItemFindBookBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemFindBookBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        binding.run {
            llTitle.isGone = headerGone
            if (holder.layoutPosition == itemCount - 1) {
                root.setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            } else {
                root.setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 0)
            }
            if (payloads.isEmpty()) {
                tvName.text = item.bookSourceName
            }
            if (exIndex == holder.layoutPosition) {
                ivStatus.setImageResource(R.drawable.ic_arrow_down)
                rotateLoading.loadingColor = context.accentColor
                rotateLoading.visible()
                Coroutine.async(callBack.scope) {
                    sourceKinds[item.bookSourceUrl]?.also {
                        return@async it
                    }
                    item.exploreKinds().also {
                        sourceKinds[item.bookSourceUrl] = it
                    }
                }.onSuccess { kindList ->
                    renderer.render(flexbox, kindList, item.bookSourceUrl, ExploreKindRenderer.Mode.TRADITIONAL)
                }.onFinally {
                    rotateLoading.gone()
                    if (scrollTo >= 0) {
                        callBack.scrollTo(scrollTo)
                        scrollTo = -1
                    }
                }
            } else kotlin.runCatching {
                ivStatus.setImageResource(R.drawable.ic_arrow_right)
                rotateLoading.gone()
                renderer.recycle(flexbox)
                flexbox.gone()
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemFindBookBinding) {
        binding.apply {
            llTitle.setOnClickListener {
                val position = holder.layoutPosition
                val oldEx = exIndex
                exIndex = if (exIndex == position) -1 else position
                notifyItemChanged(oldEx, false)
                if (exIndex != -1) {
                    scrollTo = position
                    callBack.scrollTo(position)
                    notifyItemChanged(position, false)
                }
            }
            llTitle.onLongClick {
                showMenu(binding, holder.layoutPosition)
            }
        }
    }

    fun compressExplore(): Boolean {
        return if (exIndex < 0) {
            false
        } else {
            val oldExIndex = exIndex
            exIndex = -1
            notifyItemChanged(oldExIndex)
            true
        }
    }

    /**
     * 展开指定行渲染kinds,供侧边页复用
     */
    fun expand(position: Int) {
        exIndex = position
        scrollTo = position
        notifyItemChanged(position, false)
    }

    fun onPause() {
        sourceKinds.clear()
        ExploreKindRenderer.saveInfoMaps()
    }

    private fun showMenu(binding: ItemFindBookBinding, position: Int): Boolean {
        val source = getItem(position) ?: return true
        val popupMenu = PopupMenu(context, binding.llTitle)
        popupMenu.inflate(R.menu.explore_item)
        popupMenu.menu.findItem(R.id.menu_login).isVisible = source.hasLoginUrl
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_edit -> callBack.editSource(source.bookSourceUrl)
                R.id.menu_top -> callBack.toTop(source)
                R.id.menu_search -> callBack.searchBook(source)
                R.id.menu_login -> context.startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", source.bookSourceUrl)
                }

                R.id.menu_refresh -> refreshExplore(source, position, binding)

                R.id.menu_del -> callBack.deleteSource(source)
            }
            true
        }
        popupMenu.show()
        return true
    }

    private fun refreshExplore(source: BookSourcePart, position: Int, binding: ItemFindBookBinding) {
        binding.rotateLoading.visible()
        Coroutine.async(callBack.scope) {
            source.clearExploreKindsCache()
            sourceKinds[source.bookSourceUrl] = source.exploreKinds()
        }.onSuccess {
            notifyItemChanged(position, false)
        }.onFinally {
            binding.rotateLoading.gone()
        }
    }

    interface CallBack {
        val scope: CoroutineScope
        fun scrollTo(pos: Int)
        fun openExplore(sourceUrl: String, title: String, exploreUrl: String?)
        fun editSource(sourceUrl: String)
        fun toTop(source: BookSourcePart)
        fun deleteSource(source: BookSourcePart)
        fun searchBook(bookSource: BookSourcePart)
    }
}
