package io.legado.app.ui.main.discovery

import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.book.isNotShelf
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchContentType
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.homepage.PyHomepageViewModel
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一发现页 Fragment。
 *
 * 作为底部导航「发现」Tab 的容器，使用 ComposeView 承载 [DiscoverScreen]。
 * 顶栏（首页源式）与发现内容在此接线：
 * - 搜索跳转 [SearchActivity]、历史跳转 [DiscoverHistoryActivity]、设置跳转源管理
 * - 书源与订阅源在发现页显示分类和内容、首页源切换并展示首页内容
 */
class DiscoverFragment() : Fragment(), MainFragmentInterface {

    /** 发现页 ViewModel，绑定到 Activity 作用域，避免 Tab 切换时数据重载 */
    private val discoverViewModel: DiscoverViewModel by activityViewModels()

    /** 首页源 ViewModel，与首页 Tab 共享，复用首页源数据加载与播放能力 */
    private val pyViewModel: PyHomepageViewModel by activityViewModels()
    private val bookViewModel: DiscoverBookViewModel by activityViewModels()
    private val rssViewModel: DiscoverRssViewModel by activityViewModels()

    /**
     * 分类浏览位置记录缓存（分类键 -> 首个可见条目下标）。
     *
     * 仅当前源发现有效：切源由 DiscoverScreen 清空，切走本页（Tab 切换）在此清空。
     * 视图在离屏缓存中组合不销毁，必须显式清理。
     */
    private val scrollPositionCache = mutableMapOf<String, Int>()

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        if (!isVisibleToUser) scrollPositionCache.clear()
    }

    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        val uris = (result.uris + listOfNotNull(result.uri)).distinct()
        when (uris.size) {
            0 -> Unit
            1 -> pyViewModel.onFilePicked(uris.single(), getDisplayName(uris.single()))
            else -> pyViewModel.onFilesPicked(uris.map { it to getDisplayName(it) })
        }
    }

    private fun getDisplayName(uri: Uri): String = runCatching {
        requireContext().contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported.py"

    override fun onResume() {
        super.onResume()
        discoverViewModel.refresh()
        pyViewModel.refreshSources()
        updateStartHtmlNavigation()
    }

    override fun onPause() {
        (activity as? MainActivity)?.setMainBottomNavigationVisible(true)
        super.onPause()
    }

    private fun updateStartHtmlNavigation() {
        val source = discoverViewModel.currentSource.value
        (activity as? MainActivity)?.setMainBottomNavigationVisible(
            source?.type != DiscoverSourceType.RSS || source.startHtml.isNullOrBlank(),
        )
    }

    /** 底栏占用的底部高度（px），由 MainActivity 通知更新 */
    private var bottomPaddingPx by mutableIntStateOf(0)
    private var scrollToTopRequest by mutableIntStateOf(0)

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override fun updateMainBottomPadding(bottomPadding: Int) {
        bottomPaddingPx = bottomPadding
    }

    /** 底栏重复选中时通知 Compose 内容回到顶部 */
    fun scrollToTop() {
        scrollToTopRequest++
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        bottomPaddingPx = (activity as? MainActivity)?.mainContentBottomPadding() ?: 0
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DiscoverScreen(
                        viewModel = discoverViewModel,
                        bookViewModel = bookViewModel,
                        rssViewModel = rssViewModel,
                        pyViewModel = pyViewModel,
                        bottomPaddingPx = bottomPaddingPx,
                        scrollToTopRequest = scrollToTopRequest,
                        scrollPositionCache = scrollPositionCache,
                        onSearchRequested = {
                            val source = discoverViewModel.currentSource.value
                            val type = source?.pickerType?.let { SearchContentType.valueOf(it) }
                                ?: SearchContentType.BOOK
                            SearchActivity.start(
                                requireContext(), null,
                                searchScope = source?.takeIf { it.type == DiscoverSourceType.BOOK }
                                    ?.let { "${it.name.replace(":", "")}::${it.url}" },
                                searchType = type,
                                sourceKey = source?.let {
                                    if (it.type == DiscoverSourceType.HOME) it.key else it.id
                                },
                            )
                        },
                        onHistoryRequested = {
                            startActivity<DiscoverHistoryActivity>()
                        },
                        onSettingsRequested = {
                            startActivity<SourceManageActivity>()
                        },
                        onEditRequested = { source ->
                            when (source.type) {
                                DiscoverSourceType.BOOK -> startActivity<BookSourceEditActivity> {
                                    putExtra("sourceUrl", source.url)
                                }
                                DiscoverSourceType.RSS -> startActivity<RssSourceEditActivity> {
                                    putExtra("sourceUrl", source.url)
                                }
                                DiscoverSourceType.HOME -> Unit
                            }
                        },
                        onImportRequested = {
                            importDoc.launch {
                                mode = HandleFileContract.FILE
                                allowExtensions = arrayOf("py")
                                multiple = true
                            }
                        },
                        onNetworkImportRequested = { type, url ->
                            (activity as? MainActivity)?.openImportUi(type, url)
                        },
                        onStartHtmlVisibilityChanged = { visible ->
                            (activity as? MainActivity)?.setMainBottomNavigationVisible(!visible)
                            if (visible) bottomPaddingPx = 0
                            else bottomPaddingPx = (activity as? MainActivity)?.mainContentBottomPadding() ?: 0
                        },
                        onLoginRequested = { source ->
                            startActivity<SourceLoginActivity> {
                                putExtra(
                                    "type",
                                    if (source.type == DiscoverSourceType.BOOK) "bookSource" else "rssSource"
                                )
                                putExtra("key", source.url)
                            }
                        },
                        onHomeSourceTapped = { source ->
                            pyViewModel.selectSource(source.id)
                        },
                        onBookTapped = { book ->
                            if (book.type and BookType.video > 0) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val inBookshelf = withContext(IO) {
                                        appDb.bookDao.getBook(book.bookUrl)?.isNotShelf == false
                                    }
                                    startActivity<VideoPlayerActivity> {
                                        putExtra("bookUrl", book.bookUrl)
                                        putExtra("inBookshelf", inBookshelf)
                                        putExtra(PreferKey.layoutModeOverride, 1)
                                    }
                                }
                            } else {
                                startActivity<BookInfoActivity> {
                                    putExtra("name", book.name)
                                    putExtra("author", book.author)
                                    putExtra("bookUrl", book.bookUrl)
                                }
                            }
                        },
                        onArticleTapped = { article, origin, articles, category, nextPageUrl, page ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                val source = withContext(IO) { appDb.rssSourceDao.getByKey(origin) }
                                ReadRss.readRss(
                                    this@DiscoverFragment, article, source, articles,
                                    category?.first, category?.second ?: source?.sourceUrl,
                                    nextPageUrl, page,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

}
