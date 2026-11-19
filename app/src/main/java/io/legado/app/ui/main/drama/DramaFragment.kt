package io.legado.app.ui.main.drama

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.pageSecondaryTextColor

/**
 * 短剧占位页。
 *
 * 短剧功能尚未开发，此处保留空白占位，显示「短剧功能开发中」。
 */
class DramaFragment() : Fragment(), MainFragmentInterface {

    private var bottomPaddingPx by mutableIntStateOf(0)

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
                    DramaPlaceholder(bottomPaddingPx = bottomPaddingPx)
                }
            }
        }
    }

    fun scrollToTop() = Unit
}

@Composable
private fun DramaPlaceholder(bottomPaddingPx: Int) {
    val bottomPadding = with(LocalDensity.current) { bottomPaddingPx.toDp() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = bottomPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "短剧功能开发中",
            style = MaterialTheme.typography.titleMedium,
            color = pageSecondaryTextColor(),
            textAlign = TextAlign.Center,
        )
    }
}
