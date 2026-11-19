package io.legado.app.ui.main.homepage

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeSourceManageActivity : BaseComposeActivity() {
    private var sources by mutableStateOf<List<HomeSourceManagementStore.Source>>(emptyList())
    private var groups by mutableStateOf(HomeSourceManagementStore.Groups())
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        runOperation {}
    }

    private fun runOperation(operation: () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.localizedMessage ?: "操作失败，请重试"
            } finally {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        HomeSourceManagementStore.sources() to HomeSourceManagementStore.groups()
                    }
                    sources = snapshot.first
                    groups = snapshot.second
                } catch (e: Exception) {
                    error = e.localizedMessage ?: "加载源列表失败"
                }
                busy = false
            }
        }
    }

    @Composable
    override fun ComposeContent() {
        BackHandler(enabled = busy) { }
        var query by rememberSaveable { mutableStateOf("") }
        // null = all, empty string = ungrouped.
        var filter by rememberSaveable { mutableStateOf<String?>(null) }
        var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var showFilter by rememberSaveable { mutableStateOf(false) }
        var groupTargets by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
        var newGroup by rememberSaveable { mutableStateOf("") }
        var deletion by remember { mutableStateOf<List<HomeSourceManagementStore.Source>?>(null) }
        val visible = sources.filter { source ->
            (filter == null || groups.assignments[source.key].orEmpty() == filter) &&
                (query.isBlank() || listOf(source.name, source.id, source.type, source.parentName,
                    groups.assignments[source.key].orEmpty()).any { it.contains(query.trim(), true) })
        }
        val selectedKeys = selected.toSet().intersect(sources.map { it.key }.toSet())
        AppManagementScaffold(
            title = "首页源管理",
            selectedCount = selectedKeys.size,
            totalCount = visible.size,
            searchQuery = query,
            searchHint = "搜索名称、类型、配置或分组",
            onSearchChange = { query = it },
            onBack = { if (!busy) finish() },
            topActions = listOf(AppManagementAction("按组筛选", R.drawable.ic_more_vert,
                onClick = { showFilter = true })),
            bottomActions = listOf(
                AppManagementAction("设置分组", primary = true, onClick = {
                    if (!busy && selectedKeys.isNotEmpty()) {
                        newGroup = ""
                        groupTargets = ArrayList(selectedKeys)
                    }
                }),
                AppManagementAction("启用", onClick = {
                    runOperation { selectedKeys.forEach { HomeSourceManagementStore.setEnabled(it, true) } }
                }),
                AppManagementAction("禁用", onClick = {
                    runOperation { selectedKeys.forEach { HomeSourceManagementStore.setEnabled(it, false) } }
                }),
                AppManagementAction("删除", danger = true, onClick = {
                    if (!busy) deletion = sources.filter { it.key in selectedKeys }.takeIf { it.isNotEmpty() }
                }),
            ),
            onSelectAll = { selected = ArrayList(selectedKeys + visible.map { it.key }) },
            onInvertSelection = {
                val visibleKeys = visible.map { it.key }.toSet()
                selected = ArrayList((selectedKeys - visibleKeys) + (visibleKeys - selectedKeys))
            },
        ) { _ ->
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showFilter = true }) {
                        Text("分组：${filter?.ifEmpty { "未分组" } ?: "全部"}")
                    }
                    Text("${visible.size} / ${sources.size} 个源", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { newGroup = ""; groupTargets = arrayListOf() }) {
                        Text("新增分组")
                    }
                }
                if (busy) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (sources.isEmpty()) "暂无已导入的源" else "没有匹配的源")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visible, key = { it.key }) { source ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selected = ArrayList(if (source.key in selectedKeys) selectedKeys - source.key else selectedKeys + source.key)
                                }.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(source.key in selectedKeys, onCheckedChange = { checked ->
                                    selected = ArrayList(if (checked) selectedKeys + source.key else selectedKeys - source.key)
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(source.type + if (source.parentId.isNotEmpty()) " · 配置：${source.parentName}" else "",
                                        style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { newGroup = ""; groupTargets = arrayListOf(source.key) }) {
                                        Text("分组：${groups.assignments[source.key] ?: "未分组"}")
                                    }
                                }
                                Switch(checked = source.enabled, enabled = !busy, onCheckedChange = { enabled ->
                                    runOperation { HomeSourceManagementStore.setEnabled(source.key, enabled) }
                                })
                                IconButton(enabled = !busy, onClick = { deletion = HomeSourceManagementStore.affected(source, sources) }) {
                                    Icon(Icons.Default.Delete, "删除 ${source.name}", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (showFilter) {
            AlertDialog(onDismissRequest = { showFilter = false }, title = { Text("按组筛选") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        listOf(null to "全部", "" to "未分组").plus(groups.names.map { it to it }).forEach { (value, label) ->
                            TextButton(onClick = { filter = value; showFilter = false }) { Text(label) }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { showFilter = false }) { Text("关闭") } })
        }
        groupTargets?.let { targets ->
            fun assign(name: String?) {
                val updated = groups.copy(
                    names = if (name == null) groups.names else (groups.names + name).distinct(),
                    assignments = (groups.assignments - targets.toSet()) + targets.mapNotNull { key -> name?.let { key to it } },
                )
                groupTargets = null
                runOperation { HomeSourceManagementStore.saveGroups(updated) }
            }
            AlertDialog(onDismissRequest = { groupTargets = null },
                title = { Text(if (targets.isEmpty()) "新增分组" else "设置分组（${targets.size} 个源）") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(newGroup, { newGroup = it }, label = { Text("新分组名称") }, singleLine = true)
                        if (targets.isNotEmpty()) {
                            TextButton(onClick = { assign(null) }) { Text("未分组") }
                            groups.names.forEach { name -> TextButton(onClick = { assign(name) }) { Text(name) } }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = newGroup.isNotBlank() && !busy, onClick = { assign(newGroup.trim()) }) {
                        Text(if (targets.isEmpty()) "新增" else "新增并设置")
                    }
                }, dismissButton = { TextButton(onClick = { groupTargets = null }) { Text("取消") } })
        }
        deletion?.let { affected ->
            AlertDialog(onDismissRequest = { deletion = null }, title = { Text("确认删除范围") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("将删除以下 ${affected.size} 个所选源。TVbox 子站点将持久化隐藏，父配置与兄弟站点保留；Python 插件将卸载，独立源将删除其本地文件。")
                        affected.forEach { Text("${it.name}（${it.id}）", Modifier.padding(top = 8.dp)) }
                        Text("此操作无法撤销，收藏与历史记录保留。", Modifier.padding(top = 12.dp))
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        deletion = null
                        runOperation { affected.forEach { HomeSourceManagementStore.delete(it) } }
                    }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
                }, dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
        }
        error?.let { message ->
            AlertDialog(onDismissRequest = { error = null }, title = { Text("操作提示") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = { error = null }) { Text("确定") } })
        }
    }
}
