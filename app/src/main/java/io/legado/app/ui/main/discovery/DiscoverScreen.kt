package io.legado.app.ui.main.discovery

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.constant.AppPattern
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.lib.theme.eInkGrayscale
import io.legado.app.ui.main.homepage.PyHomepageViewModel
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.theme.pageTopBarBackground
import io.legado.app.ui.theme.pageTopBarColors
import io.legado.app.ui.widget.components.AppBookCover
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    bookViewModel: DiscoverBookViewModel,
    rssViewModel: DiscoverRssViewModel,
    pyViewModel: PyHomepageViewModel,
    bottomPaddingPx: Int,
    scrollToTopRequest: Int,
    scrollPositionCache: MutableMap<String, Int>,
    onSearchRequested: () -> Unit,
    onHistoryRequested: () -> Unit,
    onSettingsRequested: () -> Unit,
    onEditRequested: (DiscoverSourceItem) -> Unit,
    onImportRequested: () -> Unit,
    onNetworkImportRequested: (Int, String) -> Unit,
    onStartHtmlVisibilityChanged: (Boolean) -> Unit,
    onLoginRequested: (DiscoverSourceItem) -> Unit,
    onHomeSourceTapped: (DiscoverSourceItem) -> Unit,
    onBookTapped: (SearchBook) -> Unit,
    onArticleTapped: (RssArticle, String, List<RssArticle>, Pair<String, String>?, String?, Int) -> Unit,
) {
    var showSourceMenu by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showNetworkImportDialog by remember { mutableStateOf(false) }
    var networkImportUrl by remember { mutableStateOf("") }
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val currentSourceName by viewModel.currentSourceName.collectAsStateWithLifecycle()
    val loginVisible by viewModel.loginVisible.collectAsStateWithLifecycle()
    val homeSources by pyViewModel.sources.collectAsStateWithLifecycle()
    val selected = currentSource

    LaunchedEffect(homeSources) {
        viewModel.refresh()
    }
    LaunchedEffect(selected, homeSources) {
        onStartHtmlVisibilityChanged(selected?.type == DiscoverSourceType.RSS && !selected.startHtml.isNullOrBlank())
        selected?.let { source ->
            when (source.type) {
                DiscoverSourceType.BOOK -> bookViewModel.selectSource(source.url)
                DiscoverSourceType.RSS -> {
                    if (!source.startHtml.isNullOrBlank()) rssViewModel.selectStartHtml(source.url)
                    else rssViewModel.selectSource(source.url)
                }
                DiscoverSourceType.HOME -> {
                    if (homeSources.any { it.id == source.id }) onHomeSourceTapped(source)
                }
            }
        }
    }

    // 分类浏览位置记录仅在当前源发现有效：切换源后清空全部缓存
    LaunchedEffect(selected?.key) {
        scrollPositionCache.clear()
    }

    val inlineStartHtmlSelected = selected?.type == DiscoverSourceType.RSS && !selected.startHtml.isNullOrBlank()
    Column(Modifier.fillMaxSize().background(
        if (inlineStartHtmlSelected) Color.Transparent else MaterialTheme.colorScheme.background,
    )) {
        val topBarColors = pageTopBarColors()
        Box(
            modifier = Modifier
                .pageTopBarBackground(topBarColors)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).clickable { showSourceMenu = true }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        Text(
                            currentSourceName.let { if (it.length > 10) it.take(10) + "..." else it },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = topBarColors.contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Default.KeyboardArrowDown, "切换源", Modifier.size(18.dp), tint = topBarColors.contentColor)
                }
                if (loginVisible) {
                    IconButton(onClick = { selected?.let(onLoginRequested) }) {
                        AndroidView(
                            factory = { context ->
                                android.widget.ImageView(context).apply {
                                    setImageResource(io.legado.app.R.drawable.ic_bottom_person)
                                    contentDescription = "登录"
                                }
                            },
                            modifier = Modifier.size(20.dp),
                            update = { it.setColorFilter(topBarColors.contentColor.toArgb()) },
                        )
                    }
                }
                IconButton(onClick = onSearchRequested) { Icon(Icons.Default.Search, "搜索", tint = topBarColors.contentColor) }
                IconButton(onClick = onHistoryRequested) { Icon(Icons.Default.History, "历史", tint = topBarColors.contentColor) }
                Box {
                    IconButton(onClick = { showSettingsMenu = true }) {
                        Icon(Icons.Default.Settings, "设置", tint = topBarColors.contentColor)
                    }
                    DropdownMenu(
                        expanded = showSettingsMenu,
                        onDismissRequest = { showSettingsMenu = false },
                    ) {
                        if (selected?.type == DiscoverSourceType.BOOK || selected?.type == DiscoverSourceType.RSS) {
                            DropdownMenuItem(text = { Text("编辑当前源") }, onClick = {
                                showSettingsMenu = false
                                selected?.let(onEditRequested)
                            })
                        }
                        DropdownMenuItem(text = { Text("源管理") }, onClick = {
                            showSettingsMenu = false
                            onSettingsRequested()
                        })
                        DropdownMenuItem(text = { Text("本地导入") }, onClick = {
                            showSettingsMenu = false
                            onImportRequested()
                        })
                        DropdownMenuItem(text = { Text("网络导入") }, onClick = {
                            showSettingsMenu = false
                            networkImportUrl = ""
                            showNetworkImportDialog = true
                        })
                        DropdownMenuItem(text = { Text("TVbox 接口") }, onClick = {
                            showSettingsMenu = false
                            pyViewModel.showTvboxDialog = true
                        })
                        DropdownMenuItem(text = { Text("弹幕 API 配置") }, onClick = {
                            showSettingsMenu = false
                            pyViewModel.showDanmakuApiDialog = true
                        })
                    }
                }
            }
        }

        SourcePickerDialog(
            visible = showSourceMenu,
            sources = sources,
            currentSource = selected,
            onDismiss = { showSourceMenu = false },
            onSelected = { source ->
                showSourceMenu = false
                viewModel.selectSource(source.key)
            },
        )

        key(selected?.key) {
            when (selected?.type) {
                DiscoverSourceType.BOOK -> BookContent(bookViewModel, bottomPaddingPx, scrollToTopRequest, scrollPositionCache, onBookTapped)
                DiscoverSourceType.RSS -> {
                    if (!selected.startHtml.isNullOrBlank()) {
                        val html = rssViewModel.startHtml
                        val source = rssViewModel.startHtmlSource
                        if (html == null || source == null || source.sourceUrl != selected.url) {
                            if (rssViewModel.error != null) {
                                EmptyBox("加载失败，请重试") { rssViewModel.selectStartHtml(selected.url) }
                            } else {
                                LoadingBox("订阅源首页加载中…")
                            }
                        } else {
                            RssStartHtmlContent(
                                source, html, rssViewModel.startHtmlUserAgent, rssViewModel.startHtmlCookie,
                            )
                        }
                    } else {
                        RssContent(rssViewModel, bottomPaddingPx, scrollToTopRequest, scrollPositionCache, onArticleTapped)
                    }
                }
                DiscoverSourceType.HOME -> {
                    if (pyViewModel.currentSourceType() == "web") {
                        val html = pyViewModel.htmlContent
                        if (html == null) LoadingBox("页面加载中…") else HtmlWebView(html)
                    } else {
                        HomeContent(pyViewModel, bottomPaddingPx, scrollToTopRequest, scrollPositionCache)
                    }
                }
                null -> EmptyBox("暂无可用源\n点击设置，导入书源、订阅源或首页源")
            }
        }
    }

    if (pyViewModel.showImportDialog) {
        var importName by remember(pyViewModel.importUri) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = pyViewModel::cancelImport,
            title = { Text("导入源") },
            text = {
                OutlinedTextField(
                    value = importName,
                    onValueChange = { importName = it },
                    label = { Text(pyViewModel.importDefaultName.ifBlank { "名称" }) },
                    supportingText = { Text("自定义名称，留空使用文件名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { pyViewModel.confirmImportName(importName) }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = pyViewModel::cancelImport) { Text("取消") } },
        )
    }
    if (pyViewModel.showTvboxDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pyViewModel.showTvboxDialog = false },
            title = { Text("添加 TVbox 源") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("TVbox 订阅接口地址") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = url.isNotBlank(), onClick = {
                    pyViewModel.importTvboxSource(url)
                    pyViewModel.showTvboxDialog = false
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pyViewModel.showTvboxDialog = false }) { Text("取消") }
            },
        )
    }
    if (pyViewModel.showDanmakuApiDialog) {
        var url by remember { mutableStateOf(pyViewModel.danmakuApiUrl) }
        AlertDialog(
            onDismissRequest = { pyViewModel.showDanmakuApiDialog = false },
            title = { Text("LogVar 弹幕服务器") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("弹幕 API 地址") },
                    supportingText = { Text("兼容弹弹play API，留空关闭弹幕检索") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pyViewModel.saveDanmakuApiUrl(url)
                    pyViewModel.showDanmakuApiDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pyViewModel.showDanmakuApiDialog = false }) { Text("取消") }
            },
        )
    }
    if (showNetworkImportDialog) {
        AlertDialog(
            onDismissRequest = { showNetworkImportDialog = false },
            title = { Text("网络导入") },
            text = {
                OutlinedTextField(
                    value = networkImportUrl,
                    onValueChange = { networkImportUrl = it },
                    label = { Text("书源或订阅源链接") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Row {
                    TextButton(enabled = networkImportUrl.isNotBlank(), onClick = {
                        showNetworkImportDialog = false
                        onNetworkImportRequested(0, networkImportUrl.trim())
                    }) { Text("导入书源") }
                    TextButton(enabled = networkImportUrl.isNotBlank(), onClick = {
                        showNetworkImportDialog = false
                        onNetworkImportRequested(1, networkImportUrl.trim())
                    }) { Text("导入订阅源") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showNetworkImportDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SourcePickerDialog(
    visible: Boolean,
    sources: List<DiscoverSourceItem>,
    currentSource: DiscoverSourceItem?,
    onDismiss: () -> Unit,
    onSelected: (DiscoverSourceItem) -> Unit,
) {
    val kinds = listOf("BOOK" to "书源", "RSS" to "订阅源", "PY" to "py源", "TVBOX" to "TVbox源")
    var selectedKind by remember { mutableStateOf(currentSource?.pickerType ?: "BOOK") }
    val selectedGroups = remember { mutableStateMapOf<String, String>() }
    val queries = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(visible) {
        if (visible) selectedKind = currentSource?.pickerType ?: "BOOK"
    }
    if (!visible) return
    val selectedGroup = selectedGroups[selectedKind] ?: "全部"
    val query = queries[selectedKind].orEmpty()
    val kindSources = sources.filter { it.pickerType == selectedKind }
    val sourceGroups = kindSources.associate { source ->
        source.key to source.group?.splitNotBlank(AppPattern.splitGroupRegex).orEmpty().toList()
            .ifEmpty { listOf("未分组") }
    }
    val groups = listOf("全部") + sourceGroups.values.flatten().distinct()
    val visibleGroup = selectedGroup.takeIf { it in groups } ?: "全部"
    val filteredSources = kindSources.filter { source ->
        (visibleGroup == "全部" || visibleGroup in sourceGroups.getValue(source.key)) &&
            source.name.contains(query, ignoreCase = true)
    }.sortedByDescending { it.key == currentSource?.key }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f).widthIn(max = 560.dp)
                .eInkGrayscale(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { queries[selectedKind] = it },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    singleLine = true,
                    label = { Text("搜索源") },
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    kinds.forEach { (kind, label) ->
                        CategoryChip(label, selectedKind == kind) {
                            selectedKind = kind
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    groups.forEach { group ->
                        CategoryChip(group, visibleGroup == group) { selectedGroups[selectedKind] = group }
                    }
                }
                if (filteredSources.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("没有匹配的源", color = pageSecondaryTextColor())
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredSources, key = { it.key }) { source ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { onSelected(source) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (source.key == currentSource?.key) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    source.name,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookContent(
    viewModel: DiscoverBookViewModel,
    bottomPaddingPx: Int,
    scrollToTopRequest: Int,
    scrollPositionCache: MutableMap<String, Int>,
    onBookTapped: (SearchBook) -> Unit,
) {
    val categories = viewModel.categories
    val selectedCategory = viewModel.selectedCategory
    val categoryKey = selectedCategory?.let { "book:$it" }
    // 滚动状态按分类键创建：切换分类时拿到全新状态从顶部开始，避免沿用上一分类的滚动位置
    val gridState = remember(categoryKey) { LazyGridState() }
    var endReached by remember(selectedCategory) { mutableStateOf(false) }
    LaunchedEffect(scrollToTopRequest) { gridState.scrollToItem(0) }
    var restorePending by remember { mutableStateOf<String?>(null) }
    // 切换分类后挂起恢复：旧分类位置在滚动时持续记录，数据就位后再恢复新分类的缓存位置
    LaunchedEffect(categoryKey) {
        restorePending = categoryKey
    }
    // 滚动时记录当前分类位置；恢复进行中不记录，避免用待恢复的内容污染缓存
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset, categoryKey) {
        if (restorePending != null) return@LaunchedEffect
        val key = categoryKey ?: return@LaunchedEffect
        // 列表为空或布局仍是上一分类内容时不记录，避免把旧分类位置写进当前分类缓存
        if (viewModel.books.isEmpty()) return@LaunchedEffect
        if (gridState.layoutInfo.totalItemsCount != viewModel.books.size) return@LaunchedEffect
        scrollPositionCache[key] = gridState.firstVisibleItemIndex
    }
    // 数据就位后恢复分类缓存位置
    LaunchedEffect(viewModel, categoryKey, restorePending) {
        val target = restorePending ?: return@LaunchedEffect
        var awaitingData = true
        snapshotFlow { viewModel.books.size to viewModel.error }.collect { (count, error) ->
            if (awaitingData) {
                awaitingData = false
                if (count == 0) {
                    // 加载失败时放弃恢复，避免永久抑制位置记录
                    if (error != null) restorePending = null
                    return@collect
                }
            }
            val cached = scrollPositionCache[target]
            restorePending = null
            cached?.takeIf { count > it }?.let { gridState.scrollToItem(it) }
        }
    }
    LaunchedEffect(viewModel, selectedCategory) {
        snapshotFlow {
            val count = viewModel.books.size
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val shouldLoad = count > 0 && lastVisible >= (count - 7).coerceAtLeast(0) &&
                selectedCategory != null && !endReached && !viewModel.isLoading &&
                !viewModel.isLoadingMore && viewModel.error == null
            shouldLoad to viewModel.page
        }.collect { (shouldLoad, page) ->
            if (shouldLoad) {
                viewModel.loadNextPage()
                snapshotFlow { viewModel.isLoadingMore }.first { !it }
                // An empty response leaves the page unchanged; there is no total-page API.
                if (viewModel.error == null && viewModel.page == page) endReached = true
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (categories.isNotEmpty()) {
            CategoryBar(categories, selectedCategory) { viewModel.selectCategory(it) }
        }
        when {
            viewModel.isLoading && viewModel.books.isEmpty() -> LoadingBox("书目加载中…")
            viewModel.error != null && viewModel.books.isEmpty() -> EmptyBox("加载失败，请重试", viewModel::retry)
            viewModel.books.isEmpty() -> EmptyBox("该分类暂无内容")
            else -> {
                val bottomPadding = with(LocalDensity.current) { bottomPaddingPx.toDp() }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + bottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(viewModel.books, key = { it.bookUrl }) { book ->
                        Column(Modifier.fillMaxWidth().clickable { onBookTapped(book) }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(4.dp))) {
                                AppBookCover(
                                    modifier = Modifier.fillMaxSize(),
                                    name = book.name,
                                    author = book.author,
                                    coverPath = book.coverUrl,
                                    galleryIdentity = book.bookUrl,
                                    contentDescription = book.name,
                                    sourceOrigin = book.origin,
                                    loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
                                    drawAuthorOverlay = false,
                                )
                                book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { chapter ->
                                    Box(
                                        Modifier.align(Alignment.TopEnd).fillMaxWidth()
                                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)))
                                            .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 18.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Text(chapter, color = Color.White, style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                book.author?.takeIf { it.isNotBlank() }?.let { author ->
                                    Box(
                                        Modifier.align(Alignment.BottomEnd).fillMaxWidth()
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                                            .padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 5.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Text(author, color = Color.White, style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Text(book.name, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (viewModel.isLoadingMore) {
                            PageLoadingIndicator()
                        } else if (viewModel.error != null) {
                            RetryMessage("加载失败，请重试", viewModel::loadNextPage)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RssContent(
    viewModel: DiscoverRssViewModel,
    bottomPaddingPx: Int,
    scrollToTopRequest: Int,
    scrollPositionCache: MutableMap<String, Int>,
    onArticleTapped: (RssArticle, String, List<RssArticle>, Pair<String, String>?, String?, Int) -> Unit,
) {
    val selectedCategory = viewModel.selectedCategory
    val currentArticles = viewModel.articles
    var retainedArticles by remember(selectedCategory) { mutableStateOf<List<RssArticle>>(emptyList()) }
    LaunchedEffect(currentArticles) {
        if (currentArticles.isNotEmpty()) retainedArticles = currentArticles
    }
    val articles = currentArticles.ifEmpty {
        if (viewModel.isLoading || viewModel.error != null) retainedArticles else emptyList()
    }
    val categoryKey = selectedCategory?.let { "rss:$it" }
    // 滚动状态按分类键创建：切换分类时拿到全新状态从顶部开始，避免沿用上一分类的滚动位置
    val gridState = remember(categoryKey) { LazyGridState() }
    var endReached by remember(selectedCategory) { mutableStateOf(false) }
    LaunchedEffect(scrollToTopRequest) { gridState.scrollToItem(0) }
    var restorePending by remember { mutableStateOf<String?>(null) }
    // 切换分类后挂起恢复：旧分类位置在滚动时持续记录，数据就位后再恢复新分类的缓存位置
    LaunchedEffect(categoryKey) {
        restorePending = categoryKey
    }
    // 滚动时记录当前分类位置；恢复进行中不记录，避免用待恢复的内容污染缓存
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset, categoryKey) {
        if (restorePending != null) return@LaunchedEffect
        val key = categoryKey ?: return@LaunchedEffect
        // 列表为空或布局仍是上一分类内容时不记录，避免把旧分类位置写进当前分类缓存
        if (articles.isEmpty()) return@LaunchedEffect
        if (gridState.layoutInfo.totalItemsCount != articles.size) return@LaunchedEffect
        scrollPositionCache[key] = gridState.firstVisibleItemIndex
    }
    // 数据就位后恢复分类缓存位置
    LaunchedEffect(viewModel, categoryKey, restorePending) {
        val target = restorePending ?: return@LaunchedEffect
        var awaitingData = true
        snapshotFlow { viewModel.articles.size to viewModel.error }.collect { (count, error) ->
            if (awaitingData) {
                awaitingData = false
                if (count == 0) {
                    // 加载失败时放弃恢复，避免永久抑制位置记录
                    if (error != null) restorePending = null
                    return@collect
                }
            }
            val cached = scrollPositionCache[target]
            restorePending = null
            cached?.takeIf { count > it }?.let { gridState.scrollToItem(it) }
        }
    }
    LaunchedEffect(viewModel, selectedCategory) {
        snapshotFlow {
            val count = viewModel.articles.size
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val shouldLoad = count > 0 && lastVisible >= (count - 7).coerceAtLeast(0) &&
                !viewModel.nextPageUrl.isNullOrBlank() && !endReached && !viewModel.isLoading &&
                !viewModel.isLoadingMore && viewModel.error == null
            shouldLoad to viewModel.page
        }.collect { (shouldLoad, page) ->
            if (shouldLoad) {
                viewModel.loadNextPage()
                snapshotFlow { viewModel.isLoadingMore }.first { !it }
                // 页码未推进说明没有新内容，与书源翻页结束判定一致
                if (viewModel.error == null && viewModel.page == page) {
                    endReached = true
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (viewModel.categories.size > 1) {
            CategoryBar(viewModel.categories, viewModel.selectedCategory) { viewModel.selectCategory(it) }
        }
        when {
            viewModel.isLoading && articles.isEmpty() -> LoadingBox("订阅内容加载中…")
            viewModel.error != null && articles.isEmpty() -> EmptyBox("加载失败，请重试", viewModel::retry)
            articles.isEmpty() -> EmptyBox("该订阅源暂无内容")
            else -> {
                val bottomPadding = with(LocalDensity.current) { bottomPaddingPx.toDp() }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + bottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(articles, key = { "${it.origin}:${it.sort}:${it.link}" }) { article ->
                        Column(Modifier.fillMaxWidth().clickable {
                            onArticleTapped(
                                article, article.origin, articles.toList(), selectedCategory,
                                viewModel.nextPageUrl, viewModel.page,
                            )
                        }) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                if (!article.image.isNullOrBlank()) {
                                    SourceVideoImage(article.image.orEmpty(), article.title)
                                } else {
                                    Text(
                                        article.title.take(2),
                                        modifier = Modifier.align(Alignment.Center),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = pageSecondaryTextColor(),
                                    )
                                }
                                article.pubDate?.takeIf { it.isNotBlank() }?.let { published ->
                                    Box(
                                        Modifier.align(Alignment.TopEnd).fillMaxWidth()
                                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)))
                                            .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 18.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Text(published, color = Color.White, style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Text(article.title, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (viewModel.isLoading || viewModel.isLoadingMore) {
                            PageLoadingIndicator()
                        } else if (viewModel.error != null) {
                            RetryMessage("加载失败，请重试") {
                                if (viewModel.nextPageUrl.isNullOrBlank()) viewModel.retry()
                                else viewModel.loadNextPage()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeContent(
    viewModel: PyHomepageViewModel,
    bottomPaddingPx: Int,
    scrollToTopRequest: Int,
    scrollPositionCache: MutableMap<String, Int>,
) {
    val homeData = viewModel.homeData
    val categories = listOf("推荐" to "") + homeData?.categories.orEmpty().map { it.name to it.id }
    val selected = categories.find { it.second == viewModel.selectedCategoryId }
    val categoryKey = "home:${viewModel.selectedCategoryId}"
    val videos = (viewModel.categoryData?.videos ?: homeData?.videos.orEmpty()).distinctBy { it.id }
    // 滚动状态按分类键创建：切换分类时拿到全新状态从顶部开始，避免沿用上一分类的滚动位置
    val gridState = remember(categoryKey) { LazyGridState() }
    var paginationError by remember(viewModel.selectedSourceId, viewModel.selectedCategoryId, viewModel.selectedFilters) {
        mutableStateOf<String?>(null)
    }
    var retryRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrollToTopRequest) { gridState.scrollToItem(0) }
    var restorePending by remember { mutableStateOf<String?>(null) }
    // 切换分类后挂起恢复：旧分类位置在滚动时持续记录，数据就位后再恢复新分类的缓存位置
    LaunchedEffect(categoryKey) {
        restorePending = categoryKey
    }
    // 滚动时记录当前分类位置；恢复进行中不记录，避免用待恢复的内容污染缓存
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset, categoryKey) {
        if (restorePending != null) return@LaunchedEffect
        // 列表为空或布局仍是上一分类内容时不记录，避免把旧分类位置写进当前分类缓存
        if (videos.isEmpty()) return@LaunchedEffect
        if (gridState.layoutInfo.totalItemsCount != videos.size) return@LaunchedEffect
        scrollPositionCache[categoryKey] = gridState.firstVisibleItemIndex
    }
    // 数据就位后恢复分类缓存位置
    LaunchedEffect(viewModel, categoryKey, restorePending) {
        val target = restorePending ?: return@LaunchedEffect
        var awaitingData = true
        snapshotFlow {
            (viewModel.categoryData?.videos?.size ?: viewModel.homeData?.videos?.size ?: 0) to viewModel.homeError
        }.collect { (count, error) ->
            if (awaitingData) {
                awaitingData = false
                if (count == 0) {
                    // 加载失败时放弃恢复，避免永久抑制位置记录
                    if (error != null) restorePending = null
                    return@collect
                }
            }
            val cached = scrollPositionCache[target]
            restorePending = null
            cached?.takeIf { count > it }?.let { gridState.scrollToItem(it) }
        }
    }
    LaunchedEffect(viewModel, viewModel.selectedSourceId, viewModel.selectedCategoryId, viewModel.selectedFilters) {
        snapshotFlow {
            val count = viewModel.categoryData?.videos?.distinctBy { it.id }?.size ?: 0
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val shouldLoad = count > 0 && lastVisible >= (count - 6).coerceAtLeast(0) &&
                viewModel.selectedCategoryId.isNotEmpty() && viewModel.currentPage < viewModel.totalPages &&
                !viewModel.isLoadingMore && viewModel.homeError == null && paginationError == null
            Triple(shouldLoad, viewModel.currentPage, retryRequest)
        }.collect { (shouldLoad, page, _) ->
            if (shouldLoad) {
                viewModel.loadMorePage()
                snapshotFlow { viewModel.isLoadingMore }.first { !it }
                if (viewModel.currentPage == page) paginationError = "加载更多失败或该页暂无内容"
            }
        }
    }
    val retry: () -> Unit = {
        if (viewModel.selectedCategoryId.isEmpty()) viewModel.loadHome(viewModel.selectedSourceId)
        else viewModel.loadCategory(viewModel.selectedSourceId, viewModel.selectedCategoryId, viewModel.currentPage)
    }
    Column(Modifier.fillMaxSize()) {
        if (categories.isNotEmpty()) CategoryBar(categories, selected) { viewModel.selectCategory(it?.second.orEmpty()) }
        when {
            videos.isEmpty() && viewModel.homeError != null -> EmptyBox("加载失败，请重试", retry)
            homeData == null && videos.isEmpty() -> LoadingBox("首页加载中…")
            videos.isEmpty() -> EmptyBox("该源暂无内容")
            else -> {
                val bottomPadding = with(LocalDensity.current) { bottomPaddingPx.toDp() }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + bottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(videos, key = { it.id }) { video ->
                        Column(Modifier.fillMaxWidth().clickable {
                            viewModel.playVideoDirectly(viewModel.selectedSourceId, video.id, video.title)
                        }) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                if (video.pic.isNotEmpty()) {
                                    SourceVideoImage(video.pic, video.title)
                                } else {
                                    Icon(Icons.Default.OndemandVideo, null, Modifier.align(Alignment.Center), tint = pageSecondaryTextColor())
                                }
                            }
                            Text(video.title, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            viewModel.isLoadingMore -> PageLoadingIndicator()
                            paginationError != null -> RetryMessage(paginationError.orEmpty()) {
                                paginationError = null
                                retryRequest++
                            }
                            viewModel.homeError != null -> RetryMessage("加载失败，请重试", retry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBar(
    categories: List<Pair<String, String>>,
    selected: Pair<String, String>?,
    onSelected: (Pair<String, String>?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        categories.forEach { category ->
            CategoryChip(category.first, category == selected) { onSelected(category) }
        }
    }
}

@Composable
private fun CategoryChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(name.ifBlank { "全部" }, style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else pageSecondaryTextColor())
    }
}

@Composable
private fun HtmlWebView(html: String) {
    val context = LocalContext.current
    key(html) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.releaseDiscoverWebView() },
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun SourceVideoImage(url: String, title: String) {
    GlideImage(url, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

@Composable
private fun RssStartHtmlContent(source: RssSource, html: String, userAgent: String?, cookie: String?) {
    key(source.sourceUrl, html, userAgent, cookie) {
        var webView by remember { mutableStateOf<WebView?>(null) }
        var isResumed by remember { mutableStateOf(false) }
        var closed by remember { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                isResumed = event == Lifecycle.Event.ON_RESUME
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) isResumed = false
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val closePage: () -> Unit = {
            val current = webView
            if (current?.canGoBack() == true) current.goBack() else closed = true
        }
        BackHandler(enabled = isResumed && !closed, onBack = closePage)
        if (closed) {
            EmptyBox("页面已关闭") { closed = false }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        webView = this
                        settings.javaScriptEnabled = source.enableJs
                        settings.domStorageEnabled = true
                        userAgent?.let { settings.userAgentString = it }
                        settings.cacheMode = if (source.cacheFirst) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
                        addJavascriptInterface(WebCacheManager, nameCache)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                source.injectJs?.takeIf { it.isNotBlank() }?.let { view.evaluateJavascript(it, null) }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) = closePage()
                        }
                        addJavascriptInterface(BasicRssBridge(this, closePage), nameBasic)
                        val activity = findActivity(viewContext) as? androidx.appcompat.app.AppCompatActivity
                        val bridge = WebJsExtensions(source, activity, this)
                        addJavascriptInterface(bridge, nameJava)
                        addJavascriptInterface(source, nameSource)
                        val view = this
                        if (cookie.isNullOrBlank()) {
                            loadDataWithBaseURL(source.sourceUrl, html, "text/html", "utf-8", source.sourceUrl)
                        } else {
                            val manager = CookieManager.getInstance()
                            val cookies = cookie.split(';').map(String::trim).filter(String::isNotEmpty)
                            fun loadWhenReady(remaining: Int) {
                                if (remaining == 0) {
                                    manager.flush()
                                    if (webView === view) {
                                        view.loadDataWithBaseURL(source.sourceUrl, html, "text/html", "utf-8", source.sourceUrl)
                                    }
                                } else {
                                    manager.setCookie(source.sourceUrl, cookies[cookies.lastIndex - remaining + 1]) {
                                        loadWhenReady(remaining - 1)
                                    }
                                }
                            }
                            loadWhenReady(cookies.size)
                        }
                    }
                },
                onRelease = {
                    if (webView === it) {
                        webView = null
                    }
                    it.releaseDiscoverWebView()
                },
            )
        }
    }
}

private fun findActivity(context: android.content.Context): android.app.Activity? = when (context) {
    is android.app.Activity -> context
    is android.content.ContextWrapper -> findActivity(context.baseContext)
    else -> null
}

private class BasicRssBridge(private val webView: WebView, private val onClose: () -> Unit) {

    @android.webkit.JavascriptInterface
    fun lockOrientation(@Suppress("UNUSED_PARAMETER") orientation: String) = Unit

    @android.webkit.JavascriptInterface
    fun onCloseRequested() {
        webView.post { onClose() }
    }
}

private fun WebView.releaseDiscoverWebView() {
    stopLoading()
    removeJavascriptInterface(nameCache)
    removeJavascriptInterface(nameBasic)
    removeJavascriptInterface(nameJava)
    removeJavascriptInterface(nameSource)
    webChromeClient = null
    webViewClient = WebViewClient()
    removeAllViews()
    destroy()
}

@Composable
private fun PageLoadingIndicator() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun RetryMessage(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = pageSecondaryTextColor(), textAlign = TextAlign.Center)
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun LoadingBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyMedium, color = pageSecondaryTextColor(), modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun EmptyBox(text: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (onRetry == null) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = pageSecondaryTextColor(), textAlign = TextAlign.Center)
        } else {
            RetryMessage(text, onRetry)
        }
    }
}
