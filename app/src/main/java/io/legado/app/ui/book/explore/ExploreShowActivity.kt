package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
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
import io.legado.app.ui.main.explore.ExploreKindRenderer
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 发现列表:三栏平铺分类树 + 筛选页(已选分类/筛选设置/传统发现面板)
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

    /** 筛选页两个渲染器:筛选设置区(完整交互)/传统发现面板(点击弹添加菜单) */
    private val filterRenderer by lazy {
        ExploreKindRenderer(this, lifecycleScope, object : ExploreKindRenderer.Callback {
            override fun onKindClick(view: View, sourceUrl: String, kind: ExploreKind) {
            }

            override fun onSelectOptionClick(
                view: View, sourceUrl: String, kind: ExploreKind, option: String
            ) {
            }

            override fun onRequestRefresh(sourceUrl: String) {
                viewModel.onPanelChanged()
            }
        })
    }
    private val panelRenderer by lazy {
        ExploreKindRenderer(this, lifecycleScope, object : ExploreKindRenderer.Callback {
            override fun onKindClick(view: View, sourceUrl: String, kind: ExploreKind) {
                showAddMenu(view, kind, null)
            }

            override fun onSelectOptionClick(
                view: View, sourceUrl: String, kind: ExploreKind, option: String
            ) {
                showAddMenu(view, kind, option)
            }

            override fun onRequestRefresh(sourceUrl: String) {
                viewModel.onPanelChanged()
            }
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
        }
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.treeData.observe(this) {
            upL1Bar()
            if (isFilterOpen()) {
                upFilterSelected()
            }
        }
        viewModel.l2Data.observe(this) {
            upL2Bar()
            if (isFilterOpen()) {
                upFilterSelected()
            }
        }
        viewModel.l3Data.observe(this) {
            upL3Bar()
            if (isFilterOpen()) {
                upFilterSelected()
            }
        }
        viewModel.nodeInvalidLiveData.observe(this) {
            toastOnUi(R.string.explore_node_invalid)
        }
        viewModel.nodeExecutedLiveData.observe(this) {
            toastOnUi(R.string.explore_node_executed)
        }
        viewModel.panelRefreshLiveData.observe(this) {
            if (isFilterOpen()) {
                upFilterSettings()
                upFilterPanel()
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
            openFilterDrawer()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /** 副标题显示当前分类名,无分类时隐藏 */
    private fun updateSubtitle() {
        val subtitle = viewModel.currentL3?.name
            ?: viewModel.currentL2?.name
            ?: viewModel.currentL1?.name
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
     * 列表页三栏chip:高亮当前定位,点击切换联动刷新下级栏;再点已高亮项无操作
     */
    private fun upL1Bar() = upBar(
        binding.hsvBigCategory, binding.llBigCategory,
        viewModel.l1Kinds(), viewModel.currentL1
    ) { viewModel.selectL1(it) }

    private fun upL2Bar() = upBar(
        binding.hsvSubCategory, binding.llSubCategory,
        viewModel.l2Kinds(), viewModel.currentL2
    ) { viewModel.selectL2(it) }

    private fun upL3Bar() = upBar(
        binding.hsvThirdCategory, binding.llThirdCategory,
        viewModel.l3Kinds(), viewModel.currentL3
    ) { viewModel.selectL3(it) }

    private fun upBar(
        scrollView: HorizontalScrollView,
        container: ViewGroup,
        nodes: List<ExploreCatNode>,
        current: ExploreCatNode?,
        onSelect: (ExploreCatNode) -> Boolean
    ) {
        if (nodes.isEmpty()) {
            scrollView.gone()
            return
        }
        scrollView.visible()
        container.removeAllViews()
        val names = displayNames(nodes)
        var selectedView: TextView? = null
        nodes.forEachIndexed { index, node ->
            val tv = layoutInflater.inflate(
                R.layout.item_quick_group, container, false
            ) as TextView
            tv.text = names[index]
            tv.isSelected = current == node
            tv.setOnClickListener {
                if (onSelect(node)) {
                    clearAndReload()
                }
            }
            container.addView(tv)
            if (tv.isSelected) {
                selectedView = tv
            }
        }
        selectedView?.let { ensureChipVisible(scrollView, it) }
        if (titleInited) {
            updateSubtitle()
        }
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

    //==================== 筛选页 ====================

    private fun isFilterOpen(): Boolean {
        return binding.drawerLayout.isDrawerOpen(GravityCompat.END)
    }

    /**
     * 筛选页:已选分类三栏 + 清空重建/清空 + 筛选设置 + 传统发现面板
     */
    private fun openFilterDrawer() {
        if (!viewModel.hasExploreKinds) {
            return
        }
        if (viewModel.sourceData.value == null) {
            return
        }
        upFilterSelected()
        upFilterSettings()
        upFilterPanel()
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    private fun initDrawerListener() {
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                ExploreKindRenderer.saveInfoMaps()
                viewModel.onFilterClosed()
            }
        })
        binding.drawerPanel.btnResetPreset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.explore_reset_preset)
                .setMessage(R.string.explore_reset_preset_confirm)
                .setPositiveButton(R.string.ok) { _, _ ->
                    viewModel.resetToPreset()
                    upFilterSelected()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.drawerPanel.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.explore_clear_all)
                .setMessage(R.string.explore_clear_all_confirm)
                .setPositiveButton(R.string.ok) { _, _ ->
                    viewModel.clearAll()
                    upFilterSelected()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * 已选分类三栏:chip点击设为当前定位,长按弹引用信息+删除,栏末尾「＋」提示
     */
    private fun upFilterSelected() {
        upSelectedBar(
            binding.drawerPanel.fbxL1, viewModel.l1Kinds(), 1
        )
        upSelectedBar(
            binding.drawerPanel.fbxL2, viewModel.l2Kinds(), 2,
            viewModel.currentL1, null
        )
        upSelectedBar(
            binding.drawerPanel.fbxL3, viewModel.l3Kinds(), 3,
            viewModel.currentL1, viewModel.currentL2
        )
    }

    private fun upSelectedBar(
        fbx: FlexboxLayout,
        nodes: List<ExploreCatNode>,
        level: Int,
        l1: ExploreCatNode? = null,
        l2: ExploreCatNode? = null
    ) {
        fbx.removeAllViews()
        val names = displayNames(nodes)
        nodes.forEachIndexed { index, node ->
            val tv = layoutInflater.inflate(
                R.layout.item_quick_group, fbx, false
            ) as TextView
            tv.text = names[index]
            tv.setOnClickListener {
                when (level) {
                    1 -> viewModel.selectL1(node)
                    2 -> viewModel.selectL2(node)
                    else -> viewModel.selectL3(node)
                }
                upFilterSelected()
            }
            tv.setOnLongClickListener {
                showNodeDialog(node, level, l1, l2)
                true
            }
            fbx.addView(tv)
        }
        val plus = layoutInflater.inflate(
            R.layout.item_quick_group, fbx, false
        ) as TextView
        plus.text = "＋"
        plus.setOnClickListener {
            toastOnUi(R.string.explore_plus_hint)
        }
        fbx.addView(plus)
    }

    /**
     * 长按节点:展示完整引用信息,可删除(父级删除连带子级由remove实现)
     */
    private fun showNodeDialog(
        node: ExploreCatNode,
        level: Int,
        l1: ExploreCatNode?,
        l2: ExploreCatNode?
    ) {
        val refInfo = buildString {
            append(getString(R.string.explore_node_ref_info))
            append("\n")
            append("type: ${node.type}")
            append("\nkind: ${node.kindTitle}")
            node.url?.takeIf { it.isNotBlank() }?.let { append("\nurl: $it") }
            node.option?.let { append("\noption: $it") }
            node.action?.takeIf { it.isNotBlank() }?.let { append("\naction: ${it.take(300)}") }
        }
        AlertDialog.Builder(this)
            .setTitle(node.name)
            .setMessage(refInfo)
            .setPositiveButton(R.string.explore_node_delete) { _, _ ->
                when (level) {
                    1 -> viewModel.removeL1(node)
                    2 -> l1?.let { viewModel.removeL2(it, node) }
                    else -> {
                        if (l1 != null && l2 != null) {
                            viewModel.removeL3(l1, l2, node)
                        }
                    }
                }
                upFilterSelected()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 筛选设置区:select/text/toggle完整交互,改动即存 */
    private fun upFilterSettings() {
        val sourceUrl = viewModel.sourceData.value?.bookSourceUrl ?: return
        filterRenderer.render(
            binding.drawerPanel.fbxFilter,
            viewModel.filterSettingKinds(),
            sourceUrl,
            ExploreKindRenderer.Mode.FILTER_SETTINGS
        )
    }

    /** 传统发现面板:点击弹添加菜单 */
    private fun upFilterPanel() {
        val sourceUrl = viewModel.sourceData.value?.bookSourceUrl ?: return
        panelRenderer.render(
            binding.drawerPanel.fbxPanel,
            viewModel.panelKinds(),
            sourceUrl,
            ExploreKindRenderer.Mode.PANEL_ADD
        )
    }

    /**
     * 添加分类菜单:添加一级始终可用;二级/三级按辖域过滤+跳级禁用
     */
    private fun showAddMenu(anchor: View, kind: ExploreKind, option: String?) {
        val popup = PopupMenu(this, anchor)
        val mAdd1 = popup.menu.add(R.string.explore_add_level_one)
        //实际挂靠父级与addNode的fallback一致
        val l2Parent = viewModel.currentL1 ?: viewModel.l1Kinds().firstOrNull()
        val l3Parent = viewModel.currentL2 ?: l2Parent?.children?.firstOrNull()
        val mAdd2 = popup.menu.add(R.string.explore_add_level_two)
        val l2Scope = viewModel.scopeCandidates(l2Parent)
        mAdd2.isEnabled =
            l2Scope.isNotEmpty() && viewModel.scopeContains(l2Scope, kind, option)
        val mAdd3 = popup.menu.add(R.string.explore_add_level_three)
        val l3Scope = viewModel.scopeCandidates(l3Parent)
        mAdd3.isEnabled =
            l3Scope.isNotEmpty() && viewModel.scopeContains(l3Scope, kind, option)
        popup.menu.add(R.string.explore_cancel)
        popup.setOnMenuItemClickListener { item ->
            when (item) {
                mAdd1 -> viewModel.addNode(1, kind, option)
                mAdd2 -> viewModel.addNode(2, kind, option)
                mAdd3 -> viewModel.addNode(3, kind, option)
            }
            upFilterSelected()
            true
        }
        popup.show()
    }

    /** 同名称不同引用显示"名称（序号）" */
    private fun displayNames(nodes: List<ExploreCatNode>): List<String> {
        val groups = nodes.groupBy { it.name }
        return nodes.map { node ->
            val group = groups[node.name].orEmpty()
            if (group.size > 1) {
                "${node.name}（${group.indexOf(node) + 1}）"
            } else {
                node.name
            }
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
