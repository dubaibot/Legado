package io.legado.app.ui.book.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.databinding.ActivityBookSearchBinding
import io.legado.app.help.book.isNotShelf
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : VMBaseActivity<ActivityBookSearchBinding, SearchViewModel>(),
    SearchScopeDialog.Callback, SearchAdapter.CallBack {

    override val binding by viewBinding(ActivityBookSearchBinding::inflate)
    override val viewModel by viewModels<SearchViewModel>()
    private val hotViewModel by viewModels<SearchHotViewModel>()
    private val unifiedViewModel by viewModels<UnifiedSearchViewModel>()
    private var showSources by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var books by mutableStateOf<List<SearchBook>>(emptyList(), neverEqualPolicy())
    private var history by mutableStateOf<List<SearchKeyword>>(emptyList())
    private var groups by mutableStateOf<List<String>>(emptyList())
    private var scope by mutableStateOf("")
    private var searching by mutableStateOf(false)
    private var hasMore by mutableStateOf(false)
    private var submitted by mutableStateOf(false)
    private var preferences by mutableStateOf(SearchPreferences())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        preferences = SearchPreferences.read(this)
        scope = viewModel.searchScope.toString()
        query = savedInstanceState?.getString("composeQuery") ?: viewModel.searchKey
        submitted = viewModel.searchKey.isNotBlank()
        savedInstanceState?.getString("composeScope")?.let {
            scope = it
            viewModel.searchScope.update(it, save = false)
        }
        savedInstanceState?.getString("composeSearchType")?.let { name ->
            SearchContentType.entries.firstOrNull { it.name == name }?.let(unifiedViewModel::selectType)
        }
        savedInstanceState?.getString("composeSourceKey")?.let(unifiedViewModel::selectSource)
        binding.root.removeAllViews()
        binding.root.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val type = unifiedViewModel.type
                    val currentSources = unifiedViewModel.sources.filter { it.type == type }
                    val selectedKey = if (type == SearchContentType.BOOK) {
                        if (scope.contains("::")) scope.substringAfter("::") else ""
                    } else unifiedViewModel.sourceKey
                    val scopeName = if (type == SearchContentType.BOOK) {
                        scope.substringBefore("::").ifEmpty { "全部启用源" }
                    } else if (selectedKey.isEmpty()) "全部启用源"
                    else currentSources.firstOrNull { it.key == selectedKey }?.name ?: "所选源不可用"
                    val typeBar: @Composable () -> Unit = {
                        SearchTypeBar(type, scopeName, { selectSearchType(it) }) { showSources = true }
                    }
                    if (type != SearchContentType.BOOK) {
                        UnifiedSearchScreen(query, { query = it }, unifiedViewModel,
                            onSearch = { submit() }, onResult = ::openResult, typeBar = typeBar)
                    } else {
                        BookSearchScreen(
                            query = query, onQueryChange = { query = it },
                            books = books, history = history, groups = groups, scope = scope,
                            searching = searching, hasMore = hasMore, submitted = submitted,
                            preferences = preferences, hotViewModel = hotViewModel,
                            onSearch = { submit() },
                            onScope = { viewModel.searchScope.update(it) },
                            onPreferences = {
                                val repeatSearch = preferences.precision != it.precision ||
                                    preferences.hideNoCover != it.hideNoCover ||
                                    preferences.status != it.status || preferences.minWords != it.minWords
                                preferences = it
                                it.save(this@SearchActivity)
                                if (repeatSearch && submitted) submit(viewModel.searchKey)
                            },
                            onHistory = { query = it; submit() },
                            onDeleteHistory = viewModel::deleteHistory,
                            onClearHistory = viewModel::clearHistory,
                            onBook = ::showBookInfo, isInBookshelf = ::isInBookshelf,
                            onManageSources = { startActivity<BookSourceActivity>() },
                            onLog = { showDialogFragment(AppLogDialog()) },
                            onRefresh = {
                                hotViewModel.refresh()
                                if (submitted) submit(viewModel.searchKey)
                            },
                            onLoadMore = ::loadMore,
                            onStopSearch = {
                                hasMore = false
                                viewModel.hasMore = false
                                viewModel.stop()
                            },
                            typeBar = typeBar,
                        )
                    }
                    if (showSources) SearchSourceDialog(type, currentSources, selectedKey,
                        unifiedViewModel.loadingSources, unifiedViewModel.errors,
                        onSelect = { source ->
                            showSources = false
                            if (type == SearchContentType.BOOK) {
                                viewModel.searchScope.update(source?.bookScope.orEmpty())
                            } else {
                                unifiedViewModel.selectSource(source?.key.orEmpty())
                                if (query.isNotBlank()) submit()
                            }
                        }, onDismiss = { showSources = false })
                }
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        viewModel.searchBookLiveData.observe(this) { books = it.toList() }
        viewModel.isSearchLiveData.observe(this) {
            searching = it
            hasMore = viewModel.hasMore
        }
        viewModel.searchFinishLiveData.observe(this) { hasMore = viewModel.hasMore }
        viewModel.upAdapterLiveData.observe(this) { books = books.toList() }
        viewModel.searchScope.stateLiveData.observe(this) {
            val changed = scope != it
            scope = it
            if (changed && query.isNotBlank() && unifiedViewModel.type == SearchContentType.BOOK) submit()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { appDb.bookSourceDao.flowEnabledGroups().collect { groups = it } }
                launch {
                    appDb.searchKeywordDao.flowByTime().catch {
                        AppLog.put("搜索历史加载失败", it)
                    }.collect { history = it.take(20) }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                unifiedViewModel.refreshSources()
                viewModel.resume()
                try { awaitCancellation() } finally { viewModel.pause() }
            }
        }
        if (savedInstanceState == null) {
            receiptIntent(intent)
        } else if (unifiedViewModel.type != SearchContentType.BOOK) {
            if (!unifiedViewModel.submitted) {
                savedInstanceState.getString("composeUnifiedKey")?.takeIf(String::isNotBlank)?.let { submit(it) }
            }
        } else if (!submitted) {
            savedInstanceState.getString("composeSearchKey")?.takeIf(String::isNotBlank)?.let { submit(it) }
        }
    }

    private fun submit(key: String = query) {
        val value = key.trim()
        if (value.isEmpty()) return
        if (unifiedViewModel.type != SearchContentType.BOOK) {
            viewModel.stop()
            viewModel.saveSearchKey(value)
            unifiedViewModel.search(value)
            return
        }
        submitted = true
        searching = true
        hasMore = true
        books = emptyList()
        viewModel.saveSearchKey(value)
        viewModel.search(value)
    }

    private fun loadMore() {
        if (unifiedViewModel.type != SearchContentType.BOOK) return
        if (searching || viewModel.isSearchLiveData.value == true || !hasMore ||
            !viewModel.hasMore || viewModel.searchKey.isBlank()) return
        searching = true
        viewModel.search("")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("composeQuery", query)
        outState.putString("composeScope", scope)
        outState.putString("composeSearchKey", viewModel.searchKey)
        outState.putString("composeSearchType", unifiedViewModel.type.name)
        outState.putString("composeSourceKey", unifiedViewModel.sourceKey)
        if (unifiedViewModel.submitted) outState.putString("composeUnifiedKey", unifiedViewModel.keyword)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiptIntent(intent)
    }

    private fun receiptIntent(intent: Intent) {
        val requestedType = intent.getStringExtra("searchType")?.let { name ->
            SearchContentType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        selectSearchType(requestedType ?: SearchContentType.BOOK, search = false)
        intent.getStringExtra("sourceKey")?.let {
            unifiedViewModel.selectSource(it)
            unifiedViewModel.refreshSources()
        }
        intent.getStringExtra("searchScope")?.let {
            scope = it
            viewModel.searchScope.update(it, save = false)
        }
        intent.getStringExtra("key")?.takeIf { it.isNotBlank() }?.let {
            query = it
            submit()
        }
    }

    override fun onSearchScopeOk(searchScope: SearchScope) {
        viewModel.searchScope.update(searchScope.toString())
    }

    override fun isInBookshelf(book: SearchBook) = viewModel.isInBookShelf(book)

    override fun showBookInfo(book: SearchBook) {
        if (book.type and BookType.video > 0) {
            lifecycleScope.launch {
                val inBookshelf = withContext(Dispatchers.IO) {
                    appDb.bookDao.getBook(book.bookUrl)?.isNotShelf == false
                }
                startActivity<VideoPlayerActivity> {
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("inBookshelf", inBookshelf)
                    putExtra(PreferKey.layoutModeOverride, 1)
                }
            }
            return
        }
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    private fun selectSearchType(type: SearchContentType, search: Boolean = true) {
        if (unifiedViewModel.type == type) return
        viewModel.stop()
        searching = false
        unifiedViewModel.selectType(type)
        if (search && query.isNotBlank()) submit() else unifiedViewModel.refreshSources()
    }

    private fun openResult(result: UnifiedSearchResult) {
        result.video?.let { video ->
            startActivity<VideoPlayerActivity> {
                putExtra("videoTitle", video.title)
                putExtra("pyPluginId", result.source.video?.id)
                putExtra("pyVideoItemId", video.id.ifBlank { video.url })
                putExtra(PreferKey.layoutModeOverride, 1)
            }
            return
        }
        result.article?.let { article ->
            val articles = unifiedViewModel.results.filter { it.source.key == result.source.key }
                .mapNotNull { it.article }
            lifecycleScope.launch {
                try {
                    val current = withContext(Dispatchers.IO) {
                        val source = appDb.rssSourceDao.getByKey(article.origin) ?: error("订阅源已移除")
                        val current = article.copy(type = source.type)
                        appDb.rssArticleDao.insert(current)
                        current
                    }
                    ReadRss.readRss(this@SearchActivity, current,
                        rssArticles = articles.map { it.copy(type = current.type) }, sortName = current.sort)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toastOnUi(e.localizedMessage ?: "打开搜索结果失败")
                }
            }
        }
    }

    companion object {
        fun start(
            context: Context,
            key: String?,
            searchScope: String? = null,
            searchType: SearchContentType = SearchContentType.BOOK,
            sourceKey: String? = null,
        ) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", searchScope)
                putExtra("searchType", searchType.name)
                putExtra("sourceKey", sourceKey)
            }
        }

        fun start(context: Context, source: BookSource, key: String? = null) =
            start(context, key, SearchScope(source).toString())

        fun start(context: Context, source: BookSourcePart, key: String? = null) =
            start(context, key, SearchScope(source).toString())
    }
}
