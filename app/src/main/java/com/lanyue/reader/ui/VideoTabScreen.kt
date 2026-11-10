package com.lanyue.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanyue.reader.source.model.VideoDetail
import com.lanyue.reader.source.model.VideoEpisode
import com.lanyue.reader.source.model.VideoItem
import com.lanyue.reader.source.model.PlayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface VideoTabRoute {
    data object List : VideoTabRoute
    data class Explore(val row: VideoSiteRow) : VideoTabRoute
    data class Detail(val row: VideoSiteRow, val item: VideoItem) : VideoTabRoute
    data class Player(
        val row: VideoSiteRow,
        val detail: VideoDetail,
        val episode: VideoEpisode,
        val playResult: PlayResult
    ) : VideoTabRoute
}

@Composable
internal fun VideoTabScreen(sites: List<VideoSiteRow>) {
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<VideoTabRoute>(VideoTabRoute.List) }

    when (val r = route) {
        is VideoTabRoute.List -> {
            Column(Modifier.fillMaxSize()) {
                Text("影视", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                if (sites.isEmpty()) {
                    Text(
                        "还没有影视源，在「书源」Tab 导入 TVBox 配置后自动出现",
                        style = TEXT_STYLE_DIM,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(sites, key = { it.key }) { row ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(row.label, fontWeight = FontWeight.Medium, maxLines = 1)
                                        Text(
                                            row.site.api ?: row.site.configUrl,
                                            style = TEXT_STYLE_DIM,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    TextButton(onClick = { route = VideoTabRoute.Explore(row) }) { Text("浏览") }
                                }
                            }
                        }
                    }
                }
            }
        }

        is VideoTabRoute.Explore -> VideoExploreScreen(
            source = r.row.source,
            onBack = { route = VideoTabRoute.List },
            onOpenItem = { item -> route = VideoTabRoute.Detail(r.row, item) }
        )

        is VideoTabRoute.Detail -> VideoDetailScreen(
            source = r.row.source,
            item = r.item,
            onBack = { route = VideoTabRoute.Explore(r.row) },
            onPlay = { src, detail, episode ->
                scope.launch {
                    val pr = withContext(Dispatchers.IO) {
                        runCatching { src.getPlay(episode) }.getOrNull()
                    } ?: return@launch
                    route = VideoTabRoute.Player(r.row, detail, episode, pr)
                }
            }
        )

        is VideoTabRoute.Player -> PlayerScreen(
            source = r.row.source,
            detail = r.detail,
            episode = r.episode,
            playResult = r.playResult,
            onBack = { route = VideoTabRoute.List }
        )
    }
}
