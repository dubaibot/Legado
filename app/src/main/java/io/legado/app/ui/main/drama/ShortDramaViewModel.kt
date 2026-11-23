package io.legado.app.ui.main.drama

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppLog
import io.legado.app.data.drama.DramaCandidate
import io.legado.app.data.drama.DramaEpisode
import io.legado.app.data.drama.DramaHistorySnapshot
import io.legado.app.data.drama.DramaPlayable
import io.legado.app.data.drama.DramaRankResult
import io.legado.app.data.drama.DramaSeriesPlaylist
import io.legado.app.data.drama.DramaSourceRef
import io.legado.app.data.drama.ShortDramaCandidateProvider
import io.legado.app.data.drama.ShortDramaHistoryStore
import io.legado.app.data.drama.ShortDramaPlaybackResolver
import io.legado.app.data.drama.ShortDramaRecommendEngine
import io.legado.app.data.drama.ShortDramaSourceStore
import kotlin.random.Random
import io.legado.app.data.entities.ShortDramaAction
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
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

    /** 推荐去重窗口：只强去重最近一段，较早曝光的候选在冷却后可重新进入推荐 */
    private val recentDedupWindow = 24

    /**
     * 循环复用强去重窗口，必须显著小于典型候选池规模。
     * 单源池子通常 20~60 条，复用只用最近 4 条做规避，避免整池被排除后停推。
     */
    private val recycleGuardWindow = 4

    /** 候选池容量上限，后台补池时防止无限增长 */
    private val maxPoolSize = 500

    /** 后台补池最小间隔，避免每次翻页都重复拉源 */
    private val replenishIntervalMs = 30_000L
    private var lastReplenishAt = 0L

    /** 已成功分类扩容的源签名；同一批源成功后不重复翻页，失败则允许下次重试 */
    private var expandedSignature: String = ""
    private var expandJob: Job? = null
    /** 同一批源扩容失败的重试次数上限：PY 源冷启动可能首几次超时，但不能无限重试 */
    private var expandAttempts = 0
    private val maxExpandAttempts = 3

    /** 复用轮转起点，避免每次都从同一部开始 */
    private var recycledCursor = 0

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
            val previousFeed = _state.value.feed
            _state.update { it.copy(loading = true, error = null, ended = false) }
            val sources = ShortDramaSourceStore.enabled()
            if (sources.isEmpty()) {
                _state.update {
                    it.copy(loading = false, sources = emptyList(), needsSource = true, feed = emptyList())
                }
                return@launch
            }
            val signature = sources.joinToString("|") { it.id }
            if (force || signature != expandedSignature) {
                expandedSignature = ""
                // 换源或强制刷新时重置扩容重试计数，避免新源继承旧源耗尽的重试次数
                expandAttempts = 0
            }
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
            // 手动刷新时排除当前这批，避免每次都看到同样几部剧
            var ranked = ShortDramaRecommendEngine.rank(
                candidates = pool,
                snapshot = snapshot,
                sessionExposed = emptyList(),
                currentFeed = if (force) previousFeed else emptyList(),
                rng = Random.Default,
            )
            if (ranked.candidates.isEmpty() && force && previousFeed.isNotEmpty()) {
                ranked = ShortDramaRecommendEngine.rank(
                    candidates = pool,
                    snapshot = snapshot,
                    sessionExposed = emptyList(),
                    currentFeed = emptyList(),
                    rng = Random.Default,
                )
            }
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
        if (current.loading || current.loadingMore) return
        // 不再因 ended 提前返回：ended 只表示「严格推荐暂时无新内容」，
        // 信息流语义下仍应继续循环复用，否则一旦置位就永久卡死。
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, ended = false) }
            if (candidatePool.isEmpty()) {
                replenishPoolNow()
                if (candidatePool.isEmpty()) {
                    _state.update { it.copy(loadingMore = false, ended = true) }
                    return@launch
                }
            }
            // 先确保后台扩容在跑：池子只有首页 20 条时，靠分类翻页继续补新内容
            ensureExpanded()
            val feedNow = _state.value.feed
            val recentFeed = feedNow.takeLast(recentDedupWindow)
            val recentExposed = sessionExposed.toList().takeLast(recentDedupWindow)
            val snapshot = ShortDramaHistoryStore.snapshot(
                sessionSkips = sessionSkips,
            )
            var ranked = ShortDramaRecommendEngine.rank(
                candidates = candidatePool,
                snapshot = snapshot,
                sessionExposed = recentExposed,
                currentFeed = recentFeed,
                rng = Random.Default,
            )
            // 严格推荐不满一批时用循环复用补足，避免池子小而整批取空
            if (ranked.candidates.size < ShortDramaRecommendEngine.BATCH_SIZE) {
                val recycled = pickRecycled(snapshot, recentFeed)
                val existing = ranked.candidates.mapTo(HashSet()) { it.seriesId }
                val fill = recycled.candidates
                    .filter { it.seriesId !in existing }
                    .take(ShortDramaRecommendEngine.BATCH_SIZE - ranked.candidates.size)
                ranked = ranked.copy(
                    candidates = ranked.candidates + fill,
                    supplement = true,
                )
            }
            _state.update {
                it.copy(
                    loadingMore = false,
                    feed = it.feed + ranked.candidates,
                    supplement = ranked.supplement,
                    ended = ranked.candidates.isEmpty() && candidatePool.isEmpty(),
                )
            }
            if (ranked.candidates.isNotEmpty()) replenishPoolIfNeeded()
        }
    }

    /**
     * 循环复用：从池中挑「最久未曝光」的一批重新推。
     * 第一级避开最近 [recycleGuardWindow] 条并做标题去重与完播冷却；
     * 填不满时第二级只避开最近几条与本批已选，保证即使池子比一批还小也能填满。
     */
    private fun pickRecycled(
        snapshot: DramaHistorySnapshot,
        recentFeed: List<DramaCandidate>,
    ): DramaRankResult {
        if (candidatePool.isEmpty()) return DramaRankResult(emptyList(), supplement = true)
        val exposureRank = sessionExposed.toList()
        val poolSorted = candidatePool.sortedBy { candidate ->
            val idx = exposureRank.indexOf(candidate.seriesId)
            if (idx < 0) -1 else idx
        }
        val batch = ShortDramaRecommendEngine.BATCH_SIZE
        val guard = recentFeed.takeLast(recycleGuardWindow)
        val guardIds = guard.map { it.seriesId }.toSet()
        val titles = LinkedHashSet<String>().apply {
            guard.forEach { persistTitle(it.title, this) }
        }

        val picked = ArrayList<DramaCandidate>(batch)
        val pickedIds = HashSet<String>()
        var cursor = recycledCursor.coerceIn(0, poolSorted.lastIndex.coerceAtLeast(0))
        var scanned = 0
        while (picked.size < batch && scanned < poolSorted.size) {
            val candidate = poolSorted[cursor % poolSorted.size]
            cursor++
            scanned++
            if (candidate.seriesId in guardIds || candidate.seriesId in pickedIds) continue
            val titleKey = normalizeTitle(candidate.title)
            if (titleKey.isNotBlank() && titleKey in titles) continue
            val completedAt = snapshot.completedSeries[candidate.seriesId]
            if (completedAt != null && System.currentTimeMillis() - completedAt < 60_000L) continue
            picked.add(candidate)
            pickedIds.add(candidate.seriesId)
            if (titleKey.isNotBlank()) titles.add(titleKey)
        }

        if (picked.size < batch) {
            scanned = 0
            while (picked.size < batch && scanned < poolSorted.size) {
                val candidate = poolSorted[cursor % poolSorted.size]
                cursor++
                scanned++
                if (candidate.seriesId in guardIds || candidate.seriesId in pickedIds) continue
                picked.add(candidate)
                pickedIds.add(candidate.seriesId)
            }
        }

        recycledCursor = cursor
        return DramaRankResult(picked, supplement = true)
    }

    private fun normalizeTitle(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.lowercase()
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
            .replace(Regex("第?\\d+集?"), "")
            .replace(Regex("全集|完整版|电影版|高清|国语|粤语"), "")
            .take(24)
    }

    private fun persistTitle(raw: String, into: MutableSet<String>) {
        normalizeTitle(raw).takeIf { it.isNotBlank() }?.let { into.add(it) }
    }

    /** 池内余量不足一批时后台补源；30 秒节流，避免频繁请求同一批源首页。 */
    private fun replenishPoolIfNeeded() {
        val remaining = candidatePool.size - sessionExposed.size
        if (remaining >= ShortDramaRecommendEngine.BATCH_SIZE) return
        val now = System.currentTimeMillis()
        if (now - lastReplenishAt < replenishIntervalMs) return
        lastReplenishAt = now
        viewModelScope.launch { replenishPoolNow() }
    }

    /** 实际执行一次补源：优先分类翻页拿新条目，其次重读首页；补到新内容时清除停推标记。 */
    private suspend fun replenishPoolNow() {
        val sources = ShortDramaSourceStore.enabled()
        if (sources.isEmpty()) return
        val existing = candidatePool.mapTo(HashSet()) { it.seriesId }
        var fresh = ShortDramaCandidateProvider.expand(sources, existing)
        if (fresh.isEmpty()) {
            fresh = ShortDramaCandidateProvider.load(sources)
                .filter { it.seriesId !in existing }
        }
        if (fresh.isEmpty()) return
        mergeIntoPool(fresh)
    }

    private fun mergeIntoPool(fresh: List<DramaCandidate>) {
        if (fresh.isEmpty()) return
        val merged = (candidatePool + fresh)
            .distinctBy { it.seriesId }
            .take(maxPoolSize)
        val grew = merged.size > candidatePool.size
        candidatePool = merged
        cachedPool = merged
        cachedPoolAt = System.currentTimeMillis()
        if (grew && _state.value.ended) {
            _state.update { it.copy(ended = false) }
        }
    }

    /**
     * 后台分类扩容：把「首页 20 条之外」的分类/翻页内容补进候选池。
     *
     * 只有成功拿到新内容才固化 [expandedSignature]；PY 源首次调用要初始化 Python 引擎，
     * 常因冷启动超时返回空——旧实现在调用前就写签名，导致一次失败后本批源永久不再扩容，
     * 池子永远停在首页 20 条。现在失败会清签名并在后续 loadMore 重试，最多 [maxExpandAttempts] 次。
     */
    private fun ensureExpanded() {
        if (expandJob?.isActive == true) return
        expandJob = viewModelScope.launch {
            val sources = ShortDramaSourceStore.enabled()
            if (sources.isEmpty()) return@launch
            val signature = sources.joinToString("|") { it.id }
            if (signature == expandedSignature) return@launch
            if (expandAttempts >= maxExpandAttempts) return@launch
            expandAttempts++
            val existing = candidatePool.mapTo(HashSet()) { it.seriesId }
            val fresh = runCatching {
                ShortDramaCandidateProvider.expand(sources, existing)
            }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) {
                mergeIntoPool(fresh)
                // 仅在成功后固化签名：本批源不再重复扩容
                expandedSignature = signature
                AppLog.put(
                    "ShortDramaViewModel.expand: fresh=${fresh.size}, pool=${candidatePool.size}"
                )
            } else {
                // 失败：清除签名，允许下次 loadMore 再试（PY 冷启动超时可自愈）
                expandedSignature = ""
                AppLog.put("ShortDramaViewModel.expand: 本轮未拿到新内容(第 $expandAttempts/$maxExpandAttempts 次)，池=${candidatePool.size}")
                // 重试耗尽且池子仍停在首页条数时，给用户可见反馈，避免误以为「只有 20 条」是界面 bug
                if (expandAttempts >= maxExpandAttempts && candidatePool.size <= 20) {
                    appCtx.toastOnUi("当前视频源只提供 ${candidatePool.size} 条内容，可在源管理更换视频源")
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
        // 翻页触发必须先于曝光去重早退：
        // 当信息流靠循环复用填入「已曝光过」的候选时，若仍按旧顺序先 return，
        // 这些复用页就永远不会触发 loadMore，池子停在首页 20 条 → 滑动卡死。
        // loadMore 内部有 loadingMore 防重入，且 rank 会做去重，提前触发是安全的。
        val size = _state.value.feed.size
        if (size > 0 && index >= (size + 1) / 2) loadMore()
        // 曝光记录仍需去重，避免同页反复写历史
        if (candidate.seriesId in sessionExposed) return
        viewModelScope.launch { recordExposure(candidate, index) }
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
    suspend fun resolvePlayable(candidate: DramaCandidate, route: String? = null): DramaPlayable? {
        if (route.isNullOrBlank()) {
            playableCache[candidate.seriesId]?.let { return it }
        }
        val playable = ShortDramaPlaybackResolver.resolve(candidate, route)
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

    /** 选集弹窗解析成功后回填选集，保证「下一集/自动续播」与弹窗共用同一份列表。 */
    fun cacheEpisodes(candidate: DramaCandidate, episodes: List<DramaEpisode>) {
        if (episodes.isEmpty()) return
        val current = _state.value.playlist?.takeIf { it.seriesId == candidate.seriesId }
        if (current != null && current.episodes.size >= episodes.size) return
        val index = (current?.currentIndex ?: 0).coerceIn(0, episodes.lastIndex)
        _state.update {
            it.copy(playlist = DramaSeriesPlaylist(candidate.seriesId, episodes, index))
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

    /**
     * 收藏/取消收藏。加入书架时同步记录当前集数，下次从书架打开能直接续到这一集。
     *
     * @param episodeIndex 1 基当前集号
     * @param episodeTitle 当前集标题
     * @param totalEpisodes 总集数（未知时传 0）
     */
    fun toggleBookshelf(
        candidate: DramaCandidate,
        add: Boolean,
        episodeIndex: Int = 0,
        episodeTitle: String = "",
        totalEpisodes: Int = 0,
    ) {
        viewModelScope.launch {
            if (add) {
                ShortDramaBookshelf.add(
                    candidate = candidate,
                    episodeIndex = episodeIndex,
                    episodeTitle = episodeTitle,
                    totalEpisodes = totalEpisodes,
                )
            } else {
                ShortDramaBookshelf.remove(candidate)
            }
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