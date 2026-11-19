package io.legado.app.ui.main.discovery

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.RssSource
import io.legado.app.data.video.SourceType
import io.legado.app.lib.theme.view.ThemeCheckBox
import io.legado.app.lib.theme.view.ThemeSwitch
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.main.homepage.HomeSourceManagementStore
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val ALL_TAB = "ALL"

class SourceManageActivity : BaseComposeActivity() {

    private enum class SourceTab(val labelRes: Int) {
        BOOK(R.string.book_source),
        RSS(R.string.rss_source),
        PY(R.string.py_source),
        TVBOX(R.string.tvbox_source),
    }

    private data class SourceRow(
        val key: String,
        val name: String,
        val detail: String,
        val groups: List<String>,
        val enabled: Boolean,
        val bookUrl: String? = null,
        val rssUrl: String? = null,
        val homeSource: HomeSourceManagementStore.Source? = null,
    )

    // BookSourcePart.equals 仅比较 URL，必须接收启用状态和分组变化后的新快照。
    private var books by mutableStateOf<List<BookSourcePart>>(emptyList(), neverEqualPolicy())
    private var rssSources by mutableStateOf<List<RssSource>>(emptyList())
    private var bookGroups by mutableStateOf<List<String>>(emptyList())
    private var rssGroups by mutableStateOf<List<String>>(emptyList())
    private var homeSources by mutableStateOf<List<HomeSourceManagementStore.Source>>(emptyList())
    private var homeGroups by mutableStateOf<Map<SourceTab, HomeSourceManagementStore.Groups>>(emptyMap())
    private var groupOperationBusy by mutableStateOf(false)
    private var groupOperationError by mutableStateOf<String?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            launch {
                combine(
                    appDb.bookSourceDao.flowAll(),
                    appDb.rssSourceDao.flowAll(),
                    appDb.bookSourceDao.flowGroups(),
                    appDb.rssSourceDao.flowGroups(),
                ) { bookList, rssList, bookGroupList, rssGroupList ->
                    SourceSnapshot(bookList, rssList, bookGroupList, rssGroupList)
                }.collect { snapshot ->
                    books = snapshot.books
                    rssSources = snapshot.rss
                    bookGroups = snapshot.bookGroups
                    rssGroups = snapshot.rssGroups
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runOperation {}
    }

    private data class SourceSnapshot(
        val books: List<BookSourcePart>,
        val rss: List<RssSource>,
        val bookGroups: List<String>,
        val rssGroups: List<String>,
    )

    private suspend fun refreshHomeSources() {
        val snapshot = withContext(Dispatchers.IO) {
            HomeSourceManagementStore.sources() to listOf(SourceTab.PY, SourceTab.TVBOX).associateWith {
                HomeSourceManagementStore.groups(it.name)
            }
        }
        homeSources = snapshot.first
        homeGroups = snapshot.second
    }

    private fun runOperation(operation: () -> Unit) {
        if (groupOperationBusy) return
        groupOperationBusy = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                groupOperationError = e.localizedMessage ?: "操作失败，请重试"
            } finally {
                try {
                    refreshHomeSources()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    groupOperationError = e.localizedMessage ?: "刷新失败，请重试"
                } finally {
                    groupOperationBusy = false
                }
            }
        }
    }

    private fun assignGroup(tab: SourceTab?, rows: List<SourceRow>, group: String?) = runOperation {
        val targets = tab?.let { listOf(it) } ?: SourceTab.entries.toList()
        targets.forEach { target ->
            val targetRows = rows.filter { it.tab() == target }
            if (targetRows.isNotEmpty()) assignGroupFor(target, targetRows, group)
        }
    }

    private fun SourceRow.tab(): SourceTab = when {
        bookUrl != null -> SourceTab.BOOK
        rssUrl != null -> SourceTab.RSS
        homeSource?.type == SourceType.PY.name -> SourceTab.PY
        else -> SourceTab.TVBOX
    }

    private fun assignGroupFor(tab: SourceTab, rows: List<SourceRow>, group: String?) {
        when (tab) {
            SourceTab.BOOK -> rows.forEach { row ->
                val source = appDb.bookSourceDao.getBookSource(row.bookUrl.orEmpty())
                    ?: error("书源已不存在")
                source.bookSourceGroup = group
                appDb.bookSourceDao.update(source)
            }
            SourceTab.RSS -> rows.forEach { row ->
                val source = appDb.rssSourceDao.getByKey(row.rssUrl.orEmpty())
                    ?: error("订阅源已不存在")
                source.sourceGroup = group
                appDb.rssSourceDao.update(source)
            }
            SourceTab.PY, SourceTab.TVBOX -> {
                val current = HomeSourceManagementStore.groups(tab.name)
                val names = if (group.isNullOrBlank()) current.names else (current.names + group).distinct()
                val keys = rows.map { it.key }.toSet()
                val assignments = (current.assignments - keys) +
                    rows.mapNotNull { row -> group?.let { row.key to it } }
                HomeSourceManagementStore.saveGroups(current.copy(names = names, assignments = assignments), tab.name)
            }
        }
    }

    private fun setEnabled(rows: List<SourceRow>, enabled: Boolean) = runOperation {
        rows.forEach { row ->
            when {
                row.bookUrl != null -> appDb.bookSourceDao.enable(row.bookUrl, enabled)
                row.rssUrl != null -> appDb.rssSourceDao.enable(row.rssUrl, enabled)
                else -> HomeSourceManagementStore.setEnabled(row.key, enabled)
            }
        }
    }

    private fun deleteRows(rows: List<SourceRow>) = runOperation {
        rows.forEach { row ->
            when {
                row.bookUrl != null -> appDb.bookSourceDao.delete(row.bookUrl)
                row.rssUrl != null -> appDb.rssSourceDao.delete(row.rssUrl)
                else -> HomeSourceManagementStore.delete(requireNotNull(row.homeSource))
            }
        }
    }

    private fun persistOrder(tab: SourceTab, orderedRows: List<SourceRow>) = runOperation {
        val allRows = sourceRows(tab).toMutableList()
        val visibleKeys = orderedRows.map { it.key }.toSet()
        val slots = allRows.indices.filter { allRows[it].key in visibleKeys }
        slots.forEachIndexed { index, slot -> allRows[slot] = orderedRows[index] }
        when (tab) {
            SourceTab.BOOK -> allRows.forEachIndexed { index, row ->
                appDb.bookSourceDao.upOrder(row.bookUrl.orEmpty(), index)
            }
            SourceTab.RSS -> allRows.forEachIndexed { index, row ->
                appDb.rssSourceDao.getByKey(row.rssUrl.orEmpty())?.let { source ->
                    source.customOrder = index
                    appDb.rssSourceDao.update(source)
                }
            }
            SourceTab.PY, SourceTab.TVBOX -> HomeSourceManagementStore.saveOrder(tab.name, allRows.map { it.key })
        }
    }

    private fun moveRow(tab: SourceTab, key: String, toTop: Boolean) {
        val current = sourceRows(tab).toMutableList()
        val index = current.indexOfFirst { it.key == key }
        if (index < 0) return
        val row = current.removeAt(index)
        if (toTop) current.add(0, row) else current.add(row)
        persistOrder(tab, current)
    }

    private fun removeGroup(tab: SourceTab?, group: String) = runOperation {
        val targets = tab?.let { listOf(it) } ?: SourceTab.entries.toList()
        targets.forEach { removeGroupFor(it, group) }
    }

    private fun removeGroupFor(tab: SourceTab, group: String) {
        when (tab) {
            SourceTab.BOOK -> appDb.bookSourceDao.getByGroup(group).forEach { source ->
                source.bookSourceGroup = source.bookSourceGroup.groups().filterNot { it == group }.joinToString(",")
                appDb.bookSourceDao.update(source)
            }
            SourceTab.RSS -> appDb.rssSourceDao.getByGroup(group).forEach { source ->
                source.sourceGroup = source.sourceGroup.groups().filterNot { it == group }.joinToString(",")
                appDb.rssSourceDao.update(source)
            }
            SourceTab.PY, SourceTab.TVBOX -> {
                val current = HomeSourceManagementStore.groups(tab.name)
                val keys = HomeSourceManagementStore.sources().filter {
                    (it.type == SourceType.PY.name) == (tab == SourceTab.PY)
                }.map { it.key }.toSet()
                HomeSourceManagementStore.saveGroups(current.copy(
                    names = current.names - group,
                    assignments = current.assignments.filterNot { (key, value) -> key in keys && value == group },
                ), tab.name)
            }
        }
    }

    @Composable
    override fun ComposeContent() {
        var selectedTabName by rememberSaveable { mutableStateOf(ALL_TAB) }
        var allSelectedGroup by rememberSaveable { mutableStateOf("全部") }
        var bookSelectedGroup by rememberSaveable { mutableStateOf("全部") }
        var rssSelectedGroup by rememberSaveable { mutableStateOf("全部") }
        var pySelectedGroup by rememberSaveable { mutableStateOf("全部") }
        var tvboxSelectedGroup by rememberSaveable { mutableStateOf("全部") }
        BackHandler(enabled = groupOperationBusy) { }
        var groupTargets by remember { mutableStateOf<List<SourceRow>?>(null) }
        var deletion by remember { mutableStateOf<List<SourceRow>?>(null) }
        var deletingGroup by remember { mutableStateOf<String?>(null) }
        var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var query by rememberSaveable { mutableStateOf("") }
        var newGroup by rememberSaveable { mutableStateOf("") }
        var detailSource by remember { mutableStateOf<SourceRow?>(null) }
        val selectedTab = selectedTabName.takeIf { it != ALL_TAB }?.let { SourceTab.valueOf(it) }
        val selectedGroup = when (selectedTab) {
            SourceTab.BOOK -> bookSelectedGroup
            SourceTab.RSS -> rssSelectedGroup
            SourceTab.PY -> pySelectedGroup
            SourceTab.TVBOX -> tvboxSelectedGroup
            null -> allSelectedGroup
        }
        fun updateSelectedGroup(value: String) {
            when (selectedTab) {
                SourceTab.BOOK -> bookSelectedGroup = value
                SourceTab.RSS -> rssSelectedGroup = value
                SourceTab.PY -> pySelectedGroup = value
                SourceTab.TVBOX -> tvboxSelectedGroup = value
                null -> allSelectedGroup = value
            }
        }
        val availableGroups = when (selectedTab) {
            SourceTab.BOOK -> bookGroups
            SourceTab.RSS -> rssGroups
            SourceTab.PY -> homeGroups[SourceTab.PY]?.names.orEmpty()
            SourceTab.TVBOX -> homeGroups[SourceTab.TVBOX]?.names.orEmpty()
            null -> (
                bookGroups + rssGroups +
                    homeGroups[SourceTab.PY]?.names.orEmpty() +
                    homeGroups[SourceTab.TVBOX]?.names.orEmpty()
                ).distinct()
        }
        val groups = listOf("全部", "未分组") + availableGroups.filterNot { it == "未分组" }
        val visibleSelectedGroup = selectedGroup.takeIf { it in groups } ?: "全部"
        val allRows = sourceRows(selectedTab)
        val selectedRows = allRows.filter { it.key in selected }
        val selectedKeys = selectedRows.map { it.key }.toSet()
        val rows = allRows.filter { row ->
            (visibleSelectedGroup == "全部" ||
                (visibleSelectedGroup == "未分组" && (row.groups.isEmpty() || "未分组" in row.groups)) ||
                visibleSelectedGroup in row.groups) &&
                (query.isBlank() || row.name.contains(query.trim(), true))
        }
        val visibleKeys = rows.map { it.key }.toSet()
        val allVisibleSelected = visibleKeys.isNotEmpty() && visibleKeys.all { it in selectedKeys }
        val toggleVisibleSelection = {
            if (!groupOperationBusy) {
                selected = ArrayList(if (allVisibleSelected) selectedKeys - visibleKeys else selectedKeys + visibleKeys)
            }
        }

        AppManagementScaffold(
            title = stringResource(R.string.source_manage),
            selectedCount = selectedRows.size,
            totalCount = rows.size,
            searchQuery = query,
            searchHint = "搜索源名称",
            onSearchChange = { query = it },
            onBack = { if (!groupOperationBusy) finish() },
            topActions = listOf(AppManagementAction(
                text = stringResource(R.string.more_menu),
                menuActions = {
                    buildList {
                        add(AppManagementMenuAction(
                            text = if (allVisibleSelected) "取消全选" else "全选",
                            enabled = visibleKeys.isNotEmpty() && !groupOperationBusy,
                            onClick = toggleVisibleSelection,
                        ))
                        if (selectedTab == SourceTab.PY || selectedTab == SourceTab.TVBOX) {
                            add(AppManagementMenuAction("新增分组", enabled = !groupOperationBusy) {
                                newGroup = ""
                                groupTargets = emptyList()
                            })
                        }
                        if (visibleSelectedGroup != "全部" && visibleSelectedGroup != "未分组") {
                            add(AppManagementMenuAction("删除分组", enabled = !groupOperationBusy, danger = true) {
                                deletingGroup = visibleSelectedGroup
                            })
                        }
                    }
                },
            )),
            onSelectAll = toggleVisibleSelection,
            onInvertSelection = {
                if (!groupOperationBusy) {
                    val keys = rows.map { it.key }.toSet()
                    selected = ArrayList((selectedKeys - keys) + (keys - selectedKeys))
                }
            },
            bottomActions = listOf(
                AppManagementAction("启用", onClick = { setEnabled(selectedRows, true) }),
                AppManagementAction("禁用", onClick = { setEnabled(selectedRows, false) }),
                AppManagementAction("分组", onClick = {
                    if (!groupOperationBusy) { newGroup = ""; groupTargets = selectedRows }
                }),
                AppManagementAction("删除", danger = true, onClick = {
                    if (!groupOperationBusy) deletion = selectedRows
                }),
            ),
        ) { _ ->
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                ChoiceBar(
                    items = listOf(ALL_TAB to "全部") + SourceTab.entries.map { it.name to stringResource(it.labelRes) },
                    selected = selectedTabName,
                    onSelected = {
                        if (!groupOperationBusy) {
                            selectedTabName = it
                            selected = arrayListOf()
                        }
                    },
                )
                ChoiceBar(
                    items = groups.map { it to it },
                    selected = visibleSelectedGroup,
                    onSelected = ::updateSelectedGroup,
                )
                if (groupOperationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无源", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val listState = rememberLazyListState()
                    var dragRows by remember(rows) { mutableStateOf(rows) }
                    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                        if (selectedTab != null) {
                            dragRows = dragRows.toMutableList().apply {
                                val fromIndex = from.index.coerceIn(indices)
                                val toIndex = to.index.coerceIn(indices)
                                add(toIndex, removeAt(fromIndex))
                            }
                        }
                    }
                    LaunchedEffect(reorderState.isAnyItemDragging) {
                        if (selectedTab != null && !reorderState.isAnyItemDragging &&
                            dragRows.map { it.key } != rows.map { it.key }
                        ) {
                            persistOrder(selectedTab, dragRows)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(dragRows, key = { it.key }) { row ->
                            ReorderableItem(reorderState, key = row.key) { dragging ->
                                var menuExpanded by remember(row.key) { mutableStateOf(false) }
                                Row(
                                    Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                                        .then(if (selectedTab != null) Modifier.longPressDraggableHandle() else Modifier)
                                        .padding(horizontal = 16.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                AndroidView(
                                    factory = { context ->
                                        ThemeCheckBox(context, null).apply {
                                            setOnUserCheckedChangeListener { checked ->
                                                val current = selected.toSet()
                                                selected = ArrayList(if (checked) current + row.key else current - row.key)
                                            }
                                        }
                                    },
                                    update = {
                                        it.isChecked = row.key in selectedKeys
                                        it.isEnabled = !groupOperationBusy
                                    },
                                )
                                Text(row.name, Modifier.weight(1f).clickable(enabled = !groupOperationBusy) {
                                    when {
                                        row.bookUrl != null -> startActivity<BookSourceEditActivity> {
                                            putExtra("sourceUrl", row.bookUrl)
                                        }
                                        row.rssUrl != null -> startActivity<RssSourceEditActivity> {
                                            putExtra("sourceUrl", row.rssUrl)
                                        }
                                        else -> detailSource = row
                                    }
                                }, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                AndroidView(
                                    factory = { context ->
                                        ThemeSwitch(context, null).apply {
                                            setOnUserCheckedChangeListener { checked ->
                                                setEnabled(listOf(row), checked)
                                            }
                                        }
                                    },
                                    update = {
                                        it.isChecked = row.enabled
                                        it.isEnabled = !groupOperationBusy
                                    },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                    if (row.bookUrl != null || row.rssUrl != null) {
                                        IconButton(modifier = Modifier.size(36.dp), enabled = !groupOperationBusy, onClick = {
                                            if (row.bookUrl != null) startActivity<BookSourceEditActivity> {
                                                putExtra("sourceUrl", row.bookUrl)
                                            } else startActivity<RssSourceEditActivity> {
                                                putExtra("sourceUrl", row.rssUrl)
                                            }
                                        }) {
                                            Icon(
                                                painterResource(R.drawable.ic_edit),
                                                "编辑 ${row.name}",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    } else {
                                        IconButton(modifier = Modifier.size(36.dp), enabled = !groupOperationBusy, onClick = { deletion = listOf(row) }) {
                                            Icon(Icons.Default.Delete, "删除 ${row.name}", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    Box {
                                        IconButton(modifier = Modifier.size(36.dp), enabled = !groupOperationBusy, onClick = { menuExpanded = true }) {
                                            Icon(
                                                painterResource(R.drawable.ic_more_vert),
                                                stringResource(R.string.more_menu),
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                            if (selectedTab != null) {
                                                DropdownMenuItem(text = { Text("置顶") }, onClick = {
                                                    menuExpanded = false
                                                    moveRow(selectedTab, row.key, true)
                                                })
                                                DropdownMenuItem(text = { Text("置底") }, onClick = {
                                                    menuExpanded = false
                                                    moveRow(selectedTab, row.key, false)
                                                })
                                            }
                                            if (row.bookUrl != null || row.rssUrl != null) {
                                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = {
                                                    menuExpanded = false
                                                    deletion = listOf(row)
                                                })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        deletion?.takeIf { it.isNotEmpty() }?.let { targets ->
            AlertDialog(onDismissRequest = { deletion = null }, title = { Text("删除 ${targets.size} 个源？") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("仅删除以下所选源；TVbox 子站点将持久化隐藏，父配置及兄弟站点保留。")
                        targets.forEach { Text(it.name, Modifier.padding(top = 8.dp)) }
                    }
                },
                confirmButton = { TextButton(enabled = !groupOperationBusy, onClick = {
                    deletion = null
                    deleteRows(targets)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
        }
        deletingGroup?.let { group ->
            AlertDialog(onDismissRequest = { deletingGroup = null }, title = { Text("删除分组「$group」？") },
                text = { Text("源及其其他分组保留，仅移除此分组。") },
                confirmButton = { TextButton(enabled = !groupOperationBusy, onClick = {
                    deletingGroup = null
                    removeGroup(selectedTab, group)
                    updateSelectedGroup("全部")
                }) { Text("删除分组") } },
                dismissButton = { TextButton(onClick = { deletingGroup = null }) { Text("取消") } })
        }
        detailSource?.let { source ->
            AlertDialog(
                onDismissRequest = { detailSource = null },
                title = { Text(source.name) },
                text = { Text(source.detail + "\n" + source.key) },
                confirmButton = {
                    TextButton(onClick = { detailSource = null }) { Text("关闭") }
                },
            )
        }
        groupTargets?.let { targets ->
            AlertDialog(
                onDismissRequest = { if (!groupOperationBusy) groupTargets = null },
                title = { Text(if (targets.isEmpty()) "新增分组" else "设置分组（${targets.size} 个源）") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (targets.isNotEmpty()) Text("所选源的分组将替换为单个分组")
                        OutlinedTextField(
                            value = newGroup,
                            onValueChange = { newGroup = it },
                            label = { Text("新分组名称") },
                            singleLine = true,
                        )
                        if (targets.isNotEmpty()) {
                            TextButton(enabled = !groupOperationBusy, onClick = {
                                assignGroup(selectedTab, targets, null)
                                groupTargets = null
                            }) { Text("移至未分组") }
                        }
                        availableGroups.forEach { group ->
                            TextButton(enabled = !groupOperationBusy, onClick = {
                                assignGroup(selectedTab, targets, group)
                                groupTargets = null
                            }) { Text(group) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = newGroup.isNotBlank() && newGroup.trim() !in listOf("全部", "未分组") &&
                        !groupOperationBusy, onClick = {
                        assignGroup(selectedTab, targets, newGroup.trim())
                        groupTargets = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(enabled = !groupOperationBusy, onClick = { groupTargets = null }) { Text("取消") }
                },
            )
        }
        groupOperationError?.let { message ->
            AlertDialog(
                onDismissRequest = { groupOperationError = null },
                title = { Text("操作提示") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { groupOperationError = null }) { Text("关闭") } },
            )
        }
    }

    private fun sourceRows(tab: SourceTab?): List<SourceRow> = when (tab) {
        SourceTab.BOOK -> books.map { source ->
            SourceRow(
                key = "book:${source.bookSourceUrl}",
                name = source.bookSourceName,
                detail = source.bookSourceUrl,
                groups = source.bookSourceGroup.groups(),
                enabled = source.enabled,
                bookUrl = source.bookSourceUrl,
            )
        }
        SourceTab.RSS -> rssSources.map { source ->
            SourceRow(
                key = "rss:${source.sourceUrl}",
                name = source.sourceName,
                detail = source.sourceUrl,
                groups = source.sourceGroup.groups(),
                enabled = source.enabled,
                rssUrl = source.sourceUrl,
            )
        }
        SourceTab.PY, SourceTab.TVBOX -> homeSources.filter { source ->
            if (tab == SourceTab.PY) source.type == SourceType.PY.name
            else source.type in setOf(SourceType.HTML.name, SourceType.JSON.name, SourceType.TXT.name, SourceType.JAR.name, "CONFIG", "WEB")
        }.map { source ->
            SourceRow(
                key = source.key,
                name = source.name,
                detail = source.type + if (source.parentName.isNotBlank()) " · ${source.parentName}" else "",
                groups = listOfNotNull(homeGroups[tab]?.assignments?.get(source.key)),
                enabled = source.enabled,
                homeSource = source,
            )
        }
        null -> SourceTab.entries.flatMap { sourceRows(it) }
    }

    private fun String?.groups(): List<String> =
        this?.splitNotBlank(AppPattern.splitGroupRegex).orEmpty().toList()
}

@Composable
private fun ChoiceBar(
    items: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (key, label) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelected(key) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
