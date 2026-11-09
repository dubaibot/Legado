package com.lanyue.reader.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.lanyue.reader.ui.RuntimeGraph
import kotlinx.coroutines.delay

/**
 * §6.1 音频书源播放器 UI。
 *
 * 复用 §4.9 PlayerManager（loadAudio 装载音频直链 URL，不注入字幕）；
 * 章节列表由调用方（ReaderScreen / ArticleScreen）提供，
 * 上一章/下一章按钮触发，避免在此区分场景。
 */
@Composable
internal fun AudioPlayerScreen(
    title: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { PlayerManager(context, RuntimeGraph.get(context).metrics) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        manager.ensurePlayer()
        manager.loadAudio(url, headers)
    }

    // 500ms 轮询 position / duration / isPlaying（media3 1.4.1 无连续 onPositionChanged）
    LaunchedEffect(manager) {
        while (true) {
            val p = manager.player
            if (p != null) {
                positionMs = p.currentPosition
                durationMs = p.duration.takeIf { it > 0L } ?: 0L
                isPlaying = p.isPlaying
            }
            delay(500L)
        }
    }

    DisposableEffect(Unit) {
        onDispose { manager.release() }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).also { pv ->
                    manager.player?.let { pv.setPlayer(it) }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (durationMs > 0L) {
            Slider(
                value = positionMs.toFloat(),
                onValueChange = { manager.seekTo(it.toLong()) },
                valueRange = 0F..durationMs.toFloat()
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(positionMs), style = MaterialTheme.typography.bodySmall)
                Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = { if (isPlaying) manager.pause() else manager.resume() }) {
                Text(if (isPlaying) "暂停" else "播放")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    // §4.9：把当前章转交给 AudioPlaybackService 后台播放，
                    // 通知栏/锁屏控件由 MediaSessionService 接管。
                    AudioPlaybackService.start(
                        context,
                        chapters = listOf(
                            AudioPlaybackService.AudioChapter(
                                title = title,
                                url = url,
                                headers = headers
                            )
                        )
                    )
                    onBack()
                }
            ) {
                Text("后台播放")
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
