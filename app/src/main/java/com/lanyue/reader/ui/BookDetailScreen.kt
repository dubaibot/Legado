package com.lanyue.reader.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanyue.reader.bookshelf.ShelfOps.AddOutcome
import com.lanyue.reader.bookshelf.ShelfOps.ShelfBook
import com.lanyue.reader.source.book.LegadoBookSource
import com.lanyue.reader.source.model.BookDetail
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.SourceRegistry
import com.lanyue.reader.source.model.TocItem
import kotlinx.coroutines.launch

/**
 * §3.5 统一详情页（书路最小闭环）：
 * 信息区 + 加入书架 / 开始阅读按钮 + 目录折叠列表；
 * 订阅、影视详情页随类型轮次接入。
 */
@Composable
fun BookDetailScreen(
    book: SearchBookItem,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = RuntimeGraph.get(context)
    val registry = remember {
        SourceRegistry(
            bookFactory = { e -> LegadoBookSource(e, g.net, g.rules, g.metrics) },
            rssFactory = { null },
            videoFactory = { null },
            danmakuFactory = { null }
        )
    }

    var detail by remember { mutableStateOf(BookDetail(name = book.name, bookUrl = book.bookUrl)) }
    val toc = remember { mutableStateListOf<TocItem>() }
    var introExpanded by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var loadedBy by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            val entity = g.db.bookSourceDao().all().firstOrNull { it.bookSourceUrl == book.origin }
            val src = entity?.let { registry.book(it) }
            if (src == null) {
                toast = "未找到该书源（来源 ${book.originName ?: book.origin}），或已被停用"
                loadedBy = true
                return@launch
            }
            try {
                detail = src.getDetail(book.bookUrl)
                toc.clear()
                toc.addAll(src.getToc(book.bookUrl))
            } catch (e: Exception) {
                toast = "详情加载失败：" + friendlyText(e)
            }
            loadedBy = true
        }
    }
    LaunchedEffect(book.bookUrl) { load() }

    val inBookshelf = g.shelfOps.find(book.bookUrl) != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("书详情（§3.5.1）", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(0.6f))
        }

        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(88.dp).height(116.dp), contentAlignment = Alignment.Center) {
                        Text("封面", color = Color.Gray)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(detail.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("作者：${detail.author ?: book.author ?: "未知"}", style = DIM_TEXT)
                        Text("来源：${book.originName ?: book.origin}", style = DIM_TEXT)
                        (detail.lastChapter ?: book.latestChapter)?.let {
                            Text("最新：$it", style = DIM_TEXT)
                        }
                        detail.wordCount?.let { Text("字数：$it", style = DIM_TEXT) }
                    }
                }
                detail.intro?.takeIf { it.isNotBlank() }?.let { raw ->
                    Text(
                        if (introExpanded) raw else raw.take(48),
                        fontSize = 13.sp,
                        maxLines = if (introExpanded) 12 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { introExpanded = !introExpanded }) {
                        Text(if (introExpanded) "收起简介" else "展开简介")
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (inBookshelf) {
                        toast = "该书已在书架中"
                    } else {
                        val report = g.shelfOps.add(
                            ShelfBook(
                                bookUrl = book.bookUrl,
                                name = detail.name,
                                author = detail.author ?: book.author,
                                cover = detail.cover ?: book.cover,
                                intro = detail.intro ?: book.intro,
                                origin = book.origin,
                                originName = book.originName ?: book.origin,
                                latestChapter = book.latestChapter
                            )
                        )
                        toast = when (report.outcome) {
                            AddOutcome.ADDED -> "已加入书架"
                            AddOutcome.UPDATED -> "已在书架中，已更新元数据"
                            AddOutcome.RESTORED -> "已从移除区重新加入书架"
                        }
                    }
                }
            ) { Text(if (inBookshelf) "已在书架" else "加入书架") }
            OutlinedButton(onClick = { toast = "阅读器页面（§3.6）由后续轮次接入" }) {
                Text("开始阅读")
            }
            OutlinedButton(onClick = { toast = "换源候选在源详情页接入（§1.2 合并策略）" }) {
                Text("换源")
            }
        }

        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("目录 ${toc.size} 章", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { loadedBy = false; load() }) { Text("刷新") }
                }
                if (!loadedBy) {
                    Text("目录加载中…", style = DIM_TEXT)
                } else if (toc.isEmpty()) {
                    Text("该书源未配置 toc 规则，或目录为空", style = DIM_TEXT)
                } else {
                    toc.take(20).forEach { ch ->
                        TextButton(
                            onClick = { toast = "阅读 ${ch.title}（§3.6 阅读器后续轮次接入）" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${(ch.index + 1).toString().padStart(3, '0')} ${ch.title}")
                        }
                    }
                }
                if (toc.size > 20) Text("仅展示前 20 章，阅读页将提供完整目录", style = DIM_TEXT)
            }
        }

        toast?.let { Text(it, modifier = Modifier.padding(12.dp), style = DIM_TEXT) }
    }
}

private val DIM_TEXT = androidx.compose.ui.text.TextStyle(color = Color.Gray, fontSize = 13.sp)

private fun friendlyText(e: Exception): String =
    e.javaClass.simpleName + (e.message?.take(60)?.let { "— $it" } ?: "")
