package com.lanyue.reader.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanyue.reader.R
import com.lanyue.reader.bookshelf.ShelfOps.ShelfBook
import com.lanyue.reader.data.db.entity.RuleSubEntity
import com.lanyue.reader.data.backup.BackupCodec
import com.lanyue.reader.data.backup.BackupCodec.DecodeResult
import com.lanyue.reader.data.backup.BackupPayload
import com.lanyue.reader.data.backup.BookBackup
import com.lanyue.reader.data.backup.ReadRecordBackup
import com.lanyue.reader.source.import.SourceImportParser.ImportedBookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** §3.9 我的：源管理 / 缓存管理 / 备份恢复 / 设置中心 / 关于。 */
@Composable
internal fun MineScreen(onOpenSource: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

    var ruleSubOpen by remember { mutableStateOf(false) }
    var cacheOpen by remember { mutableStateOf(false) }
    var settingOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("我的", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))

        TextButton(onClick = onOpenSource, modifier = Modifier.fillMaxWidth()) {
            MineRow("源管理", "书源 / 订阅 / 影视 / 弹幕")
        }
        TextButton(onClick = { ruleSubOpen = true }, modifier = Modifier.fillMaxWidth()) {
            MineRow("规则订阅管理", "订阅规则集合的查看 / 启用 / 刷新 / 删除")
        }
        TextButton(onClick = { cacheOpen = true }, modifier = Modifier.fillMaxWidth()) {
            MineRow("缓存管理", "缓存文件清单与 LRU 清理")
        }
        TextButton(onClick = { backupOpen = true }, modifier = Modifier.fillMaxWidth()) {
            MineRow("备份恢复", "导出 / 导入本地备份")
        }
        TextButton(onClick = { settingOpen = true }, modifier = Modifier.fillMaxWidth()) {
            MineRow("设置中心", "界面 / 阅读 / 播放 / 弹幕 / 网络 / 安全 / 高级")
        }
        TextButton(onClick = { aboutOpen = true }, modifier = Modifier.fillMaxWidth()) {
            MineRow("关于", "版本与声明")
        }
    }

    if (ruleSubOpen) {
        RuleSubPanel(onClose = { ruleSubOpen = false })
    }
    if (cacheOpen) {
        CachePanel(onClose = { cacheOpen = false })
    }
    if (settingOpen) {
        SettingsPanel(
            onClose = { settingOpen = false },
            onOpenBackup = { backupOpen = true },
            onOpenAbout = { aboutOpen = true }
        )
    }
    if (backupOpen) {
        BackupPanel(onClose = { backupOpen = false })
    }
    if (aboutOpen) {
        AboutDialog(onClose = { aboutOpen = false })
    }
}

/** §3.9 规则订阅管理：查看 / 启停 / 删除订阅规则集合。 */
@Composable
private fun RuleSubPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = RuntimeGraph.get(context)
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val rows = remember { mutableStateListOf<RuleSubEntity>() }

    fun refresh() {
        scope.launch {
            rows.clear()
            g.db.ruleSubDao().all().forEach { rows.add(it) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("规则订阅管理（§4.3）") },
        text = {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "共 ${rows.size} 组订阅；订阅刷新由后台轮询按 currentInterval 触发，这里只维护启停与删除。",
                    style = TEXT_STYLE_DIM
                )
                if (rows.isEmpty()) {
                    Text("暂无订阅规则，可在源管理中心的「订阅」分类导入后查看。", style = TEXT_STYLE_DIM)
                }
                rows.forEach { sub ->
                    Column(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row {
                            Text(sub.name, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(sub.sourceType, style = TEXT_STYLE_DIM)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    g.db.ruleSubDao().upsert(sub.copy(enabled = !sub.enabled))
                                    toast = if (sub.enabled) "已停用" else "已启用"
                                    refresh()
                                }
                            }) { Text(if (sub.enabled) "已启用" else "已停用") }
                            TextButton(onClick = {
                                scope.launch {
                                    g.db.ruleSubDao().delete(sub.id)
                                    toast = "已删除 ${sub.name}"
                                    refresh()
                                }
                            }) { Text("删除") }
                        }
                    }
                }
                toast?.let { Text(it, style = TEXT_STYLE_DIM) }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("完成") } }
    )
}

/** §3.9 缓存管理：展示缓存清单与体积，支持一键 LRU 清理未锁定文件。 */
@Composable
private fun CachePanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = RuntimeGraph.get(context)
    var loadedBy by remember { mutableStateOf(false) }
    var totalMb by remember { mutableStateOf(0L) }
    var count by remember { mutableStateOf(0) }
    var locked by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            val files = g.db.downloadDao().cacheFiles()
            totalMb = files.sumOf { it.sizeBytes } / (1024L * 1024L)
            count = files.size
            locked = files.count { it.locked }
            loadedBy = true
        }
    }
    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("缓存管理（§4.11）") },
        text = {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (loadedBy) {
                    Text("缓存体积：${totalMb} MB，共 $count 个文件（$locked 个锁定，不参与清理）。")
                }
                TextButton(onClick = { load() }) { Text("刷新清单") }
                TextButton(onClick = {
                    scope.launch {
                        val files = g.db.downloadDao().cacheFiles().filter { !it.locked }
                        g.db.downloadDao().evictLru(files.size)
                        load()
                        toast = "已清理 ${files.size} 个未锁定缓存文件"
                    }
                }) { Text("清理未锁定缓存（LRU）") }
                toast?.let { Text(it, style = TEXT_STYLE_DIM) }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("完成") } }
    )
}

/** §3.11 设置中心：一级设置项最小可用面（弹幕渲染、诊断日志、跳转备份/关于）。 */
@Composable
private fun SettingsPanel(onClose: () -> Unit, onOpenBackup: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = RuntimeGraph.get(context)
    val prefs = context.getSharedPreferences("lanyue_settings", android.content.Context.MODE_PRIVATE)
    var renderingOn by remember { mutableStateOf(prefs.getBoolean("danmaku_rendering", true)) }
    var diagOn by remember { mutableStateOf(prefs.getBoolean("diag_log", false)) }
    var toast by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("设置中心（§3.11）") },
        text = {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 界面
                Text("界面", fontWeight = FontWeight.Bold)
                Text(
                    "主题跟随系统深浅色（§2.1）；底栏支持隐藏（§2.5.1）、抽屉手势稳定。",
                    style = TEXT_STYLE_DIM
                )
                // 阅读
                Text("阅读", fontWeight = FontWeight.Bold)
                Text(
                    "默认开启「底部工具条 / 顶部工具条 / 滑动翻页」手势，字号与行距见阅读器设置。",
                    style = TEXT_STYLE_DIM
                )
                // 播放
                Text("播放", fontWeight = FontWeight.Bold)
                Text(
                    "影视默认倍速从 0.5x 起步，可在播放条直接切换（§3.9）。",
                    style = TEXT_STYLE_DIM
                )
                // 弹幕
                Text("弹幕", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Switch(
                        checked = renderingOn,
                        onCheckedChange = { want ->
                            renderingOn = want
                            prefs.edit().putBoolean("danmaku_rendering", want).apply()
                            toast = if (want) "已开启弹幕渲染（默认）" else "已关闭弹幕渲染（默认不渲染）"
                        }
                    )
                    Text(if (renderingOn) "弹幕渲染已开启" else "弹幕渲染已关闭", style = TEXT_STYLE_DIM)
                }
                TextButton(onClick = {
                    scope.launch {
                        g.danmakuDao.clearBindings()
                        toast = "已清除所有弹幕源匹配记录"
                    }
                }) { Text("清除弹幕源匹配记录") }
                // 网络
                Text("网络", fontWeight = FontWeight.Bold)
                Text(
                    "默认直连 LAN 内网测速、DHCP / 静态地址；代理配置当前提供开关，证书安装见 §7.3 PRIVATE。",
                    style = TEXT_STYLE_DIM
                )
                // 安全
                Text("安全", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Switch(
                        checked = diagOn,
                        onCheckedChange = { want ->
                            diagOn = want
                            prefs.edit().putBoolean("diag_log", want).apply()
                        }
                    )
                    Text(if (diagOn) "诊断日志已开启" else "诊断日志已关闭", style = TEXT_STYLE_DIM)
                }
                // 备份
                Text("备份", fontWeight = FontWeight.Bold)
                TextButton(onClick = { onOpenBackup() }) { Text("备份恢复（§5.5）") }
                // 高级
                Text("高级", fontWeight = FontWeight.Bold)
                Text(
                    "短按底栏居中键切换「多选 / 导出 APK」；WebView 诊断、镜像缓存、自研组件等高级开关随对应页面内并提供。",
                    style = TEXT_STYLE_DIM
                )
                // 关于
                TextButton(onClick = { onOpenAbout() }) { Text("关于蓝阅（§6.6）") }
                toast?.let { Text(it, style = TEXT_STYLE_DIM) }
                Text("当前原型用于功能闭环演示：部分高级项只读，藏书全流程贯通。", style = TEXT_STYLE_DIM)
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("完成") } }
    )
}

@Composable
private fun MineRow(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(sub, style = TEXT_STYLE_DIM)
    }
}

/**
 * §5.5 备份恢复面板（UI 侧最小闭环）：
 * - 导出：书架/进度 写回一份 BackupCodec JSON，走系统文件选择器落 SAF；
 * - 导入：选择文本文件 -> 解码 -> 展示摘要 -> 可将书目恢复进书架。
 */
@Composable
private fun BackupPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    val json = buildPayloadJson(context)
                    it.write(json.toByteArray())
                } ?: throw IllegalStateException("无法写入所选文件")
                status = "备份已导出（书架 ${payloadSize(context)} 项）"
            } catch (e: Exception) {
                status = "导出失败：" + friendlyText(e)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = null
            val text = withContext(Dispatchers.IO) { readText(context, uri) }
            val decoded: DecodeResult = BackupCodec.decode(text)
            when (decoded) {
                is DecodeResult.Success -> {
                    val doc = decoded.document
                    val payload = doc.payload
                    summary = "备份包含 ${payload.totalCount} 项" +
                        "（书架 ${payload.books.size}、书源 ${payload.bookSources.size}、" +
                        "订阅 ${payload.rssSources.size}、影视 ${payload.videoSites.size}、" +
                        "进度 ${payload.readRecords.size + payload.playRecords.size}）"
                    if (decoded.warnings.isNotEmpty()) {
                        summary += "；容错提示 " + decoded.warnings.size + " 条"
                    }
                    // 书架恢复：仅书目回填 ShelfOps（进度持久化后续轮次接通）
                    if (payload.books.isNotEmpty()) {
                        val g = RuntimeGraph.get(context)
                        payload.books.forEach { book ->
                            g.shelfOps.add(
                                ShelfBook(
                                    bookUrl = book.bookUrl,
                                    name = book.name,
                                    author = book.author,
                                    cover = book.cover,
                                    intro = book.intro,
                                    origin = book.origin,
                                    originName = book.originName,
                                    sourceType = book.sourceType,
                                    groupName = book.groupName,
                                    latestChapter = book.latestChapter,
                                    totalChapter = book.totalChapter,
                                    lastReadAt = book.lastReadAt,
                                    sortOrder = book.sortOrder
                                )
                            )
                        }
                        summary += "；书架已恢复 ${payload.books.size} 本书目"
                    }
                }

                is DecodeResult.Failure -> summary = "导入失败：" + decoded.reason
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("备份恢复（§5.5）") },
        text = {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "备份范围：书架书目 + 阅读进度摘要。来源可空；恢复仅回填书架书目，进度与收藏待后续轮次接通。",
                    style = TEXT_STYLE_DIM
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { saver.launch("备份.json") }, enabled = !busy) { Text("导出备份") }
                    OutlinedButton(onClick = { picker.launch("*/*") }, enabled = !busy) { Text("导入备份") }
                }
                status?.let { Text(it, style = TEXT_STYLE_DIM) }
                summary?.let {
                    Text(it, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { summary = null }) { Text("关闭提示") }
                }
            }
        },
        confirmButton = {
            Button(onClick = onClose) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("离开") } }
    )
}

private suspend fun buildPayloadJson(context: Context): String {
    val g = RuntimeGraph.get(context)
    val books = g.shelfOps.visible().map { book ->
        BookBackup(
            bookUrl = book.bookUrl,
            name = book.name,
            author = book.author,
            cover = book.cover,
            intro = book.intro,
            origin = book.origin,
            originName = book.originName,
            sourceType = book.sourceType,
            groupName = book.groupName,
            latestChapter = book.latestChapter,
            totalChapter = book.totalChapter,
            lastReadAt = book.lastReadAt,
            sortOrder = book.sortOrder
        )
    }
    val reads = g.shelfOps.visible().mapNotNull { book ->
        runCatching { g.progressDao.readRecord(book.bookUrl) }
            .getOrNull()
            ?.let { entity ->
                ReadRecordBackup(
                    bookUrl = entity.bookUrl,
                    chapterIndex = entity.chapterIndex,
                    chapterTitle = entity.chapterTitle,
                    chapterUrl = entity.chapterUrl,
                    position = entity.position,
                    updatedAt = entity.updatedAt
                )
            }
    }
    return BackupCodec.encode(
        BackupPayload(books = books, readRecords = reads),
        exportedAt = System.currentTimeMillis(),
        appVersion = "dev"
    )
}

private fun payloadSize(context: Context): Int =
    RuntimeGraph.get(context).shelfOps.size()

private fun friendlyText(e: Exception): String =
    e.message ?: e.javaClass.simpleName

private suspend fun readText(context: Context, uri: Uri): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("无法读取所选文件")
    return withContext(Dispatchers.IO) {
        stream.use { it.bufferedReader().readText() }
    }
}

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("关于") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("蓝阅 阅读器（架构骨架） v1.7-final")
                Text("§6.2 合规红线：本应用不提供、不存储、不分发任何内容；", style = TEXT_STYLE_DIM)
                Text("所有内容均来自用户自行导入的第三方源，使用风险由用户自行承担。", style = TEXT_STYLE_DIM)
                Text(context.getString(R.string.disclaimer_import), style = TEXT_STYLE_DIM)
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("知道") } }
    )
}
