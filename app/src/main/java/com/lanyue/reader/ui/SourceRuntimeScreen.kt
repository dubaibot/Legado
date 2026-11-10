package com.lanyue.reader.ui

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import com.lanyue.reader.player.AudioPlayerScreen
import com.lanyue.reader.source.book.LegadoBookSource
import com.lanyue.reader.source.import.SourceEntityConverter
import com.lanyue.reader.source.import.SourceImportParser.ImportedBookSource
import com.lanyue.reader.source.model.BookDetail
import com.lanyue.reader.source.model.ContentResult
import com.lanyue.reader.source.model.RssContentType
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.model.SortRule
import com.lanyue.reader.source.model.SubscriptionItem
import com.lanyue.reader.source.model.TocItem
import com.lanyue.reader.source.rss.LegadoRssSource
import com.lanyue.reader.source.rule.UrlTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI

/**
 * 书源 / 订阅源的运行链路：
 * 书源：搜索 -> 结果 -> 详情+目录 -> 正文阅读；
 * 订阅源：分类 -> 文章列表 -> 正文（无规则走 WebView 原样打开）。
 * 影视播放（订阅 type=2 嗅探链路）按规格属后续轮次，本轮给出明确提示。
 */

@Composable
internal fun BookSearchScreen(
    source: ImportedBookSource,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onOpenItem: (SearchBookItem, String) -> Unit
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchBookItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var searchBaseUrl by remember { mutableStateOf(source.url) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(12.dp))
            Text(source.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCheck) { Text("体检") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入书名或作者") }
            )
            Button(
                onClick = {
                    val k = key.trim()
                    if (k.isEmpty() || busy) return@Button
                    busy = true
                    error = null
                },
                enabled = !busy
            ) {
                Text(if (busy) "搜索中…" else "搜索")
            }
        }
        error?.let { Text(it, style = TEXT_STYLE_BAD) }
        val list = results
        if (list != null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onOpenItem(item, searchBaseUrl)
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.name, fontWeight = FontWeight.Medium)
                            item.author?.let { Text("作者：$it", style = TEXT_STYLE_DIM) }
                            item.latestChapter?.let {
                                Text(
                                    "最新：$it",
                                    style = TEXT_STYLE_DIM,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 搜索执行：按钮点击只置状态，这里响应 busy 变化真正发起请求
    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect
        val k = key.trim()
        try {
            val entity = SourceEntityConverter.toBookEntity(source.raw)
            if (entity?.searchUrl.isNullOrBlank()) {
                error = "该源没有搜索地址（导入体检里已标出缺失项），无法搜索"
                results = null
            } else {
                val g = RuntimeGraph.get(context)
                val src = LegadoBookSource(
                    entity = entity!!,
                    net = g.net,
                    rules = g.rules,
                    metrics = g.metrics
                )
                searchBaseUrl = UrlTemplate.fill(entity.searchUrl!!, k, 1)
                val r = withContext(Dispatchers.IO) { src.search(k, 1) }
                results = r
                if (r.isEmpty()) error = "没有搜索到内容：列表规则不匹配或站点无结果"
            }
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            busy = false
        }
    }
}

@Composable
internal fun BookDetailScreen(
    source: ImportedBookSource,
    item: SearchBookItem,
    referrerUrl: String,
    onBack: () -> Unit,
    onOpenChapter: (TocItem, List<TocItem>, String) -> Unit,
    onAddToShelf: () -> Unit
) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<BookDetail?>(null) }
    var toc by remember { mutableStateOf<List<TocItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var tocBaseUrl by remember { mutableStateOf(referrerUrl) }

    LaunchedEffect(item.bookUrl) {
        try {
            val entity = SourceEntityConverter.toBookEntity(source.raw)
                ?: throw IllegalStateException("该源缺少可运行的地址，无法打开详情")
            val g = RuntimeGraph.get(context)
            val src = LegadoBookSource(entity = entity, net = g.net, rules = g.rules, metrics = g.metrics)
            val bookUrl = resolveUrl(referrerUrl, item.bookUrl)
            val d = withContext(Dispatchers.IO) { src.getDetail(bookUrl) }
            detail = d
            val target = if (d.bookUrl.isNotBlank()) d.bookUrl else bookUrl
            val t = withContext(Dispatchers.IO) { src.getToc(target) }
            toc = t
            if (t.isEmpty()) error = "目录为空：目录规则不匹配（详情与简介仍可查看）"
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            busy = false
        }
    }

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("← 返回") }
                Spacer(Modifier.width(12.dp))
                Text(item.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onAddToShelf) { Text("加入书架") }
            }
        if (busy) Text("加载详情与目录…", style = TEXT_STYLE_DIM)
        error?.let { Text(it, style = TEXT_STYLE_BAD) }
        detail?.let { d ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (d.name.isNotBlank()) Text(d.name, fontWeight = FontWeight.Medium)
                    d.author?.let { Text("作者：$it", style = TEXT_STYLE_DIM) }
                    d.lastChapter?.let { Text("最新：$it", style = TEXT_STYLE_DIM) }
                    d.intro?.let {
                        Text(
                            it,
                            style = TEXT_STYLE_DIM,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Text("目录（${toc.size} 章）", fontWeight = FontWeight.Bold)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(toc) { chapter ->
                Text(
                    chapter.title,
                    modifier = Modifier.fillMaxWidth().clickable {
                        onOpenChapter(chapter, toc, tocBaseUrl)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun ReaderScreen(
    source: ImportedBookSource,
    chapter: TocItem,
    toc: List<TocItem>,
    referrerUrl: String,
    bookUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var webUrl by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf(chapter) }
    /** §6.1 音频书源结果装载：AudioPlayerScreen 替代正文文本显示。 */
    var audioUrl by remember { mutableStateOf<String?>(null) }
    var audioHeaders by remember { mutableStateOf(emptyMap<String, String>()) }
    var showAudio by remember { mutableStateOf(false) }

    LaunchedEffect(current.url) {
        busy = true
        error = null
        text = null
        webUrl = null
        showAudio = false
        try {
            val entity = SourceEntityConverter.toBookEntity(source.raw)
                ?: throw IllegalStateException("该源缺少可运行的地址，无法读取正文")
            val g = RuntimeGraph.get(context)
            val src = LegadoBookSource(entity = entity, net = g.net, rules = g.rules, metrics = g.metrics)
            val url = resolveUrl(referrerUrl.ifBlank { bookUrl }, current.url)
            when (val r = withContext(Dispatchers.IO) { src.getContent(url) }) {
                is ContentResult.Html -> text = htmlToText(r.html)
                is ContentResult.Audio -> {
                    audioUrl = r.url
                    audioHeaders = r.headers
                    showAudio = true
                    return@LaunchedEffect
                }
                is ContentResult.WebViewFallback -> webUrl = url
            }
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            busy = false
        }
    }

    if (showAudio && audioUrl != null) {
        AudioPlayerScreen(
            title = current.title,
            url = audioUrl!!,
            headers = audioHeaders,
            onBack = { showAudio = false }
        )
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("← 目录") }
                Spacer(Modifier.width(12.dp))
                Text(
                    current.title,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val idx = toc.indexOfFirst { it.url == current.url }
                OutlinedButton(
                    onClick = { if (idx > 0) current = toc[idx - 1] },
                    enabled = idx > 0 && !busy
                ) { Text("上一章") }
                OutlinedButton(
                    onClick = { if (idx in 0 until toc.size - 1) current = toc[idx + 1] },
                    enabled = idx < toc.size - 1 && !busy
                ) { Text("下一章") }
            }
            if (busy) Text("加载正文…", style = TEXT_STYLE_DIM)
            error?.let { Text(it, style = TEXT_STYLE_BAD) }
            webUrl?.let { u -> WebViewPane(u, Modifier.weight(1f)) }
            text?.let { t ->
                LazyColumn(Modifier.weight(1f)) {
                    item { Text(t) }
                }
            }
        }
    }
}

@Composable
internal fun RssBrowseScreen(
    source: ImportedBookSource,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onOpenItem: (SubscriptionItem, String) -> Unit
) {
    val context = LocalContext.current
    var sorts by remember { mutableStateOf<List<SortRule>>(emptyList()) }
    var selected by remember { mutableStateOf<SortRule?>(null) }
    var articles by remember { mutableStateOf<List<SubscriptionItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var listBaseUrl by remember { mutableStateOf(source.url) }

    LaunchedEffect(Unit) {
        try {
            val entity = SourceEntityConverter.toRssEntity(source.raw)
                ?: throw IllegalStateException("该源缺少可运行的地址，无法浏览")
            val g = RuntimeGraph.get(context)
            val src = LegadoRssSource(
                entity = entity,
                net = g.net,
                rules = g.rules,
                sortParser = g.sortParser,
                rssFallback = g.rssFallback,
                metrics = g.metrics
            )
            sorts = withContext(Dispatchers.IO) { src.getSorts() }
        } catch (e: Exception) {
            error = friendlyError(e)
            busy = false
        }
    }

    // 分类变化（含首次的「全部」）触发文章加载
    LaunchedEffect(selected) {
        if (sorts.isEmpty() && selected == null && busy && articles.isEmpty() && error == null) {
            return@LaunchedEffect // 分类还没加载完
        }
        busy = true
        error = null
        try {
            val entity = SourceEntityConverter.toRssEntity(source.raw)
                ?: throw IllegalStateException("该源缺少可运行的地址，无法浏览")
            val g = RuntimeGraph.get(context)
            val src = LegadoRssSource(
                entity = entity,
                net = g.net,
                rules = g.rules,
                sortParser = g.sortParser,
                rssFallback = g.rssFallback,
                metrics = g.metrics
            )
            val list = withContext(Dispatchers.IO) { src.getArticles(selected, 1) }
            articles = list
            listBaseUrl = selected?.url ?: source.url
            if (list.isEmpty()) error = "没有文章：列表规则不匹配或站点无内容"
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(12.dp))
            Text(source.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCheck) { Text("体检") }
        }
        if (sorts.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selected == null,
                        onClick = { selected = null },
                        label = { Text("全部") }
                    )
                }
                items(sorts) { s ->
                    FilterChip(
                        selected = selected == s,
                        onClick = { selected = s },
                        label = { Text(s.name) }
                    )
                }
            }
        }
        if (busy) Text("加载文章…", style = TEXT_STYLE_DIM)
        error?.let { Text(it, style = TEXT_STYLE_BAD) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(articles) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onOpenItem(item, listBaseUrl)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.title, fontWeight = FontWeight.Medium)
                        item.description?.let {
                            Text(
                                it,
                                style = TEXT_STYLE_DIM,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArticleScreen(
    source: ImportedBookSource,
    item: SubscriptionItem,
    referrerUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var webUrl by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    /** §6.1 音频书源结果装载：AudioPlayerScreen 替代正文文本显示。 */
    var audioUrl by remember { mutableStateOf<String?>(null) }
    var audioHeaders by remember { mutableStateOf(emptyMap<String, String>()) }
    var showAudio by remember { mutableStateOf(false) }

    LaunchedEffect(item.url) {
        if (item.contentType != RssContentType.WEB) {
            busy = false
            return@LaunchedEffect
        }
        try {
            val entity = SourceEntityConverter.toRssEntity(source.raw)
                ?: throw IllegalStateException("该源缺少可运行的地址，无法读取正文")
            val g = RuntimeGraph.get(context)
            val src = LegadoRssSource(
                entity = entity,
                net = g.net,
                rules = g.rules,
                sortParser = g.sortParser,
                rssFallback = g.rssFallback,
                metrics = g.metrics
            )
            val url = resolveUrl(referrerUrl, item.url)
            when (val r = withContext(Dispatchers.IO) { src.getContent(url) }) {
                is ContentResult.Html -> text = htmlToText(r.html)
                is ContentResult.Audio -> {
                    audioUrl = r.url
                    audioHeaders = r.headers
                    showAudio = true
                    return@LaunchedEffect
                }
                is ContentResult.WebViewFallback -> webUrl = url
            }
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            busy = false
        }
    }

    if (showAudio && audioUrl != null) {
        AudioPlayerScreen(
            title = item.title,
            url = audioUrl!!,
            headers = audioHeaders,
            onBack = { showAudio = false }
        )
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("← 返回") }
                Spacer(Modifier.width(12.dp))
                Text(
                    item.title,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.contentType == RssContentType.VIDEO) {
                Text("视频条目：播放链路后续轮次提供", style = TEXT_STYLE_DIM)
            }
            if (item.contentType == RssContentType.IMAGE) {
                Text("图片条目：浏览链路后续轮次提供", style = TEXT_STYLE_DIM)
            }
            if (busy) Text("加载正文…", style = TEXT_STYLE_DIM)
            error?.let { Text(it, style = TEXT_STYLE_BAD) }
            webUrl?.let { u -> WebViewPane(u, Modifier.weight(1f)) }
            text?.let { t ->
                LazyColumn(Modifier.weight(1f)) {
                    item { Text(t) }
                }
            }
        }
    }
}

@Composable
private fun WebViewPane(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                loadUrl(url)
            }
        }
    )
}

// ============================ 运行时装配 ============================

/** 相对地址 -> 绝对地址；已是绝对地址原样返回。 */
internal fun resolveUrl(base: String, link: String): String {
    val t = link.trim()
    if (t.startsWith("http://") || t.startsWith("https://")) return t
    if (t.startsWith("//")) return "https:" + t
    val b = base.trim()
    if (b.isEmpty()) return t
    return try {
        URI(b).resolve(t).toString()
    } catch (e: Exception) {
        t
    }
}

/** HTML -> 可读文本：块级标签换行，折叠多余空行。 */
internal fun htmlToText(html: String): String {
    val doc = Jsoup.parse(html)
    doc.outputSettings().prettyPrint(false)
    doc.select("br").append("\\n")
    doc.select("p, div, li, h1, h2, h3, h4, h5, tr, section, article").append("\\n")
    val raw = doc.text().replace("\\n", "\n")
    return raw.split("\n")
        .map { it.trim() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** 内核错误码到用户提示（对齐 4.3 产品级文案）。 */
private fun friendlyError(e: Exception): String = when {
    e.message?.contains("timeout", ignoreCase = true) == true ||
        e.message?.contains("超时") == true -> "加载超时，请检查网络后重试"
    e.message?.contains("http 403") == true ||
        e.message?.contains("http 429") == true ||
        e.message?.contains("http 503") == true ->
        "站点拦截了请求（指纹防护），该源暂需增强通道，稍后重试"
    e is java.net.UnknownHostException -> "域名解析失败，站点可能已失效"
    else -> "加载失败：" + (e.message ?: e.javaClass.simpleName)
}
