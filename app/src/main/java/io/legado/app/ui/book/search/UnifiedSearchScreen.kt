package io.legado.app.ui.book.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.pageTopBarBackground
import io.legado.app.ui.theme.pageTopBarColors
import io.legado.app.ui.widget.components.AppBookCover

@Composable
fun SearchTypeBar(
    type: SearchContentType,
    scopeName: String,
    onType: (SearchContentType) -> Unit,
    onSource: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchContentType.entries.forEach { item ->
                FilterChip(selected = type == item, onClick = { onType(item) }, label = { Text(item.title) })
            }
        }
        TextButton(onClick = onSource, modifier = Modifier.fillMaxWidth()) {
            Text("搜索范围：$scopeName · 按源选择", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SearchSourceDialog(
    type: SearchContentType,
    sources: List<UnifiedSearchSource>,
    selectedKey: String,
    loading: Boolean,
    errors: Map<String, String>,
    onSelect: (UnifiedSearchSource?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择${type.title}搜索源") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            item {
                TextButton(onClick = { onSelect(null) }) {
                    Text(if (selectedKey.isEmpty()) "全部启用源（已选）" else "全部启用源")
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!loading && sources.isEmpty()) item { Text("暂无已启用且支持搜索的源") }
            items(sources, key = { it.key }) { source ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(source) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selectedKey == source.key, onClick = null)
                    Text(source.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            items(errors.entries.toList(), key = { "error:${it.key}" }) {
                Text(it.value, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
fun UnifiedSearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    model: UnifiedSearchViewModel,
    onSearch: () -> Unit,
    onResult: (UnifiedSearchResult) -> Unit,
    typeBar: @Composable () -> Unit,
) {
    val focus = LocalFocusManager.current
    val submit = { focus.clearFocus(); onSearch() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .navigationBarsPadding().imePadding()) {
        val colors = pageTopBarColors()
        Column(Modifier.pageTopBarBackground(colors).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("搜索${model.type.title}内容") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }))
                Button(onClick = submit) { Text("搜索") }
            }
            typeBar()
        }
        if (model.loadingSources) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${model.results.size} 个结果", Modifier.weight(1f))
            if (model.loading) {
                Text("${model.completed}/${model.total}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::stop) { Text("停止") }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(model.errors.entries.toList(), key = { "error:${it.key}" }) {
                Text(it.value, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            items(model.results, key = { "result:${it.source.key.length}:${it.source.key}:${it.identity}" }) { result ->
                Row(Modifier.fillMaxWidth().clickable { onResult(result) },
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppBookCover(Modifier.width(64.dp).height(88.dp), name = result.title, author = null,
                        coverPath = result.cover, galleryIdentity = result.source.key + result.identity,
                        contentDescription = result.title,
                        sourceOrigin = result.article?.origin)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(result.source.name, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        if (result.description.isNotBlank()) Text(result.description,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                Text(when {
                    model.loading -> "正在搜索…"
                    model.stopped -> "搜索已停止"
                    !model.submitted -> "输入关键词，选择搜索类型和源"
                    model.results.isEmpty() -> "暂无搜索结果"
                    else -> "已完成首屏搜索"
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
