package io.legado.app.ui.main.drama

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.drama.DramaCandidate
import io.legado.app.data.drama.DramaEpisode
import io.legado.app.data.drama.DramaHistorySnapshot
import io.legado.app.data.drama.DramaPlayable
import io.legado.app.data.drama.DramaSeriesPlaylist
import io.legado.app.data.drama.DramaSourceRef
import io.legado.app.data.drama.ShortDramaCandidateProvider
import io.legado.app.data.drama.ShortDramaHistoryStore
import io.legado.app.data.drama.ShortDramaPlaybackResolver
import io.legado.app.data.drama.ShortDramaRecommendEngine
import io.legado.app.data.drama.ShortDramaSourceStore
import io.legado.app.data.entities.ShortDramaAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 短剧信息流 ViewModel。
 *
 * 职责：
 * - 管理已勾选的短剧源与候选池
 * - 通过 [ShortDramaRecommendEngine] 分批产出（默认 10 条一批）
 * - 记录曝光 / 开始播放 / 完播 / 快速划走，供下一批去重与偏好使用
 */
class ShortDramaViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val needsSource: Boolean = false,
        val sources: List<DramaSourceRef> = emptyList(),
        val feed: List<DramaCandidate> = emptyList(),
        val error: String? = null,
        val ended: Boolean = false,
        val supplement: Boolean = false,
        val playlist: DramaSeriesPlaylist? = null,
        val speed: Float = 1f,
        val cleanScreen: Boolean = false,
        val autoSlide: Boolean = true,
        val autoNext: Boolean = true,
        val searchQuery: String = "",
        val searching: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val sessionExposed = LinkedHashSet<String>()
    private val sessionSkips = HashSet<String>()
    private var candidatePool: List<DramaCandidate> = emptyList()
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    // 候选池缓存：同一批源短时间内复用，避免每次进入都重新拉取
    private var cachedPool: List<DramaCandidate> = emptyList()
    private var cachedPoolSignature: String = ""
    private var cachedPoolAt: Long = 0L
    private val poolCacheTtlMs = 60_000L

    /** 页内播放解析缓存：key = seriesId；解析失败不缓存，滑动回来可重试 */
    private val playableCache = HashMap<String, DramaPlayable>()

    init {
        _state.update {
            it.copy(
                speed = ShortDramaPrefs.getSpeed(),
                cleanScreen = ShortDramaPrefs.getCleanScreen(),
                autoSlide = ShortDramaPrefs.getAutoSlide(),
                autoNext = ShortDramaPrefs.getAutoNext(),
            )
        }
    }

    fun ensureLoaded() {
        if (_state.value.loading) return
        if (_state.value.feed.isEmpty()) refresh(force = false)
    }

    fun refresh() = refresh(force = true)

    private fun refresh(force: Boolean) {
        if (force) {
            searchJob?.cancel()
            searchJob = null
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, ended = false) }
            val sources = ShortDramaSourceStore.enabled()
            if (sources.isEmpty()) {
                _state.update {
                    it.copy(loading = false, sources = emptyList(), needsSource = true, feed = emptyList())
                }
                return@launch
            }
            val signature = sources.joinToString("|") { it.id }
            val now = System.currentTimeMillis()
            val usableCache = !force && signature == cachedPoolSignature &&
                now - cachedPoolAt < poolCacheTtlMs && cachedPool.isNotEmpty()
            val pool = if (usableCache) {
                cachedPool
            } else {
                ShortDramaCandidateProvider.load(sources).also {
                    cachedPool = it
                    cachedPoolSignature = signature
                    cachedPoolAt = System.currentTimeMillis()
                }
            }
            candidatePool = pool
            sessionExposed.clear()
            sessionSkips.clear()
            if (pool.isEmpty()) {
                _state.update {
                    it.copy(
                        loading = false,
                        sources = sources,
                        needsSource = false,
                        feed = emptyList(),
                        error = "所选源没有返回内容",
                    )
                }
                return@launch
            }
            val snapshot = ShortDramaHistoryStore.snapshot()
            val ranked = ShortDramaRecommendEngine.rank(
                candidates = pool,
                snapshot = snapshot,
                sessionExposed = emptyList(),
                currentFeed = emptyList(),
            )
            _state.update {
                it.copy(
                    loading = false,
                    sources = sources,
                    needsSource = false,
                    feed = ranked.candidates,
                    error = null,
                    ended = ranked.candidates.isEmpty(),
                    supplement = ranked.supplement,
                )
            }
            ranked.candidates.firstOrNull()?.let { recordExposure(it, 0) }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.searchQuery.isNotEmpty()) return
        if (current.loading || current.loadingMore || current.ended) return
        if (candidatePool.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val snapshot = ShortDramaHistoryStore.snapshot(
                sessionSkips = sessionSkips,
            )
            val ranked = ShortDramaRecommendEngine.rank(
                candidates = candidatePool,
                snapshot = snapshot,
                sessionExposed = sessionExposed.toList(),
                currentFeed = current.feed,
            )
            if (ranked.candidates.isEmpty()) {
                _state.update { it.copy(loadingMore = false, ended = true) }
            } else {
                _state.update {
                    it.copy(
                        loadingMore = false,
                        feed = it.feed + ranked.candidates,
                        supplement = ranked.supplement,
                    )
                }
            }
        }
    }

    /** 关键字搜索：替换信息流为搜索结果；清空后恢复推荐。 */
    fun search(keyword: String) {
        val key = keyword.trim()
        if (key.isEmpty()) {
            clearSearch()
            return
        }
        loadJob?.cancel()
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = key, searching = true, error = null) }
        searchJob = viewModelScope.launch {
            val sources = ShortDramaSourceStore.enabled()
            val results = ShortDramaCandidateProvider.search(key, sources)
            val ranked = ShortDramaRecommendEngine.rank(
                candidates = results,
                snapshot = DramaHistorySnapshot.EMPTY,
                sessionExposed = emptyList(),
                currentFeed = emptyList(),
                limit = results.size.coerceAtLeast(1),
            )
            _state.update {
                it.copy(
                    searching = false,
                    feed = ranked.candidates,
                    ended = true,
                    error = if (ranked.candidates.isEmpty()) "没有找到相关短剧" else null,
                )
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        if (_state.value.searchQuery.isEmpty() && !_state.value.searching) return
        _state.update { it.copy(searchQuery = "", searching = false, error = null) }
        refresh(force = false)
    }

    fun onPageShown(candidate: DramaCandidate, index: Int) {
        if (candidate.seriesId in sessionExposed) return
        viewModelScope.launch { recordExposure(candidate, index) }
        if (index >= _state.value.feed.size - 3) loadMore()
    }

    private suspend fun recordExposure(candidate: DramaCandidate, index: Int) {
        sessionExposed.add(candidate.seriesId)
        ShortDramaHistoryStore.record(
            seriesId = candidate.seriesId,
            sourceId = candidate.source.id,
            action = ShortDramaAction.EXPOSURE,
            tags = candidate.tags,
        )
    }

    /**
     * 按源类别解析页内可播地址（视频源 detail/play；订阅源抓页+嗅探；书源目录首章）。
     *
     * 解析结果按 seriesId 缓存，失败（超时、无线路、空地址）返回 null，由页面兜底。
     */
    suspend fun resolvePlayable(candidate: DramaCandidate): DramaPlayable? {
        playableCache[candidate.seriesId]?.let { return it }
        val playable = ShortDramaPlaybackResolver.resolve(candidate)
            ?: candidate.pageUrl.takeIf { it.isMediaUrl() }?.let { DramaPlayable(url = it) }
            ?: return null
        if (playable.url.isBlank()) return null
        playableCache[candidate.seriesId] = playable
        return playable
    }

    private fun String.isMediaUrl(): Boolean =
        startsWith("http", ignoreCase = true) &&
            (contains(".m3u8", ignoreCase = true) || contains(".mp4", ignoreCase = true))

    fun onPlay(candidate: DramaCandidate) {
        viewModelScope.launch {
            ShortDramaHistoryStore.record(
                seriesId = candidate.seriesId,
                sourceId = candidate.source.id,
                action = ShortDramaAction.START,
                tags = candidate.tags,
            )
        }
    }

    fun onSkip(candidate: DramaCandidate) {
        sessionSkips.add(candidate.seriesId)
        viewModelScope.launch {
            ShortDramaHistoryStore.record(
                seriesId = candidate.seriesId,
                sourceId = candidate.source.id,
                action = ShortDramaAction.SKIP,
                tags = candidate.tags,
            )
        }
    }

    /** 解析直链章节列表（选集弹窗直接消费，返回空表示无可用选集）。 */
    suspend fun resolveEpisodes(candidate: DramaCandidate): List<DramaEpisode> =
        ShortDramaPlaybackResolver.resolveEpisodes(candidate)

    /** 把选中集的中间 id 解析为可播放直链（PY/TVBOX 需经源 play 转换）。 */
    suspend fun resolveEpisodePlayable(
        candidate: DramaCandidate,
        episode: DramaEpisode,
    ): DramaPlayable? = ShortDramaPlaybackResolver.resolveEpisodePlayable(candidate, episode)

    /** 加载整部剧的直链章节列表，供选集弹窗/下一集/续播用。失败保持 null（单集兜底）。 */
    fun loadPlaylist(candidate: DramaCandidate) {
        viewModelScope.launch {
            val episodes = ShortDramaPlaybackResolver.resolveEpisodes(candidate)
            if (episodes.isEmpty()) return@launch
            _state.update { it.copy(playlist = DramaSeriesPlaylist(candidate.seriesId, episodes, 0)) }
        }
    }

    /** 选中某集：更新当前集、回填集标题、记 START（带 episodeId）。 */
    fun selectEpisode(candidate: DramaCandidate, index: Int) {
        val playlist = _state.value.playlist?.takeIf { it.seriesId == candidate.seriesId } ?: return
        val episode = playlist.episodes.getOrNull(index) ?: return
        _state.update {
            it.copy(
                playlist = it.playlist?.copy(currentIndex = index),
                feed = it.feed.map { item ->
                    if (item.seriesId == candidate.seriesId) item.copy(chapterTitle = episode.title) else item
                },
            )
        }
        viewModelScope.launch {
            ShortDramaHistoryStore.record(
                seriesId = candidate.seriesId,
                sourceId = candidate.source.id,
                action = ShortDramaAction.START,
                tags = candidate.tags,
                episodeId = episode.url,
            )
        }
    }

    /** 切到下一集，成功返回 true，末集返回 false。 */
    fun nextEpisode(candidate: DramaCandidate): Boolean {
        val playlist = _state.value.playlist?.takeIf { it.seriesId == candidate.seriesId }
            ?: return false
        val next = playlist.currentIndex + 1
        if (next >= playlist.episodes.size) return false
        selectEpisode(candidate, next)
        return true
    }

    fun setSpeed(value: Float) {
        ShortDramaPrefs.setSpeed(value)
        _state.update { it.copy(speed = value) }
    }

    fun setCleanScreen(value: Boolean) {
        ShortDramaPrefs.setCleanScreen(value)
        _state.update { it.copy(cleanScreen = value) }
    }

    fun setAutoSlide(value: Boolean) {
        ShortDramaPrefs.setAutoSlide(value)
        _state.update { it.copy(autoSlide = value) }
    }

    fun setAutoNext(value: Boolean) {
        ShortDramaPrefs.setAutoNext(value)
        _state.update { it.copy(autoNext = value) }
    }

    /** 播放进度落库：>60% 或 >30s 记 PROGRESS，>95% 记 COMPLETE。 */
    fun recordProgress(
        candidate: DramaCandidate,
        episodeId: String,
        position: Long,
        duration: Long,
    ) {
        if (duration <= 0) return
        viewModelScope.launch {
            val ratio = position.toDouble() / duration
            val action = when {
                ratio >= 0.95 -> ShortDramaAction.COMPLETE
                ratio >= 0.60 || position >= 30_000L -> ShortDramaAction.PROGRESS
                else -> return@launch
            }
            ShortDramaHistoryStore.record(
                seriesId = candidate.seriesId,
                sourceId = candidate.source.id,
                action = action,
                tags = candidate.tags,
                episodeId = episodeId,
                position = position,
                duration = duration,
            )
        }
    }

    fun toggleLike(candidate: DramaCandidate, liked: Boolean) {
        viewModelScope.launch {
            ShortDramaHistoryStore.toggleLike(
                seriesId = candidate.seriesId,
                sourceId = candidate.source.id,
                tags = candidate.tags,
                liked = liked,
            )
        }
    }

    suspend fun isLiked(seriesId: String): Boolean = ShortDramaHistoryStore.isLiked(seriesId)

    suspend fun isInBookshelf(candidate: DramaCandidate): Boolean =
        ShortDramaBookshelf.isInBookshelf(candidate)

    fun toggleBookshelf(candidate: DramaCandidate, add: Boolean) {
        viewModelScope.launch {
            if (add) ShortDramaBookshelf.add(candidate) else ShortDramaBookshelf.remove(candidate)
        }
    }

    /** 源选择页返回后调用：源有变化才重建信息流。 */
    fun onSourcesMaybeChanged() {
        viewModelScope.launch {
            val before = _state.value.sources
            val after = ShortDramaSourceStore.enabled()
            if (before.map { it.id } != after.map { it.id }) refresh()
        }
    }
}