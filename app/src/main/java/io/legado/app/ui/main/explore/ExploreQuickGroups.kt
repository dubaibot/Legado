package io.legado.app.ui.main.explore

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.data.dao.BookSourceDao
import io.legado.app.help.config.LocalConfig

object ExploreQuickGroups {

    fun setupQuickGroupBar(
        container: LinearLayout,
        inflater: LayoutInflater,
        onClickGroup: (String) -> Unit,
        onLongClickGroup: (String) -> Boolean,
        onConfig: () -> Unit
    ) {
        container.removeAllViews()
        val groups = LocalConfig.exploreQuickGroups.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (groups.isEmpty()) {
            val tv = inflater.inflate(R.layout.item_quick_group, container, false) as TextView
            tv.text = container.context.getString(R.string.explore_quick_group_config)
            tv.setOnClickListener { onConfig() }
            container.addView(tv)
            return
        }

        groups.forEach { group ->
            val tv = inflater.inflate(R.layout.item_quick_group, container, false) as TextView
            tv.text = group
            tv.setOnClickListener { onClickGroup(group) }
            tv.setOnLongClickListener { onLongClickGroup(group) }
            container.addView(tv)
        }

        val add = inflater.inflate(R.layout.item_quick_group, container, false) as TextView
        add.text = "＋"
        add.setOnClickListener { onConfig() }
        container.addView(add)
    }

    fun showQuickGroupConfigDialog(
        context: Context,
        bookSourceDao: BookSourceDao,
        onSaved: () -> Unit
    ) {
        val allGroups = bookSourceDao.exploreGroups()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (allGroups.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.explore_quick_group_title)
                .setMessage(R.string.explore_empty)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val selected = LocalConfig.exploreQuickGroups.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toHashSet()
        val checked = BooleanArray(allGroups.size) { allGroups[it] in selected }

        AlertDialog.Builder(context)
            .setTitle(R.string.explore_quick_group_title)
            .setMultiChoiceItems(allGroups.toTypedArray(), checked) { _, position, isChecked ->
                checked[position] = isChecked
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val result = allGroups.filterIndexed { index, _ -> checked[index] }
                    .joinToString(",")
                LocalConfig.exploreQuickGroups = result
                onSaved()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
