package io.legado.app.ui.main.drama

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.drama.DramaSourceKind
import io.legado.app.data.drama.DramaSourceRef
import io.legado.app.data.drama.ShortDramaSourceStore
import io.legado.app.ui.theme.pageSecondaryTextColor

/**
 * 短剧源选择页。
 *
 * 只负责勾选「哪些已导入的源可作为短剧来源」，源自身的配置仍由各自的源管理页维护。
 */
class ShortDramaSourceActivity : BaseComposeActivity() {

    @Composable
    override fun ComposeContent() {
        val groups = remember { mutableStateMapOf<DramaSourceKind, List<DramaSourceRef>>() }
        val enabled = remember { mutableStateMapOf<String, Boolean>() }
        var loading by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            val available = ShortDramaSourceStore.availableAll()
            groups.clear()
            groups.putAll(available)
            val selected = ShortDramaSourceStore.enabledIds()
            enabled.clear()
            available.values.flatten().forEach { ref -> enabled[ref.id] = ref.id in selected }
            loading = false
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "短剧源",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "勾选后作为短剧信息流的内容来源",
                        fontSize = 12.sp,
                        color = pageSecondaryTextColor(),
                    )
                }
                TextButton(onClick = { finish() }) { Text("完成") }
            }
            Spacer(Modifier.height(8.dp))

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在读取源列表…", color = pageSecondaryTextColor())
                }
                return@Column
            }

            if (groups.values.all { it.isEmpty() }) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "还没有导入任何源，请先在书源/订阅/源管理页导入。",
                        color = pageSecondaryTextColor(),
                        modifier = Modifier.padding(32.dp),
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 32.dp
                ),
            ) {
                DramaSourceKind.entries.forEach { kind ->
                    val refs = groups[kind].orEmpty()
                    if (refs.isEmpty()) return@forEach
                    item(key = "header-${kind.name}") {
                        Text(
                            text = "${kind.label}（${refs.count { enabled[it.id] == true }}/${refs.size}）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = pageSecondaryTextColor(),
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(refs, key = { it.id }) { ref ->
                        SourceRow(
                            ref = ref,
                            checked = enabled[ref.id] == true,
                            onToggle = {
                                val next = ShortDramaSourceStore.toggle(ref)
                                enabled[ref.id] = next
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    ref: DramaSourceRef,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            text = ref.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = ref.kind.label,
            fontSize = 11.sp,
            color = pageSecondaryTextColor(),
        )
        Spacer(Modifier.size(4.dp))
    }
}