package com.lanyue.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import com.lanyue.reader.source.import.SourceImportParser
import com.lanyue.reader.source.model.SourceValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** §3.10 源管理中心：小说书源 / 订阅源 / 影视站点 / 弹幕源。 */
enum class SourceSection(val label: String) {
    BOOK("小说源"),
    RSS("订阅源"),
    VIDEO("影视源"),
    DANMAKU("弹幕源"),
}

@Composable
internal fun SourceCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val g = remember { RuntimeGraph.get(context) }
    val scope = rememberCoroutineScope()

    var section by remember { mutableStateOf(SourceSection.BOOK) }
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    val books = remember { mutableStateListOf<BookSourceEntity>() }
    val rss = remember { mutableStateListOf<RssSourceEntity>() }
    val videos = remember { mutableStateListOf<VideoSiteEntity>() }
    val danmaku = remember { mutableStateListOf<DanmakuSourceEntity>() }

    /** 检测摘要：key = 主键（书源 url / 订阅 sourceUrl / 视频 configUrl#siteKey）。 */
    val summaries = remember { mutableStateMapOf<String, String>() }
    var detecting by remember { mutableStateOf(false) }
    var detectDone by remember { mutableStateOf(0) }
    var detectTotal by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf("") }

    var editKey by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editGroup by remember { mutableStateOf("") }

    fun keyOf(src: BookSourceEntity) = "book:" + src.bookSourceUrl
    fun keyOf(src: RssSourceEntity) = "rss:" + src.sourceUrl
    fun keyOf(src: VideoSiteEntity) = "video:" + src.configUrl + "#" + src.siteKey
    fun keyOf(src: DanmakuSourceEntity) = "danmaku:" + src.id
    fun keyOfOfAny(src: Any): String = when (src) {
        is BookSourceEntity -> keyOf(src)
        is RssSourceEntity -> keyOf(src)
        is VideoSiteEntity -> keyOf(src)
        is DanmakuSourceEntity -> keyOf(src)
        else -> ""
    }

    fun entityName(section: SourceSection, src: Any): String = when (src) {
        is BookSourceEntity -> src.bookSourceName.ifBlank { src.bookSourceUrl }
        is RssSourceEntity -> src.sourceName.ifBlank { src.sourceUrl }
        is VideoSiteEntity -> src.name.ifBlank { src.siteKey }
        is DanmakuSourceEntity -> src.name.ifBlank { src.apiBase }
        else -> ""
    }

    LaunchedEffect(section) {
        selected.clear()
        selecting = false
        query = ""
        when (section) {
            SourceSection.BOOK -> books.clear().also { books.addAll(g.db.bookSourceDao().all()) }
            SourceSection.RSS -> rss.clear().also { rss.addAll(g.db.rssSourceDao().all()) }
            SourceSection.VIDEO -> videos.clear().also { videos.addAll(g.db.videoDao().allSites()) }
            SourceSection.DANMAKU -> danmaku.clear().also { danmaku.addAll(g.db.danmakuDao().all()) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        scope.launch {
            val text = uri?.let { u ->
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(u)?.bufferedReader()?.readText()
                }
            } ?: ""
            val result = SourceImportParser.parse(text)
            g.db.bookSourceDao().upsertAll(
                result.accepted.map { src ->
                    BookSourceEntity(
                        bookSourceUrl = src.url,
                        bookSourceName = src.name,
                        bookSourceGroup = src.group,
                        bookSourceType = src.type,
                        enabled = true
                    )
                }
            )
            books.clear().also { books.addAll(g.db.bookSourceDao().all()) }
            toast = "${result.format}：导入完成（已安装 ${result.accepted.size} 源）"
        }
    }

    // ---- 批量 / 单条操作 ----

    fun batchEnable(enabled: Boolean) {
        scope.launch {
            when (section) {
                SourceSection.BOOK -> {
                    val dao = g.db.bookSourceDao()
                    books.forEach { b ->
                        if (keyOf(b) in selected) dao.upsert(b.copy(enabled = enabled))
                    }
                }
                SourceSection.RSS -> {
                    val dao = g.db.rssSourceDao()
                    rss.forEach { b ->
                        if (keyOf(b) in selected) dao.upsert(b.copy(enabled = enabled))
                    }
                }
                SourceSection.VIDEO -> {
                    val dao = g.db.videoDao()
                    videos.forEach { b ->
                        if (keyOf(b) in selected) dao.upsertSite(b.copy(enabled = enabled))
                    }
                }
                SourceSection.DANMAKU -> {
                    val dao = g.db.danmakuDao()
                    danmaku.forEach { b ->
                        if (keyOf(b) in selected) dao.setEnabled(b.id, enabled)
                    }
                }
            }
            toast = "已${if (enabled) "启用" else "禁用"} ${selected.size} 条"
        }
    }

    fun batchDelete() {
        val keys = selected.toList()
        scope.launch {
            when (section) {
                SourceSection.BOOK -> keys.forEach { k ->
                    val url = k.removePrefix("book:")
                    g.db.bookSourceDao().delete(url)
                }
                SourceSection.RSS -> keys.forEach { k ->
                    val url = k.removePrefix("rss:")
                    g.db.rssSourceDao().delete(url)
                }
                SourceSection.VIDEO -> keys.forEach { k ->
                    val body = k.removePrefix("video:")
                    val idx = body.indexOf('#')
                    val config = body.substringBefore('#')
                    val siteKey = body.substringAfter('#')
                    g.db.videoDao().deleteSite(config, siteKey)
                }
                SourceSection.DANMAKU -> keys.forEach { k ->
                    g.db.danmakuDao().delete(k.removePrefix("danmaku:"))
                }
            }
            selected.clear()
            selecting = false
            toast = "已删除 ${keys.size} 条"
        }
    }

    fun confirmEdit() {
        val target = when (section) {
            SourceSection.BOOK -> books.find { it.bookSourceUrl == editKey?.removePrefix("book:") }
            SourceSection.RSS -> rss.find { it.sourceUrl == editKey?.removePrefix("rss:") }
            SourceSection.VIDEO -> videos.find { it.configUrl + "#" + it.siteKey == editKey?.removePrefix("video:") }
            SourceSection.DANMAKU -> danmaku.find { it.id == editKey?.removePrefix("danmaku:") }
        } ?: run { editKey = null; return }
        scope.launch {
            when (target) {
                is BookSourceEntity -> g.db.bookSourceDao().upsert(
                    target.copy(bookSourceName = editName, bookSourceGroup = editGroup)
                )
                is RssSourceEntity -> g.db.rssSourceDao().upsert(
                    target.copy(sourceName = editName, sourceGroup = editGroup)
                )
                is VideoSiteEntity -> g.db.videoDao().upsertSite(
                    target.copy(name = editName)
                )
                is DanmakuSourceEntity -> g.db.danmakuDao().upsert(
                    target.copy(name = editName)
                )
            }
            editKey = null
            toast = "已保存"
        }
    }

    // ---- 列表数据 ----

    val shown: List<Any> = when (section) {
        SourceSection.BOOK -> books.filter { it.matchQuery(query) }
        SourceSection.RSS -> rss.filter { it.matchQuery(query) }
        SourceSection.VIDEO -> videos.filter { it.matchQuery(query) }
        SourceSection.DANMAKU -> danmaku.filter { (it.name + " " + it.apiBase).contains(query) }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 标题 + 统计
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(section.label, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(0.5f))
            val stats = when (section) {
                SourceSection.BOOK -> "${books.size} 源 · ${books.count { it.enabled }} 启用"
                SourceSection.RSS -> "${rss.size} 源 · ${rss.count { it.enabled }} 启用"
                SourceSection.VIDEO -> "${videos.size} 站点 · ${videos.count { it.enabled }} 启用"
                SourceSection.DANMAKU -> "${danmaku.size} 源 · ${danmaku.count { it.enabled }} 启用"
            }
            Text(stats)
        }

        // 分段 chip
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFF5F5F5))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SourceSection.entries.forEach { s ->
                FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s.label) })
            }
        }

        // 操作行
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(0.5f).height(36.dp),
                label = { Text("搜索源名 / 地址") },
                singleLine = true
            )
            TextButton(onClick = {
                selecting = !selecting
                if (!selecting) selected.clear()
            }) { Text(if (selecting) "完成" else "多选") }
            if (section != SourceSection.VIDEO) {
                TextButton(onClick = {
                    val targets: List<Any> = when (section) {
                        SourceSection.BOOK -> books.filter { keyOf(it) in selected || selected.isEmpty() }
                        SourceSection.RSS -> rss.filter { keyOf(it) in selected || selected.isEmpty() }
                        else -> emptyList()
                    }
                    if (targets.isEmpty()) { toast = "没有可检测的源"; return@TextButton }
                    detecting = true
                    detectDone = 0
                    detectTotal = targets.size
                    scope.launch {
                        var errors = 0
                        var warnings = 0
                        var ok = 0
                        targets.forEach { t ->
                            val rep = when (t) {
                                is BookSourceEntity -> SourceValidator.validateBook(
                                    t.bookSourceUrl, t.bookSourceName, t.searchUrl, t.ruleSearch,
                                    t.exploreUrl, t.enabledExplore, t.ruleToc, t.ruleContent,
                                    t.loginUrl, t.bookSourceType, t.jsLib
                                )
                                is RssSourceEntity -> SourceValidator.validateRss(
                                    t.sourceUrl, t.sourceName, t.sortUrl, t.ruleArticles, t.ruleTitle,
                                    t.ruleLink, null, 0
                                )
                                else -> null
                            }
                            if (rep != null) {
                                summaries[keyOfOfAny(t)] = rep.summary()
                                when {
                                    rep.hasError -> errors++
                                    rep.issues.isNotEmpty() -> warnings++
                                    else -> ok++
                                }
                            }
                            detectDone++
                        }
                        detecting = false
                        toast = "检测完成：通过 $ok，可用（提示 $warnings 处），无效 $errors"
                    }
                }) {
                    Text("检测${if (selected.isNotEmpty()) "选中" else "全部"}")
                }
            }
            if (section == SourceSection.BOOK) {
                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("批量导入") }
                TextButton(onClick = {
                    scope.launch {
                        val out = File(context.filesDir, "book-sources-export.json")
                        val arr = JSONArray()
                        books.forEach { b ->
                            val obj = org.json.JSONObject()
                            obj.put("bookSourceUrl", b.bookSourceUrl)
                            obj.put("bookSourceName", b.bookSourceName)
                            obj.put("bookSourceGroup", b.bookSourceGroup)
                            obj.put("bookSourceType", b.bookSourceType)
                            arr.put(obj)
                        }
                        withContext(Dispatchers.IO) { out.writeText(arr.toString()) }
                        toast = "已导出到 ${out.absolutePath}"
                    }
                }) { Text("导出书源") }
            }
        }

        // 多选操作条
        if (selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("已选 ${selected.size} 条", fontWeight = FontWeight.Bold)
                TextButton(onClick = { batchEnable(true) }) { Text("启用") }
                TextButton(onClick = { batchEnable(false) }) { Text("禁用") }
                TextButton(onClick = { batchDelete() }) { Text("删除") }
            }
        }

        if (detecting) {
            LinearProgressIndicator(
                progress = { (detectDone / detectTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).padding(8.dp)
            )
        }

        Box(Modifier.fillMaxSize().weight(1f)) {
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无${section.label}${if (query.isNotBlank()) "（无匹配）" else "，去书源 Tab 导入"}")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { keyOfOfAny(it) }) { src ->
                        SourceRow(
                            section = section,
                            src = src,
                            summary = summaries[keyOfOfAny(src)],
                            selected = keyOfOfAny(src) in selected,
                            selecting = selecting,
                            onToggle = {
                                if (selecting) {
                                    val k = keyOfOfAny(src)
                                    if (k in selected) selected.remove(k) else selected.add(k)
                                } else {
                                    editKey = keyOfOfAny(src)
                                    editName = entityName(section, src)
                                    editGroup = when (src) {
                                        is BookSourceEntity -> src.bookSourceGroup ?: ""
                                        is RssSourceEntity -> src.sourceGroup ?: ""
                                        is VideoSiteEntity -> ""
                                        else -> ""
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    when (src) {
                                        is BookSourceEntity -> g.db.bookSourceDao().delete(src.bookSourceUrl)
                                        is RssSourceEntity -> g.db.rssSourceDao().delete(src.sourceUrl)
                                        is VideoSiteEntity -> g.db.videoDao().deleteSite(src.configUrl, src.siteKey)
                                        is DanmakuSourceEntity -> g.db.danmakuDao().delete(src.id)
                                    }
                                    toast = "已删除 " + entityName(section, src)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 编辑弹窗
    if (editKey != null) {
        val isVideo = editKey.orEmpty().startsWith("video:")
        AlertDialog(
            onDismissRequest = { editKey = null },
            title = { Text(if (isVideo) "编辑影视站点" else "编辑${section.label}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("名称") },
                        singleLine = true
                    )
                    if (!isVideo) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editGroup,
                            onValueChange = { editGroup = it },
                            label = { Text("分组") },
                            singleLine = true
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "分组等字段需要真实网络导入的站点配置；这里是本地重命名与分组标注。",
                        fontSize = 11.sp,
                        color = Color(0xFF9E9E9E)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { confirmEdit() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editKey = null }) { Text("取消") } }
        )
    }

    if (toast.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { toast = "" },
            title = { Text("源管理中心") },
            text = { Text(toast) },
            confirmButton = { TextButton(onClick = { toast = "" }) { Text("知道了") } }
        )
    }
}

// ===================== 单行卡片 =====================

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SourceRow(
    section: SourceSection,
    src: Any,
    summary: String?,
    selected: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.fillMaxWidth(0.85f)) {
                val line = when (src) {
                    is BookSourceEntity -> Triple(
                        src.bookSourceName.ifBlank { src.bookSourceUrl },
                        src.bookSourceUrl + "  ·  " + (src.bookSourceGroup ?: "未分组"),
                        src.enabled
                    )
                    is RssSourceEntity -> Triple(
                        src.sourceName.ifBlank { src.sourceUrl },
                        src.sourceUrl + "  ·  " + (src.sourceGroup ?: "未分组"),
                        src.enabled
                    )
                    is VideoSiteEntity -> Triple(
                        src.name.ifBlank { src.siteKey },
                        src.siteKey + "  ·  type " + src.type,
                        src.enabled
                    )
                    else -> Triple("", "", false)
                }
                val (title, subtitle, enabled) = line
                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (enabled) "启用" else "停用",
                    fontSize = 11.sp,
                    color = if (enabled) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
                )
                summary?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

// ===================== 查询扩展 =====================

private fun BookSourceEntity.matchQuery(q: String): Boolean =
    q.isBlank() || (bookSourceName + bookSourceUrl + (bookSourceGroup ?: "")).contains(q, ignoreCase = true)

private fun RssSourceEntity.matchQuery(q: String): Boolean =
    q.isBlank() || (sourceName + sourceUrl + (sourceGroup ?: "")).contains(q, ignoreCase = true)

private fun VideoSiteEntity.matchQuery(q: String): Boolean =
    q.isBlank() || (name + siteKey + configUrl).contains(q, ignoreCase = true)
