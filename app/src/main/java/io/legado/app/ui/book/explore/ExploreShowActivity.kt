package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreCatNode
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 发现页:顶栏select下拉+分组胶囊总览选分类,点胶囊切书单列表,菜单弹窗编辑树
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
    private var topHeaderAdded = false

    /** 自定义标题视图,toolbar附加完成后inflate */
    private var titleInited = false

    private val titleView: View?
        get() = binding.titleBar.toolbar.findViewById(R.id.explore_title_root)
    private val tvPage: TextView?
        get() = binding.titleBar.toolbar.findViewById(R.id.tv_page)
    private val tvTitle: TextView?
        get() = binding.titleBar.toolbar.findViewById(R.id.tv_title)
    private val tvSubtitle: TextView?
        get() = binding.titleBar.toolbar.findViewById(R.id.tv_subtitle)

    private var searchMenuItem: MenuItem? = null
    private var categoryMenuItem: MenuItem? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.contentView.isVisible) {
                showCats()
            } else {
                finish()
            }
        }
        //初始模式预判,减少intent直达书单时的闪烁
        upUiMode(intent.getStringExtra("exploreUrl") != null)
        binding.svCats.applyNavigationBarPadding()
        //post到附加完成后执行,确保setSupportActionBar已完成
        binding.titleBar.post { initTitleView() }
        viewModel.sourceData.observe(this) { source ->
            initRecyclerView(
                source?.exploreStyle == 1 || intent.getBooleanExtra("newStyle", false)
            )
            if (titleInited) {
                tvTitle?.text = source?.bookSourceName ?: intent.getStringExtra("exploreName")
            }
            //source异步加载完成,补刷搜索按钮可见性
            searchMenuItem?.isVisible = !source?.searchUrl.isNullOrBlank()
        }
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.treeData.observe(this) {
            //列表模式下定位被删(弹窗操作)时回总览
            if (binding.contentView.isVisible && !viewModel.hasCurrent()) {
                showCats()
            }
            upOverview()
        }
        viewModel.showListLiveData.observe(this) { showList ->
            upUiMode(showList == true)
        }
        viewModel.nodeInvalidLiveData.observe(this) {
            toastOnUi(R.string.explore_node_invalid)
        }
        viewModel.nodeExecutedLiveData.observe(this) {
            toastOnUi(R.string.explore_node_executed)
        }
        viewModel.kindsLoadedLiveData.observe(this) {
            //exploreKinds异步解析完成,补刷筛选按钮可见性(菜单创建时通常还未加载完)
            categoryMenuItem?.isVisible = viewModel.hasExploreKinds
        }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(this) {
            loadMoreViewTop.error(it)
        }
        viewModel.pageLiveData.observe(this) {
            tvPage?.text = getString(R.string.menu_page, it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.explore_show, menu)
        searchMenuItem = menu.findItem(R.id.menu_search)
        categoryMenuItem = menu.findItem(R.id.menu_category)
        initSearchView()
        val source = viewModel.sourceData.value
        searchMenuItem?.isVisible = !source?.searchUrl.isNullOrBlank()
        categoryMenuItem?.isVisible = viewModel.hasExploreKinds
        return super.onCompatCreateOptionsMenu(menu)
    }

    /**
     * 方案A标题栏:[←][第N页] [书源名居中+副标题] [搜索][筛选]
     * 页码与标题用toolbar内自定义视图,搜索与筛选走标准menu
     */
    private fun initTitleView() {
        val toolbar = binding.titleBar.toolbar
        toolbar.title = null
        layoutInflater.inflate(R.layout.view_explore_show_title, toolbar, true)
        titleInited = true
        tvPage?.text = getString(R.string.menu_page, 1)
        tvPage?.setOnClickListener { changePage() }
        tvTitle?.text = viewModel.sourceData.value?.bookSourceName
            ?: intent.getStringExtra("exploreName")
        updateSubtitle()
        binding.titleBar.setColorFilter(primaryTextColor)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_category) {
            CategoryEditDialogFragment().show(supportFragmentManager, "categoryEdit")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /** 副标题显示当前分类名,仅列表模式显示 */
    private fun updateSubtitle() {
        val subtitle = viewModel.currentL3?.name
            ?: viewModel.currentL2?.name
            ?: viewModel.currentL1?.name
        tvSubtitle?.let {
            it.isVisible = subtitle != null && binding.contentView.isVisible
            it.text = subtitle
        }
    }

    /**
     * 搜索按钮对齐订阅源:有searchUrl才显示,展开时隐藏标题视图,提交搜当前书源
     */
    private fun initSearchView() {
        val searchView = searchMenuItem?.actionView as? SearchView ?: return
        searchView.isSubmitButtonEnabled = true
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchView.clearFocus()
                val source = viewModel.sourceData.value ?: return true
                if (!query.isNullOrBlank()) {
                    SearchActivity.start(this@ExploreShowActivity, source, query)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
        })
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                searchView.isIconified = true
            }
        }
        searchMenuItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                titleView?.isVisible = false
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                titleView?.isVisible = true
                return true
            }
        })
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
     * 双视图切换:总览(分组胶囊)与书单列表
     */
    private fun upUiMode(showList: Boolean) {
        binding.svCats.isVisible = !showList
        binding.contentView.isVisible = showList
        updateSubtitle()
    }

    private fun showList() {
        upUiMode(true)
    }

    private fun showCats() {
        upUiMode(false)
    }

    /** 总览渲染:顶栏select下拉+分组胶囊 */
    private fun upOverview() {
        upSelectBar()
        upCats()
    }

    /**
     * 顶栏select下拉:按控件标题分组,chip显示"控件:当前值",
     * 点击弹选项列表,选中即定位该选项实例并切列表
     */
    private fun upSelectBar() {
        val groups = viewModel.selectGroups()
        binding.llSelect.removeAllViews()
        binding.hsvSelect.isVisible = groups.isNotEmpty()
        val currentRef = listOfNotNull(
            viewModel.currentL1, viewModel.currentL2, viewModel.currentL3
        )
        groups.forEach { group ->
            val tv = layoutInflater.inflate(
                R.layout.item_explore_select, binding.llSelect, false
            ) as TextView
            val current = group.nodes.firstOrNull { node ->
                currentRef.any { it.sameRefAs(node) }
            }
            tv.text = getString(
                R.string.explore_select_value,
                group.kindTitle,
                current?.option ?: group.nodes.first().option ?: group.nodes.first().name
            )
            tv.setOnClickListener { anchor ->
                showSelectPopup(tv, group)
            }
            binding.llSelect.addView(tv)
        }
    }

    private fun showSelectPopup(anchor: View, group: ExploreShowViewModel.SelectGroup) {
        val names = group.nodes.map { it.option ?: it.name }
        val popup = ListPopupWindow(this)
        popup.setAdapter(ArrayAdapter(this, R.layout.item_text_common, names))
        popup.setAnchorView(anchor)
        popup.width = maxOf(anchor.width, 120.dpToPx())
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            group.nodes.getOrNull(position)?.let { node ->
                if (viewModel.selectNode(node)) {
                    showList()
                }
            }
        }
        popup.show()
    }

    /**
     * 分类总览:分支节点渲染组标题大胶囊,叶子(url/button)按字数聚簇换行;
     * select节点已在顶栏,总览不重复渲染;点叶子定位分类,url类切书单列表
     */
    private fun upCats() {
        binding.llCats.removeAllViews()
        val cluster = mutableListOf<ExploreCatNode>()

        fun flush() {
            if (cluster.isEmpty()) return
            val names = ExploreCatNode.displayNames(cluster)
            val fbx = FlexboxLayout(this)
            fbx.flexWrap = FlexWrap.WRAP
            cluster.forEachIndexed { index, node ->
                val tv = layoutInflater.inflate(
                    R.layout.item_quick_group, fbx, false
                ) as TextView
                tv.text = names[index]
                tv.isSelected = isCurrentNode(node)
                tv.setOnClickListener {
                    if (viewModel.selectNode(node)
                        && node.type != ExploreKind.Type.button
                    ) {
                        showList()
                    }
                }
                fbx.addView(
                    tv,
                    FlexboxLayout.LayoutParams(
                        FlexboxLayout.LayoutParams.WRAP_CONTENT,
                        FlexboxLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        //item_quick_group自带右距,补上行距保证多行不贴边
                        topMargin = 8.dpToPx()
                    }
                )
            }
            binding.llCats.addView(fbx)
            cluster.clear()
        }

        fun render(nodes: List<ExploreCatNode>) {
            nodes.forEach { node ->
                when {
                    node.type == ExploreKind.Type.select -> Unit
                    node.children.isNotEmpty() -> {
                        flush()
                        addGroupTitle(node.name)
                        render(node.children)
                    }
                    node.type == ExploreCatNode.TYPE_HEADER -> Unit
                    else -> cluster.add(node)
                }
            }
        }

        render(viewModel.overviewNodes())
        flush()
        if (binding.llCats.childCount == 0) {
            val tv = TextView(this)
            tv.text = getString(R.string.explore_cats_empty)
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, 100.dpToPx(), 0, 0)
            binding.llCats.addView(tv)
        }
    }

    private fun addGroupTitle(name: String) {
        val tv = layoutInflater.inflate(
            R.layout.item_explore_group, binding.llCats, false
        ) as TextView
        tv.text = name
        binding.llCats.addView(tv)
    }

    private fun isCurrentNode(node: ExploreCatNode): Boolean {
        return listOfNotNull(
            viewModel.currentL1, viewModel.currentL2, viewModel.currentL3
        ).any { it.sameRefAs(node) }
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
                    if (it != 1) {
                        if (!topHeaderAdded) { //初次添加头
                            topHeaderAdded = true
                            adapter.addHeaderView {
                                ViewLoadMoreBinding.bind(loadMoreViewTop)
                            }
                        } else { //把头显示出来
                            val layoutParams = loadMoreViewTop.layoutParams
                            if (layoutParams?.height == 0) {
                                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                loadMoreViewTop.layoutParams = layoutParams
                            }
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
