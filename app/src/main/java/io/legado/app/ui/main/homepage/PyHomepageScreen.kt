package io.legado.app.ui.main.homepage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.ui.theme.LegadoTheme

/**
 * Python 首页界面。
 *
 * 布局：
 * - 顶部：胶囊栏（已安装插件图标，横向滚动）
 * - 搜索区：关键词输入 + 搜索全部开关 + 搜索按钮
 * - 结果区：搜索结果列表（JSON 解析后展示）
 * - 底部弹窗：插件详情
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyHomepageScreen(
    viewModel: PyHomepageViewModel,
    bottomPaddingPx: Int = 0,
    onRefreshClick: () -> Unit = {},
) {
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()

    LegadoTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(PaddingValues(bottom = bottomPaddingPx.dp))
        ) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Python 影视源",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                TextButton(onClick = onRefreshClick) {
                    Text("刷新")
                }
            }

            // 胶囊栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // 搜索全部胶囊
                CapsuleItem(
                    text = "全部",
                    selected = viewModel.searchAll,
                    onClick = { viewModel.toggleSearchAll() }
                )
                Spacer(modifier = Modifier.width(8.dp))

                // 插件胶囊
                plugins.forEach { plugin ->
                    CapsuleItem(
                        text = plugin.meta.name,
                        selected = !viewModel.searchAll && viewModel.selectedPluginId == plugin.meta.id,
                        onClick = {
                            viewModel.selectPlugin(plugin.meta.id)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            // 搜索区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = viewModel.searchKeyword,
                    onValueChange = { viewModel.onSearchKeywordChange(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索关键词") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.search(viewModel.searchKeyword) },
                    enabled = viewModel.searchKeyword.isNotBlank() && !viewModel.isSearching
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索"
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("搜索")
                }
            }

            // 搜索状态
            if (viewModel.isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "搜索中...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // 结果区
            val results = parseSearchResults(viewModel.searchResults)
            if (results.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    items(results, key = { it.id }) { item ->
                        SearchResultItem(
                            title = item.title,
                            url = item.url,
                            onClick = {
                                viewModel.getPluginDetail(viewModel.selectedPluginId)
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            } else if (viewModel.searchKeyword.isNotBlank() && !viewModel.isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // 底部弹窗：插件详情
            if (viewModel.showBottomSheet) {
                PluginBottomSheet(
                    pluginName = viewModel.selectedPluginId,
                    onDismiss = { viewModel.dismissBottomSheet() }
                )
            }
        }
    }
}

/** 胶囊项 */
@Composable
private fun CapsuleItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 搜索结果项 */
@Composable
private fun SearchResultItem(
    title: String,
    url: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
        ) {
            Text(
                text = title.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 插件底部弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginBottomSheet(
    pluginName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("插件详情") },
        text = {
            Column {
                Text("插件ID: $pluginName")
                Spacer(modifier = Modifier.height(8.dp))
                Text("状态: 已加载")
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) { Text("关闭") }
        }
    )
}

/** 解析搜索结果 JSON */
private fun parseSearchResults(json: String): List<SearchItem> {
    return try {
        val jsonObject = com.google.gson.JsonParser.parseString(json).asJsonArray
        jsonObject.map { element ->
            val obj = element.asJsonObject
            SearchItem(
                id = obj.get("id")?.asString ?: "",
                title = obj.get("title")?.asString ?: "",
                url = obj.get("url")?.asString ?: ""
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
