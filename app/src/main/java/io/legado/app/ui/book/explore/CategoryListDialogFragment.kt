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
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.DialogCategoryListBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.explore.ExploreKindRenderer
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 分类列表弹窗:平铺所有分类,checkbox表示是否在树中,点击toggle添加/移除
 * 保留筛选设置入口和清空重建/清空按钮
 */
class CategoryListDialogFragment : BaseDialogFragment(R.layout.dialog_category_list) {

    private val binding by viewBinding(DialogCategoryListBinding::bind)
    private val viewModel by activityViewModels<ExploreShowViewModel>()

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

    private fun upEntries() {
        val hasFilter = viewModel.filterSettingKinds().isNotEmpty()
        val source = viewModel.sourceData.value
        val canLogin = source != null
                && (!source.loginUrl.isNullOrBlank() || !source.loginUi.isNullOrBlank())
        binding.llEntries.isVisible = true
        binding.tvFilterSettings.isVisible = true
        binding.tvSourceLogin.isVisible = canLogin
    }

    private fun upAll() {
        upList()
    }

    /** 分类列表:按面板分段标题分组,url分类平铺toggle,select控件弹选项列表按选项toggle */
    private fun upList() {
        val ll = binding.llList
        ll.removeAllViews()
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
                fbx.addView(buildKindRow(fbx, kind))
            }
            ll.addView(fbx)
        }
        if (viewModel.panelGroups().all { it.kinds.isEmpty() }) {
            binding.tvHint.text = getString(R.string.no_explore_kind)
        } else {
            binding.tvHint.text = getString(R.string.explore_grid_hint)
        }
    }

    private fun buildKindRow(parent: ViewGroup, kind: ExploreKind): View {
        val row = layoutInflater.inflate(
            R.layout.item_category_row, parent, false
        )
        val tvName = row.findViewById<TextView>(R.id.tv_row_name)
        val tvCheck = row.findViewById<TextView>(R.id.tv_row_check)
        tvName.text = kind.title.ifBlank {
            getString(R.string.explore_unnamed_category)
        }
        val exists = viewModel.existsAtAnyLevel(kind, null)
        tvCheck.text = if (exists) "✓" else ""
        tvCheck.setTextColor(
            if (exists) {
                requireContext().accentColor
            } else {
                android.graphics.Color.TRANSPARENT
            }
        )
        tvName.setOnClickListener {
            if (kind.type == ExploreKind.Type.select) {
                showOptionPopup(row, kind)
            } else {
                viewModel.toggleAtAnyLevel(kind, null)
                upAll()
            }
        }
        tvName.setOnLongClickListener {
            val node = viewModel.nodeAtAnyLevel(kind, null)
            if (node != null) {
                showNodeInfo(node)
                true
            } else {
                false
            }
        }
        return row.apply {
            layoutParams = FlexboxLayout.LayoutParams(
                FlexboxLayout.LayoutParams.WRAP_CONTENT,
                FlexboxLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4.dpToPx()
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
                viewModel.toggleAtAnyLevel(kind, option)
                upAll()
            }
            popup.dismiss()
        }
        popup.show()
    }

    /** 长按已选分类:展示完整引用信息,可删除 */
    private fun showNodeInfo(node: io.legado.app.data.entities.rule.ExploreCatNode) {
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
                viewModel.removeNodeAtAnyLevel(node)
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
                upAll()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

}
