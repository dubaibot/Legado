package io.legado.app.ui.book.read.ai

import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.ai.AiConfig
import io.legado.app.help.ai.AiPurifyHelper
import io.legado.app.help.ai.AiPurifyResult
import io.legado.app.help.book.ContentProcessor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读界面 AI 助读动作集合（D15/5.2.2 划词净化）。
 *
 * 交互与 ng 对齐：
 * - 划词 → WaitDialog 进度 → AiPurifyHelper.purify
 * - 自动应用口径：AiConfig.purifyAutoApply 且结果有变化，且（purifyExceptionIntercept 关闭或 canAutoApply）
 * - 应用 = 生成「AI净化」替换规则（非正则、作用正文）并启用书籍替换开关
 */
object AiReadActions {

    fun purifySelectedText(activity: ReadBookActivity, selectedText: String) {
        val source = AiPurifyHelper.normalizeSelectedText(selectedText)
        if (source.isBlank()) {
            activity.toastOnUi(activity.getString(R.string.ai_purify_text_empty))
            return
        }
        val waitDialog = WaitDialog(activity).apply {
            setText(activity.getString(R.string.ai_purify_progress))
            show()
        }
        activity.lifecycleScope.launch {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                val result = withContext(Dispatchers.IO) {
                    AiPurifyHelper.purify(source)
                }
                withContext(Dispatchers.Main) {
                    waitDialog.dismiss()
                    if (shouldAutoApply(result)) {
                        applyResult(activity, result)
                    } else {
                        activity.showDialogFragment(
                            AiPurifyConfirmDialog(result)
                                .onApply { applyResult(activity, it) }
                                .onRetry { retrySource ->
                                    purifySelectedText(activity, retrySource)
                                }
                        )
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    waitDialog.dismiss()
                    activity.toastOnUi(
                        activity.getString(R.string.ai_purify_failed, e.message ?: "ERROR")
                    )
                }
            }
        }
    }

    private fun shouldAutoApply(result: AiPurifyResult): Boolean {
        if (!AiConfig.purifyAutoApply || result.original == result.cleaned) {
            return false
        }
        return !AiConfig.purifyExceptionIntercept || result.canAutoApply
    }

    fun applyResult(activity: ReadBookActivity, result: AiPurifyResult) {
        if (result.original == result.cleaned) {
            activity.toastOnUi(activity.getString(R.string.ai_purify_no_change))
            return
        }
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val maxOrder = appDb.replaceRuleDao.maxOrder
                    val baseId = System.currentTimeMillis()
                    val rule = ReplaceRule(
                        id = baseId,
                        name = result.original.ruleNamePreview(),
                        group = "AI净化",
                        pattern = result.original,
                        replacement = result.cleaned,
                        scope = currentReplaceScope(),
                        scopeTitle = false,
                        scopeContent = true,
                        isEnabled = true,
                        isRegex = false,
                        timeoutMillisecond = 3000L,
                        order = maxOrder + 1
                    )
                    appDb.replaceRuleDao.insert(rule)
                    ContentProcessor.upReplaceRules()
                }
                ReadBook.book?.let { book ->
                    if (!book.getUseReplaceRule()) {
                        book.setUseReplaceRule(true)
                        ReadBook.saveRead()
                    }
                }
                activity.toastOnUi(activity.getString(R.string.ai_purify_saved))
            } catch (e: Throwable) {
                activity.toastOnUi(
                    activity.getString(R.string.ai_purify_failed, e.message ?: "ERROR")
                )
            }
        }
    }

    private fun currentReplaceScope(): String {
        val scopes = arrayListOf<String>()
        ReadBook.book?.name?.let { scopes.add(it) }
        ReadBook.bookSource?.bookSourceUrl?.let { scopes.add(it) }
        return scopes.joinToString(";")
    }

    private fun String.ruleNamePreview(): String {
        val compact = replace("\n", " ").trim()
        return "AI净化: " + if (compact.length <= 40) compact else compact.take(40) + "..."
    }
}
