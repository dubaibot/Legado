package io.legado.app.ui.book.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.pageTopBarBackground
import io.legado.app.ui.theme.pageTopBarColors
import io.legado.app.ui.widget.components.AppBookCover
import kotlinx.coroutines.flow.distinctUntilChanged
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BookSearchScreen(
    query: String, onQueryChange: (String) -> Unit,
    books: List<SearchBook>, history: List<SearchKeyword>, groups: List<String>, scope: String,
    searching: Boolean, hasMore: Boolean, submitted: Boolean,
    preferences: SearchPreferences, hotViewModel: SearchHotViewModel,
    onSearch: () -> Unit, onScope: (List<String>) -> Unit,
    onPreferences: (SearchPreferences) -> Unit, onHistory: (String) -> Unit,
    onDeleteHistory: (SearchKeyword) -> Unit, onClearHistory: () -> Unit,
    onBook: (SearchBook) -> Unit, isInBookshelf: (SearchBook) -> Boolean,
    onManageSources: () -> Unit, onLog: () -> Unit,
    onRefresh: () -> Unit, onLoadMore: () -> Unit, onStopSearch: () -> Unit,
    typeBar: @Composable () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf("") }
    var showRecommendations by rememberSaveable { mutableStateOf(false) }
    var searchStopped by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(searching) {
        if (searching) {
            showRecommendations = false
            searchStopped = false
        }
    }
    val focus = LocalFocusManager.current
    val selectedGroups = if (scope.contains("::")) emptyList() else scope.split(',').filter(String::isNotBlank)
    val orderedGroups = groups.sortedBy { if (it in selectedGroups) 0 else 1 }
    val toggleMulti = {
        val multi = !preferences.multiGroup
        onPreferences(preferences.copy(multiGroup = multi))
        if (!multi && selectedGroups.size > 1) onScope(selectedGroups.take(1))
    }
    val submit = { focus.clearFocus(); onSearch() }
    val visibleBooks = books.filter(preferences::accepts)
    // Multiples of three preserve discovery columns independently of result columns.
    val columns = if (preferences.grid) preferences.columns else 1
    val lanes = if (columns % 3 == 0) columns else columns * 3
    val gridState = rememberLazyGridState()
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    val recommendationsVisible by rememberUpdatedState(showRecommendations)
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            Triple(
                info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1 &&
                    info.totalItemsCount > 0,
                gridState.isScrollInProgress,
                info.totalItemsCount
            )
        }.distinctUntilChanged().collect { atBottom ->
            if (atBottom.first && atBottom.second && !recommendationsVisible) latestLoadMore()
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .navigationBarsPadding().imePadding()) {
        val topColors = pageTopBarColors()
        Column(Modifier.pageTopBarBackground(topColors).statusBarsPadding()) {
            CompositionLocalProvider(LocalContentColor provides topColors.contentColor) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Search, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            BasicTextField(value = query, onValueChange = onQueryChange,
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp), singleLine = true,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submit() }),
                                decorationBox = { field ->
                                    Box {
                                        if (query.isEmpty()) Text("搜索书名、作者", style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        field()
                                    }
                                })
                            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, "清除输入", Modifier.size(16.dp))
                            }
                            Button(onClick = submit, shape = RoundedCornerShape(22.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                                Text("搜索", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    IconButton(onClick = { dialog = "groupList" }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.GridView, "分组列表")
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.MoreVert, "更多选项")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            listOf("书源管理", "推荐设置", "视图设置", "搜索日志")
                                .forEachIndexed { index, title ->
                                    DropdownMenuItem(text = { Text(title) },
                                        onClick = {
                                            menu = false
                                            when (index) {
                                                0 -> onManageSources()
                                                1 -> dialog = "hot"
                                                2 -> dialog = "cards"
                                                3 -> onLog()
                                            }
                                        })
                                }
                        }
                    }
                }
            }
        }
        typeBar()
        PullToRefreshBox(isRefreshing = false,
            onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(columns = GridCells.Fixed(lanes), state = gridState,
                modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 3.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "groups", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onPreferences(preferences.copy(precision = !preferences.precision)) }) {
                                Text("精准搜索", style = MaterialTheme.typography.labelMedium)
                                Checkbox(preferences.precision, null, Modifier.size(32.dp))
                            }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { toggleMulti() }) {
                                Text("多组搜索", style = MaterialTheme.typography.labelMedium)
                                Checkbox(preferences.multiGroup, null, Modifier.size(32.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { dialog = "filters" }, contentPadding = PaddingValues(0.dp)) {
                                Text("高级筛选", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SearchPill(scope.isEmpty(), "综合") { onScope(emptyList()) }
                            if (scope.contains("::")) {
                                SearchPill(true, scope.substringBefore("::")) { onScope(emptyList()) }
                            }
                            orderedGroups.forEach { group ->
                                val selected = !scope.contains("::") && group in scope.split(',')
                                SearchPill(selected, group) {
                                    val next = if (preferences.multiGroup && !scope.contains("::")) {
                                        val current = scope.split(',').filter(String::isNotBlank)
                                        if (selected) current - group else current + group
                                    } else if (selected) emptyList() else listOf(group)
                                    onScope(next)
                                }
                            }
                        }
                        }
                    }
                }
                item(key = "history", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SectionHeading("搜索历史", "清空") { dialog = "clear" }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            history.take(20).forEach { entry ->
                                Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 1.dp,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.combinedClickable(onClick = { onHistory(entry.word) },
                                        onLongClickLabel = "删除搜索历史", onLongClick = { onDeleteHistory(entry) })) {
                                     Text(entry.word, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                         color = Color.Gray,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.widthIn(max = 200.dp).padding(horizontal = 10.dp, vertical = 6.dp))
                                }
                            }
                        }
                        if (history.isEmpty()) Text("暂无搜索历史", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (submitted) item(key = "resultsTitle", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("找到${visibleBooks.size}个搜索结果", style = MaterialTheme.typography.titleSmall)
                        if (searching) IconButton(onClick = { searchStopped = true; onStopSearch() }, modifier = Modifier.size(36.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showRecommendations = !showRecommendations }) {
                            Icon(Icons.Default.AutoStories, if (showRecommendations) "切换搜索结果" else "切换推荐",
                                tint = if (showRecommendations) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onPreferences(preferences.copy(grid = !preferences.grid)) }) {
                            Icon(if (preferences.grid) Icons.Default.ViewList else Icons.Default.GridView, "切换布局")
                        }
                    }
                }
                val hotLoading = hotViewModel.loading
                if ((books.isEmpty() || showRecommendations) && hotViewModel.selections.isNotEmpty()) {
                    item(key = "hotTitle", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("推荐", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                             IconButton(onClick = { hotViewModel.refresh() }, enabled = !hotLoading) {
                                if (hotLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, "刷新热门榜单")
                            }
                        }
                    }
                    item(key = "hotAddresses", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            hotViewModel.selections.forEach { selection ->
                                val source = hotViewModel.sources.firstOrNull { it.bookSourceUrl == selection.sourceUrl }
                                val title = if (hotViewModel.selections.count { it.title == selection.title } > 1)
                                    "${source?.bookSourceName.orEmpty()} · ${selection.title}" else selection.title
                                SearchPill(hotViewModel.activeSelection == selection, title) { hotViewModel.selectAddress(selection) }
                            }
                        }
                    }
                    if (!hotLoading && hotViewModel.cards.isEmpty()) item(key = "hotEmpty", span = { GridItemSpan(maxLineSpan) }) {
                        Text("这个榜单暂时没有内容，试试其他发现地址", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (hotLoading && hotViewModel.cards.isEmpty()) {
                        items(3, key = { "hotSkeleton:$it" }, span = { GridItemSpan(lanes / 3) }) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(.72f)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium))
                                Box(Modifier.fillMaxWidth(.7f).height(14.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small))
                            }
                        }
                        item(key = "hotLoading", span = { GridItemSpan(maxLineSpan) }) {
                            Text("加载热门榜单…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        items(hotViewModel.cards.size, key = { "hot:$it:${hotViewModel.cards[it].book.primaryStr()}" },
                            span = { GridItemSpan(lanes / 3) }) { index ->
                            val card = hotViewModel.cards[index]
                            HotBookCard(card, index + 1, onClick = { onHistory(card.book.name) })
                        }
                    }
                }
                if (!showRecommendations) {
                items(visibleBooks.size, key = { "result:$it:${visibleBooks[it].primaryStr()}" },
                    span = { GridItemSpan(lanes / columns) }) { index ->
                    val book = visibleBooks[index]
                    SearchBookCard(book, compact = preferences.grid,
                        badge = "${book.originName} · ${book.origins.size} 个书源",
                        inShelf = isInBookshelf(book), onClick = { onBook(book) })
                }
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(when {
                            searching -> "正在搜索…"
                            searchStopped -> "搜索已停止"
                            !submitted -> "输入书名或作者开始搜索"
                            visibleBooks.isEmpty() -> "暂无符合条件的结果"
                            hasMore -> "继续上滑加载更多"
                            else -> "已加载全部结果"
                        }, style = MaterialTheme.typography.bodySmall)
                        if (submitted && hasMore && !searching) TextButton(onClick = onLoadMore) { Text("加载更多") }
                    }
                }
                }
            }
        }
    }
    when (dialog) {
        "groupList" -> AlertDialog(onDismissRequest = { dialog = "" }, title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选择分组", Modifier.weight(1f))
                Row(Modifier.clickable { toggleMulti() }, verticalAlignment = Alignment.CenterVertically) {
                Text("多选", style = MaterialTheme.typography.labelLarge)
                    Checkbox(preferences.multiGroup, null, Modifier.size(32.dp))
                }
            }
        },
            text = {
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { SearchPill(scope.isEmpty(), "综合") { onScope(emptyList()) } }
                    if (scope.contains("::")) item {
                        SearchPill(true, scope.substringBefore("::")) { onScope(emptyList()) }
                    }
                    items(orderedGroups.size) { index ->
                        val group = orderedGroups[index]
                        val selected = !scope.contains("::") && group in scope.split(',')
                        SearchPill(selected, group) {
                            val current = scope.split(',').filter(String::isNotBlank)
                            onScope(if (preferences.multiGroup && !scope.contains("::")) {
                                if (selected) current - group else current + group
                            } else if (selected) emptyList() else listOf(group))
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("完成") } })
        "hot" -> HotSourcesDialog(hotViewModel) { dialog = "" }
        "clear" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("清空搜索历史") },
            text = { Text("确定清空全部搜索历史？") },
            confirmButton = { TextButton(onClick = { onClearHistory(); dialog = "" }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("取消") } })
        "cards", "groups", "filters" -> SearchSettingsDialog(dialog, preferences,
            onSave = { updated ->
                onPreferences(updated)
                if (!updated.multiGroup && !scope.contains("::") && scope.split(',').size > 1) onScope(scope.split(',').take(1))
                dialog = ""
            }, onDismiss = { dialog = "" })
    }
}

@Composable
private fun SearchPill(selected: Boolean, title: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), shadowElevation = 1.dp,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = Color.Black) {
        Text(title, Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun SectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = onClick) { Text(action, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun HotBookCard(card: HotCard, rank: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                BookCover(card.book, Modifier.fillMaxWidth().aspectRatio(.72f))
                if (rank <= 3) Surface(color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.small) {
                    Text("$rank", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            Text(card.book.name, style = MaterialTheme.typography.labelLarge,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(card.book.author.ifBlank { "佚名" }, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchBookCard(book: SearchBook, compact: Boolean, badge: String, inShelf: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        if (compact) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BookCover(book, Modifier.fillMaxWidth().aspectRatio(.72f))
                Text(book.name, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author.ifBlank { "佚名" }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(badge, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (inShelf) Text("已在书架", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BookCover(book, Modifier.width(76.dp).height(108.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(book.author, book.wordCount, book.kind).filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(book.intro.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.getDisplayLastChapterTitle(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(badge + if (inShelf) " · 已在书架" else "", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun BookCover(book: SearchBook, modifier: Modifier) {
    AppBookCover(modifier = modifier, name = book.name, author = book.author,
        coverPath = book.coverUrl, galleryIdentity = book.bookUrl, contentDescription = book.name,
        sourceOrigin = book.origin, loadOnlyWifi = AppConfig.loadCoverOnlyWifi)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchSettingsDialog(kind: String, preferences: SearchPreferences,
    onSave: (SearchPreferences) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(kind) { mutableStateOf(preferences) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(when (kind) { "cards" -> "视图设置"; "groups" -> "分组设置"; else -> "高级筛选" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (kind) {
                    "cards" -> {
                        SettingCheck("网格卡片视图", draft.grid) { draft = draft.copy(grid = it) }
                        Text("网格列数")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (2..5).forEach { count -> FilterChip(draft.columns == count,
                                { draft = draft.copy(columns = count) }, { Text("$count 列") }) }
                        }
                    }
                    "groups" -> SettingCheck("多组搜索", draft.multiGroup) { draft = draft.copy(multiGroup = it) }
                    else -> {
                        SettingCheck("隐藏无封面书籍", draft.hideNoCover) { draft = draft.copy(hideNoCover = it) }
                        Text("连载状态（依据书源分类和最新章节标记）")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("全部", "连载", "完结").forEachIndexed { index, title ->
                                FilterChip(draft.status == index, { draft = draft.copy(status = index) }, { Text(title) })
                            }
                        }
                        Text("最低字数（字数未知时仅在不限档显示）")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 100000, 300000, 500000, 1000000, 2000000).forEach { words ->
                                FilterChip(draft.minWords == words, { draft = draft.copy(minWords = words) },
                                    { Text(if (words == 0) "不限" else "${words / 10000} 万") })
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SettingCheck(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked, onChange)
    }
}

@Composable
private fun HotSourcesDialog(viewModel: SearchHotViewModel, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(viewModel.selections.toList()) }
    var sourceOrder by remember { mutableStateOf(
        (viewModel.sourceOrder + viewModel.sources.map { it.bookSourceUrl }).distinct()
    ) }
    var addressOrders by remember { mutableStateOf(viewModel.addressOrders.toMap()) }
    LaunchedEffect(viewModel.sources) {
        sourceOrder = (sourceOrder + viewModel.sources.map { it.bookSourceUrl }).distinct()
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceQuery by rememberSaveable { mutableStateOf("") }
    val matchingSources = viewModel.sources.filter {
        it.bookSourceName.contains(sourceQuery.trim(), ignoreCase = true) ||
            it.bookSourceUrl.contains(sourceQuery.trim(), ignoreCase = true)
    }.sortedWith(compareBy(
        { source -> if (draft.any { it.sourceUrl == source.bookSourceUrl }) 0 else 1 },
        { source -> sourceOrder.indexOf(source.bookSourceUrl).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    ))
    val rows = buildList {
        matchingSources.forEach { source ->
            val sourceUrl = source.bookSourceUrl
            add(HotConfigRow(sourceUrl))
            if (expanded == sourceUrl) {
                val savedOrder = addressOrders[sourceUrl].orEmpty()
                val categories = (viewModel.kinds[sourceUrl].orEmpty().map {
                    HotSelection(sourceUrl, it.title, it.url.orEmpty())
                } + draft.filter { it.sourceUrl == sourceUrl }).distinctBy { it.url }
                val ordered = categories.sortedWith(compareBy(
                    { category -> if (draft.any { it.sourceUrl == sourceUrl && it.url == category.url }) 0 else 1 },
                    { category ->
                        val selectedIndex = draft.indexOfFirst { it.sourceUrl == sourceUrl && it.url == category.url }
                        if (selectedIndex >= 0) selectedIndex
                        else savedOrder.indexOf(category.url).takeIf { it >= 0 } ?: Int.MAX_VALUE
                    }
                ))
                ordered.forEach { add(HotConfigRow(sourceUrl, it)) }
                add(HotConfigRow(sourceUrl, status = true))
            }
        }
    }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val moving = rows.firstOrNull { it.key == from.key }
        val target = rows.firstOrNull { it.key == to.key }
        if (moving != null && target != null && !moving.status && !target.status) {
            val choice = moving.selection
            val targetChoice = target.selection
            if (choice == null) {
                val movingSelected = draft.any { it.sourceUrl == moving.sourceUrl }
                val targetSelected = draft.any { it.sourceUrl == target.sourceUrl }
                if (moving.sourceUrl != target.sourceUrl && movingSelected == targetSelected) {
                    sourceOrder = sourceOrder.toMutableList().apply {
                        val fromIndex = indexOf(moving.sourceUrl)
                        val toIndex = indexOf(target.sourceUrl)
                        if (fromIndex >= 0 && toIndex >= 0) add(toIndex, removeAt(fromIndex))
                    }
                    draft = draft.sortedBy { sourceOrder.indexOf(it.sourceUrl) }
                }
            } else if (targetChoice != null && choice.sourceUrl == targetChoice.sourceUrl) {
                val fromIndex = draft.indexOfFirst { it.sourceUrl == choice.sourceUrl && it.url == choice.url }
                val toIndex = draft.indexOfFirst { it.sourceUrl == targetChoice.sourceUrl && it.url == targetChoice.url }
                // Selected-first is a fixed partition; reorder within each partition by stable keys.
                if ((fromIndex >= 0) == (toIndex >= 0)) {
                    val urls = rows.filter { it.sourceUrl == choice.sourceUrl }
                        .mapNotNull { it.selection?.url }.toMutableList()
                    val fromUrlIndex = urls.indexOf(choice.url)
                    val toUrlIndex = urls.indexOf(targetChoice.url)
                    if (fromUrlIndex >= 0 && toUrlIndex >= 0) {
                        urls.add(toUrlIndex, urls.removeAt(fromUrlIndex))
                        addressOrders = addressOrders + (choice.sourceUrl to
                            (urls + addressOrders[choice.sourceUrl].orEmpty()).distinct())
                        if (fromIndex >= 0) draft = draft.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                    }
                }
            }
        }
    }
    val selectedBlue = Color(0xFF448AFF)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("推荐设置") },
        text = {
            Column {
                Text("选择参与热门榜单的书源与发现地址", style = MaterialTheme.typography.bodyMedium)
                Text("已选 ${draft.size} 项 · 长按书源或地址拖动排序，已选置顶", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = sourceQuery, onValueChange = { sourceQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                    placeholder = { Text("搜索书源名称或地址") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (sourceQuery.isNotEmpty()) IconButton(onClick = { sourceQuery = "" }) {
                            Icon(Icons.Default.Close, "清空书源搜索")
                        }
                    }, shape = RoundedCornerShape(16.dp))
                TextButton(onClick = { draft = emptyList() }) { Text("清除选择") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = listState) {
                    if (viewModel.sources.isEmpty()) item { Text("暂无启用发现的书源，请先在书源管理中配置") }
                    else if (matchingSources.isEmpty()) item { Text("没有匹配的书源，试试其他关键词") }
                    items(rows, key = { it.key }) { row ->
                        if (row.status) {
                            if (row.sourceUrl in viewModel.loadingKinds) LinearProgressIndicator(Modifier.fillMaxWidth())
                            val kinds = viewModel.kinds[row.sourceUrl]
                            if (kinds != null && kinds.isEmpty()) Text("该书源暂无可用分类")
                            if (kinds == null && row.sourceUrl !in viewModel.loadingKinds) {
                                TextButton(onClick = { viewModel.loadKinds(row.sourceUrl) }) { Text("加载失败，点击重试") }
                            }
                        } else ReorderableItem(reorderState, key = row.key) { dragging ->
                            Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = if (dragging) 4.dp else 0.dp,
                                color = MaterialTheme.colorScheme.surface) {
                                Row(Modifier.fillMaxWidth().longPressDraggableHandle(
                                    onDragStarted = { if (row.selection == null) expanded = null }
                                ), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.DragHandle, "长按拖动排序", Modifier.size(20.dp))
                                    Box(Modifier.weight(1f)) {
                                        val category = row.selection
                                        if (category == null) {
                                            val source = matchingSources.first { it.bookSourceUrl == row.sourceUrl }
                                            TextButton(onClick = {
                                                expanded = if (expanded == row.sourceUrl) null else row.sourceUrl
                                                if (expanded != null) viewModel.loadKinds(row.sourceUrl)
                                            }) {
                                                Text("${if (expanded == row.sourceUrl) "收起" else "展开"} · ${source.bookSourceName} (${draft.count { it.sourceUrl == row.sourceUrl }})",
                                                    color = if (draft.any { it.sourceUrl == row.sourceUrl }) selectedBlue else MaterialTheme.colorScheme.onSurface)
                                            }
                                        } else {
                                            val index = draft.indexOfFirst { it.sourceUrl == row.sourceUrl && it.url == category.url }
                                            CompositionLocalProvider(LocalContentColor provides if (index >= 0) selectedBlue else MaterialTheme.colorScheme.onSurface) {
                                                Row(Modifier.fillMaxWidth().clickable {
                                                    draft = if (index >= 0) draft.filterNot { it.sourceUrl == row.sourceUrl && it.url == category.url }
                                                    else draft + category
                                                }, verticalAlignment = Alignment.CenterVertically) {
                                                    Text(if (index >= 0) "${index + 1}. ${category.title}" else category.title,
                                                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                                    Checkbox(index >= 0, null, colors = CheckboxDefaults.colors(checkedColor = selectedBlue))
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
        }, confirmButton = { TextButton(onClick = {
            viewModel.saveConfiguration(draft, sourceOrder, addressOrders)
            onDismiss()
        }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private data class HotConfigRow(
    val sourceUrl: String, val selection: HotSelection? = null, val status: Boolean = false
) {
    val key: String = org.json.JSONArray().put(sourceUrl)
        .put(if (status) "status" else if (selection == null) "source" else "address")
        .put(selection?.url.orEmpty()).toString()
}
