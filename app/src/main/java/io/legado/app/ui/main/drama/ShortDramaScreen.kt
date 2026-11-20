package io.legado.app.ui.main.drama

import android.app.Activity
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import io.legado.app.data.drama.DramaCandidate
import io.legado.app.data.drama.DramaEpisode
import io.legado.app.data.drama.DramaPlayable
import io.legado.app.data.drama.DramaSeriesPlaylist
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.exoplayer.mediaMimeTypeOfUrl
import io.legado.app.help.http.videoStreamClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 短剧竖滑信息流：一屏一条，上下滑动切换，页内直接播放。
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
                onResolve = viewModel::resolvePlayable,
                onResolveEpisodes = viewModel::resolveEpisodes,
                onLoadPlaylist = viewModel::loadPlaylist,
                onSelectEpisode = viewModel::selectEpisode,
                onNextEpisode = viewModel::nextEpisode,
                onRecordProgress = viewModel::recordProgress,
                onToggleLike = viewModel::toggleLike,
                onIsLiked = viewModel::isLiked,
                onIsInBookshelf = viewModel::isInBookshelf,
                onToggleBookshelf = viewModel::toggleBookshelf,
                onSetSpeed = viewModel::setSpeed,
                onSetCleanScreen = viewModel::setCleanScreen,
                onSetAutoSlide = viewModel::setAutoSlide,
                onSetAutoNext = viewModel::setAutoNext,
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
    onResolve: suspend (DramaCandidate) -> DramaPlayable?,
    onResolveEpisodes: suspend (DramaCandidate) -> List<DramaEpisode>,
    onLoadPlaylist: (DramaCandidate) -> Unit,
    onSelectEpisode: (DramaCandidate, Int) -> Unit,
    onNextEpisode: (DramaCandidate) -> Boolean,
    onRecordProgress: (DramaCandidate, String, Long, Long) -> Unit,
    onToggleLike: (DramaCandidate, Boolean) -> Unit,
    onIsLiked: suspend (String) -> Boolean,
    onIsInBookshelf: suspend (DramaCandidate) -> Boolean,
    onToggleBookshelf: (DramaCandidate, Boolean) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetCleanScreen: (Boolean) -> Unit,
    onSetAutoSlide: (Boolean) -> Unit,
    onSetAutoNext: (Boolean) -> Unit,
    onOpenSources: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { state.feed.size })
    val scope = rememberCoroutineScope()

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

    // 一集播完：autoNext 有下一集就切集，否则 autoSlide 滑到下一部
    val handleEpisodeEnd: (DramaCandidate) -> Unit = { candidate ->
        if (!state.autoNext || !onNextEpisode(candidate)) {
            if (state.autoSlide && pagerState.currentPage < state.feed.size - 1) {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            }
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
                active = pagerState.currentPage == page,
                state = state,
                onPlay = { onPlay(candidate) },
                onSkip = { onSkip(candidate) },
                onResolve = onResolve,
                onResolveEpisodes = onResolveEpisodes,
                onLoadPlaylist = { onLoadPlaylist(candidate) },
                onSelectEpisode = { index -> onSelectEpisode(candidate, index) },
                onEpisodeEnd = { handleEpisodeEnd(candidate) },
                onRecordProgress = { id, pos, dur -> onRecordProgress(candidate, id, pos, dur) },
                onToggleLike = { liked -> onToggleLike(candidate, liked) },
                onIsLiked = onIsLiked,
                onIsInBookshelf = { onIsInBookshelf(candidate) },
                onToggleBookshelf = { add -> onToggleBookshelf(candidate, add) },
                onSetSpeed = onSetSpeed,
                onSetCleanScreen = onSetCleanScreen,
                onSetAutoSlide = onSetAutoSlide,
                onSetAutoNext = onSetAutoNext,
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
    active: Boolean,
    state: ShortDramaViewModel.UiState,
    onPlay: () -> Unit,
    onSkip: () -> Unit,
    onResolve: suspend (DramaCandidate) -> DramaPlayable?,
    onResolveEpisodes: suspend (DramaCandidate) -> List<DramaEpisode>,
    onLoadPlaylist: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onEpisodeEnd: () -> Unit,
    onRecordProgress: (String, Long, Long) -> Unit,
    onToggleLike: (Boolean) -> Unit,
    onIsLiked: suspend (String) -> Boolean,
    onIsInBookshelf: suspend () -> Boolean,
    onToggleBookshelf: (Boolean) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetCleanScreen: (Boolean) -> Unit,
    onSetAutoSlide: (Boolean) -> Unit,
    onSetAutoNext: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playable by remember(candidate.seriesId) { mutableStateOf<DramaPlayable?>(null) }
    var resolving by remember(candidate.seriesId) { mutableStateOf(false) }
    var playFailed by remember(candidate.seriesId) { mutableStateOf(false) }
    var liked by remember(candidate.seriesId) { mutableStateOf(false) }
    var inBookshelf by remember(candidate.seriesId) { mutableStateOf(false) }
    var showEpisodes by remember(candidate.seriesId) { mutableStateOf(false) }
    var showMore by remember(candidate.seriesId) { mutableStateOf(false) }
    var episodes by remember(candidate.seriesId) { mutableStateOf<List<DramaEpisode>>(emptyList()) }
    var episodesLoading by remember(candidate.seriesId) { mutableStateOf(false) }
    var episodesFailed by remember(candidate.seriesId) { mutableStateOf(false) }
    var episodeEnded by remember(candidate.seriesId) { mutableStateOf(false) }

    val playlist = state.playlist?.takeIf { it.seriesId == candidate.seriesId }
    val chapterTitle = playlist?.episodes?.getOrNull(playlist.currentIndex)?.title
        ?: candidate.chapterTitle

    LaunchedEffect(playable?.url) {
        episodeEnded = false
    }

    // 选集/自动续播改变当前集时，把播放器切到该集直链（仅激活页）
    LaunchedEffect(active, playlist?.seriesId, playlist?.currentIndex) {
        if (!active) return@LaunchedEffect
        val current = playlist ?: return@LaunchedEffect
        val episode = current.episodes.getOrNull(current.currentIndex) ?: return@LaunchedEffect
        if (playable?.url != episode.url) {
            playable = DramaPlayable(
                url = episode.url,
                headers = mapOf("Referer" to episode.url),
                title = episode.title,
            )
            playFailed = false
            episodeEnded = false
        }
    }

    LaunchedEffect(active, candidate.seriesId) {
        onLoadPlaylist()
        if (!active || playable != null || resolving) return@LaunchedEffect
        resolving = true
        val resolved = onResolve(candidate)
        resolving = false
        if (resolved != null) {
            if (playable == null) playable = resolved
        } else if (playable == null) {
            playFailed = true
        }
    }

    LaunchedEffect(active, candidate.seriesId) {
        liked = onIsLiked(candidate.seriesId)
        inBookshelf = onIsInBookshelf()
    }

    LaunchedEffect(showEpisodes, candidate.seriesId) {
        if (!showEpisodes || episodes.isNotEmpty()) return@LaunchedEffect
        episodesLoading = true
        episodesFailed = false
        val loaded = onResolveEpisodes(candidate)
        episodes = loaded
        episodesLoading = false
        episodesFailed = loaded.isEmpty()
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    val playingInline = playable != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (playingInline) Modifier else Modifier.clickable(onClick = onPlay))
    ) {
        if (playingInline) {
            DramaVideoPlayer(
                playable = playable!!,
                active = active,
                speed = state.speed,
                autoNext = state.autoNext,
                onPlaybackError = {
                    playable = null
                    playFailed = true
                },
                onEpisodeEnd = {
                    if (state.autoNext || state.autoSlide) episodeEnded = true
                },
                onProgress = { pos, dur ->
                    onRecordProgress(playable?.url.orEmpty(), pos, dur)
                },
            )
        } else if (candidate.cover.isNotBlank()) {
            GlideImage(
                model = candidate.cover,
                contentDescription = candidate.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent)
                    )
                )
        )
        if (resolving && !playingInline) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        if (!state.cleanScreen) {
            DramaActionRail(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                liked = liked,
                inBookshelf = inBookshelf,
                onLike = {
                    val next = !liked
                    liked = next
                    onToggleLike(next)
                    toast(if (next) "已加强推荐" else "已取消推荐")
                },
                onBookshelf = {
                    val add = !inBookshelf
                    inBookshelf = add
                    onToggleBookshelf(add)
                    toast(if (add) "已加入书架" else "已移出书架")
                },
                onMore = { showMore = true },
                onSkip = onSkip,
            )
            DramaInfoPanel(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                candidate = candidate,
                active = active,
                chapterTitle = chapterTitle,
                episodeCount = playlist?.episodes?.size ?: candidate.episodeCount,
                hasPlaylist = !playlist?.episodes.isNullOrEmpty(),
                countdownActive = episodeEnded,
                playFailed = playFailed,
                playingInline = playingInline,
                onOpenDetail = onPlay,
                onShowEpisodes = { showEpisodes = true },
                onNext = {
                    episodeEnded = false
                    onEpisodeEnd()
                },
            )
        }
    }

    if (showEpisodes) {
        DramaEpisodeSheet(
            title = candidate.title,
            episodes = episodes,
            currentIndex = playlist?.currentIndex ?: 0,
            loading = episodesLoading,
            failed = episodesFailed,
            onDismiss = { showEpisodes = false },
            onReload = {
                scope.launch {
                    episodesLoading = true
                    episodesFailed = false
                    episodes = onResolveEpisodes(candidate)
                    episodesLoading = false
                    episodesFailed = episodes.isEmpty()
                }
            },
            onSelect = { index ->
                val episode = episodes.getOrNull(index)
                onSelectEpisode(index)
                if (episode != null) {
                    playable = DramaPlayable(
                        url = episode.url,
                        headers = mapOf("Referer" to episode.url),
                        title = episode.title,
                    )
                    playFailed = false
                }
                showEpisodes = false
            },
        )
    }

    if (showMore) {
        DramaMoreSheet(
            speed = state.speed,
            cleanScreen = state.cleanScreen,
            autoSlide = state.autoSlide,
            autoNext = state.autoNext,
            onDismiss = { showMore = false },
            onSpeed = onSetSpeed,
            onCleanScreen = onSetCleanScreen,
            onAutoSlide = onSetAutoSlide,
            onAutoNext = onSetAutoNext,
        )
    }
}

/**
 * 页内播放器：ExoPlayer + PlayerView，关闭默认控制器以保持沉浸感。
 */
@Composable
private fun DramaVideoPlayer(
    playable: DramaPlayable,
    active: Boolean,
    speed: Float,
    autoNext: Boolean,
    onPlaybackError: () -> Unit,
    onEpisodeEnd: () -> Unit,
    onProgress: (Long, Long) -> Unit,
) {
    val context = LocalContext.current
    val currentOnError by rememberUpdatedState(onPlaybackError)
    val currentOnEnd by rememberUpdatedState(onEpisodeEnd)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val exoPlayer = remember(playable.url, playable.headers) {
        val requestHeaders = HashMap(playable.headers)
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestHeaders["User-Agent"] = ExoPlayerHelper.BROWSER_UA
        }
        val dataSourceFactory = OkHttpDataSource.Factory(videoStreamClient)
            .setDefaultRequestProperties(requestHeaders)
        val mime = mediaMimeTypeOfUrl(playable.url)
        val mediaItem = MediaItem.Builder()
            .setUri(playable.url)
            .apply { mime?.let { setMimeType(it) } }
            .build()
        val mediaSource = if (mime == MimeTypes.APPLICATION_M3U8) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            repeatMode = if (autoNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            setPlaybackSpeed(speed)
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                currentOnError()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) currentOnEnd()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(autoNext) {
        exoPlayer.repeatMode = if (autoNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }
    LaunchedEffect(speed) {
        exoPlayer.setPlaybackSpeed(speed)
    }
    LaunchedEffect(active, playable.url) {
        while (active) {
            delay(5_000L)
            val duration = exoPlayer.duration
            if (duration != C.TIME_UNSET && duration > 0) {
                currentOnProgress(exoPlayer.currentPosition, duration)
            }
        }
    }
    LaunchedEffect(active) {
        if (active) exoPlayer.playWhenReady = true else exoPlayer.pause()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { exoPlayer.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (active) exoPlayer.playWhenReady = true else exoPlayer.pause()
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = exoPlayer
                setOnClickListener {
                    exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** 右侧竖排操作栏：爱心（加权）/ 收藏（加书架）/ 更多。 */
@Composable
private fun DramaActionRail(
    modifier: Modifier = Modifier,
    liked: Boolean,
    inBookshelf: Boolean,
    onLike: () -> Unit,
    onBookshelf: () -> Unit,
    onMore: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RailAction(
            icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            label = "喜欢",
            tint = if (liked) Color(0xFFFF4D6A) else Color.White,
            onClick = onLike,
        )
        Spacer(Modifier.height(18.dp))
        RailAction(
            icon = if (inBookshelf) Icons.Filled.Star else Icons.Filled.StarBorder,
            label = "收藏",
            tint = if (inBookshelf) Color(0xFFFFD54F) else Color.White,
            onClick = onBookshelf,
        )
        Spacer(Modifier.height(18.dp))
        RailAction(
            icon = Icons.Filled.MoreVert,
            label = "更多",
            tint = Color.White,
            onClick = onMore,
        )
        Spacer(Modifier.height(18.dp))
        RailAction(
            icon = Icons.Filled.Close,
            label = "不感兴趣",
            tint = Color.White,
            onClick = onSkip,
        )
    }
}

@Composable
private fun RailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(30.dp))
        }
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DramaInfoPanel(
    modifier: Modifier = Modifier,
    candidate: DramaCandidate,
    active: Boolean,
    countdownActive: Boolean,
    chapterTitle: String,
    episodeCount: Int,
    hasPlaylist: Boolean,
    playFailed: Boolean,
    playingInline: Boolean,
    onOpenDetail: () -> Unit,
    onShowEpisodes: () -> Unit,
    onNext: () -> Unit,
) {
    var expanded by remember(candidate.seriesId) { mutableStateOf(false) }
    var countdown by remember(candidate.seriesId) { mutableIntStateOf(4) }

    // 一集播完后开始 4 秒倒计时，到 0 自动切下一集（播放中不触发）
    LaunchedEffect(countdownActive, active, chapterTitle) {
        if (!countdownActive || !active) {
            countdown = 4
            return@LaunchedEffect
        }
        countdown = 4
        while (countdown > 0) {
            delay(1_000L)
            countdown--
        }
        onNext()
    }

    Column(
        modifier = modifier.padding(start = 20.dp, end = 72.dp, bottom = 40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onOpenDetail)) {
            GlideImage(
                model = candidate.cover,
                contentDescription = candidate.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${candidate.title} >",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "新剧",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE91E63))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (episodeCount > 1) {
                Text("共 $episodeCount 集", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            candidate.tags.take(2).forEach { tag ->
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
        Spacer(Modifier.height(8.dp))
        val introText = buildString {
            if (chapterTitle.isNotBlank()) append(chapterTitle)
            if (isNotBlank() && candidate.intro.isNotBlank()) append("｜")
            append(candidate.intro)
        }
        if (introText.isNotBlank()) {
            Text(
                text = introText,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (expanded) "收起" else "展开",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
        if (playFailed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "播放解析失败，点击画面用外部播放器打开",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
        if (!playingInline) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpenDetail) { Text("立即观看") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onShowEpisodes) { Text("观看全集") }
            OutlinedButton(
                onClick = onNext,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White.copy(alpha = 0.9f)
                ),
                enabled = hasPlaylist,
            ) {
                Text(if (countdownActive) "下一集($countdown)" else "下一集")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "作者声明：内容由 AI 生成",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
        )
    }
}

/** 选集弹窗：网格列出直链章节，点击切集。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DramaEpisodeSheet(
    title: String,
    episodes: List<DramaEpisode>,
    currentIndex: Int,
    loading: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选集", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                failed || episodes.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("暂无选集", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onReload) { Text("重试") }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(episodes.size) { index ->
                        val selected = index == currentIndex
                        Button(
                            onClick = { onSelect(index) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (selected) {
                                ButtonDefaults.buttonColors()
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        ) {
                            Text(
                                text = "第${index + 1}集",
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 更多面板：倍速 / 清屏 / 自动上滑 / 自动续播。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DramaMoreSheet(
    speed: Float,
    cleanScreen: Boolean,
    autoSlide: Boolean,
    autoNext: Boolean,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onCleanScreen: (Boolean) -> Unit,
    onAutoSlide: (Boolean) -> Unit,
    onAutoNext: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("更多", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("倍速", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1f, 1.5f, 2f, 3f).forEach { value ->
                    FilterChip(
                        selected = speed == value,
                        onClick = { onSpeed(value) },
                        label = { Text("${value}x", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SwitchRow("清屏", cleanScreen, onCleanScreen)
            SwitchRow("自动上滑", autoSlide, onAutoSlide)
            SwitchRow("短剧自动续播", autoNext, onAutoNext)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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
