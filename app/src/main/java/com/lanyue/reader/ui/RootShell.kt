package com.lanyue.reader.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lanyue.reader.bookshelf.ShelfOps.ShelfBook
import com.lanyue.reader.source.import.SourceImportParser.ImportedBookSource
import kotlin.math.roundToInt
/**
 * §2.5.1 常规底栏：书架、书源、订阅、影视、我的。
 *
 * 书源/订阅/影视三方状态由 SourceApp 导入产生，上提到 RootShell 持有，
 * 让订阅、影视两个发现页与书源页共享同一份导入结果（进程内演示态）。
 */
enum class MainTab(val label: String) {
    Shelf("书架"),
    Source("书源"),
    Subs("订阅"),
    Video("影视"),
    Mine("我的"),
}

@Composable
fun RootShell() {
    val context = LocalContext.current
    val density = LocalDensity.current
    var tab by remember { mutableStateOf(MainTab.Shelf) }
    var sourceCenter by remember { mutableStateOf(false) }
    var openSearch by remember { mutableStateOf(false) }
    var shelfHint by remember { mutableStateOf<ShelfBook?>(null) }
    val sources = remember { mutableStateListOf<ImportedBookSource>() }
    val videoSites = remember { mutableStateListOf<VideoSiteRow>() }

    val barHeightPx = with(density) { 64.dp.toPx() }
    var barOffsetPx by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize().weight(1f), color = MaterialTheme.colorScheme.background) {
            if (openSearch) {
                SearchScreen(onBack = { openSearch = false })
            } else if (sourceCenter) {
                SourceCenterScreen(onBack = { sourceCenter = false })
            } else {
                when (tab) {
                    MainTab.Shelf -> ShelfScreen(
                        onOpenBook = { book ->
                            // 书架 -> 书源页：以浮标提示该书的来源书源，找回入口走书源 Tab
                            shelfHint = book
                            tab = MainTab.Source
                        },
                        onOpenDownloads = { tab = MainTab.Mine },
                        onOpenSearch = { openSearch = true }
                    )
                    MainTab.Source -> SourceApp(
                        sources = sources,
                        videoSites = videoSites,
                        shelfHint = shelfHint,
                        onGoShelf = { shelfHint = null; tab = MainTab.Shelf }
                    )
                    MainTab.Subs -> SubsScreen(sources = sources)
                    MainTab.Video -> VideoTabScreen(sites = videoSites)
                    MainTab.Mine -> MineScreen(
                        onOpenSource = { sourceCenter = true }
                    )
                }
            }
        }
        NavigationBar(
            modifier = Modifier
                .offset { IntOffset(0, barOffsetPx.roundToInt()) }
                .alpha(1f - (barOffsetPx / barHeightPx).coerceIn(0f, 1f))
                .pointerInput(Unit) {
                    var dragFromY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragFromY = barOffsetPx },
                        onVerticalDrag = { change, drag ->
                            barOffsetPx = (dragFromY + drag).coerceIn(0f, barHeightPx)
                            change.consume()
                        },
                        onDragEnd = {
                            barOffsetPx = if (barOffsetPx > barHeightPx * 0.5f) barHeightPx else 0f
                        },
                    )
                }
        ) {
            MainTab.entries.forEach { t ->
                NavigationBarItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Text(t.label.take(1)) },
                    label = { Text(t.label) }
                )
            }
        }
    }
}
