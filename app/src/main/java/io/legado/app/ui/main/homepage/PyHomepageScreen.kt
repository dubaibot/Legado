package io.legado.app.ui.main.homepage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.help.python.PyLoader
import io.legado.app.lib.theme.eInkGrayscale
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.theme.pageTopBarBackground
import io.legado.app.ui.theme.pageTopBarColors

/**
 * Python 影视首页：订阅式顶栏 + 内容区。
 *
 * 顶栏：当前源名称 + 搜索 / 历史 / 收藏 / 分组 / 设置（下拉菜单）。
 * 内容区默认空白；搜索后展示结果；开启「自定义首页」后嵌入原首页推荐模块。
 */
@Composable
fun PyHomepageScreen(
    viewModel: PyHomepageViewModel,
    homepageViewModel: HomepageViewModel? = null,
    bottomPaddingPx: Int = 0,
    onImportPySource: () -> Unit = {},
    onBookClick: (name: String?, author: String?, bookUrl: String, origin: String?, coverPath: String?) -> Unit = { _, _, _, _, _ -> },
    onModuleHeaderClick: (title: String?, sourceUrl: String, exploreUrl: String?) -> Unit = { _, _, _ -> },
) {
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showFavoritesDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }

    val plugins by viewModel.plugins.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ---- 顶栏（订阅式） ----
        val topBarColors = pageTopBarColors()
        Box(
            modifier = Modifier
                .pageTopBarBackground(topBarColors)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = viewModel.currentSourceName() ?: "首页",
                    fontWeight = FontWeight.Bold,
                    color = topBarColors.contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                )
                // 搜索
                IconButton(onClick = { viewModel.toggleSearchMode() }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = topBarColors.contentColor,
                    )
                }
                // 历史
                IconButton(onClick = { showHistoryDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "历史",
                        tint = topBarColors.contentColor,
                    )
                }
                // 收藏
                IconButton(onClick = { showFavoritesDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "收藏",
                        tint = topBarColors.contentColor,
                    )
                }
                // 分组（源选择）
                IconButton(onClick = { showGroupDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "分组",
                        tint = topBarColors.contentColor,
                    )
                }
                // 设置
                Box {
                    IconButton(onClick = { showSettingsMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = topBarColors.contentColor,
                        )
                    }
                    DropdownMenu(
                        modifier = Modifier.eInkGrayscale(),
                        expanded = showSettingsMenu,
                        onDismissRequest = { showSettingsMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("自定义首页") },
                            onClick = {
                                viewModel.toggleCustomHomepage()
                                showSettingsMenu = false
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = if (viewModel.customHomepageEnabled) Icons.Default.CheckBox
                                    else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (viewModel.customHomepageEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导入 py 源") },
                            onClick = {
                                showSettingsMenu = false
                                onImportPySource()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("py 源管理") },
                            onClick = {
                                showSettingsMenu = false
                                viewModel.showManageDialog()
                            },
                        )
                    }
                }
            }
        }

        // ---- 搜索输入行（点搜索按钮展开） ----
        if (viewModel.searchMode) {
            SearchInputRow(viewModel)
        }

        // ---- 内容区 ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPaddingPx.dp)
        ) {
            when {
                viewModel.isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                text = "搜索中...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = pageSecondaryTextColor(),
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }

                parseSearchResults(viewModel.searchResults).isNotEmpty() -> {
                    SearchResultList(
                        viewModel = viewModel,
                        results = parseSearchResults(viewModel.searchResults),
                    )
                }

                viewModel.searchMode && viewModel.searchKeyword.isNotBlank() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无结果",
                            style = MaterialTheme.typography.bodyMedium,
                            color = pageSecondaryTextColor(),
                        )
                    }
                }

                viewModel.customHomepageEnabled && homepageViewModel != null -> {
                    HomepageScreen(
                        viewModel = homepageViewModel,
                        bottomPaddingPx = 0,
                        hideTopBar = true,
                        onBookClick = onBookClick,
                        onModuleHeaderClick = onModuleHeaderClick,
                    )
                }

                else -> {
                    // 空白内容区
                }
            }
        }
    }

    // ---- 弹窗 ----
    if (showHistoryDialog) {
        HistoryDialog(
            viewModel = viewModel,
            onDismiss = { showHistoryDialog = false },
        )
    }
    if (showFavoritesDialog) {
        FavoritesDialog(
            viewModel = viewModel,
            onDismiss = { showFavoritesDialog = false },
        )
    }
    if (showGroupDialog) {
        GroupDialog(
            viewModel = viewModel,
            plugins = plugins,
            onDismiss = { showGroupDialog = false },
        )
    }
    if (viewModel.showManageDialog) {
        ManageDialog(
            viewModel = viewModel,
            plugins = plugins,
            onDismiss = { viewModel.dismissManageDialog() },
        )
    }
    viewModel.detailState?.let { detail ->
        DetailDialog(
            viewModel = viewModel,
            detail = detail,
            onDismiss = { viewModel.dismissDetail() },
        )
    }
}

/** 搜索输入行 */
@Composable
private fun SearchInputRow(viewModel: PyHomepageViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = viewModel.searchKeyword,
            onValueChange = { viewModel.onSearchKeywordChange(it) },
            modifier = Modifier.weight(1f),
            placeholder = { Text("搜索") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(
            onClick = { viewModel.search(viewModel.searchKeyword) },
            enabled = viewModel.searchKeyword.isNotBlank() && !viewModel.isSearching,
        ) {
            Text("搜索")
        }
        IconButton(onClick = { viewModel.toggleSearchMode() }) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "收起")
        }
    }
    // 历史联想（输入为空时展示最近搜索）
    if (viewModel.searchKeyword.isBlank() && viewModel.searchHistory.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            viewModel.searchHistory.take(3).forEach { word ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { viewModel.search(word) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = word,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 搜索结果列表 */
@Composable
private fun SearchResultList(
    viewModel: PyHomepageViewModel,
    results: List<SearchItem>,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        items(results, key = { it.id }) { item ->
            val pluginId = viewModel.selectedPluginId
            val fav = viewModel.isFavorite(pluginId, item.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { viewModel.showDetail(pluginId, item.id, item.title) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .width(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.title.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    viewModel.toggleFavorite(pluginId, item.id, item.title, item.url)
                }) {
                    Icon(
                        imageVector = if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "收藏",
                        tint = if (fav) MaterialTheme.colorScheme.primary else pageSecondaryTextColor(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/** 历史弹窗 */
@Composable
private fun HistoryDialog(
    viewModel: PyHomepageViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索历史") },
        text = {
            if (viewModel.searchHistory.isEmpty()) {
                Text("暂无历史", color = pageSecondaryTextColor())
            } else {
                LazyColumn {
                    items(viewModel.searchHistory, key = { it }) { word ->
                        Text(
                            text = word,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    viewModel.onSearchKeywordChange(word)
                                    if (!viewModel.searchMode) viewModel.toggleSearchMode()
                                    viewModel.search(word)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.clearHistory()
            }) { Text("清空") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 收藏弹窗 */
@Composable
private fun FavoritesDialog(
    viewModel: PyHomepageViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏") },
        text = {
            if (viewModel.favorites.isEmpty()) {
                Text("暂无收藏", color = pageSecondaryTextColor())
            } else {
                LazyColumn {
                    items(viewModel.favorites, key = { it.pluginId + it.id }) { fav ->
                        Text(
                            text = fav.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    viewModel.showDetail(fav.pluginId, fav.id, fav.title)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 分组弹窗（源选择） */
@Composable
private fun GroupDialog(
    viewModel: PyHomepageViewModel,
    plugins: List<PyLoader.PyPlugin>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择源") },
        text = {
            if (plugins.isEmpty()) {
                Text("暂无已安装 py 源，请在设置中导入", color = pageSecondaryTextColor())
            } else {
                LazyColumn {
                    items(plugins, key = { it.meta.id }) { plugin ->
                        val selected = plugin.meta.id == viewModel.selectedPluginId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectPlugin(plugin.meta.id)
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = plugin.meta.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.CheckBox,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** py 源管理弹窗 */
@Composable
private fun ManageDialog(
    viewModel: PyHomepageViewModel,
    plugins: List<PyLoader.PyPlugin>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("py 源管理") },
        text = {
            if (plugins.isEmpty()) {
                Text("暂无已安装 py 源", color = pageSecondaryTextColor())
            } else {
                LazyColumn {
                    items(plugins, key = { it.meta.id }) { plugin ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plugin.meta.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${plugin.meta.id} v${plugin.meta.version}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = pageSecondaryTextColor(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                viewModel.uninstallPlugin(plugin.meta.id)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "卸载",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 详情弹窗 */
@Composable
private fun DetailDialog(
    viewModel: PyHomepageViewModel,
    detail: PyHomepageViewModel.DetailState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = detail.title.ifBlank { "详情" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            if (detail.loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = detail.json,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            if (!detail.loading && detail.json.isNotBlank() && detail.json != "{}") {
                val fav = viewModel.isFavorite(detail.pluginId, detail.id)
                TextButton(onClick = {
                    viewModel.toggleFavorite(detail.pluginId, detail.id, detail.title, detail.json)
                }) {
                    Icon(
                        imageVector = if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = null,
                        tint = if (fav) MaterialTheme.colorScheme.primary else pageSecondaryTextColor(),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (fav) "已收藏" else "收藏")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 解析搜索结果 JSON */
private fun parseSearchResults(json: String): List<SearchItem> {
    return try {
        val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
        arr.map { element ->
            val obj = element.asJsonObject
            SearchItem(
                id = obj.get("id")?.asString ?: "",
                title = obj.get("title")?.asString ?: "",
                url = obj.get("url")?.asString ?: "",
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

data class SearchItem(
    val id: String,
    val title: String,
    val url: String,
)
