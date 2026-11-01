package io.legado.app.ui.main.homepage

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.help.python.PyLoader
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.theme.LegadoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页 Fragment
 *
 * 作为首页在 MainActivity 中的容器，使用 ComposeView 承载 Compose 界面。
 * 统一展示订阅式顶栏首页（PyHomepageScreen）：
 * - 顶栏：当前源名称 + 搜索/历史/收藏/分组/设置
 * - 内容区默认空白；开启「自定义首页」后显示原首页推荐模块
 * - py 源通过设置菜单导入（SAF），导入后即可搜索
 */
class HomepageFragment() : Fragment(), MainFragmentInterface {

    /**
     * 使用 activityViewModels 将 ViewModel 作用域绑定到 Activity，
     * 避免 FragmentStatePagerAdapter 销毁 Fragment 时 ViewModel 被一同清除，
     * 导致切回首页时所有模块数据重新加载。
     */
    private val viewModel: HomepageViewModel by activityViewModels()

    private val pyViewModel: PyHomepageViewModel by activityViewModels()

    /** 底栏占用的底部高度（px），由 MainActivity 通知更新，用于 Compose 内容的底部 padding */
    private var bottomPaddingPx by mutableIntStateOf(0)

    /** SAF 导入 py 源 */
    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = PyLoader.installFromUri(uri)
            withContext(Dispatchers.Main) {
                context?.let {
                    Toast.makeText(
                        it,
                        if (ok) "py 源导入成功" else "py 源导入失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                pyViewModel.refreshPlugins()
            }
        }
    }

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override fun updateMainBottomPadding(bottomPadding: Int) {
        bottomPaddingPx = bottomPadding
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 初始化时获取当前底栏高度
        bottomPaddingPx = (activity as? MainActivity)?.mainContentBottomPadding() ?: 0
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    PyHomepageScreen(
                        viewModel = pyViewModel,
                        homepageViewModel = viewModel,
                        bottomPaddingPx = bottomPaddingPx,
                        onImportPySource = {
                            importDoc.launch {
                                mode = HandleFileContract.FILE
                                allowExtensions = arrayOf("py")
                            }
                        },
                        onBookClick = { name, author, bookUrl, origin, coverPath ->
                            // RSS 订阅源文章 → 直接加载文章 URL（openUrl 路径，不依赖 DB 预存）
                            if (origin != null && appDb.rssSourceDao.has(origin)) {
                                ReadRssActivity.start(
                                    context = requireContext(),
                                    singleTop = false,
                                    origin = origin,
                                    title = name,
                                    url = bookUrl
                                )
                                return@PyHomepageScreen
                            }
                            // 书源书籍 → 跳转详情页
                            val intent = Intent(context, BookInfoActivity::class.java).apply {
                                putExtra("name", name)
                                putExtra("author", author)
                                putExtra("bookUrl", bookUrl)
                                origin?.let { putExtra("origin", it) }
                                coverPath?.let { putExtra("coverPath", it) }
                            }
                            startActivity(intent)
                        },
                        onModuleHeaderClick = { title, sourceUrl, exploreUrl ->
                            // RSS 订阅源模块 → 跳转订阅源文章列表，自动选中对应分类
                            if (appDb.rssSourceDao.has(sourceUrl)) {
                                val intent = Intent(context, RssSortActivity::class.java).apply {
                                    putExtra("sourceUrl", sourceUrl)
                                    if (!title.isNullOrBlank()) {
                                        putExtra("sortName", title)
                                    }
                                }
                                startActivity(intent)
                                return@PyHomepageScreen
                            }
                            // 书源模块 → 跳转发现页
                            if (exploreUrl.isNullOrBlank()) return@PyHomepageScreen
                            val intent = Intent(context, ExploreShowActivity::class.java).apply {
                                putExtra("exploreName", title ?: "")
                                putExtra("sourceUrl", sourceUrl)
                                putExtra("exploreUrl", exploreUrl)
                            }
                            startActivity(intent)
                        },
                    )
                }
            }
        }
    }
}
