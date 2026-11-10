package com.lanyue.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanyue.reader.data.db.entity.SearchHistoryEntity
import com.lanyue.reader.source.SourceRegistry
import com.lanyue.reader.source.SourceRouter
import com.lanyue.reader.source.aggregate.SearchAggregator
import com.lanyue.reader.source.aggregate.SearchAggregator.AggregateResult
import com.lanyue.reader.source.aggregate.SearchAggregator.BookCluster
import com.lanyue.reader.source.aggregate.SearchAggregator.SourcePage
import com.lanyue.reader.source.aggregate.SearchAggregator.SourceStatus
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.model.SubscriptionItem
import com.lanyue.reader.source.model.VideoItem
import com.lanyue.reader.source.book.LegadoBookSource
import com.lanyue.reader.source.rss.LegadoRssSource
import com.lanyue.reader.source.video.CmsVideoSource
import com.lanyue.reader.source.video.TvBoxParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §3.8 独立搜索页（book / rss / video 三路并发）。
 */
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenBook: (SearchBookItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = remember { RuntimeGraph.get(context) }
    val router = remember { SourceRouter(g.metrics) }
    val registry = remember {
        SourceRegistry(
            bookFactory = { e -> LegadoBookSource(e, g.net, g.rules, g.metrics) },
            rssFactory = { e -> LegadoRssSource(e, g.net, g.rules, g.sortParser, g.rssFallback, g.metrics) },
            videoFactory = { e ->
                CmsVideoSource(e, g.net, g.metrics, g.videoParses, emptyList())
            },
            danmakuFactory = { null }
        )
    }

    var type by remember { mutableStateOf(SearchType.BOOK) }
    var keyword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val rows = remember { mutableStateListOf<SearchRow>() }
    var summary by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }

    val loadHistory = {
        scope.launch(Dispatchers.IO) {
            val dao = g.db.searchHistoryDao()
            val recent = (dao.recent("BOOK", 30) + dao.recent("SUBSCRIPTION", 30) + dao.recent("VIDEO", 30))
                .sortedByDescending { it.searchedAt }.take(50)
            history.clear()
            recent.forEach { history.add(it.keyword) }
        }
    }

    LaunchedEffect(type) { loadHistory() }

        fun doSearch(key: String) {
            if (key.isBlank() || busy) return
            busy = true
            rows.clear(); summary = ""; hint = ""
            scope.launch {
                try {
                    val t = type
                    val traceId = "s-${System.currentTimeMillis()}"
                    when (t) {
                        SearchType.BOOK -> {
                            val entities = g.db.bookSourceDao().searchable()
                        val sources = entities.mapNotNull { registry.book(it) }
                        val items = router.searchBooks(sources, key, 1, traceId)
                        val pages = sources.map { s ->
                            SourcePage(
                                origin = s.sourceId,
                                sourceName = s.sourceId,
                                status = SourceStatus.OK,
                                items = items.filter { it.origin == s.sourceId },
                                order = entities.indexOfFirst { e -> registry.book(e)?.sourceId == s.sourceId }
                            )
                        }
                        val agg: AggregateResult = SearchAggregator.aggregate(pages, key)
                        rows.clear()
                        agg.clusters.forEach { c ->
                            rows.add(
                                SearchRow(
                                    title = c.primary.name,
                                    sub = c.primary.author ?: "未知作者",
                                    origin = c.primary.originName ?: "书源",
                                    badge = "书",
                                    bookUrl = c.primary.bookUrl,
                                    originId = c.primary.origin
                                )
                            )
                        }
                        summary = agg.summary()
                    }
                    SearchType.RSS -> {
                        val entities = g.db.rssSourceDao().searchable()
                        val sources = entities.mapNotNull { registry.rss(it) }
                        val items: List<SubscriptionItem> =
                            router.searchSubscriptions(sources, key, 1, traceId)
                        rows.clear()
                        items.forEach {
                            rows.add(
                                SearchRow(
                                    title = it.title,
                                    sub = it.sort ?: "订阅",
                                    origin = "订阅",
                                    badge = "订阅"
                                )
                            )
                        }
                        summary = "${items.size} 个订阅结果"
                    }
                    SearchType.VIDEO -> {
                        val entities = g.db.videoDao().searchableSites()
                        val sources = entities.mapNotNull { registry.video(it) }
                        val items: List<VideoItem> =
                            router.searchVideos(sources, key, 1, traceId)
                        rows.clear()
                        items.forEach {
                            rows.add(
                                SearchRow(
                                    title = it.name,
                                    sub = it.remark ?: "影视",
                                    origin = "TVbox",
                                    badge = "TV"
                                )
                            )
                        }
                        summary = "${items.size} 个影视结果"
                    }
                }
                withContext(Dispatchers.IO) {
                    g.db.searchHistoryDao().insert(
                        SearchHistoryEntity(keyword = key, sourceType = t.dbType)
                    )
                    loadHistory()
                }
            } catch (e: Exception) {
                hint = "搜索失败：${e.message}"
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索全网 / 订阅 / 影视") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SearchType.entries.forEach { t ->
                FilterChip(
                    selected = type == t,
                    onClick = {
                        type = t
                        rows.clear()
                    },
                    label = { Text(t.label) }
                )
            }
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) { g.db.searchHistoryDao().clear(type.dbType) }
                loadHistory()
            }) { Text("清空历史") }
        }
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                history.take(8).forEach { h ->
                    FilterChip(selected = false, onClick = { keyword = h; doSearch(h) }, label = { Text(h) })
                }
            }
        }
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(hint.ifEmpty { "无结果" })
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.title + it.sub }) { row ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                                    .clickable {
                                        if (type == SearchType.BOOK && row.bookUrl.isNotEmpty()) {
                                            onOpenBook(
                                                SearchBookItem(
                                                    name = row.title,
                                                    bookUrl = row.bookUrl,
                                                    origin = row.originId,
                                                    originName = row.origin
                                                )
                                            )
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(row.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text(row.sub, fontSize = 13.sp, color = Color.Gray)
                                Spacer(Modifier.width(8.dp))
                                Text(row.badge, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(row.origin, fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
        if (summary.isNotEmpty()) {
            Text(
                summary,
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}

private enum class SearchType(val label: String, val dbType: String) {
    BOOK("书", "BOOK"),
    RSS("订阅", "SUBSCRIPTION"),
    VIDEO("影视", "VIDEO"),
}

private data class SearchRow(
    val title: String,
    val sub: String,
    val origin: String,
    val badge: String,
    val bookUrl: String = "",
    val originId: String = ""
)
