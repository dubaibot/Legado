package io.legado.app.ui.main.drama

import android.app.Activity
import android.content.Intent
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import io.legado.app.data.drama.DramaCandidate
import kotlinx.coroutines.launch

/**
 * 短剧竖滑信息流：一屏一条，上下滑动切换，点击进入播放。
 */
@Composable
fun ShortDramaScreen(
    bottomPaddingPx: Int,
    scrollToTopTick: Int = 0,
    viewModel: ShortDramaViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val bottomPadding = with(LocalDensity.current) { bottomPaddingPx.dp }

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    LifecycleResumeEffect(Unit) {
        viewModel.onSourcesMaybeChanged()
        onPauseOrDispose { }
    }

    val openSources: () -> Unit = {
        context.startActivity(Intent(context, ShortDramaSourceActivity::class.java))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(bottom = bottomPadding)
    ) {
        when {
            state.loading -> CenterHint(text = "正在准备短剧…", showLoading = true)
            state.needsSource -> EmptySources(
                onPickSources = openSources,
                onRefresh = viewModel::onSourcesMaybeChanged,
            )
            state.feed.isEmpty() -> EmptySources(
                error = state.error ?: "暂无短剧内容",
                onPickSources = openSources,
                onRefresh = viewModel::refresh,
            )
            else -> DramaFeed(
                state = state,
                scrollToTopTick = scrollToTopTick,
                onShown = viewModel::onPageShown,
                onPlay = { candidate ->
                    viewModel.onPlay(candidate)
                    activity?.let { host ->
                        scope.launch { ShortDramaLauncher.open(host, candidate) }
                    }
                },
                onSkip = viewModel::onSkip,
                onOpenSources = openSources,
            )
        }
    }
}

@Composable
private fun DramaFeed(
    state: ShortDramaViewModel.UiState,
    scrollToTopTick: Int,
    onShown: (DramaCandidate, Int) -> Unit,
    onPlay: (DramaCandidate) -> Unit,
    onSkip: (DramaCandidate) -> Unit,
    onOpenSources: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { state.feed.size })

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { index ->
            state.feed.getOrNull(index)?.let { onShown(it, index) }
        }
    }

    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0 && pagerState.currentPage != 0) {
            pagerState.animateScrollToPage(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            val candidate = state.feed[page]
            DramaPage(
                candidate = candidate,
                onPlay = { onPlay(candidate) },
                onSkip = { onSkip(candidate) },
            )
        }
        IconButton(
            onClick = onOpenSources,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 8.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        ) {
            Icon(Icons.Filled.Tune, contentDescription = "短剧源管理", tint = Color.White)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DramaPage(
    candidate: DramaCandidate,
    onPlay: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onPlay)
    ) {
        if (candidate.cover.isNotBlank()) {
            GlideImage(
                model = candidate.cover,
                contentDescription = candidate.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.9f),
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 72.dp, bottom = 40.dp),
        ) {
            Text(
                text = candidate.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = candidate.source.name,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                if (candidate.episodeCount > 1) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "共 ${candidate.episodeCount} 集",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                    )
                }
            }
            if (candidate.tags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidate.tags.take(3).forEach { tag ->
                        Text(
                            text = tag,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlay) { Text("立即观看") }
                OutlinedButton(
                    onClick = onSkip,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White.copy(alpha = 0.85f)
                    ),
                ) { Text("不感兴趣") }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, showLoading: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
            }
            Text(text = text, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        }
    }
}

@Composable
private fun EmptySources(
    error: String? = null,
    onPickSources: () -> Unit,
    onRefresh: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = error ?: "还没有可用的短剧源",
                color = Color.White,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "从已导入的书源、订阅源、PY 源、TVBOX 源里挑选几个，作为短剧内容来源。",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPickSources) { Text("选择短剧源") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onRefresh,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White.copy(alpha = 0.85f)
                ),
            ) { Text("重新加载") }
        }
    }
}