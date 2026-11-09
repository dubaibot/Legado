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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.lanyue.reader.core.engine.Sniffer
import com.lanyue.reader.danmaku.DanmakuMatchEngine
import com.lanyue.reader.danmaku.render.DanmakuCanvasLayer
import com.lanyue.reader.player.PlayerManager
import com.lanyue.reader.source.model.Category
import com.lanyue.reader.source.model.DanmakuEvent
import com.lanyue.reader.source.model.DanmakuMatchRequest
import com.lanyue.reader.source.model.IVideoSource
import com.lanyue.reader.source.model.PlayKind
import com.lanyue.reader.source.model.PlayResult
import com.lanyue.reader.source.model.VideoDetail
import com.lanyue.reader.source.model.VideoEpisode
import com.lanyue.reader.source.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 影视链路 UI：§3.4 发现（分类 → 海报网格）/ §3.5 详情（线路 + 选集）/ §3.7 播放。
 * 数据解析复用 CmsVideoSource，装载由 PlayerManager 承载。
 */

// ---------- §3.4 影视发现 ----------

@Composable
internal fun VideoExploreScreen(
    source: IVideoSource,
    onBack: () -> Unit,
    onOpenItem: (VideoItem) -> Unit
) {
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedCat by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        busy = true; err = null
        val r = withContext(Dispatchers.IO) {
            runCatching { source.getCategories() }.getOrNull()
        }
        cats = r.orEmpty()
        busy = false
        if (r.isNullOrEmpty()) err = "无可发现的分类"
    }

    LaunchedEffect(selectedCat, cats) {
        val target = selectedCat.orEmpty()
            .let { id -> cats.firstOrNull { it.id == id } }
            ?: cats.firstOrNull()
            ?: return@LaunchedEffect
        busy = true; err = null
        val r = withContext(Dispatchers.IO) {
            runCatching { source.explore(target, null, 1) }.getOrNull()
        }
        items = r.orEmpty()
        if (r.isNullOrEmpty()) err = "该分类暂无内容"
        busy = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(12.dp))
            Text(source.sourceId, style = TEXT_STYLE_DIM, modifier = Modifier.weight(1f))
        }
        if (!busy) {
            err?.let {
                Text(it, style = TEXT_STYLE_BAD, modifier = Modifier.padding(top = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                cats.forEach { cat ->
                    FilterChip(
                        selected = selectedCat == cat.id,
                        onClick = { selectedCat = cat.id },
                        label = { Text(cat.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items) { it ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenItem(it) }
                    ) {
                        Column(Modifier.fillMaxSize().padding(8.dp)) {
                            Text(
                                it.name,
                                style = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Medium),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(it.remark.orEmpty(), style = TEXT_STYLE_DIM)
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

// ---------- §3.5 影视详情 ----------

@Composable
internal fun VideoDetailScreen(
    source: IVideoSource,
    item: VideoItem,
    onBack: () -> Unit,
    onPlay: (IVideoSource, VideoDetail, VideoEpisode) -> Unit
) {
    var detail by remember { mutableStateOf<VideoDetail?>(null) }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        busy = true; err = null
        val d = withContext(Dispatchers.IO) {
            runCatching { source.getDetail(item.url) }.getOrNull()
        }
        detail = d; busy = false
        if (d == null) err = "详情解析失败"
    }

    val d = detail
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(12.dp))
            Text(d?.name ?: item.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        if (busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            err?.let { Text(it, style = TEXT_STYLE_BAD) }
            d?.let { det ->
                Text(det.actor.orEmpty(), style = TEXT_STYLE_DIM)
                Text(det.remark.orEmpty(), style = TEXT_STYLE_DIM)
                Spacer(Modifier.height(12.dp))
                if (det.lines.isEmpty()) {
                    Text("暂无选集", style = TEXT_STYLE_DIM)
                } else {
                    for (idx in det.lines.indices) {
                        val line = det.lines[idx]
                        if (idx > 0) Spacer(Modifier.height(8.dp))
                        Text("线路 ${line.name}", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            line.episodes.forEach { ep ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onPlay(source, det, ep) },
                                    label = {
                                        Text(
                                            ep.name,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- §3.7 影视播放页 ----------

private sealed interface DanmakuUiState {
    data object Idle : DanmakuUiState
    data object Loading : DanmakuUiState
    data object Ready : DanmakuUiState
    data object NotMatched : DanmakuUiState
    data object Failed : DanmakuUiState
}

/** 从剧集名提取集数：第N集 / N话 / N / N集。 */
private fun parseEpisodeNumber(name: String): Int? {
    val m = Regex("""第\s*(\d+)\s*[集话]""").find(name)
    if (m != null) return m.groupValues[1].toIntOrNull()
    val m2 = Regex("""(\d+)\s*[集话]""").find(name)
    if (m2 != null) return m2.groupValues[1].toIntOrNull()
    return name.trim().toIntOrNull()
}

@Composable
internal fun PlayerScreen(
    source: IVideoSource,
    detail: VideoDetail,
    episode: VideoEpisode,
    playResult: PlayResult,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { PlayerManager(context, RuntimeGraph.get(context).metrics) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var subtitleUrl by remember { mutableStateOf("") }
    var subtitleLoaded by remember { mutableStateOf(false) }
    var currentPlayUrl by remember { mutableStateOf("") }

    // 弹幕（§4.10.3 / §4.10.4）
    var danmakuEvents by remember { mutableStateOf<List<DanmakuEvent>>(emptyList()) }
    var danmakuState by remember { mutableStateOf<DanmakuUiState>(DanmakuUiState.Idle) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isDanmakuPlaying by remember { mutableStateOf(true) }
    /** 当前匹配结果，用于跨窗口预取。 */
    var matched by remember { mutableStateOf<DanmakuMatchEngine.Outcome.Matched?>(null) }

    // ExoPlayer 位置 / 播放态轮询给弹幕层（media3 1.4.1 无连续 onPositionChanged）
    LaunchedEffect(manager) {
        while (true) {
            val exo = manager.player
            if (exo != null) {
                positionMs = exo.currentPosition
                isDanmakuPlaying = exo.isPlaying
            }
            delay(500L)
        }
    }

    LaunchedEffect(Unit) {
        manager.ensurePlayer()

        // §4.8 / §7.3：SNIFF 走嗅探渲染池，DIRECT/PARSE 走既有解析链
        var startUrl: String? = null
        var startHeaders: Map<String, String> = emptyMap()

        if (playResult.kind == PlayKind.SNIFF) {
            val hits = runCatching {
                RuntimeGraph.get(context).sniffer.sniff(
                    pageUrl = playResult.url,
                    priority = Sniffer.Priority.USER_PLAYBACK,
                    domainAllowed = { true }
                )
            }.getOrDefault(emptyList())
            val hit = hits.firstOrNull()
            if (hit == null) {
                resolveError = "嗅探未捕获媒体 URL，请切换线路或稍后重试"
                return@LaunchedEffect
            }
            startUrl = hit.url
            startHeaders = hit.headers ?: emptyMap()
        } else {
            val (url, err) = resolvePlayUrl(playResult, context)
            if (url == null) { resolveError = err; return@LaunchedEffect }
            startUrl = url
            startHeaders = playResult.headers ?: emptyMap()
        }

        currentPlayUrl = startUrl
        manager.load(startUrl, startHeaders)

        // 弹幕自动匹配 + 首屏 60s 窗口（§4.10.4）
        danmakuEvents = emptyList()
        matched = null
        danmakuState = DanmakuUiState.Loading
        val req = DanmakuMatchRequest(
            title = detail.name,
            episode = parseEpisodeNumber(episode.name)
        )
        val vkey = RuntimeGraph.get(context).danmakuEngine.videoKeyFor(req, source.sourceId, episode.url)
        val outcome = runCatching {
            RuntimeGraph.get(context).danmakuEngine.autoMatch(req, vkey)
        }.getOrDefault(DanmakuMatchEngine.Outcome.NotMatched(vkey))

        when (outcome) {
            is DanmakuMatchEngine.Outcome.Matched -> {
                matched = outcome
                val src = RuntimeGraph.get(context).danmakuEngine.sourceFor(outcome.source)
                val evs = if (src != null) {
                    RuntimeGraph.get(context).danmakuEngine.fetchComments(src, outcome.match, 0L, outcome.match.shift)
                } else {
                    emptyList()
                }
                danmakuEvents = evs
                danmakuState = if (evs.isEmpty()) DanmakuUiState.Failed else DanmakuUiState.Ready
            }
            else -> { danmakuState = DanmakuUiState.NotMatched }
        }
    }

    // §4.10.4 分段预取：positionMs 跳过 COMMENT_WINDOW_MS 边界就拉取下一窗口
    val currentWindow = (positionMs / DanmakuMatchEngine.COMMENT_WINDOW_MS).toInt()
    LaunchedEffect(matched, currentWindow) {
        val m = matched ?: return@LaunchedEffect
        if (currentWindow < 1) return@LaunchedEffect
        val net = RuntimeGraph.get(context)
        val src = net.danmakuEngine.sourceFor(m.source) ?: return@LaunchedEffect
        val fromMs = currentWindow * DanmakuMatchEngine.COMMENT_WINDOW_MS
        // 避免重复拉取（final events already include window up to fromMs + COMMENT_WINDOW_MS）
        if (danmakuEvents.any { it.timeMs >= fromMs }) return@LaunchedEffect
        val evs = net.danmakuEngine.fetchComments(src, m.match, fromMs, DanmakuMatchEngine.COMMENT_WINDOW_MS)
        danmakuEvents = danmakuEvents + evs
    }

    fun loadSubtitle() {
        val sub = subtitleUrl.trim()
        if (sub.isEmpty() || currentPlayUrl.isEmpty()) return
        val pos = manager.player?.currentPosition ?: 0L
        manager.addSubtitle(sub, media3Mime(sub))
        manager.load(currentPlayUrl, playResult.headers ?: emptyMap(), startPositionMs = pos)
        subtitleLoaded = true
    }

    fun removeSubtitle() {
        if (currentPlayUrl.isEmpty()) return
        val pos = manager.player?.currentPosition ?: 0L
        manager.clearSubtitles()
        manager.load(currentPlayUrl, playResult.headers ?: emptyMap(), startPositionMs = pos)
        subtitleLoaded = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            Text("${detail.name} · ${episode.name}", modifier = Modifier.weight(1f))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (resolveError.isNullOrEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { ctx ->
                        PlayerView(ctx).also { pv ->
                            manager.player?.let { pv.setPlayer(it) }
                        }
                    })
                    if (danmakuEvents.isNotEmpty()) {
                        DanmakuCanvasLayer(
                            events = danmakuEvents,
                            positionMs = positionMs,
                            isPlaying = isDanmakuPlaying
                        )
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(resolveError ?: "", style = TEXT_STYLE_BAD)
                    Spacer(Modifier.height(8.dp))
                    Text("可尝试切换线路或稍后重试")
                }
            }
        }
        if (danmakuState is DanmakuUiState.NotMatched || danmakuState is DanmakuUiState.Failed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    when (danmakuState) {
                        DanmakuUiState.NotMatched -> "未匹配到弹幕"
                        DanmakuUiState.Failed -> "弹幕加载失败"
                        else -> ""
                    },
                    style = TEXT_STYLE_DIM
                )
            }
        }
        if (resolveError.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = subtitleUrl,
                    onValueChange = { subtitleUrl = it },
                    label = { Text("字幕 URL (.srt/.vtt/.ass)") },
                    modifier = Modifier.weight(1f),
                    enabled = !subtitleLoaded,
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { loadSubtitle() }) { Text("装载") }
                Spacer(Modifier.width(4.dp))
                if (subtitleLoaded) {
                    OutlinedButton(onClick = { removeSubtitle() }) { Text("移除") }
                }
            }
        }
        DisposableEffect(Unit) { onDispose { manager.release() } }
    }
}

fun media3Mime(url: String): String = when {
    url.lowercase().endsWith(".vtt") -> "text/vtt"
    url.lowercase().endsWith(".srt") -> "text/x-srt"
    url.lowercase().endsWith(".ass") || url.lowercase().endsWith(".ssa") -> "text/x-ssa"
    else -> "text/x-ssa"
}

private fun resolvePlayUrl(
    pr: PlayResult,
    context: android.content.Context
): Pair<String?, String?> {
    return when (pr.kind) {
        PlayKind.DIRECT -> Pair(pr.url, null)
        PlayKind.PARSE -> {
            val resolved = runCatching {
                RuntimeGraph.get(context).localParseProxy.createPlayUrl(pr.url)
            }.getOrNull() ?: pr.url
            Pair(resolved, null)
        }
        PlayKind.SNIFF -> Pair(null, "嗅探需要渲染池（后续轮次），请切换线路或启用增强解析")
    }
}
