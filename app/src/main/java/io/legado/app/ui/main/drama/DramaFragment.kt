package io.legado.app.ui.main.drama

import android.graphics.Color
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
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.theme.LegadoTheme

/**
 * 短剧信息流页。
 *
 * 内容来自用户勾选的已导入源，竖滑切换，点击进入既有播放器。
 */
class DramaFragment() : Fragment(), MainFragmentInterface {

    private var bottomPaddingPx by mutableIntStateOf(0)
    private var scrollToTopTick by mutableIntStateOf(0)

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
        bottomPaddingPx = (activity as? MainActivity)?.mainContentBottomPadding() ?: 0
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ShortDramaScreen(
                        bottomPaddingPx = bottomPaddingPx,
                        scrollToTopTick = scrollToTopTick,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 短剧页全屏播放：底栏完全透明，画面铺到屏幕底部，页面切换/退到后台时由 onPause 还原
        (activity as? MainActivity)?.setBottomBarColorOverride(Color.TRANSPARENT)
    }

    override fun onPause() {
        super.onPause()
        (activity as? MainActivity)?.setBottomBarColorOverride(null)
    }

    fun scrollToTop() {
        scrollToTopTick++
    }
}