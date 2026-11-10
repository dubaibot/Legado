package com.lanyue.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.source.book.LegadoBookSource
import com.lanyue.reader.source.import.SourceEntityConverter
import com.lanyue.reader.source.import.SourceImportParser.ImportedBookSource
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.model.SortRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExpMode { Categories, Books }

/**
 * 1.4.4 §3.2 书源发现页。
 *
 * 视图 1「分类网格」（3 列）：「全部分类」卡片 + ruleExplore 解析出的分类卡片。
 * 视图 2「书籍网格」（3 列）：点击分类后展示书籍，点击书进入详情页（onOpenItem）。
 * 分类解析：LegadoBookSource.getSorts()——ruleExplore 中「分类」JSON 或「name::url」静态行；
 * 无分类时直接以 exploreUrl/bookSourceUrl 列表态。
 */
@Composable
internal fun BookExploreScreen(
    source: ImportedBookSource,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onOpenItem: (SearchBookItem, String) -> Unit
) {
    val context = LocalContext.current

    var sorts by remember { mutableStateOf<List<SortRule>>(emptyList()) }
    var mode by remember { mutableStateOf(ExpMode.Categories) }
    var selected by remember { mutableStateOf<SortRule?>(null) }
    var books by remember { mutableStateOf<List<SearchBookItem>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var loadedAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun legado(): Pair<LegadoBookSource, BookSourceEntity> {
        val entity = SourceEntityConverter.toBookEntity(source.raw)
            ?: throw IllegalStateException("该源缺少可运行的地址，无法浏览")
        val g = RuntimeGraph.get(context)
        return LegadoBookSource(entity, g.net, g.rules, g.metrics) to entity
    }

    fun loadPage(p: Int) {
        busy = true
        error = null
        scope.launch {
            try {
                val (src, entity) = legado()
                page = p
                val sort = selected ?: SortRule("全部分类", entity.exploreUrl ?: entity.bookSourceUrl)
                val list = withContext(Dispatchers.IO) { src.explore(sort, p) }
                books = if (p == 1) list else books + list
                loadedAll = list.size < 20
                if (list.isEmpty() && p == 1) {
                    error = "没有书籍：站点该分类为空或列表规则不匹配"
                }
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                busy = false
            }
        }
    }

    fun openCategory(s: SortRule?) {
        selected = s
        books = emptyList()
        page = 1
        error = null
        mode = ExpMode.Books
        loadPage(1)
    }

    LaunchedEffect(Unit) {
        try {
            val (src, _) = legado()
            sorts = withContext(Dispatchers.IO) { src.getSorts() }
        } catch (e: Exception) {
            error = friendlyError(e)
            busy = false
            return@LaunchedEffect
        }
        // 无分类：跳过分类网格，直接列表态
        if (sorts.isEmpty()) {
            mode = ExpMode.Books
        }
        loadPage(1)
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = if (selected != null) { { openCategory(null) } } else onBack) {
                Text(if (selected != null) "← 分类" else "← 返回")
            }
            Spacer(Modifier.width(12.dp))
            Text(source.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCheck) { Text("体检") }
        }

        if (busy && mode == ExpMode.Books && books.isEmpty()) Text("加载书籍…", style = TEXT_STYLE_DIM)
        error?.let { Text(it, style = TEXT_STYLE_BAD) }

        if (mode == ExpMode.Categories) {
            // 视图 1：分类 3 列网格（「全部分类」悬顶 + ruleExplore 分类）
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sorts.toList()) { s ->
                    CategoryCard(s.name, s.url) { openCategory(s) }
                }
            }
            // 「全部分类」入口放在网格上方按钮区（3 列第一张卡不占用分类列表）
            OutlinedButton(onClick = { openCategory(null) }) { Text("全部分类 →") }
        } else {
            // 视图 2：书籍 3 列网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(books, key = { it.bookUrl }) { b ->
                    BookCard(b, onClick = { onOpenItem(b, source.url) })
                }
            }
            if (!loadedAll) {
                OutlinedButton(onClick = { loadPage(page.inc()) }) { Text("加载更多") }
            }
        }
    }
}

@Composable
private fun CategoryCard(name: String, sub: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, style = TEXT_STYLE_DIM, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BookCard(book: SearchBookItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(book.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.let { Text(it, style = TEXT_STYLE_DIM, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            book.latestChapter?.let { Text(it, style = TEXT_STYLE_DIM, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
