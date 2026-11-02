package io.legado.app.ui.main.homepage

import android.os.Bundle
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
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainFragmentInterface
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
 * - 设置菜单：导入（py/html 文件，支持自定义名称）、管理（类似订阅源管理）
 * - 内容区：py 源显示搜索结果，HTML 页面用 WebView 渲染
 */
class HomepageFragment() : Fragment(), MainFragmentInterface {

    /**
     * 使用 activityViewModels 将 ViewModel 作用域绑定到 Activity，
     * 避免 FragmentStatePagerAdapter 销毁 Fragment 时 ViewModel 被一同清除，
     * 导致切回首页时所有模块数据重新加载。
     */
    private val pyViewModel: PyHomepageViewModel by activityViewModels()

    /** 底栏占用的底部高度（px），由 MainActivity 通知更新，用于 Compose 内容的底部 padding */
    private var bottomPaddingPx by mutableIntStateOf(0)

    /** SAF 导入 py/html 源 */
    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        val defaultName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: ""
        pyViewModel.onFilePicked(uri, defaultName)
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
                        bottomPaddingPx = bottomPaddingPx,
                        onImportRequested = {
                            importDoc.launch {
                                mode = HandleFileContract.FILE
                                allowExtensions = arrayOf("py", "html", "htm")
                            }
                        },
                    )
                }
            }
        }
    }
}
