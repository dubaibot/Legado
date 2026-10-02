package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.explore.ExploreAdapter
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

    private val listAdapter by lazy { ExploreShowAdapter(this, this) }
    private val gridAdapter by lazy { ExploreShowGridAdapter(this, this) }
    private var isGridStyle = false
    private val adapter: RecyclerAdapter<SearchBook, out ViewBinding>
        get() = if (isGridStyle) gridAdapter else listAdapter
    private val loadMoreView by lazy { LoadMoreView(this) }
    private val loadMoreViewTop by lazy { LoadMoreView(this) }
    private var oldPage = -1
    private var isClearAll = false

    private val tvPage by lazy {
        binding.titleBar.findViewById<TextView>(R.id.tv_page)
    }
    private val tvTitle by lazy {
        binding.titleBar.findViewById<TextView>(R.id.tv_title)
    }
    private var searchMenuItem: MenuItem? = null

    /**
     * 侧边页模式,null为添加大分类,非null为给该大分类添加细分类
     */
    private var drawerSubBig: CatRef? = null
    private var drawerInited = false
    private val drawerAdapter by lazy {
        ExploreAdapter(this, object : ExploreAdapter.CallBack {
            override val scope = lifecycleScope
            override fun scrollTo(pos: Int) {}
            override fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
                onDrawerKindClick(title, exploreUrl)
            }

            override fun editSource(sourceUrl: String) {}
            override fun toTop(source: BookSourcePart) {}
            override fun deleteSource(source: BookSourcePart) {}
            override fun searchBook(bookSource: BookSourcePart) {}
        })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
                binding.drawerLayout.closeDrawer(GravityCompat.END)
            } else {
                finish()
            }
        }
        //post到附加完成后执行,确保setSupportActionBar已完成
        binding.titleBar.post { initMenu() }
        tvPage.setOnClickListener { changePage() }
        viewModel.sourceData.observe(this) { source ->
            initRecyclerView(
                source?.exploreStyle == 1 || intent.getBooleanExtra("newStyle", false)
            )
            tvTitle.text = source?.bookSourceName ?: intent.getStringExtra("exploreName")
            searchMenuItem?.isVisible = !source?.searchUrl.isNullOrBlank()
        }
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.catsData.observe(this) { upBigCategoryBar() }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(this) {
            loadMoreViewTop.error(it)
        }
        viewModel.pageLiveData.observe(this) {
            tvPage.text = getString(R.string.menu_page, it)
        }
    }

    /**
     * 标题栏右侧:书内搜索(有searchUrl才显示)与侧边页入口
     */
    private fun initMenu() {
        val menu = binding.titleBar.menu
        val source = viewModel.sourceData.value
        searchMenuItem = menu.add(getString(R.string.search)).apply {
            setIcon(R.drawable.ic_search)
            isVisible = !source?.searchUrl.isNullOrBlank()
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                gotoSearch()
                true
            }
        }
        menu.add(getString(R.string.explore_add_big_category)).apply {
            setIcon(R.drawable.ic_menu)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                openDrawer(null)
                true
            }
        }
        binding.titleBar.setColorFilter(primaryTextColor)
    }

    private fun gotoSearch() {
        val source = viewModel.sourceData.value ?: return
        SearchActivity.start(this, source)
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
     * 大分类栏,仅展示用户添加过的,未添加时显示默认分类
     */
    private fun upBigCategoryBar() {
        val cats = viewModel.barCats()
        if (cats.isEmpty()) {
            binding.hsvBigCategory.gone()
            return
        }
        binding.hsvBigCategory.visible()
        val inflater = layoutInflater
        binding.llBigCategory.removeAllViews()
        cats.forEach { cat ->
            val tv = inflater.inflate(
                R.layout.item_quick_group, binding.llBigCategory, false
            ) as TextView
            tv.text = cat.t
            tv.isSelected = viewModel.currentIs(cat)
            tv.setOnClickListener { onBigCategoryClick(cat) }
            tv.setOnLongClickListener {
                openDrawer(cat)
                true
            }
            binding.llBigCategory.addView(tv)
        }
    }

    private fun onBigCategoryClick(cat: CatRef) {
        if (viewModel.currentIs(cat)) {
            showSubCategoryDialog(cat)
            return
        }
        switchCategory(cat)
    }

    private fun switchCategory(cat: CatRef) {
        viewModel.switchCategory(cat)
        upBigCategoryBar()
        adapter.clearItems()
        loadMoreView.hasMore()
        scrollToBottom(true)
    }

    /**
     * 细分下钻,再点当前分类时展示
     */
    private fun showSubCategoryDialog(big: CatRef) {
        val subs = viewModel.subCategoriesOf(big)
        if (subs.isEmpty()) {
            toastOnUi(getString(R.string.explore_no_sub_category))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(big.t)
            .setItems(subs.map { it.t }.toTypedArray()) { _, which ->
                viewModel.loadKind(subs[which])
                adapter.clearItems()
                loadMoreView.hasMore()
                scrollToBottom(true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 侧边页,复用发现页kinds渲染,url分类点击即添加
     */
    private fun openDrawer(subBig: CatRef?) {
        drawerSubBig = subBig
        binding.drawerPanel.titleDrawer.title =
            if (subBig == null) {
                getString(R.string.explore_add_big_category)
            } else {
                getString(R.string.explore_add_sub_category, subBig.t)
            }
        if (!drawerInited) {
            val source = viewModel.sourceData.value ?: return
            val part = appDb.bookSourceDao.getBookSourcePart(source.bookSourceUrl) ?: return
            binding.drawerPanel.rvDrawerKinds.layoutManager = LinearLayoutManager(this)
            binding.drawerPanel.rvDrawerKinds.adapter = drawerAdapter
            drawerAdapter.setItems(listOf(part))
            drawerAdapter.expand(0)
            drawerInited = true
        }
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    private fun onDrawerKindClick(title: String, url: String?) {
        val u = url?.takeIf { it.isNotBlank() } ?: return
        val big = drawerSubBig
        if (big == null) {
            viewModel.addBigCategory(title, u)
        } else {
            viewModel.addSubCategory(big, title, u)
        }
    }

    /**
     * 页码切换,点击标题栏页码弹出选择
     */
    private fun changePage() {
        val page = viewModel.pageLiveData.value ?: 1
        NumberPickerDialog(this)
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
    }

}
