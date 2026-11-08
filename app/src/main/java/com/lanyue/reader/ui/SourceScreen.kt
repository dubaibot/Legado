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
import com.lanyue.reader.source.debug.SourceDebugger
import com.lanyue.reader.source.debug.SourceDebugger.PipelineReport
import com.lanyue.reader.source.debug.SourceDebugger.Status
import com.lanyue.reader.source.import.SourceImportParser
import com.lanyue.reader.source.import.SourceImportParser.ImportedBookSource
import com.lanyue.reader.source.import.SourceImportParser.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

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

/** 书源 JSON 字段别名（与 SourceImportParser 保持一致）。 */
private val FIELD_URL_KEYS = listOf("bookSourceUrl", "sourceUrl", "url", "bookSourceURL")
private val FIELD_NAME_KEYS = listOf("bookSourceName", "sourceName", "name", "title")
private val FIELD_TYPE_KEYS = listOf("bookSourceType", "sourceType", "type")

private val TYPE_LABELS = listOf("文本", "音频", "图片", "文件")

private val TEXT_STYLE_DIM = TextStyle(color = Color(0xFF808080))
private val TEXT_STYLE_BAD = TextStyle(color = Color(0xFFB00020))

@Composable
fun SourceApp() {
    val sources = remember { mutableStateListOf<ImportedBookSource>() }
    var lastImport by remember { mutableStateOf<ImportResult?>(null) }
    var readStatus by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<ImportedBookSource?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    Box(Modifier.fillMaxSize()) {
        if (detail != null) {
            DetailScreen(
                source = detail!!,
                onBack = { detail = null }
            )
        } else {
            HomeScreen(
                sources = sources,
                lastImport = lastImport,
                readStatus = readStatus,
                onImport = { filePicker.launch("*/*") },
                onOpen = { detail = it },
                onDismissImport = { lastImport = null; readStatus = null }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    sources: List<ImportedBookSource>,
    lastImport: ImportResult?,
    readStatus: String?,
    onImport: () -> Unit,
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
private fun ImportedBookSource.fields(): BookFields {
    val obj = try {
        JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Throwable) {
        null
    }
    return BookFields(
        url = firstStr(obj, FIELD_URL_KEYS) ?: url,
        name = firstStr(obj, FIELD_NAME_KEYS) ?: name,
        searchUrl = firstStr(obj, listOf("searchUrl")),
        ruleSearch = firstStr(obj, listOf("ruleSearch")),
        ruleToc = firstStr(obj, listOf("ruleToc")),
        ruleContent = firstStr(obj, listOf("ruleContent")),
        type = firstInt(obj, FIELD_TYPE_KEYS) ?: type
    )
}

private data class BookFields(
    val url: String?,
    val name: String?,
    val searchUrl: String?,
    val ruleSearch: String?,
    val ruleToc: String?,
    val ruleContent: String?,
    val type: Int
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
