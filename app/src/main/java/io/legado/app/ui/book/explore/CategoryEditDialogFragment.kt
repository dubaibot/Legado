package io.legado.app.ui.book.explore

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.rule.ExploreCatNode
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.DialogCategoryEditBinding
import io.legado.app.databinding.DialogCategoryFilterBinding
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.explore.ExploreKindRenderer
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 分类编辑弹窗:一级/二级/三级页签 + 全量分类网格toggle添加移除
 * 顶部入口:筛选设置(二级弹窗)/书源登录,底部清空重建/清空
 */
class CategoryEditDialogFragment : BaseDialogFragment(R.layout.dialog_category_edit) {

    private val binding by viewBinding(DialogCategoryEditBinding::bind)
    private val viewModel by activityViewModels<ExploreShowViewModel>()
    private var currentLevel = 1
    private var selectedParent: ExploreCatNode? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.75).toInt()
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.apply {
            navigationIcon = AppCompatResources.getDrawable(
                requireContext(), R.drawable.ic_baseline_close
            )
            setNavigationOnClickListener { dismiss() }
        }
        binding.tvFilterSettings.setOnClickListener { showFilterSettings() }
        binding.tvSourceLogin.setOnClickListener { loginSource() }
        binding.tvTabL1.setOnClickListener { switchLevel(1) }
        binding.tvTabL2.setOnClickListener { switchLevel(2) }
        binding.tvTabL3.setOnClickListener { switchLevel(3) }
        binding.btnResetPreset.setOnClickListener { confirmResetPreset() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }
        viewModel.kindsLoadedLiveData.observe(this) { upAll() }
        viewModel.panelRefreshLiveData.observe(this) { upAll() }
        upEntries()
        upAll()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ExploreKindRenderer.saveInfoMaps()
        viewModel.onFilterClosed()
    }

    private fun showFilterSettings() {
        CategoryFilterDialog().show(parentFragmentManager, "categoryFilter")
    }

    private fun loginSource() {
        val source = viewModel.sourceData.value ?: return
        startActivity<SourceLoginActivity> {
            putExtra("key", source.bookSourceUrl)
            putExtra("type", "bookSource")
        }
    }

    private fun switchLevel(level: Int) {
        currentLevel = level
        selectedParent = null
        upAll()
    }

    /** 当前挂靠父级:优先记忆的选中项,失效则取第一个 */
    private fun currentParent(): ExploreCatNode? {
        if (currentLevel == 1) {
            return null
        }
        val chips = viewModel.parentChips(currentLevel)
        return selectedParent?.let { p -> chips.firstOrNull { it.sameRefAs(p) } }
            ?: chips.firstOrNull()
    }

    private fun upEntries() {
        val hasFilter = viewModel.filterSettingKinds().isNotEmpty()
        val source = viewModel.sourceData.value
        val canLogin = source != null
                && (!source.loginUrl.isNullOrBlank() || !source.loginUi.isNullOrBlank())
        binding.llEntries.isVisible = hasFilter || canLogin
        binding.tvFilterSettings.isVisible = hasFilter
        binding.tvSourceLogin.isVisible = canLogin
    }

    private fun upAll() {
        upTabs()
        upParentChips()
        upGrid()
    }

    private fun upTabs() {
        binding.tvTabL1.isSelected = currentLevel == 1
        binding.tvTabL2.isSelected = currentLevel == 2
        binding.tvTabL3.isSelected = currentLevel == 3
    }

    private fun upParentChips() {
        val chips = viewModel.parentChips(currentLevel)
        if (currentLevel == 1 || chips.isEmpty()) {
            binding.hsvParent.gone()
            return
        }
        binding.hsvParent.visible()
        selectedParent = currentParent()
        val ll = binding.llParent
        ll.removeAllViews()
        val names = ExploreCatNode.displayNames(chips)
        chips.forEachIndexed { index, node ->
            val tv = layoutInflater.inflate(
                R.layout.item_quick_group, ll, false
            ) as TextView
            tv.text = names[index]
            tv.isSelected = selectedParent?.let { node.sameRefAs(it) } == true
            tv.setOnClickListener {
                selectedParent = node
                upParentChips()
            }
            ll.addView(tv)
        }
    }

    /** 分类网格:按面板分段标题分组展示,url分类toggle挂载,select控件弹选项列表按选项挂载 */
    private fun upGrid() {
        val ll = binding.llGrid
        ll.removeAllViews()
        val noParent = currentLevel > 1 && viewModel.parentChips(currentLevel).isEmpty()
        binding.tvHint.text = when {
            noParent && currentLevel == 2 -> getString(R.string.explore_need_parent_l1)
            noParent -> getString(R.string.explore_need_parent_l2)
            else -> getString(R.string.explore_grid_hint)
        }
        viewModel.panelGroups().forEach { group ->
            group.title?.let { title ->
                val tvTitle = layoutInflater.inflate(
                    R.layout.item_explore_group, ll, false
                ) as TextView
                tvTitle.text = title
                ll.addView(tvTitle)
            }
            if (group.kinds.isEmpty()) {
                return@forEach
            }
            val fbx = FlexboxLayout(requireContext())
            fbx.flexWrap = FlexWrap.WRAP
            group.kinds.forEach { kind ->
                fbx.addView(buildKindChip(fbx, kind, noParent))
            }
            ll.addView(fbx)
        }
    }

    private fun buildKindChip(parent: ViewGroup, kind: ExploreKind, noParent: Boolean): TextView {
        val tv = layoutInflater.inflate(
            R.layout.item_quick_group, parent, false
        ) as TextView
        tv.text = kind.title.ifBlank {
            getString(R.string.explore_unnamed_category)
        }
        tv.isEnabled = !noParent
        tv.alpha = if (noParent) 0.4f else 1f
        tv.isSelected = when (kind.type) {
            ExploreKind.Type.url -> viewModel.refAtLevel(currentLevel, kind, null)
            else -> viewModel.refKindAtLevel(currentLevel, kind)
        }
        tv.setOnClickListener {
            if (kind.type == ExploreKind.Type.select) {
                showOptionPopup(tv, kind)
            } else {
                viewModel.toggleAtLevel(currentLevel, kind, null, currentParent())
                upAll()
            }
        }
        tv.setOnLongClickListener {
            val node = viewModel.nodeAtLevel(currentLevel, kind, null)
            if (node != null && tv.isSelected) {
                showNodeInfo(node)
                true
            } else {
                false
            }
        }
        return tv.apply {
            layoutParams = FlexboxLayout.LayoutParams(
                FlexboxLayout.LayoutParams.WRAP_CONTENT,
                FlexboxLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                //item_quick_group 自带右距,补上行距保证多行不贴边
                topMargin = 8.dpToPx()
            }
        }
    }

    /** select控件选项弹出列表,点选项toggle该选项分类 */
    private fun showOptionPopup(anchor: View, kind: ExploreKind) {
        val chars = kind.chars?.filterNotNull().orEmpty()
        if (chars.isEmpty()) {
            return
        }
        val popup = ListPopupWindow(requireContext())
        popup.setAdapter(ArrayAdapter(requireContext(), R.layout.item_text_common, chars))
        popup.setAnchorView(anchor)
        popup.width = maxOf(anchor.width, 120.dpToPx())
        popup.setOnItemClickListener { _, _, position, _ ->
            chars.getOrNull(position)?.let { option ->
                viewModel.toggleAtLevel(currentLevel, kind, option, currentParent())
                upAll()
            }
            popup.dismiss()
        }
        popup.show()
    }

    /** 长按已选分类:展示完整引用信息,可删除 */
    private fun showNodeInfo(node: ExploreCatNode) {
        val refInfo = buildString {
            append(getString(R.string.explore_node_ref_info))
            append("\n")
            append("type: ${node.type}")
            append("\nkind: ${node.kindTitle}")
            node.url?.takeIf { it.isNotBlank() }?.let { append("\nurl: $it") }
            node.option?.let { append("\noption: $it") }
            node.action?.takeIf { it.isNotBlank() }?.let { append("\naction: ${it.take(300)}") }
        }
        AlertDialog.Builder(requireActivity())
            .setTitle(node.name)
            .setMessage(refInfo)
            .setPositiveButton(R.string.explore_node_delete) { _, _ ->
                viewModel.removeFromLevel(currentLevel, node)
                upAll()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmResetPreset() {
        AlertDialog.Builder(requireActivity())
            .setTitle(R.string.explore_reset_preset)
            .setMessage(R.string.explore_reset_preset_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.resetToPreset()
                selectedParent = null
                upAll()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(requireActivity())
            .setTitle(R.string.explore_clear_all)
            .setMessage(R.string.explore_clear_all_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.clearAll()
                selectedParent = null
                upAll()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

}

/**
 * 筛选设置二级弹窗:select/toggle/button完整交互,改动即存并执行action
 */
class CategoryFilterDialog : BaseDialogFragment(R.layout.dialog_category_filter) {

    private val binding by viewBinding(DialogCategoryFilterBinding::bind)
    private val viewModel by activityViewModels<ExploreShowViewModel>()
    private val renderer by lazy {
        ExploreKindRenderer(requireContext(), lifecycleScope, object : ExploreKindRenderer.Callback {
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

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.apply {
            navigationIcon = AppCompatResources.getDrawable(
                requireContext(), R.drawable.ic_baseline_close
            )
            setNavigationOnClickListener { dismiss() }
        }
        viewModel.panelRefreshLiveData.observe(viewLifecycleOwner) { upFilter() }
        upFilter()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ExploreKindRenderer.saveInfoMaps()
        viewModel.onPanelChanged()
    }

    private fun upFilter() {
        val sourceUrl = viewModel.sourceData.value?.bookSourceUrl ?: return
        renderer.render(
            binding.fbxFilter,
            viewModel.filterSettingKinds(),
            sourceUrl,
            ExploreKindRenderer.Mode.FILTER_SETTINGS
        )
    }

}
