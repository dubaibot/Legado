package com.lanyue.reader.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lanyue.reader.R
import com.lanyue.reader.bookshelf.ShelfOps.ShelfBook
import com.lanyue.reader.source.debug.SourceDebugger
import com.lanyue.reader.source.debug.SourceDebugger.PipelineReport
import com.lanyue.reader.source.debug.SourceDebugger.Status
import com.lanyue.reader.source.import.SourceImportParser
import com.lanyue.reader.source.import.SourceImportParser.ImportedBookSource
import com.lanyue.reader.source.import.SourceImportParser.ImportResult
import com.lanyue.reader.source.model.IVideoSource
import com.lanyue.reader.source.model.SearchBookItem
import com.lanyue.reader.source.model.SubscriptionItem
import com.lanyue.reader.source.model.TocItem
import com.lanyue.reader.source.model.VideoDetail
import com.lanyue.reader.source.model.VideoEpisode
import com.lanyue.reader.source.model.VideoItem
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import com.lanyue.reader.core.engine.NetworkException
import com.lanyue.reader.core.net.HttpRequest
import com.lanyue.reader.source.video.CmsVideoSource
import com.lanyue.reader.source.video.TvBoxParser
import com.lanyue.reader.source.model.PlayResult

/**
 * 首个交互迭代：书源导入 -> 列表 -> 点击书源跑静态自检。
 *
 * 解析与体检完全复用已有纯逻辑（SourceImportParser / SourceDebugger.staticCheck），
 * 本文件只做 UI 接线：导入走系统文件选择器，展示层不发任何网络请求。
 */

private val STATUS_COLOR: Map<Status, Color> = mapOf(
    Status.PASS to Color(0xFF2E7D32),
    Status.WARN to Color(0xFFEF6C00),
    Status.FAIL to Color(0xFFC62828),
    Status.SKIP to Color(0xFF9E9E9E)
)

/** 书源 JSON 字段别名（与 SourceImportParser 保持一致，支持英文/中文两套键名）。 */
private val FIELD_URL_KEYS =
    listOf("bookSourceUrl", "sourceUrl", "url", "bookSourceURL", "id", "网址")
private val FIELD_NAME_KEYS =
    listOf("bookSourceName", "sourceName", "name", "title", "名称", "书源名称")
private val FIELD_TYPE_KEYS = listOf("bookSourceType", "sourceType", "type", "类型")
private val FIELD_GROUP_KEYS =
    listOf("bookSourceGroup", "sourceGroup", "group", "category", "分组")

/** 书源规则字段：存在其中任一即不是订阅源（订阅源只有最新文章规则，没有搜索/详情/目录/正文）。 */
private val BOOK_RULE_KEYS =
    listOf("搜索", "详情", "详细", "目录", "正文", "内容", "ruleSearch", "ruleToc", "ruleContent", "内容搜索", "discover")

private val TYPE_LABELS = listOf("文本", "音频", "图片", "文件")

internal val TEXT_STYLE_DIM = TextStyle(color = Color(0xFF808080))
internal val TEXT_STYLE_BAD = TextStyle(color = Color(0xFFB00020))

/** 页面路由：Home -> 书源运行链路 / 订阅源运行链路 / 静态体检。 */
internal sealed interface Route {
    data object Home : Route
    data class BookExplore(val source: ImportedBookSource) : Route
    data class BookSearch(val source: ImportedBookSource) : Route
    data class BookDetail(val source: ImportedBookSource, val item: SearchBookItem, val referrer: String) : Route
    data class Reader(
        val source: ImportedBookSource,
        val item: SearchBookItem,
        val referrer: String,
        val chapter: TocItem,
        val toc: List<TocItem>
    ) : Route
    data class RssBrowse(val source: ImportedBookSource) : Route
    data class Article(val source: ImportedBookSource, val item: SubscriptionItem, val referrer: String) : Route
    data class Check(val source: ImportedBookSource) : Route
    data class VideoDetail(val source: IVideoSource, val item: VideoItem) : Route
    data class VideoExplore(val row: VideoSiteRow) : Route
    data class Player(
        val source: IVideoSource,
        val detail: com.lanyue.reader.source.model.VideoDetail,
        val episode: VideoEpisode,
        val playResult: PlayResult
    ) : Route
}

/** 影视站点行：来自 TvBox 配置展开，复用同一记源 CmsVideoSource。 */
internal data class VideoSiteRow(
    val site: VideoSiteEntity,
    val source: IVideoSource
) {
    val key: String get() = site.siteKey
    val label: String get() = site.name
}

@Composable
internal fun SourceApp(
    sources: MutableList<ImportedBookSource> = mutableStateListOf(),
    videoSites: MutableList<VideoSiteRow> = mutableStateListOf(),
    shelfHint: ShelfBook? = null,
    onGoShelf: () -> Unit = {}
) {
    var lastImport by remember { mutableStateOf<ImportResult?>(null) }
    var readStatus by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf<Route>(Route.Home) }
    var tvUrl by remember { mutableStateOf("") }
    var tvErr by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * §3.1 加入书架：从详情页把书目回填进 RuntimeGraph 的 ShelfOps；
     * 书架页（RootShell）共享同一实例，即时可见。
     */
    fun addToShelf(s: ImportedBookSource, item: SearchBookItem) {
        val report = RuntimeGraph.get(context).shelfOps.add(
            ShelfBook(
                bookUrl = item.bookUrl,
                name = item.name,
                author = item.author,
                cover = item.cover,
                intro = item.intro,
                origin = s.name,
                originName = item.originName ?: s.name,
                sourceType = if (s.type == 1) 1 else 0,
                latestChapter = item.latestChapter
            )
        )
        readStatus = report.message
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = readUri(context, uri)
            when {
                text == null -> readStatus = context.getString(R.string.read_failed)
                text.length > SourceImportParser.MAX_BYTES ->
                    readStatus = context.getString(R.string.too_large_msg)
                else -> {
                    val result = SourceImportParser.parse(text)
                    lastImport = result
                    readStatus = null
                    sources.addAll(result.accepted)
                }
            }
        }
    }

    /**
     * §1.5 / §3.4 影视配置导入：优酷 TVBox json URL
     * -> 拉取 -> TvBoxParser.parse -> 展开站点 -> CmsVideoSource
     * -> 校验 -> 去重 -> 注入 videoSites 列表
     */
    fun importVideo(url: String) {
        val g = RuntimeGraph.get(context)
        scope.launch {
            tvErr = null
            val trimmed = url.trim()
            if (trimmed.isEmpty()) {
                tvErr = "请先填写配置 URL"
                return@launch
            }
            if (!trimmed.startsWith("http")) {
                tvErr = "配置 URL 必须是 http(s) 链接"
                return@launch
            }
            val resp = runCatching {
                g.net.execute(
                    HttpRequest(
                        url = trimmed,
                        method = "GET",
                        sourceId = "tvbox-config",
                        timeoutMs = 20_000L
                    ),
                    traceId = "import-tvbox-" + trimmed
                )
            }.getOrNull()
            val body = resp?.body
            if (body.isNullOrEmpty()) {
                val code = resp?.code?.toString() ?: "?"
                tvErr = "拉取失败（HTTP $code）"
                return@launch
            }
            val parsed = runCatching { TvBoxParser().parse(trimmed, body) }.getOrNull()
            val parsedOk = parsed.let {
                when (it) {
                    is TvBoxParser.Result.Ok -> it.config
                    is TvBoxParser.Result.Invalid -> null
                    else -> null
                }
            }
            if (parsedOk == null) {
                tvErr = "配置 JSON 不合法：" +
                    (when (parsed) {
                        is TvBoxParser.Result.Ok -> ""
                        is TvBoxParser.Result.Invalid -> parsed.reason
                        else -> ""
                    })
                return@launch
            }
            // 刷新 RuntimeGraph 的本代理 parses（§7.3 type 2 由本代理解析） + 启动服务
            RuntimeGraph.updateVideoParses(context, parsedOk.parses)

            val added = parsedOk.sites.filter { it.enabled }.filterNot { site ->
                videoSites.any { it.site.configUrl == site.configUrl && it.site.siteKey == site.siteKey }
            }
            added.forEach { site ->
                val src = CmsVideoSource(
                    entity = site,
                    net = g.net,
                    metrics = g.metrics,
                    parses = parsedOk.parses,
                    flags = parsedOk.flags
                )
                videoSites.add(VideoSiteRow(site, src))
            }
            tvErr = if (added.isEmpty()) "无新站点（可能全部已存在/全部被关停）"
            else null
        }
    }

    Column(Modifier.fillMaxSize()) {
        shelfHint?.let { hint ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "从书架打开《${hint.name}》（来源：${hint.originName ?: hint.origin}）",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onGoShelf) { Text("回书架") }
                }
            }
        }
        when (val r = route) {
            Route.Home -> HomeScreen(
                sources = sources,
                lastImport = lastImport,
                readStatus = readStatus,
                videoSites = videoSites,
                tvUrl = tvUrl,
                tvErr = tvErr,
                onImport = { filePicker.launch("*/*") },
                onTvUrlChange = { tvUrl = it },
                onImportVideo = { importVideo(it) },
                onOpenVideo = { row -> route = Route.VideoExplore(row) },
                onOpen = { src ->
                    route = if (src.fields().isRss) Route.RssBrowse(src) else Route.BookExplore(src)
                },
                onDismissImport = { lastImport = null; readStatus = null }
            )
            is Route.BookExplore -> BookExploreScreen(
                source = r.source,
                onBack = { route = Route.Home },
                onCheck = { route = Route.Check(r.source) },
                onOpenItem = { item, ref -> route = Route.BookDetail(r.source, item, ref) }
            )
            is Route.BookSearch -> BookSearchScreen(
                source = r.source,
                onBack = { route = Route.Home },
                onCheck = { route = Route.Check(r.source) },
                onOpenItem = { item, ref -> route = Route.BookDetail(r.source, item, ref) }
            )
            is Route.BookDetail -> BookDetailScreen(
                source = r.source,
                item = r.item,
                referrerUrl = r.referrer,
                onBack = { route = Route.BookExplore(r.source) },
                onOpenChapter = { chapter, toc, ref ->
                    route = Route.Reader(r.source, r.item, ref, chapter, toc)
                },
                onAddToShelf = { addToShelf(r.source, r.item) }
            )
            is Route.Reader -> ReaderScreen(
                source = r.source,
                chapter = r.chapter,
                toc = r.toc,
                referrerUrl = r.referrer,
                bookUrl = r.item.bookUrl,
                onBack = { route = Route.BookDetail(r.source, r.item, r.referrer) }
            )
            is Route.RssBrowse -> RssBrowseScreen(
                source = r.source,
                onBack = { route = Route.Home },
                onCheck = { route = Route.Check(r.source) },
                onOpenItem = { item, ref -> route = Route.Article(r.source, item, ref) }
            )
            is Route.Article -> ArticleScreen(
                source = r.source,
                item = r.item,
                referrerUrl = r.referrer,
                onBack = { route = Route.RssBrowse(r.source) }
            )
            is Route.Check -> DetailScreen(source = r.source, onBack = { route = Route.Home })
            is Route.VideoDetail -> VideoDetailScreen(
                source = r.source,
                item = r.item,
                onBack = { route = Route.Home },
                onPlay = { src, det, ep ->
                    scope.launch {
                        val pr = withContext(Dispatchers.IO) {
                            runCatching { src.getPlay(ep) }.getOrNull()
                        } ?: return@launch
                        route = Route.Player(src, det, ep, pr)
                    }
                }
            )
            is Route.VideoExplore -> VideoExploreScreen(
                source = r.row.source,
                onBack = { route = Route.Home },
                onOpenItem = { item ->
                    route = Route.VideoDetail(r.row.source, item)
                }
            )
            is Route.Player -> PlayerScreen(
                source = r.source,
                detail = r.detail,
                episode = r.episode,
                playResult = r.playResult,
                onBack = { route = Route.Home }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    sources: List<ImportedBookSource>,
    lastImport: ImportResult?,
    readStatus: String?,
    videoSites: List<VideoSiteRow>,
    tvUrl: String,
    tvErr: String?,
    onImport: () -> Unit,
    onTvUrlChange: (String) -> Unit,
    onImportVideo: (String) -> Unit,
    onOpenVideo: (VideoSiteRow) -> Unit,
    onOpen: (ImportedBookSource) -> Unit,
    onDismissImport: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onImport) {
                    Text(stringResource(R.string.import_book_source))
                }
            }
        }

        lastImport?.let { result ->
            item {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(result.summary(), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onDismissImport) {
                            Text(stringResource(R.string.import_summary_dismiss))
                        }
                    }
                    result.rejected.take(5).forEach { rj ->
                        Text("第 ${rj.index} 条失败：${rj.reason}", style = TEXT_STYLE_BAD)
                        Text(
                            "  ${rj.snippet}",
                            style = TEXT_STYLE_DIM,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (result.rejected.size > 5) {
                        Text(
                            "还有 ${result.rejected.size - 5} 条未展示",
                            style = TEXT_STYLE_DIM
                        )
                    }
                }
            }
        }
        readStatus?.let { msg ->
            item { Text(msg, style = TEXT_STYLE_BAD) }
        }

        item {
            Text(stringResource(R.string.sources_section_title), fontWeight = FontWeight.Bold)
            if (sources.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.sources_empty), style = TEXT_STYLE_DIM)
            }
        }

        items(sources) { src ->
            SourceCard(src, onOpen = { onOpen(src) })
        }

        item {
            Text(stringResource(R.string.onboarding_note), style = TEXT_STYLE_DIM)
        }

        item {
            Column(Modifier.fillMaxWidth()) {
                Text("影视配置导入", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = tvUrl,
                        onValueChange = onTvUrlChange,
                        label = { Text("TVBox JSON URL") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onImportVideo(tvUrl) }) { Text("导入") }
                }
                Spacer(Modifier.height(4.dp))
                if (tvErr != null) {
                    Text(tvErr!!, style = TEXT_STYLE_BAD, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(4.dp))
                if (videoSites.isEmpty()) {
                    Text("尚未导入影视配置", style = TEXT_STYLE_DIM)
                } else {
                    videoSites.forEach { row ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .clickable { onOpenVideo(row) },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(row.label, fontWeight = FontWeight.Medium)
                                Text(
                                    row.site.api ?: (row.site.configUrl + "#" + row.key),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TEXT_STYLE_DIM
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(src: ImportedBookSource, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(src.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(typeLabel(src.type), style = TEXT_STYLE_DIM)
            }
            Text(src.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TEXT_STYLE_DIM)
            src.group?.let { Text("分组：$it", style = TEXT_STYLE_DIM) }
        }
    }
}

@Composable
private fun DetailScreen(source: ImportedBookSource, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<PipelineReport?>(null) }
    var busy by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("← 返回") }
                Spacer(Modifier.width(12.dp))
                Text(source.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }

        item { SourceInfoCard(source) }

        item {
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        val fields = source.fields()
                        val r = withContext(Dispatchers.IO) {
                            if (fields.isRss) {
                                // 订阅源：搜索规则回落到原始 ruleSearch，正文规则作为最新文章校验
                                SourceDebugger.staticCheckRss(
                                    id = fields.url,
                                    name = fields.name,
                                    latestRule = fields.ruleContent,
                                    searchRule = fields.searchUrl,
                                    type = fields.type
                                )
                            } else {
                                SourceDebugger.staticCheck(
                                    url = fields.url,
                                    name = fields.name,
                                    searchUrl = fields.searchUrl,
                                    ruleSearch = fields.ruleSearch,
                                    ruleToc = fields.ruleToc,
                                    ruleContent = fields.ruleContent,
                                    type = fields.type
                                )
                            }
                        }
                        report = r
                        busy = false
                    }
                },
                enabled = !busy
            ) {
                Text(
                    if (busy) stringResource(R.string.checking)
                    else stringResource(R.string.static_check)
                )
            }
        }

        report?.let { r ->
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.summary(), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        r.steps.forEach { step ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(STATUS_COLOR[step.status] ?: Color.Gray)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${step.stage.label} · ${step.status.label}")
                                    Text(step.message, style = TEXT_STYLE_DIM)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceInfoCard(src: ImportedBookSource) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            FieldRow("书源名称", src.name)
            FieldRow("源类型", typeLabel(src.type))
            src.group?.let { FieldRow("分组", it) }
            Spacer(Modifier.height(4.dp))
            FieldRow("书源地址", src.url)
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(80.dp), style = TEXT_STYLE_DIM)
        Text(value, modifier = Modifier.weight(1f), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

private fun typeLabel(type: Int): String =
    TYPE_LABELS.getOrNull(type.coerceIn(0, 3)) ?: "文本"

/** 从书源原始 JSON 里按别名表抽字段，缺失时回落到导入解析的兜底值。 */
internal fun ImportedBookSource.fields(): BookFields {
    val obj = try {
        JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Throwable) {
        null
    }
    // 订阅源识别：以 id 属性为地址、没有任何书源规则字段（搜索/详情/目录/正文）。
    // 文本行格式的 raw 不是 JSON，obj 为 null，一律当作书源。
    val isRss = obj != null &&
        !BOOK_RULE_KEYS.any { obj.has(it) } &&
        (firstStr(obj, listOf("id"))?.startsWith("http") == true)
    return BookFields(
        url = firstStr(obj, FIELD_URL_KEYS)?.takeIf { it.startsWith("http") }
            ?: SourceImportParser.httpBase(
                firstStr(obj, listOf("搜索", "详情", "详细", "searchUrl"))
            )
            ?: url,
        name = firstStr(obj, FIELD_NAME_KEYS) ?: name,
        group = firstStr(obj, FIELD_GROUP_KEYS),
        searchUrl = firstStr(obj, listOf("searchUrl")),
        ruleSearch = ruleText(obj, listOf("搜索", "searchRule", "ruleSearch", "内容搜索")),
        ruleToc = ruleText(obj, listOf("目录", "ruleToc", "目录规则")),
        ruleContent = ruleText(obj, listOf("内容", "正文", "最新", "最新规则", "最新内容", "ruleContent")),
        type = firstInt(obj, FIELD_TYPE_KEYS) ?: type,
        isRss = isRss
    )
}

/** 规则字段可能是扁平模板字符串，也可能是嵌套规则对象（取第一个 URL/规则文本）；
 *  还支持 Legado 的 {"规则": {"搜索": "..."}} 嵌套一层。 */
private fun ruleText(obj: JsonObject?, keys: List<String>): String? {
    if (obj == null) return null
    // 任意 key 的取值范围：对象本身 + 顶层的「规则/rules」子对象
    val subs = sequenceOf(obj) + listOf("规则", "rules")
        .mapNotNull { obj.get(it) }
        .filter { it.isJsonObject }
        .map { it.asJsonObject }
    for (k in keys) {
        for (sub in subs) {
            val el = sub.get(k) ?: continue
            when {
                el.isJsonPrimitive -> {
                    val v = try { el.asString } catch (e: Throwable) { continue }
                    if (v.isNotBlank()) return v
                }
                el.isJsonObject -> {
                    val nested = el.asJsonObject
                    val v = nested.entrySet().firstNotNullOfOrNull { e ->
                        e.value.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    }?.trim()?.takeIf { it.isNotBlank() } ?: nested.toString()
                    return v
                }
            }
        }
    }
    return null
}

internal data class BookFields(
    val url: String?,
    val name: String?,
    val group: String?,
    val searchUrl: String?,
    val ruleSearch: String?,
    val ruleToc: String?,
    val ruleContent: String?,
    val type: Int,
    /** 订阅源：只有 id 地址与名称，没有书源规则（目录/正文）。 */
    val isRss: Boolean
)

private fun firstStr(obj: JsonObject?, keys: List<String>): String? {
    if (obj == null) return null
    for (k in keys) {
        val el = obj.get(k) ?: continue
        if (!el.isJsonPrimitive) continue
        val v = try { el.asString } catch (e: Throwable) { continue }
        if (v.isNotBlank()) return v
    }
    return null
}

private fun firstInt(obj: JsonObject?, keys: List<String>): Int? {
    if (obj == null) return null
    for (k in keys) {
        val v = firstStr(obj, listOf(k)) ?: continue
        v.toIntOrNull()?.let { return it.coerceIn(0, 3) }
    }
    return null
}

/** 读 Uri 文件文本：读不到返回 null（上层给提示）；UTF-8 解码遇非法字节回落 GBK。 */
private suspend fun readUri(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val raw = input.readBytes()
                if (raw.size > SourceImportParser.MAX_BYTES) return@use null
                val utf8 = String(raw, StandardCharsets.UTF_8)
                val decoded =
                    if (utf8.contains('\uFFFD')) String(raw, Charset.forName("GBK")) else utf8
                decoded.trim().removePrefix("\uFEFF")
            }
        } catch (e: Exception) {
            null
        }
    }
