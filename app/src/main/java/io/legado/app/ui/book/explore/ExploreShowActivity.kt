package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
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
import io.legado.app.data.entities.rule.ExploreKind
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
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.startActivity
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

    /** 侧边页模式,false为传统发现分类面板,true为大分类管理 */
    private var drawerModeManage = false
    private var drawerInited = false
    private val manageChecked = linkedSetOf<String>()
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
            setupCategoryLongClick()
        }
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.bigKindsData.observe(this) { bigs ->
            //单层平铺(大分类栏隐藏)时三横仍可用
            categoryMenuItem?.isVisible = viewModel.hasExploreKinds
            upBigCategoryBar()
            upSubCategoryBar()
            if (titleInited) {
                updateSubtitle()
            }
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
        initDrawerListener()
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
     * 方案A标题栏:[←][第N页] [书源名居中+副标题] [搜索][三横]
     * 页码与标题用toolbar内自定义视图,搜索与三横走标准menu
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
        setupCategoryLongClick()
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_category) {
            openDrawer()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /** 副标题显示当前分类名,无分类树时隐藏 */
    private fun updateSubtitle() {
        val subtitle = viewModel.currentSub?.title ?: viewModel.currentBig?.kind?.title
        tvSubtitle?.let {
            it.isVisible = subtitle != null
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
                setupCategoryLongClick()
                return true
            }
        })
    }

    /**
     * 三横长按为大分类管理,menu item无长按事件,对toolbar内的动作视图注册
     */
    private fun setupCategoryLongClick() {
        binding.titleBar.toolbar.post {
            val menuView = binding.titleBar.toolbar.children
                .firstOrNull { it is ActionMenuView } as? ActionMenuView
            val itemView = menuView?.children?.lastOrNull { it !is SearchView }
            itemView?.setOnLongClickListener {
                openManageDrawer()
                true
            }
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
     * 大分类栏:高亮currentBig,长按进管理侧边页
     */
    private fun upBigCategoryBar() {
        val bigs = viewModel.bigKindsData.value ?: emptyList()
        if (bigs.isEmpty()) {
            binding.hsvBigCategory.gone()
            return
        }
        binding.hsvBigCategory.visible()
        binding.llBigCategory.removeAllViews()
        val inflater = layoutInflater
        var selectedView: TextView? = null
        bigs.forEach { big ->
            val tv = inflater.inflate(
                R.layout.item_quick_group, binding.llBigCategory, false
            ) as TextView
            tv.text = big.kind.title
            tv.isSelected = viewModel.currentBig == big
            tv.setOnClickListener { onBigCategoryClick(big) }
            tv.setOnLongClickListener {
                openManageDrawer()
                true
            }
            binding.llBigCategory.addView(tv)
            if (tv.isSelected) {
                selectedView = tv
            }
        }
        selectedView?.let { ensureChipVisible(binding.hsvBigCategory, it) }
    }

    private fun onBigCategoryClick(big: BigKind) {
        val current = viewModel.currentBig
        if (current == big && viewModel.currentSub == null) { //再点高亮大分类,无操作
            return
        }
        //选中或回本级:currentSub复位,按大分类本级url拉书
        viewModel.selectBig(big)
        clearAndReload()
        upBigCategoryBar()
        upSubCategoryBar()
    }

    /**
     * 细分栏:当前大分类有细分才显示;单层平铺书源显示全部url分类
     */
    private fun upSubCategoryBar() {
        val subs = viewModel.subBarKinds
        if (subs.isEmpty()) {
            binding.hsvSubCategory.gone()
            return
        }
        binding.hsvSubCategory.visible()
        binding.llSubCategory.removeAllViews()
        val inflater = layoutInflater
        var selectedView: TextView? = null
        subs.forEach { sub ->
            val tv = inflater.inflate(
                R.layout.item_quick_group, binding.llSubCategory, false
            ) as TextView
            tv.text = sub.title
            tv.textSize = 12f
            tv.isSelected = viewModel.currentSub == sub
            tv.setOnClickListener { onSubCategoryClick(sub) }
            binding.llSubCategory.addView(tv)
            if (tv.isSelected) {
                selectedView = tv
            }
        }
        selectedView?.let { ensureChipVisible(binding.hsvSubCategory, it) }
    }

    private fun onSubCategoryClick(sub: ExploreKind) {
        if (viewModel.currentSub == sub) { //再点当前细分,无操作
            return
        }
        viewModel.selectSub(sub)
        clearAndReload()
        upSubCategoryBar()
    }

    /** 高亮chip滚动到可见 */
    private fun ensureChipVisible(scrollView: HorizontalScrollView, chip: TextView) {
        scrollView.post {
            val target = (chip.left - 16.dpToPx()).coerceAtLeast(0)
            if (chip.left < scrollView.scrollX + 8.dpToPx()
                || chip.right > scrollView.scrollX + scrollView.width - 8.dpToPx()
            ) {
                scrollView.smoothScrollTo(target, 0)
            }
        }
    }

    /**
     * 切分类复位分页现场:页码回到第1页,顶部回滚视图高度归零,防止旧页码列表错误前置
     */
    private fun clearAndReload() {
        oldPage = -1
        isClearAll = false
        val layoutParams = loadMoreViewTop.layoutParams
        if (layoutParams != null && layoutParams.height != 0) {
            layoutParams.height = 0
            loadMoreViewTop.layoutParams = layoutParams
        }
        adapter.clearItems()
        loadMoreView.hasMore()
        scrollToBottom(true)
    }

    /**
     * 侧边页关闭:infoMap落盘;管理模式提交白名单
     */
    private fun initDrawerListener() {
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                drawerAdapter.onPause()
                if (drawerModeManage) {
                    drawerModeManage = false
                    drawerAdapter.manageChecker = null
                    val changed = viewModel.applyManageSelection(manageChecked.toList())
                    manageChecked.clear()
                    if (changed) {
                        clearAndReload()
                    }
                }
            }
        })
    }

    /**
     * 点三横:传统发现分类面板,点击行为与传统发现相同
     */
    private fun openDrawer() {
        if (viewModel.sourceData.value == null) {
            return
        }
        drawerModeManage = false
        drawerAdapter.manageChecker = null
        binding.drawerPanel.titleDrawer.title = getString(R.string.explore_category_list)
        prepareDrawerAdapter()
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    /**
     * 长按chip或三横:大分类管理,每个url分类都可勾选为大分类,关闭时生效
     */
    private fun openManageDrawer() {
        if (!viewModel.hasExploreKinds) {
            return
        }
        drawerModeManage = true
        val candidates = viewModel.bigCandidates.map { it.title }.toSet()
        manageChecked.clear()
        //初始勾选:当前大分类栏中属于候选的分类(分段标题不在候选,单层平铺初始为空)
        viewModel.bigKindsData.value?.forEach { big ->
            if (big.kind.title in candidates) {
                manageChecked.add(big.kind.title)
            }
        }
        drawerAdapter.manageChecker = ExploreAdapter.ManageChecker(
            candidateTitles = candidates,
            checkedTitles = manageChecked,
            onToggle = { title ->
                if (!manageChecked.remove(title)) {
                    manageChecked.add(title)
                }
                manageChecked.contains(title)
            }
        )
        binding.drawerPanel.titleDrawer.title = getString(R.string.explore_adjust_category)
        prepareDrawerAdapter()
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    private fun prepareDrawerAdapter() {
        val source = viewModel.sourceData.value ?: return
        val part = appDb.bookSourceDao.getBookSourcePart(source.bookSourceUrl) ?: return
        if (!drawerInited) {
            binding.drawerPanel.rvDrawerKinds.layoutManager = LinearLayoutManager(this)
            binding.drawerPanel.rvDrawerKinds.adapter = drawerAdapter
            drawerInited = true
        }
        //每次打开重渲染,保证两种模式样式正确
        drawerAdapter.setItems(listOf(part))
        drawerAdapter.expand(0)
    }

    /**
     * 侧边页使用模式点击url分类:切到该分类拉书并关闭侧边页
     */
    private fun onDrawerKindClick(title: String, exploreUrl: String?) {
        if (drawerModeManage) {
            return
        }
        val url = exploreUrl?.takeIf { it.isNotBlank() } ?: return
        if (viewModel.selectFromUrl(title, url)) {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
            clearAndReload()
            upBigCategoryBar()
            upSubCategoryBar()
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
