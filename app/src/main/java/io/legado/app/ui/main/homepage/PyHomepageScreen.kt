package io.legado.app.ui.main.homepage

import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OndemandVideo
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.lib.theme.eInkGrayscale
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.theme.pageTopBarBackground
import io.legado.app.ui.theme.pageTopBarColors

/**
 * Python 影视首页：订阅式顶栏 + 内容区。
 *
 * 顶栏：当前源名称 + 搜索 / 历史 / 收藏 / 分组 / 设置（下拉菜单：导入、管理）。
 * 内容区：
 * - 选中 py 源：搜索中 spinner / 搜索结果列表 / 暂无结果 / 空白
 * - 选中 HTML 页面：WebView 渲染该页面
 * - 无源：提示导入
 */
@Composable
fun PyHomepageScreen(
    viewModel: PyHomepageViewModel,
    bottomPaddingPx: Int = 0,
    onImportRequested: () -> Unit = {},
) {
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showFavoritesDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var importName by remember(viewModel.importUri) { mutableStateOf("") }

    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val currentType = viewModel.currentSourceType()

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
                // 搜索（仅 py 源可用）
                if (currentType == "py") {
                    IconButton(onClick = { viewModel.toggleSearchMode() }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = topBarColors.contentColor,
                        )
                    }
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
                            text = { Text("导入") },
                            onClick = {
                                showSettingsMenu = false
                                onImportRequested()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("管理") },
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
        val results = parseSearchResults(viewModel.searchResults)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPaddingPx.dp)
        ) {
            when {
                // HTML 自定义页面：WebView 渲染
                currentType == "html" -> {
                    val html = viewModel.htmlContent
                    if (html != null) {
                        HtmlWebView(html)
                    } else {
                        LoadingBox("页面加载中...")
                    }
                }

                viewModel.isSearching -> {
                    LoadingBox("搜索中...")
                }

                results.isNotEmpty() -> {
                    SearchResultList(
                        viewModel = viewModel,
                        results = results,
                    )
                }

                viewModel.searchMode && viewModel.searchKeyword.isNotBlank() -> {
                    EmptyBox("暂无结果")
                }

                sources.isEmpty() -> {
                    EmptyBox("暂无首页源\n点击设置，导入 py 源或 HTML 页面")
                }

                else -> {
                    // 空白内容区
                }
            }
        }
    }

    // ---- 导入命名弹窗 ----
    if (viewModel.showImportDialog) {
        val defaultName = viewModel.importDefaultName
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("导入源") },
            text = {
                Column {
                    Text(
                        text = "自定义名称（留空则使用文件名）",
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = { Text(if (defaultName.isNotBlank()) defaultName else "名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImportName(importName) }) {
                    Text("导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) {
                    Text("取消")
                }
            },
        )
    }

    // ---- 弹窗 ----
    if (showHistoryDialog) {
        HistoryDialog(viewModel, onDismiss = { showHistoryDialog = false })
    }
    if (showFavoritesDialog) {
        FavoritesDialog(viewModel, onDismiss = { showFavoritesDialog = false })
    }
    if (showGroupDialog) {
        GroupDialog(viewModel, sources, onDismiss = { showGroupDialog = false })
    }
    if (viewModel.detailState != null) {
        DetailDialog(viewModel, viewModel.detailState!!, onDismiss = { viewModel.dismissDetail() })
    }

    // ---- 源管理页（全屏） ----
    if (viewModel.showManageDialog) {
        ManageScreen(viewModel, sources, onDismiss = { viewModel.dismissManageDialog() })
    }
}

/** HTML 页面 WebView 渲染 */
@Composable
private fun HtmlWebView(html: String) {
    key(html) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 加载中提示 */
@Composable
private fun LoadingBox(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = pageSecondaryTextColor(),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** 空态提示 */
@Composable
private fun EmptyBox(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = pageSecondaryTextColor(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 搜索输入行（点搜索按钮展开） */
@Composable
private fun SearchInputRow(viewModel: PyHomepageViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = viewModel.searchKeyword,
                onValueChange = { viewModel.onSearchKeywordChange(it) },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                label = { Text("搜索当前源") },
                singleLine = true,
                trailingIcon = {
                    if (viewModel.searchKeyword.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchKeywordChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                            )
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { viewModel.search(viewModel.searchKeyword) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "搜索",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (viewModel.searchHistory.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewModel.searchHistory.forEach { word ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { viewModel.search(word) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = word,
                            style = MaterialTheme.typography.bodySmall,
                            color = pageSecondaryTextColor(),
                        )
                    }
                }
            }
        }
    }
}

/** 搜索结果列表 */
@Composable
private fun SearchResultList(
    viewModel: PyHomepageViewModel,
    results: List<SearchItem>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(results, key = { it.id }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.showDetail(viewModel.selectedSourceId, item.id, item.title) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (viewModel.isFavorite(viewModel.selectedSourceId, item.id)) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                IconButton(onClick = {
                    viewModel.toggleFavorite(
                        viewModel.selectedSourceId,
                        item.id,
                        item.title,
                        item.url,
                    )
                }) {
                    Icon(
                        imageVector = if (viewModel.isFavorite(viewModel.selectedSourceId, item.id)) {
                            Icons.Default.Star
                        } else {
                            Icons.Default.StarBorder
                        },
                        contentDescription = null,
                        tint = if (viewModel.isFavorite(viewModel.selectedSourceId, item.id)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            pageSecondaryTextColor()
                        },
                    )
                }
            }
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
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("搜索历史")
                TextButton(onClick = { viewModel.clearHistory() }) { Text("清空") }
            }
        },
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
                                    viewModel.toggleSearchMode()
                                    viewModel.search(word)
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
    sources: List<PyHomepageViewModel.HomeSource>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择源") },
        text = {
            if (sources.isEmpty()) {
                Text("暂无已安装源，请在设置中导入", color = pageSecondaryTextColor())
            } else {
                LazyColumn {
                    items(sources, key = { it.id }) { source ->
                        val selected = source.id == viewModel.selectedSourceId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectSource(source.id)
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (source.type == "html") {
                                    Icons.Default.Code
                                } else {
                                    Icons.Default.OndemandVideo
                                },
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                else pageSecondaryTextColor(),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = source.name,
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

/** 源管理页（类似订阅源管理） */
@Composable
private fun ManageScreen(
    viewModel: PyHomepageViewModel,
    sources: List<PyHomepageViewModel.HomeSource>,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏
        val topBarColors = pageTopBarColors()
        Box(
            modifier = Modifier
                .pageTopBarBackground(topBarColors)
                .windowInsetsPadding(WindowInsets.statusBars)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "首页源管理",
                    fontWeight = FontWeight.Bold,
                    color = topBarColors.contentColor,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = topBarColors.contentColor,
                    )
                }
            }
        }

        // 列表
        if (sources.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无已导入的源",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pageSecondaryTextColor(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(sources, key = { it.id }) { source ->
                    val selected = source.id == viewModel.selectedSourceId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.background
                            )
                            .clickable {
                                viewModel.selectSource(source.id)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (source.type == "html") {
                                Icons.Default.Code
                            } else {
                                Icons.Default.OndemandVideo
                            },
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else pageSecondaryTextColor(),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when (source.type) {
                                    "html" -> "HTML 页面"
                                    else -> if (source.loaded) "py 源 · 已加载" else "py 源 · 未加载"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = pageSecondaryTextColor(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            viewModel.loadErrorFor(source.id)?.let { err ->
                                Text(
                                    text = "加载错误：${err.substringBefore("\n")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.CheckBox,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        IconButton(onClick = { viewModel.deleteSource(source) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
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

/** 解析搜索结果 JSON（兼容数组或 {list/data/items/results} 包装） */
private fun parseSearchResults(json: String): List<SearchItem> {
    return try {
        val parsed = com.google.gson.JsonParser.parseString(json)
        val arr = if (parsed.isJsonArray) {
            parsed.asJsonArray
        } else {
            val obj = parsed.asJsonObject
            val candidates = listOf("list", "data", "items", "results", "sources", "videoList")
            val found = candidates.firstNotNullOfOrNull { key ->
                obj.get(key)?.takeIf { it.isJsonArray }
            } ?: return emptyList()
            found.asJsonArray
        }
        arr.mapNotNull { element ->
            val obj = element.asJsonObject
            SearchItem(
                id = obj.get("id")?.asString
                    ?: obj.get("vid")?.asString
                    ?: obj.get("url")?.asString
                    ?: "",
                title = obj.get("title")?.asString
                    ?: obj.get("name")?.asString
                    ?: "",
                url = obj.get("url")?.asString ?: "",
            )
        }.filter { it.title.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }
}

data class SearchItem(
    val id: String,
    val title: String,
    val url: String,
)
