package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 发现列表
 */
class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>(),
    ExploreShowAdapter.CallBack {
    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private val isNewStyle: Boolean
        get() = intent.getBooleanExtra("newStyle", false)
    private val listAdapter by lazy { ExploreShowAdapter(this, this) }
    private val gridAdapter by lazy { ExploreShowGridAdapter(this, this) }
    private var isGridStyle = false
    private val adapter: RecyclerAdapter<SearchBook, out ViewBinding>
        get() = if (isGridStyle) gridAdapter else listAdapter
    private val loadMoreView by lazy { LoadMoreView(this) }
    private val loadMoreViewTop by lazy { LoadMoreView(this) }
    private var oldPage = -1
    private var isClearAll = false
    private val menuPage by lazy {
        binding.titleBar.menu.add(getString(R.string.menu_page, 1)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val page = viewModel.pageLiveData.value ?: 1
                NumberPickerDialog(this@ExploreShowActivity)
                    .setTitle(getString(R.string.change_page))
                    .setMaxValue(999)
                    .setMinValue(1)
                    .setValue(page)
                    .show {
                        if (page != it) {
                            if (oldPage == -1 && it != 1) { //初次添加头
                                adapter.addHeaderView {
                                    ViewLoadMoreBinding.bind(loadMoreViewTop)
                                }
                            } else if (it != 1) { //把头显示出来
                                val layoutParams = loadMoreViewTop.layoutParams
                                if (layoutParams?.height == 0) {
                                    layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                    loadMoreViewTop.layoutParams = layoutParams
                                }
                            }
                            oldPage = it
                            viewModel.skipPage(it)
                            isClearAll = true
                            adapter.clearItems() //清空，然后会自动触发scrollToBottom
                            if (!loadMoreView.hasMore) { //强制触发
                                scrollToBottom(true)
                            }
                        }
                    }
                true
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        viewModel.sourceData.observe(this) {
            initRecyclerView(
                it?.exploreStyle == 1 || intent.getBooleanExtra("newStyle", false)
            )
        }
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.kindsData.observe(this) { upBigCategoryBar() }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(this) {
            loadMoreViewTop.error(it)
        }
        viewModel.pageLiveData.observe(this) {
            menuPage.title = getString(R.string.menu_page, it)
        }
    }

    private fun initRecyclerView(isGrid: Boolean) {
        isGridStyle = isGrid
        if (isGrid) {
            val gridLayoutManager = GridLayoutManager(this, 3)
            gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    //Header和Footer占满整行
                    val isHeaderOrFooter = position < adapter.getHeaderCount()
                            || position >= adapter.itemCount - adapter.getFooterCount()
                    return if (isHeaderOrFooter) gridLayoutManager.spanCount else 1
                }
            }
            binding.recyclerView.layoutManager = gridLayoutManager
        } else {
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            binding.recyclerView.addItemDecoration(VerticalDivider(this))
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.startLoad()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf(it to null))
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                } else if (!recyclerView.canScrollVertically(-1) && dy < 0) {
                    scrollToTop()
                }
            }
        })
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((loadMoreView.hasMore && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreView.hasMore()
            viewModel.explore()
        }
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if ((oldPage > 1 && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreViewTop.hasMore()
            oldPage--
            viewModel.explore(oldPage)
        }
    }

    private fun upData(books: List<SearchBook>) {
        loadMoreView.stopLoad()
        if (books.isEmpty() && adapter.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            adapter.setItems(books)
            if (isClearAll) { //全清空后,加了头,位置下移一个
                val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
                layoutManager.scrollToPositionWithOffset(1, 0)
                isClearAll = false
            }
        }
    }

    private fun upDataTop(books: List<SearchBook>) {
        loadMoreViewTop.stopLoad()
        adapter.addItems(0, books)
        val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
        if (layoutManager.findFirstVisibleItemPosition() <= 1) { //顶部刷新,未滚动，矫正位置
            layoutManager.scrollToPositionWithOffset(books.size, 0)
        }
        if (oldPage <= 1) { //已到顶,隐藏头
            val layoutParams = loadMoreViewTop.layoutParams
            if (layoutParams != null) {
                layoutParams.height = 0
                loadMoreViewTop.layoutParams = layoutParams
            }
        }
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun showBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    override fun readNow(book: SearchBook) {
        viewModel.readNow(book) { bookUrl ->
            startActivity<ReadBookActivity> {
                putExtra("bookUrl", bookUrl)
                putExtra("inBookshelf", viewModel.isInBookShelf(book))
            }
        }
    }

    /**
     * 大分类栏,仅新版发现显示,末尾为调整分类入口
     */
    private fun upBigCategoryBar() {
        if (!isNewStyle) {
            binding.hsvBigCategory.gone()
            return
        }
        val adjusted = viewModel.getAdjustedCategories()
        val kinds = if (adjusted.isEmpty()) {
            viewModel.kinds
        } else {
            viewModel.kinds.filter { it.title in adjusted }
        }
        if (kinds.isEmpty()) {
            binding.hsvBigCategory.gone()
            return
        }
        binding.hsvBigCategory.visible()
        val inflater = layoutInflater
        binding.llBigCategory.removeAllViews()
        val currentUrl = viewModel.exploreUrl
        kinds.forEach { kind ->
            val tv = inflater.inflate(
                R.layout.item_quick_group, binding.llBigCategory, false
            ) as TextView
            tv.text = kind.title
            tv.isSelected = kind.url == currentUrl
            tv.setOnClickListener { onBigCategoryClick(kind) }
            binding.llBigCategory.addView(tv)
        }
        val add = inflater.inflate(
            R.layout.item_quick_group, binding.llBigCategory, false
        ) as TextView
        add.text = "＋"
        add.setOnClickListener { showAdjustCategoryDialog() }
        binding.llBigCategory.addView(add)
    }

    private fun onBigCategoryClick(kind: ExploreKind) {
        if (kind.url == viewModel.exploreUrl) {
            showSubCategoryDialog(kind)
            return
        }
        switchCategory(kind)
    }

    private fun switchCategory(kind: ExploreKind) {
        viewModel.switchCategory(kind)
        upBigCategoryBar()
        adapter.clearItems()
        loadMoreView.hasMore()
        scrollToBottom(true)
    }

    /**
     * 细分下钻,再点当前分类时展示
     */
    private fun showSubCategoryDialog(kind: ExploreKind) {
        val subKinds = viewModel.getSubCategories(kind.url)
        if (subKinds.isEmpty()) {
            toastOnUi(getString(R.string.explore_no_sub_category))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(kind.title)
            .setItems(subKinds.map { it.title }.toTypedArray()) { _, which ->
                switchCategory(subKinds[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 调整分类,按 sourceUrl 记录勾选的分类名,空为全部展示
     */
    private fun showAdjustCategoryDialog() {
        val allKinds = viewModel.kinds
        if (allKinds.isEmpty()) return
        val selected = viewModel.getAdjustedCategories()
        val checked = BooleanArray(allKinds.size) { allKinds[it].title in selected }
        AlertDialog.Builder(this)
            .setTitle(R.string.explore_adjust_category)
            .setMultiChoiceItems(allKinds.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.saveAdjustedCategories(
                    allKinds.filterIndexed { index, _ -> checked[index] }.map { it.title }
                )
                upBigCategoryBar()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
