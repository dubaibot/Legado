package io.legado.app.ui.main.homepage

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import io.legado.app.lib.theme.eInkGrayscale
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.theme.pageTopBarBackground
import io.legado.app.ui.theme.pageTopBarColors
import kotlinx.coroutines.delay

/**
 * 影视首页：订阅式顶栏 + 内容区。
 *
 * 顶栏：搜索 / 历史 / 收藏 / 分组 / 设置（下拉菜单：导入、管理）。
 * 内容区：
 * - 选中 py/json/txt 源：分类标签 + 视频网格（统一数据模型，支持翻页）
 * - 选中 HTML 页面：WebView 渲染该页面
 * - 播放：启动 VideoPlayerActivity
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
    var showSourceMenu by remember { mutableStateOf(false) }
    var importName by remember(viewModel.importUri) { mutableStateOf("") }
    var tvboxUrlInput by remember { mutableStateOf("") }
    var danmakuApiInput by remember { mutableStateOf("") }

    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val currentType = viewModel.currentSourceType()
    val currentSourceName = viewModel.currentSourceName() ?: "首页"

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
                // 源名称 + 下拉切换
                Box {
                    Row(
                        modifier = Modifier
                            .clickable { showSourceMenu = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = currentSourceName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = topBarColors.contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "切换源",
                            modifier = Modifier.size(18.dp),
                            tint = topBarColors.contentColor,
                        )
                    }
                    DropdownMenu(
                        modifier = Modifier.eInkGrayscale(),
                        expanded = showSourceMenu,
                        onDismissRequest = { showSourceMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("选择其他源") },
                            onClick = { showSourceMenu = false },
                            enabled = false,
                        )
                        sources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.name) },
                                onClick = {
                                    showSourceMenu = false
                                    viewModel.selectSource(source.id)
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
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
                            text = { Text("导入") },
                            onClick = {
                                showSettingsMenu = false
                                onImportRequested()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("添加TVbox源") },
                            onClick = {
                                showSettingsMenu = false
                                tvboxUrlInput = ""
                                viewModel.showTvboxDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("自定义弹幕API") },
                            onClick = {
                                showSettingsMenu = false
                                danmakuApiInput = viewModel.danmakuApiUrl
                                viewModel.showDanmakuApiDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("管理") },
                            onClick = {
                                showSettingsMenu = false
                                viewModel.openManageDialog()
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
            val homeData = viewModel.homeData
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                when {
                    // WebView HTML 页面：直接渲染
                    currentType == "web" -> {
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
                            bottomPaddingPx = bottomPaddingPx,
                        )
                    }

                    viewModel.searchMode && viewModel.searchKeyword.isNotBlank() -> {
                        EmptyBox("暂无结果")
                    }

                    sources.isEmpty() -> {
                        EmptyBox("暂无首页源\n点击设置，导入 py/json/txt/html 源或 HTML 页面")
                    }

                    // py/json/txt/html 源首页内容（统一数据模型）
                    (currentType == "py" || currentType == "json" || currentType == "txt" || currentType == "html") && homeData != null -> {
                        HomeContent(viewModel, homeData, bottomPaddingPx)
                    }

                    // py/json/txt/html 源加载首页中
                    (currentType == "py" || currentType == "json" || currentType == "txt" || currentType == "html") && homeData == null && !viewModel.isSearching -> {
                        val homeError = viewModel.homeError
                        val loadError = viewModel.loadErrorFor(viewModel.selectedSourceId)
                        when {
                            homeError != null -> EmptyBox("源调用失败:\n$homeError")
                            loadError != null -> EmptyBox("源加载失败:\n$loadError")
                            else -> LoadingBox("首页加载中...")
                        }
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

    // ---- TVbox 订阅 URL 输入弹窗 ----
    if (viewModel.showTvboxDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showTvboxDialog = false },
            title = { Text("添加 TVbox 源") },
            text = {
                Column {
                    Text(
                        text = "输入 TVbox 订阅接口地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tvboxUrlInput,
                        onValueChange = { tvboxUrlInput = it },
                        label = { Text("https://example.com/sub.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importTvboxSource(tvboxUrlInput)
                    viewModel.showTvboxDialog = false
                }) {
                    Text("导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showTvboxDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // ---- 自定义弹幕 API 输入弹窗 ----
    if (viewModel.showDanmakuApiDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showDanmakuApiDialog = false },
            title = { Text("自定义弹幕 API") },
            text = {
                Column {
                    Text(
                        text = "输入弹幕 API 地址（支持 XML/JSON，{id} 为视频 ID 占位符）",
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = danmakuApiInput,
                        onValueChange = { danmakuApiInput = it },
                        label = { Text("https://example.com/api/danmaku?id={id}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveDanmakuApiUrl(danmakuApiInput)
                    viewModel.showDanmakuApiDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDanmakuApiDialog = false }) {
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
    bottomPaddingPx: Int,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 8.dp,
            bottom = 8.dp + with(androidx.compose.ui.platform.LocalDensity.current) { bottomPaddingPx.toDp() },
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

/** 首页内容：分类标签栏 + 视频网格（滚动翻页） */
@Composable
private fun HomeContent(
    viewModel: PyHomepageViewModel,
    homeData: PyHomepageViewModel.HomeData,
    bottomPaddingPx: Int,
) {
    key(viewModel.selectedCategoryId) {
        val listState = rememberLazyGridState()

        Column(modifier = Modifier.fillMaxSize()) {
            // 分类标签栏（水平滚动）
            if (homeData.categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CategoryChip(
                        name = "推荐",
                        selected = viewModel.selectedCategoryId.isEmpty(),
                        onClick = { viewModel.selectCategory("") },
                    )
                    homeData.categories.forEach { cat ->
                        CategoryChip(
                            name = cat.name,
                            selected = viewModel.selectedCategoryId == cat.id,
                            onClick = { viewModel.selectCategory(cat.id) },
                        )
                    }
                }
            }

            // 分类为空时检查错误
            if (homeData.categories.isEmpty()) {
                val homeError = viewModel.homeError
                val loadError = viewModel.loadErrorFor(viewModel.selectedSourceId)
                when {
                    homeError != null -> {
                        EmptyBox("源调用失败:\n$homeError")
                        return@Column
                    }
                    loadError != null -> {
                        EmptyBox("源加载失败:\n$loadError")
                        return@Column
                    }
                }
            }

            // 视频内容区（占满剩余空间，滚动到底部自动加载更多）
            val categoryData = viewModel.categoryData
            val videos = categoryData?.videos ?: homeData.videos
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (videos.isEmpty()) {
                    // 空态：不显示提示文字
                    Box(modifier = Modifier.fillMaxSize())
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 8.dp,
                            bottom = 8.dp + with(androidx.compose.ui.platform.LocalDensity.current) { bottomPaddingPx.toDp() },
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(videos, key = { it.id }) { video ->
                            VideoCard(
                                video = video,
                                onClick = {
                                    viewModel.showDetail(viewModel.selectedSourceId, video.id, video.title)
                                },
                            )
                        }
                        // 加载更多指示器
                        if (viewModel.selectedCategoryId.isNotEmpty() &&
                            viewModel.currentPage < viewModel.totalPages
                        ) {
                            item(span = { GridItemSpan(3) }) {
                                if (viewModel.isLoadingMore) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 滚动到底部时自动加载更多
        LaunchedEffect(listState) {
            if (viewModel.selectedCategoryId.isEmpty()) return@LaunchedEffect
            while (true) {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems > 0) {
                    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    if (lastVisibleIndex >= totalItems - 5) {
                        if (viewModel.currentPage < viewModel.totalPages && !viewModel.isLoadingMore) {
                            viewModel.loadMorePage()
                        }
                    }
                }
                delay(100)
            }
        }
    }
}

/** 视频卡片 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun VideoCard(
    video: PyHomepageViewModel.VideoItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f/4f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (video.pic.isNotEmpty()) {
                GlideImage(
                    model = video.pic,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.OndemandVideo,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = pageSecondaryTextColor(),
                    )
                }
            }
        }
        Text(
            text = video.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 分类标签 */
@Composable
private fun CategoryChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else pageSecondaryTextColor(),
        )
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
                                imageVector = if (source.type == "web") {
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
    Column(
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                            imageVector = if (source.type == "web") {
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
                                    "web" -> "HTML 页面"
                                    "html" -> "HTML 源"
                                    "json" -> "JSON 源"
                                    "txt" -> "TXT 源"
                                    "py" -> if (source.loaded) "py 源 · 已加载" else "py 源 · 未加载"
                                    else -> source.type
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
    val playUrls = remember(detail.json) { parsePlayUrls(detail.json) }

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
                val detailObj = parseDetailInfo(detail.json)
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (detailObj != null) {
                        detailObj.desc?.let { desc ->
                            if (desc.isNotEmpty()) {
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                        }
                    } else if (detail.json.isNotBlank() && detail.json != "{}") {
                        Text(
                            text = detail.json,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (playUrls.isNotEmpty()) {
                        Text(
                            text = "播放",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            playUrls.forEach { (name, url) ->
                                TextButton(
                                    onClick = {
                                        val idx = playUrls.indexOfFirst { it.second == url }
                                        viewModel.playVideo(detail.pluginId, playUrls, detail.title, idx.coerceAtLeast(0))
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
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

/** 解析详情 JSON 的 play_urls 数组 → [(名称, URL)] */
private fun parsePlayUrls(json: String): List<Pair<String, String>> {
    return try {
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        val arr = obj.get("play_urls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        arr.mapNotNull { e ->
            val o = e.asJsonObject
            val name = o.get("name")?.asString ?: ""
            val url = o.get("url")?.asString ?: ""
            if (name.isNotEmpty() && url.isNotEmpty()) name to url else null
        }
    } catch (e: Exception) {
        emptyList()
    }
}

data class DetailInfo(val desc: String?)

/** 解析详情 JSON 的描述字段 */
private fun parseDetailInfo(json: String): DetailInfo? {
    return try {
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        DetailInfo(obj.get("desc")?.asString ?: "")
    } catch (e: Exception) {
        null
    }
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
