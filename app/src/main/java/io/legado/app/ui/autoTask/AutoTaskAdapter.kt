package io.legado.app.ui.autoTask

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.base.adapter.SelectableAdapter
import io.legado.app.databinding.ItemManageBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.startActivity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.visible
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoTaskAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<AutoTaskRule, ItemManageBinding>(context),
    ItemTouchCallback.Callback,
    SelectableAdapter<AutoTaskRule, String> {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override val selectedKeys = linkedSetOf<String>()

    override fun keyOf(item: AutoTaskRule): String = item.id

    override fun onSelectionChanged() {
        callBack.upCountView()
    }

    val diffItemCallBack = object : DiffUtil.ItemCallback<AutoTaskRule>() {
        override fun areItemsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: AutoTaskRule, newItem: AutoTaskRule): Any? {
            val payload = Bundle()
            if (oldItem.name != newItem.name) {
                payload.putBoolean("name", true)
            }
            if (oldItem.enable != newItem.enable) {
                payload.putBoolean("enabled", true)
            }
            if (oldItem.lastRunAt != newItem.lastRunAt ||
                oldItem.lastError != newItem.lastError ||
                oldItem.cron != newItem.cron
            ) {
                payload.putBoolean("summary", true)
            }
            return if (payload.isEmpty) null else payload
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemManageBinding {
        return ItemManageBinding.inflate(inflater, parent, false).apply {
            tvSubtitle.visible()
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemManageBinding,
        item: AutoTaskRule,
        payloads: MutableList<Any>
    ) {
        // 卡底色由换肤引擎按布局 skin_background 施加;沉浸式同样呈卡片
        if (payloads.isEmpty()) {
            binding.cbName.text = item.name.ifBlank { item.id }
            binding.swtEnabled.isChecked = item.enable
            binding.tvSubtitle.text = buildSummary(item)
            binding.cbName.isChecked = isSelected(item)
            upSelectStroke(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as? Bundle ?: continue
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> binding.cbName.text = item.name.ifBlank { item.id }
                        "enabled" -> binding.swtEnabled.isChecked = item.enable
                        "summary" -> binding.tvSubtitle.text = buildSummary(item)
                    }
                }
            }
            binding.cbName.isChecked = isSelected(item)
            upSelectStroke(binding, item)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemManageBinding) {
        binding.cbName.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                getItem(holder.layoutPosition)?.let { task ->
                    setSelected(task, isChecked)
                    upSelectStroke(binding, task)
                    callBack.upCountView()
                }
            }
        }
        binding.swtEnabled.setOnCheckedChangeListener { buttonView, isChecked ->
            val item = getItem(holder.layoutPosition) ?: return@setOnCheckedChangeListener
            if (buttonView.isPressed) {
                callBack.toggle(item, isChecked)
            }
        }
        binding.ivEdit.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.edit(it) }
        }
        binding.ivMenuMore.setOnClickListener { view ->
            getItem(holder.layoutPosition)?.let { showMenu(view, it) }
        }
        binding.contentLayout.setOnClickListener {
            getItem(holder.layoutPosition)?.let { task ->
                val nowSelected = !isSelected(task)
                setSelected(task, nowSelected)
                binding.cbName.isChecked = nowSelected
                upSelectStroke(binding, task)
                callBack.upCountView()
            }
        }
    }

    override fun onCurrentListChanged() {
        val currentIds = getItems().map { it.id }.toHashSet()
        val iterator = selectedKeys.iterator()
        while (iterator.hasNext()) {
            if (!currentIds.contains(iterator.next())) {
                iterator.remove()
            }
        }
        callBack.upCountView()
    }

    // ItemTouchCallback.Callback
    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        swapItem(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        callBack.upOrder(getItems())
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<String>(DragSelectTouchHelper.AdvanceCallback.Mode.ToggleAndReverse) {
            override fun currentSelectedId(): Set<String> {
                return selectedKeys
            }

            override fun getItemId(position: Int): String {
                return getItem(position)!!.id
            }

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                getItem(position)?.let { task ->
                    setSelected(task, isSelected)
                    notifyItemChanged(position, bundleOf(Pair("selected", null)))
                    callBack.upCountView()
                    return true
                }
                return false
            }
        }

    private fun upSelectStroke(binding: ItemManageBinding, task: AutoTaskRule) {
        binding.rootCard.strokeColor = context.accentColor
        binding.rootCard.strokeWidth = if (isSelected(task)) 2.dpToPx() else 0
    }

    private fun buildSummary(task: AutoTaskRule): String {
        val cron = task.cron?.trim().orEmpty().ifBlank { "-" }
        val status = when {
            !task.lastError.isNullOrBlank() ->
                context.getString(R.string.auto_task_last_error, task.lastError)
            task.lastRunAt > 0L ->
                context.getString(
                    R.string.auto_task_last_run,
                    timeFormat.format(Date(task.lastRunAt))
                )
            else -> context.getString(R.string.auto_task_not_run)
        }
        return context.getString(R.string.auto_task_item_summary, cron, status)
    }

    private fun showMenu(view: View, task: AutoTaskRule) {
        popupActionMenu(context) {
            item(context.getString(R.string.login), "login", visible = !task.loginUrl.isNullOrBlank())
            item(context.getString(R.string.log), "log")
            item(context.getString(R.string.auto_task_delete), "delete")
            danger("delete")
        }.show(view) { action ->
            when (action) {
                "login" -> context.startActivity<SourceLoginActivity> {
                    putExtra("type", "autoTask")
                    putExtra("key", task.id)
                }
                "log" -> callBack.showLog(task)
                "delete" -> callBack.delete(task)
            }
        }
    }

    interface CallBack {
        fun edit(task: AutoTaskRule)
        fun delete(task: AutoTaskRule)
        fun toggle(task: AutoTaskRule, enabled: Boolean)
        fun upCountView()
        fun showLog(task: AutoTaskRule)
        fun upOrder(items: List<AutoTaskRule>)
    }
}
