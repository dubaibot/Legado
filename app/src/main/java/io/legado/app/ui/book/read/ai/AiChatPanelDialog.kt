package io.legado.app.ui.book.read.ai

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogAiChatPanelBinding
import io.legado.app.databinding.ItemAiChatMessageBinding
import io.legado.app.help.ai.AiConfig
import io.legado.app.help.ai.AiManager
import io.legado.app.help.ai.AiMessage
import io.legado.app.help.ai.AiModelAbility
import io.legado.app.help.ai.AiProviderStore
import io.legado.app.help.ai.AiTextParams
import io.legado.app.model.ReadBook
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书籍对话浮层（D15/5.2.4，参照 ng 书籍对话重写为 Sigma View 面板）。
 *
 * 第一版能力：
 * - 携带书籍上下文（书名 / 作者 / 当前章节）的多轮对话
 * - 使用「AI 助理」场景模型（AiConfig.requireAssistantModel），一次请求完整返回
 * - 会话内存态保存，支持新对话；技能与 MCP 工具链后置
 */
class AiChatPanelDialog() : BaseDialogFragment(R.layout.dialog_ai_chat_panel, true) {

    private val binding by viewBinding(DialogAiChatPanelBinding::bind)
    private val messages = mutableListOf<AiChatMessage>()
    private val adapter = MessagesAdapter()
    private var sendJob: Job? = null

    constructor(book: Book?) : this() {
        arguments = Bundle().apply {
            book?.let {
                putString("bookName", it.name)
                putString("bookAuthor", it.author)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.72f).toInt()
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = adapter

        val bookName = arguments?.getString("bookName").orEmpty()
        if (bookName.isNotBlank()) {
            binding.tvTitle.text = getString(R.string.ai_chat_title_book, bookName)
        }

        if (messages.isEmpty()) {
            appendStatus(getString(R.string.ai_chat_welcome))
        }

        binding.tvNewChat.setOnClickListener {
            messages.clear()
            appendStatus(getString(R.string.ai_chat_welcome))
            adapter.submitList(messages.toList())
        }
        binding.ivClose.setOnClickListener { dismiss() }
        binding.ivSend.setOnClickListener { sendCurrentInput() }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun sendCurrentInput() {
        val input = binding.etInput.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) return
        if (sendJob?.isActive == true) {
            toastOnUi(R.string.ai_chat_sending)
            return
        }
        binding.etInput.setText("")
        appendUser(input)
        val status = appendStatus(getString(R.string.ai_chat_thinking))

        val bookName = arguments?.getString("bookName").orEmpty()
        val bookAuthor = arguments?.getString("bookAuthor").orEmpty()
        val chapterTitle = ReadBook.book?.let { book ->
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)?.title
        }.orEmpty()
        val systemPrompt = buildSystemPrompt(bookName, bookAuthor, chapterTitle)

        sendJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val selection = AiConfig.requireAssistantModel()
                val provider = AiProviderStore.provider(selection.providerId)
                    ?: error(getString(R.string.ai_chat_provider_missing))
                val supportsReasoning = provider.models
                    .firstOrNull { it.id == selection.modelId }
                    ?.abilities?.contains(AiModelAbility.REASONING) == true
                val history = messages
                    .filter { it.kind == AiChatMessage.Kind.TEXT }
                    .map { AiMessage(AiMessage.Role.valueOf(it.role.name), it.content) }
                val aiMessages = buildList {
                    add(AiMessage(AiMessage.Role.SYSTEM, systemPrompt))
                    addAll(history)
                }
                val result = withContext(Dispatchers.IO) {
                    AiManager.generateText(
                        messages = aiMessages,
                        params = AiConfig.assistantChatParams(supportsReasoning),
                        providerId = selection.providerId,
                        modelId = selection.modelId
                    )
                }
                val content = result.content.trim()
                if (content.isBlank()) {
                    replaceStatus(status, getString(R.string.ai_chat_empty_reply))
                } else {
                    removeMessage(status)
                    appendAssistant(content)
                }
            } catch (e: Throwable) {
                replaceStatus(
                    status,
                    getString(R.string.ai_chat_failed, e.message ?: "ERROR")
                )
            } finally {
                sendJob = null
            }
        }
    }

    private fun buildSystemPrompt(
        bookName: String,
        bookAuthor: String,
        chapterTitle: String
    ): String {
        val sb = StringBuilder()
        sb.append(getString(R.string.ai_chat_system_base))
        if (bookName.isNotBlank()) {
            if (bookAuthor.isNotBlank()) {
                sb.append(getString(R.string.ai_chat_system_book_author, bookName, bookAuthor))
            } else {
                sb.append(getString(R.string.ai_chat_system_book, bookName))
            }
        }
        if (chapterTitle.isNotBlank()) {
            sb.append(getString(R.string.ai_chat_system_chapter, chapterTitle))
        }
        return sb.toString()
    }

    private fun appendUser(content: String) {
        messages.add(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = content
            )
        )
        adapter.submitList(messages.toList())
        scrollToBottom()
    }

    private fun appendAssistant(content: String) {
        messages.add(
            AiChatMessage(
                role = AiChatMessage.Role.ASSISTANT,
                content = content
            )
        )
        adapter.submitList(messages.toList())
        scrollToBottom()
    }

    private fun appendStatus(label: String): AiChatMessage {
        val message = AiChatMessage(
            role = AiChatMessage.Role.ASSISTANT,
            content = label,
            kind = AiChatMessage.Kind.STATUS,
            statusLabel = label
        )
        messages.add(message)
        adapter.submitList(messages.toList())
        scrollToBottom()
        return message
    }

    private fun replaceStatus(message: AiChatMessage, label: String) {
        val index = messages.indexOf(message)
        if (index >= 0) {
            messages[index] = message.copy(statusLabel = label)
            adapter.submitList(messages.toList())
        }
    }

    private fun removeMessage(message: AiChatMessage) {
        messages.remove(message)
        adapter.submitList(messages.toList())
    }

    private fun scrollToBottom() {
        binding.rvMessages.post {
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private class MessagesAdapter :
        ListAdapter<AiChatMessage, RecyclerView.ViewHolder>(Diff) {

        override fun getItemViewType(position: Int): Int {
            return when (getItem(position).kind) {
                AiChatMessage.Kind.STATUS -> TYPE_STATUS
                else -> TYPE_TEXT
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_TEXT -> TextHolder(
                    ItemAiChatMessageBinding.inflate(inflater, parent, false)
                )

                else -> StatusHolder(
                    ItemAiChatMessageBinding.inflate(inflater, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val message = getItem(position)
            when (holder) {
                is TextHolder -> {
                    val isUser = message.role == AiChatMessage.Role.USER
                    holder.binding.llBubble.gravity =
                        if (isUser) android.view.Gravity.END else android.view.Gravity.START
                    holder.binding.tvContent.text = message.content
                    holder.binding.tvContent.setBackgroundResource(
                        if (isUser) {
                            R.drawable.bg_ai_chat_user
                        } else {
                            R.drawable.bg_ai_chat_assistant
                        }
                    )
                    holder.binding.tvStatus.visibility = View.GONE
                }

                is StatusHolder -> {
                    holder.binding.tvStatus.visible()
                    holder.binding.tvStatus.text = message.statusLabel.orEmpty()
                    holder.binding.llBubble.visibility = View.GONE
                }
            }
        }

        class TextHolder(val binding: ItemAiChatMessageBinding) :
            RecyclerView.ViewHolder(binding.root)

        class StatusHolder(val binding: ItemAiChatMessageBinding) :
            RecyclerView.ViewHolder(binding.root)

        private object Diff : DiffUtil.ItemCallback<AiChatMessage>() {
            override fun areItemsTheSame(old: AiChatMessage, new: AiChatMessage): Boolean {
                return old.id == new.id
            }

            override fun areContentsTheSame(old: AiChatMessage, new: AiChatMessage): Boolean {
                return old.statusLabel == new.statusLabel && old.content == new.content
            }
        }

        companion object {
            private const val TYPE_TEXT = 0
            private const val TYPE_STATUS = 1
        }
    }
}
