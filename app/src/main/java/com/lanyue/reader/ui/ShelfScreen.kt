package com.lanyue.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanyue.reader.bookshelf.ShelfOps
import com.lanyue.reader.bookshelf.ShelfOps.ShelfBook

/** §3.1 分组：全部 / 小说 / 音频 / 追剧 / 订阅；「下载管理」为独立入口。 */
enum class ShelfGroup(val label: String) {
    ALL("全部"),
    NOVEL("小说"),
    AUDIO("音频"),
    SERIAL("追剧"),
    SUBS("订阅"),
}

@Composable
internal fun ShelfScreen(
    onOpenBook: (ShelfBook) -> Unit,
    onOpenDownloads: () -> Unit
) {
    val context = LocalContext.current
    val ops = remember { RuntimeGraph.get(context).shelfOps }

    var group by remember { mutableStateOf(ShelfGroup.ALL) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ShelfOps.SortMode.MANUAL) }
    var grid by remember { mutableStateOf(true) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    val shown = remember(ops, group, query, sort) {
        ops.shelf(sort).filter { it.matchesGroup(group) && it.matchesQuery(query) }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部：导航标题 / 搜索 / 排序
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("书架", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(0.3f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(0.6f),
                label = { Text("搜索书架") },
                singleLine = true
            )
            IconButton(onClick = { sort = nextSort(sort) }) {
                Text("⇅", fontSize = 20.sp)
            }
        }

        // 分组 chips
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF5F5F5))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ShelfGroup.entries.forEach { g ->
                FilterChip(
                    selected = group == g,
                    onClick = { group = g },
                    label = { Text(g.label) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .height(28.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onOpenDownloads) { Text("下载管理") }
        }

        // 视图与排序说明条
        val viewLabel = if (grid) "网格 2 列" else "列表"
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$viewLabel · ${sortLabel(sort)}")
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { grid = !grid }) { Text("切换视图") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { sort = nextSort(sort) }) { Text("排序") }
        }

        // 多选操作条
        if (selected.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已选 ${selected.size} 项", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    selected.forEach { ops.remove(it) }
                    selected.clear()
                    selecting = false
                }) { Text("移出") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { selected.clear(); selecting = false }) { Text("取消") }
            }
        }

        Box(Modifier.fillMaxSize().weight(1f)) {
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("书架还没有内容，在书源 Tab 搜索书籍并加入书架")
                }
            } else if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.bookUrl }) { book ->
                        ShelfGridCard(
                            book = book,
                            selected = book.bookUrl in selected,
                            onTap = {
                                if (selecting) {
                                    val i = selected.indexOf(book.bookUrl)
                                    if (i >= 0) selected.removeAt(i) else selected.add(book.bookUrl)
                                } else {
                                    onOpenBook(book)
                                }
                            },
                            onLongHold = {
                                selecting = true
                                selected.add(book.bookUrl)
                            }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                ) {
                    items(shown, key = { it.bookUrl }) { book ->
                        ShelfRow(
                            book = book,
                            selected = book.bookUrl in selected,
                            onTap = {
                                if (selecting) {
                                    val i = selected.indexOf(book.bookUrl)
                                    if (i >= 0) selected.removeAt(i) else selected.add(book.bookUrl)
                                } else {
                                    onOpenBook(book)
                                }
                            },
                            onLongHold = {
                                selecting = true
                                selected.add(book.bookUrl)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ShelfGridCard(
    book: ShelfBook,
    selected: Boolean,
    onTap: () -> Unit,
    onLongHold: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            ).padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.height(40.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFEEEEEE)),
                contentAlignment = Alignment.Center
            ) {
                Text(typeLabel(book.sourceType), fontSize = 11.sp, color = Color(0xFF999999))
            }
            Text(book.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("最新：${book.latestChapter ?: "—"}", maxLines = 1, fontSize = 12.sp)
            Text(book.originName ?: book.origin, maxLines = 1, fontSize = 12.sp)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ShelfRow(
    book: ShelfBook,
    selected: Boolean,
    onTap: () -> Unit,
    onLongHold: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            ).height(48.dp)
    ) {
        Text(book.name, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth(0.5f), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text("${typeLabel(book.sourceType)} · ${book.totalChapter} 章", fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text("最新：${book.originName ?: book.origin}", fontSize = 12.sp, maxLines = 1)
    }
}

private fun typeLabel(t: Int): String =
    if (t == 0) "小说" else if (t == 1) "音频" else if (t == 2) "追剧" else "订阅"

internal fun sortLabel(mode: ShelfOps.SortMode): String = when (mode) {
    ShelfOps.SortMode.MANUAL -> "手动"
    ShelfOps.SortMode.RECENT -> "最近阅读"
    ShelfOps.SortMode.NAME -> "书名"
}

private fun nextSort(mode: ShelfOps.SortMode): ShelfOps.SortMode = when (mode) {
    ShelfOps.SortMode.MANUAL -> ShelfOps.SortMode.RECENT
    ShelfOps.SortMode.RECENT -> ShelfOps.SortMode.NAME
    ShelfOps.SortMode.NAME -> ShelfOps.SortMode.MANUAL
}

private fun ShelfBook.matchesGroup(g: ShelfGroup): Boolean = when (g) {
    ShelfGroup.ALL -> true
    ShelfGroup.NOVEL -> sourceType == 0
    ShelfGroup.AUDIO -> sourceType == 1
    ShelfGroup.SERIAL -> sourceType == 2
    ShelfGroup.SUBS -> sourceType == 3
}

private fun ShelfBook.matchesQuery(q: String): Boolean =
    q.isBlank() || (name + (author ?: "")).contains(q, ignoreCase = true)
