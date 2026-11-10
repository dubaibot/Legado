package com.lanyue.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanyue.reader.R
import com.lanyue.reader.source.import.SourceImportParser.ImportedBookSource
import com.lanyue.reader.source.model.SubscriptionItem

/**
 * §3.3 订阅发现页：与书源发现页共用导入结果，点击进入订阅浏览。
 * 数据仅来自本次导入的进程内列表（导入态演示）；订阅持久化由
 * 后续 DAO 装配轮次替换。
 */
private sealed interface SubsRoute {
    data object List : SubsRoute
    data class Rss(val source: ImportedBookSource) : SubsRoute
    data class Article(
        val source: ImportedBookSource,
        val item: SubscriptionItem,
        val referrer: String
    ) : SubsRoute
}

@Composable
internal fun SubsScreen(
    sources: List<ImportedBookSource>,
    onOpenCheck: () -> Unit = {}
) {
    var route by remember { mutableStateOf<SubsRoute>(SubsRoute.List) }

    when (val r = route) {
        is SubsRoute.List -> {
            val rss = remember(sources) { sources.filter { it.fields().isRss } }
            Column(Modifier.fillMaxSize()) {
                Text("订阅", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                if (rss.isEmpty()) {
                    Text(
                        "还没有订阅源，在「书源」Tab 导入订阅列表后自动出现",
                        style = TEXT_STYLE_DIM,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rss, key = { it.url }) { src ->
                            SubsRow(source = src) { route = SubsRoute.Rss(src) }
                        }
                    }
                }
            }
        }

        is SubsRoute.Rss -> RssBrowseScreen(
            source = r.source,
            onBack = { route = SubsRoute.List },
            onCheck = onOpenCheck,
            onOpenItem = { item, referrer -> route = SubsRoute.Article(r.source, item, referrer) }
        )

        is SubsRoute.Article -> ArticleScreen(
            source = r.source,
            item = r.item,
            referrerUrl = r.referrer,
            onBack = { route = SubsRoute.List }
        )
    }
}

@Composable
private fun SubsRow(source: ImportedBookSource, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(source.fields().name ?: source.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    source.url,
                    style = TEXT_STYLE_DIM,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onOpen) { Text("浏览") }
        }
    }
}
