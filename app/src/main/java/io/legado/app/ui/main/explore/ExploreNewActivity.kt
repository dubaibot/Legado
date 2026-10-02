package io.legado.app.ui.main.explore

import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ActivityExploreNewBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class ExploreNewActivity : VMBaseActivity<ActivityExploreNewBinding, ExploreNewViewModel>(),
    ExploreNewSourceAdapter.CallBack {

    override val binding by viewBinding(ActivityExploreNewBinding::inflate)
    override val viewModel by viewModels<ExploreNewViewModel>()

    private val adapter by lazy { ExploreNewSourceAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.rvSource.layoutManager = GridLayoutManager(this, 3)
        binding.rvSource.adapter = adapter
        viewModel.sourcesLiveData.observe(this) {
            binding.tvEmpty.isVisible = it.isEmpty()
            adapter.setItems(it)
        }
        initGroupBar()
        loadFirstGroup()
    }

    private fun initGroupBar() {
        ExploreQuickGroups.setupQuickGroupBar(
            container = binding.llGroup,
            inflater = layoutInflater,
            onClickGroup = { group ->
                viewModel.loadSources(group)
            },
            onLongClickGroup = { group ->
                startActivity<BookSourceActivity> {
                    putExtra("group", group)
                }
                true
            },
            onConfig = {
                ExploreQuickGroups.showQuickGroupConfigDialog(this, appDb.bookSourceDao) {
                    initGroupBar()
                }
            }
        )
    }

    private fun loadFirstGroup() {
        val firstGroup = LocalConfig.exploreQuickGroups.split(",")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
        firstGroup?.let { viewModel.loadSources(it) }
    }

    override fun openExplore(source: BookSourcePart) {
        if (!source.hasExploreUrl) {
            toastOnUi(R.string.explore_empty)
            return
        }
        val exploreUrl = appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.exploreUrl
        if (exploreUrl.isNullOrBlank()) {
            toastOnUi(R.string.explore_empty)
            return
        }
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", source.bookSourceName)
            putExtra("sourceUrl", source.bookSourceUrl)
            putExtra("exploreUrl", exploreUrl)
            putExtra("newStyle", true)
        }
    }
}
