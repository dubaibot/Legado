package io.legado.app.ui.book.read.ai

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAiPurifyConfirmBinding
import io.legado.app.help.ai.AiPurifyResult
import io.legado.app.utils.GSON
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 划词净化结果确认对话框（D15/5.2.2）。
 *
 * 展示原文 / 净化文本 / 删除内容预览与统计，支持重试、取消、应用。
 * 应用语义与 ng 一致：生成「AI净化」替换规则（plain text，作用正文）。
 */
class AiPurifyConfirmDialog() : BaseDialogFragment(R.layout.dialog_ai_purify_confirm, true) {

    private val binding by viewBinding(DialogAiPurifyConfirmBinding::bind)

    private var result: AiPurifyResult? = null
    private var onApply: ((AiPurifyResult) -> Unit)? = null
    private var onRetry: ((String) -> Unit)? = null

    constructor(result: AiPurifyResult) : this() {
        arguments = Bundle().apply {
            putString("result", GSON.toJson(result))
        }
    }

    fun onApply(block: (AiPurifyResult) -> Unit): AiPurifyConfirmDialog {
        onApply = block
        return this
    }

    fun onRetry(block: (String) -> Unit): AiPurifyConfirmDialog {
        onRetry = block
        return this
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val json = arguments?.getString("result")
        val purifyResult = json?.let {
            runCatching { GSON.fromJson(it, AiPurifyResult::class.java) }.getOrNull()
        } ?: let {
            dismiss()
            null
        } ?: return
        result = purifyResult

        val infoBuilder = StringBuilder()
        infoBuilder.append(getString(R.string.ai_purify_deleted_count, purifyResult.deletedCount))
        infoBuilder.append(" · ")
        infoBuilder.append(getString(R.string.ai_purify_replacement_count, purifyResult.replacementCount))
        purifyResult.model?.takeIf { it.isNotBlank() }?.let {
            infoBuilder.append(" · ")
            infoBuilder.append(getString(R.string.ai_purify_model_label, it))
        }
        binding.tvInfo.text = infoBuilder.toString()

        purifyResult.riskReason?.takeIf { it.isNotBlank() }?.let {
            binding.tvRisk.visible()
            binding.tvRisk.text = getString(R.string.ai_purify_risk, it)
        }

        binding.tvOriginal.text = purifyResult.original
        binding.tvCleaned.text = purifyResult.cleaned
        binding.tvDeleted.text = purifyResult.deletedPreview.ifBlank {
            getString(R.string.ai_purify_deleted_empty)
        }

        binding.btnApply.setOnClickListener {
            dismiss()
            result?.let { onApply?.invoke(it) }
        }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnRetry.setOnClickListener {
            val source = result?.original.orEmpty()
            dismiss()
            if (source.isNotBlank()) {
                onRetry?.invoke(source)
            }
        }
    }
}
