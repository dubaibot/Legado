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
import androidx.compose.runtime.getValue
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("我的", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))

        TextButton(onClick = onOpenSource, modifier = Modifier.fillMaxWidth()) {
            MineRow("源管理", "书源 / 订阅 / 影视 / 弹幕")
        }
        MineRow("缓存管理", "媒体缓存服务已就绪（§4.11 MediaDownloadService），任务界面落地中")
        TextButton(onClick = { backupOpen = true }, modifier = Modifier.fillMaxWidth()) {
            MineRow("备份恢复", "导出 / 导入本地备份")
        }
        MineRow("设置中心", "一级设置项落地中，当前默认见 §2 / §6 验收单")
        TextButton(onClick = { aboutOpen = true }, modifier = Modifier.fillMaxWidth()) {
            MineRow("关于", "版本与声明")
        }
    }

    if (backupOpen) {
        BackupPanel(onClose = { backupOpen = false })
    }
    if (aboutOpen) {
        AboutDialog(onClose = { aboutOpen = false })
    }
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
