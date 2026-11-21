package io.legado.app.ui.main.drama

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    hostVisible: Boolean = true,
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
            state.searching && state.feed.isEmpty() -> CenterHint(text = "正在搜索…", showLoading = true)
            state.feed.isEmpty() -> EmptySources(
                error = state.error ?: "暂无短剧内容",
                onPickSources = openSources,
                onRefresh = { viewModel.refresh() },
            )
            else -> DramaFeed(
                state = state,
                scrollToTopTick = scrollToTopTick,
                hostVisible = hostVisible,
                bottomPaddingPx = bottomPaddingPx,
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
                onResolveEpisodePlayable = viewModel::resolveEpisodePlayable,
                onLoadPlaylist = viewModel::loadPlaylist,
                onCacheEpisodes = viewModel::cacheEpisodes,
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
            )
        }
        if (!state.cleanScreen && !state.needsSource) {
            DramaTopBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
                searchQuery = state.searchQuery,
                searching = state.searching,
                onSearch = viewModel::search,
                onClearSearch = viewModel::clearSearch,
                onOpenSources = openSources,
            )
        }
    }
}

/** 顶部搜索栏：关键字搜索短剧，常驻信息流之上。 */
@Composable
private fun DramaTopBar(
    modifier: Modifier = Modifier,
    searchQuery: String,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenSources: () -> Unit,
) {
    var keyword by remember(searchQuery) { mutableStateOf(searchQuery) }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("搜索短剧", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    IconButton(onClick = {
                        keyword = ""
                        onClearSearch()
                    }) {
                        Icon(Icons.Filled.Cancel, contentDescription = "清空")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                onSearch(keyword)
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Black.copy(alpha = 0.45f),
                unfocusedContainerColor = Color.Black.copy(alpha = 0.35f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
                focusedLeadingIconColor = Color.White.copy(alpha = 0.8f),
                unfocusedLeadingIconColor = Color.White.copy(alpha = 0.8f),
                focusedTrailingIconColor = Color.White,
                unfocusedTrailingIconColor = Color.White,
                cursorColor = Color.White,
            ),
            shape = RoundedCornerShape(20.dp),
        )
        if (searching) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(
                onClick = onOpenSources,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = "短剧源管理", tint = Color.White)
            }
        }
    }
}

@Composable
private fun DramaFeed(
    state: ShortDramaViewModel.UiState,
    scrollToTopTick: Int,
    hostVisible: Boolean,
    bottomPaddingPx: Int,
    onShown: (DramaCandidate, Int) -> Unit,
    onPlay: (DramaCandidate) -> Unit,
    onSkip: (DramaCandidate) -> Unit,
    onResolve: suspend (DramaCandidate) -> DramaPlayable?,
    onResolveEpisodes: suspend (DramaCandidate) -> List<DramaEpisode>,
    onResolveEpisodePlayable: suspend (DramaCandidate, DramaEpisode) -> DramaPlayable?,
    onLoadPlaylist: (DramaCandidate) -> Unit,
    onCacheEpisodes: (DramaCandidate, List<DramaEpisode>) -> Unit,
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
) {
    val pagerState = rememberPagerState(pageCount = { state.feed.size })
    val scope = rememberCoroutineScope()
    val bottomPaddingDp = with(LocalDensity.current) { bottomPaddingPx.toDp() }

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

    // 一集播完：自动续播只切本剧下一集，本剧没有下一集就停住；
    // 只有关闭自动续播时，自动上滑才切到下一部短剧。
    val handleEpisodeEnd: (DramaCandidate) -> Unit = { candidate ->
        if (state.autoNext) {
            onNextEpisode(candidate)
        } else if (state.autoSlide && pagerState.currentPage < state.feed.size - 1) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
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
                active = pagerState.currentPage == page && hostVisible,
                state = state,
                bottomPaddingDp = bottomPaddingDp,
                onPlay = { onPlay(candidate) },
                onSkip = { onSkip(candidate) },
                onResolve = onResolve,
                onResolveEpisodes = onResolveEpisodes,
                onResolveEpisodePlayable = { episode -> onResolveEpisodePlayable(candidate, episode) },
                onLoadPlaylist = { onLoadPlaylist(candidate) },
                onCacheEpisodes = { eps -> onCacheEpisodes(candidate, eps) },
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
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DramaPage(
    candidate: DramaCandidate,
    active: Boolean,
    state: ShortDramaViewModel.UiState,
    bottomPaddingDp: Dp,
    onPlay: () -> Unit,
    onSkip: () -> Unit,
    onResolve: suspend (DramaCandidate) -> DramaPlayable?,
    onResolveEpisodes: suspend (DramaCandidate) -> List<DramaEpisode>,
    onResolveEpisodePlayable: suspend (DramaEpisode) -> DramaPlayable?,
    onLoadPlaylist: () -> Unit,
    onCacheEpisodes: (List<DramaEpisode>) -> Unit,
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
    var selectedRoute by remember(candidate.seriesId) { mutableStateOf("") }
    var episodeEnded by remember(candidate.seriesId) { mutableStateOf(false) }
    // 已解析起播的集标识（seriesId:index:url），避免同一集重复请求直链
    var resolvedEpisodeKey by remember(candidate.seriesId) { mutableStateOf("") }
    // 长按 3 倍速：按住临时提速，松开还原
    var speedBeforeLongPress by remember(candidate.seriesId) { mutableStateOf<Float?>(null) }
    var longPressing by remember(candidate.seriesId) { mutableStateOf(false) }
    // 播放进度（每秒 2 次刷新，用于信息面板进度条）
    var playPositionMs by remember(candidate.seriesId) { mutableLongStateOf(0L) }
    var playDurationMs by remember(candidate.seriesId) { mutableLongStateOf(0L) }

    fun episodeKey(episode: DramaEpisode) =
        "${candidate.seriesId}:${episode.index}:${episode.url}"

    // 选集/续播统一出口：先把中间 id/time 解析成直链再交给播放器
    suspend fun applyEpisode(episode: DramaEpisode) {
        val key = episodeKey(episode)
        if (resolvedEpisodeKey == key) return
        resolvedEpisodeKey = key
        val fallbackHeaders = mapOf("Referer" to episode.url)
        val resolved = onResolveEpisodePlayable(episode)
        playable = resolved?.copy(headers = resolved.headers.ifEmpty { fallbackHeaders })
            ?: DramaPlayable(url = episode.url, headers = fallbackHeaders, title = episode.title)
        playFailed = false
        episodeEnded = false
    }

    val playlist = state.playlist?.takeIf { it.seriesId == candidate.seriesId }
    val chapterTitle = playlist?.episodes?.getOrNull(playlist.currentIndex)?.title
        ?: candidate.chapterTitle

    LaunchedEffect(playable?.url) {
        episodeEnded = false
        playPositionMs = 0L
        playDurationMs = 0L
    }

    LaunchedEffect(longPressing) {
        if (longPressing) return@LaunchedEffect
        val previous = speedBeforeLongPress ?: return@LaunchedEffect
        speedBeforeLongPress = null
        onSetSpeed(previous)
    }

    // 选集/自动续播改变当前集时，解析成直链后切播（仅激活页）
    LaunchedEffect(active, playlist?.seriesId, playlist?.currentIndex) {
        if (!active) return@LaunchedEffect
        val current = playlist ?: return@LaunchedEffect
        val episode = current.episodes.getOrNull(current.currentIndex) ?: return@LaunchedEffect
        // 首集已由 resolve() 起播：仅标记去重，避免重复请求
        if (current.currentIndex == 0 && playable != null && resolvedEpisodeKey.isEmpty()) {
            resolvedEpisodeKey = episodeKey(episode)
            return@LaunchedEffect
        }
        applyEpisode(episode)
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
        if (loaded.isNotEmpty()) onCacheEpisodes(loaded)
        episodesLoading = false
        episodesFailed = loaded.isEmpty()
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // 手势回调在 pointerInput 内异步执行，需用最新状态，避免读到组合时的旧值
    val currentCleanScreen by rememberUpdatedState(state.cleanScreen)
    val currentSetCleanScreen by rememberUpdatedState(onSetCleanScreen)

    val playingInline = playable != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .detectCleanScreenGesture(
                onExpand = {
                    if (!currentCleanScreen) {
                        currentSetCleanScreen(true)
                        toast("已开启清屏 · 双指内缩退出")
                    }
                },
                onShrink = {
                    if (currentCleanScreen) {
                        currentSetCleanScreen(false)
                        toast("已退出清屏")
                    }
                },
            )
            .then(if (playingInline) Modifier else Modifier.clickable(onClick = onPlay))
    ) {
        if (playingInline) {
            DramaVideoPlayer(
                playable = playable!!,
                active = active,
                speed = state.speed,
                autoNext = state.autoNext,
                autoSlide = state.autoSlide,
                onPlaybackError = {
                    playable = null
                    playFailed = true
                },
                onEpisodeEnd = {
                    // 自动续播：倒计时后切本剧下一集；仅自动上滑时直接翻到下一部
                    if (state.autoNext) {
                        episodeEnded = true
                    } else if (state.autoSlide) {
                        onEpisodeEnd()
                    }
                },
                onProgress = { pos, dur ->
                    onRecordProgress(playable?.url.orEmpty(), pos, dur)
                },
                onTick = { pos, dur ->
                    playPositionMs = pos
                    playDurationMs = dur
                },
                onLongPressStart = {
                    if (speedBeforeLongPress == null && state.speed != 3f) {
                        speedBeforeLongPress = state.speed
                        onSetSpeed(3f)
                    }
                    longPressing = true
                },
                onLongPressEnd = { longPressing = false },
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
                bottomPaddingDp = bottomPaddingDp,
                playPositionMs = playPositionMs,
                playDurationMs = playDurationMs,
                countdownActive = episodeEnded,
                playFailed = playFailed,
                playingInline = playingInline,
                onOpenDetail = onPlay,
                onShowEpisodes = {
                    selectedRoute = playlist?.episodes
                        ?.getOrNull(playlist.currentIndex)?.route.orEmpty()
                    showEpisodes = true
                },
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
            routes = episodes.mapNotNull { it.route.takeIf(String::isNotBlank) }.distinct(),
            selectedRoute = selectedRoute,
            currentIndex = playlist?.currentIndex ?: 0,
            loading = episodesLoading,
            failed = episodesFailed,
            onSelectRoute = { selectedRoute = it },
            onDismiss = { showEpisodes = false },
            onReload = {
                scope.launch {
                    episodesLoading = true
                    episodesFailed = false
                    episodes = onResolveEpisodes(candidate)
                    if (episodes.isNotEmpty()) onCacheEpisodes(episodes)
                    episodesLoading = false
                    episodesFailed = episodes.isEmpty()
                }
            },
            onSelect = { index ->
                val episode = episodes.getOrNull(index)
                onSelectEpisode(index)
                if (episode != null) {
                    scope.launch { applyEpisode(episode) }
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
    autoSlide: Boolean,
    onPlaybackError: () -> Unit,
    onEpisodeEnd: () -> Unit,
    onProgress: (Long, Long) -> Unit,
    onTick: (Long, Long) -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnError by rememberUpdatedState(onPlaybackError)
    val currentOnEnd by rememberUpdatedState(onEpisodeEnd)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnTick by rememberUpdatedState(onTick)
    // 播放器实例按页持有，切集时只替换 MediaSource：
    // 避免新建 ExoPlayer 导致 PlayerView 仍挂在旧实例上（有声音无画面）以及新实例不自动起播。
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(playable.url, playable.headers) {
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
        exoPlayer.setMediaSource(mediaSource)
        // 续播或上滑任一开启都不能循环单集，否则播完不出结束事件
        exoPlayer.repeatMode =
            if (autoNext || autoSlide) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        exoPlayer.setPlaybackSpeed(speed)
        exoPlayer.prepare()
        // 切集后 active 不变，负责起播的就只能是这里
        exoPlayer.playWhenReady = active
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

    LaunchedEffect(autoNext, autoSlide) {
        exoPlayer.repeatMode =
            if (autoNext || autoSlide) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }
    LaunchedEffect(speed) {
        exoPlayer.setPlaybackSpeed(speed)
    }
    LaunchedEffect(active, playable.url) {
        var tick = 0
        while (active) {
            delay(500L)
            val duration = exoPlayer.duration
            if (duration != C.TIME_UNSET && duration > 0) {
                val position = exoPlayer.currentPosition
                currentOnTick(position, duration)
                tick++
                // 每 10 个 tick（5 秒）落一次进度
                if (tick % 10 == 0) currentOnProgress(position, duration)
            }
        }
    }
    LaunchedEffect(active) {
        if (active) {
            exoPlayer.playWhenReady = true
        } else {
            // 必须清空 playWhenReady，否则回前台时播放器会自动续播
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        exoPlayer.playWhenReady = false
        exoPlayer.pause()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (active) exoPlayer.playWhenReady = true
    }

    val currentLongPressStart by rememberUpdatedState(onLongPressStart)
    val currentLongPressEnd by rememberUpdatedState(onLongPressEnd)

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = exoPlayer
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) view.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(exoPlayer) {
                    detectTapGestures(
                        onTap = {
                            exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                        },
                        onLongPress = { currentLongPressStart() },
                        onPress = {
                            tryAwaitRelease()
                            currentLongPressEnd()
                        },
                    )
                },
        )
    }
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
            icon = Icons.Filled.Cancel,
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
    bottomPaddingDp: Dp,
    playPositionMs: Long,
    playDurationMs: Long,
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
        modifier = modifier.padding(
            start = 20.dp,
            end = 72.dp,
            bottom = 40.dp + bottomPaddingDp + 12.dp,
        ),
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
        if (playingInline && playDurationMs > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    (playPositionMs.toFloat() / playDurationMs.toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
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
        if (!playFailed) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onShowEpisodes) { Text("观看全集") }
        }
        if (countdownActive) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${countdown} 秒后自动播放下一集",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
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
    routes: List<String>,
    selectedRoute: String,
    currentIndex: Int,
    loading: Boolean,
    failed: Boolean,
    onSelectRoute: (String) -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val visible = remember(episodes, selectedRoute) {
        if (selectedRoute.isBlank()) episodes.withIndex().toList()
        else episodes.withIndex().filter { it.value.route == selectedRoute || it.value.route.isBlank() }
    }
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
            if (routes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    routes.forEach { route ->
                        FilterChip(
                            selected = route == selectedRoute,
                            onClick = { onSelectRoute(route) },
                            label = { Text(route, fontSize = 12.sp, maxLines = 1) },
                        )
                    }
                }
            }
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
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible.size) { position ->
                        val (index, episode) = visible[position]
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
                                text = episode.title.ifBlank { "第${index + 1}集" },
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
            Text(
                text = "也可双指外扩进入清屏、双指内缩退出清屏",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
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

/**
 * 双指缩放手势：双指外扩进入清屏、双指内缩退出清屏。
 * 单指事件不消费，交给 VerticalPager 上下滑与点击；每个手势只触发一次，避免抖动反复切换。
 */
private fun Modifier.detectCleanScreenGesture(
    onExpand: () -> Unit,
    onShrink: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    val expandRatio = 1.30f
    val shrinkRatio = 0.75f
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var baseSpan = 0f
        var triggered = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pointers = event.changes.filter { it.pressed }
            if (pointers.size < 2) {
                if (pointers.isEmpty()) break
                continue
            }
            pointers.forEach { it.consume() }
            val span = (pointers[0].position - pointers[1].position).getDistance()
            if (baseSpan == 0f) {
                baseSpan = span
            } else if (!triggered && baseSpan > 1f) {
                val ratio = span / baseSpan
                if (ratio >= expandRatio) {
                    triggered = true
                    onExpand()
                } else if (ratio <= shrinkRatio) {
                    triggered = true
                    onShrink()
                }
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
