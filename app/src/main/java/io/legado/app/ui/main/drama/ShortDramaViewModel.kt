package io.legado.app.ui.main.drama

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import splitties.init.appCtx
import io.legado.app.utils.isWifiConnect
import io.legado.app.help.exoplayer.VideoPrefiller
import io.legado.app.help.exoplayer.ExoPlayerHelper

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

    /** 候选池缓存容量上限，防止长期浏览后缓慢增长导致内存占用过高。 */
    private val maxPoolSize = 400

    /** 首屏候选偏少时（低于该值）才把慢源结果补进信息流，避免打断已滑远的用户。 */
    private val shortFeedThreshold = ShortDramaRecommendEngine.BATCH_SIZE * 2

    /** 相邻页预取：最多向前预热 2 个候选的播放地址，仅 WiFi 下进行。 */
    private var prefetchJob: Job? = null
    private val prefetchAhead = 2

    /**
     * 已预填开头字节的 URL 去重集合。
     * [VideoPrefiller] 内部虽有 LRU，但这里再做一层会话级去重，避免快速来回滑动时重复发 Range 请求。
     */
    private val prefilledUrls = HashSet<String>()

    /**
     * 近期去重窗口：只对最近 N 条曝光做强去重。
     * 旧实现把整个 feed + 全部 sessionExposed 作为去重集合，池内候选一旦全部曝光，
     * rank 必然返回空 → ended=true 永久停推。改用滑动窗口后，较早曝光的条目在冷却后
     * 可重新进入推荐，这是「滑到一定次数就无法继续推送」的根治点。
     */
    private val recentDedupWindow = 24

    /**
     * 循环复用时的「强去重窗口」，必须显著小于典型候选池规模。
     *
     * 关键修复：每源只返回 10~20 条，勾选单个源时池子通常仅 10~20 条，小于 [recentDedupWindow]=24。
     * 旧实现里 pickRecycled 也用 24 条窗口做去重，导致 recentIds 覆盖整个池子 → 复用永远取不到候选
     * → ended=true 永久停推。这里把复用窗口收紧到 4 条（只避开紧邻的几条），
     * 保证即使池子很小也能循环产出，这是「滑动一定数量后没有下一条」的真正根治点。
     */
    private val recycleGuardWindow = 4

    /** 后台补充候选池的最小间隔，避免每次 loadMore 都重复拉源。 */
    private val replenishIntervalMs = 30_000L
    private var lastReplenishAt = 0L

    /** 循环复用时的轮转起点，打散顺序，避免每次都从同一部开始。 */
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
            val now = System.currentTimeMillis()
            val usableCache = !force && signature == cachedPoolSignature &&
                now - cachedPoolAt < poolCacheTtlMs && cachedPool.isNotEmpty()
            val pool = if (usableCache) {
                cachedPool
            } else {
                // 渐进式加载：快源先返回首屏，慢源完成后通过回调并入候选池
                ShortDramaCandidateProvider.load(sources) { late ->
                    if (late.isEmpty()) return@load
                    // 仅当源签名仍是本次批次时才并入，避免过期回调污染换源后的池
                    if (signature != cachedPoolSignature) return@load
                    // 切回 ViewModel 作用域处理共享字段，避免与页面状态更新竞争
                    viewModelScope.launch {
                        val merged = (candidatePool + late)
                            .distinctBy { it.seriesId }
                            .take(maxPoolSize)
                        candidatePool = merged
                        cachedPool = merged
                        cachedPoolAt = System.currentTimeMillis()
                        // 用户仍在信息流顶部、非搜索、未结束且当前批次偏少时，才补进信息流，
                        // 避免慢源延迟返回打断已经滑远的用户
                        val cur = _state.value
                        if (cur.searchQuery.isEmpty() && !cur.ended && !cur.loading &&
                            cur.feed.size < shortFeedThreshold
                        ) {
                            val existing = cur.feed.map { it.seriesId }.toSet()
                            val appended = merged.filter { it.seriesId !in existing }
                                .take(shortFeedThreshold - cur.feed.size)
                            if (appended.isNotEmpty()) {
                                val snapshotLate = ShortDramaHistoryStore.snapshot()
                                val rankedLate = ShortDramaRecommendEngine.rank(
                                    candidates = appended,
                                    snapshot = snapshotLate,
                                    sessionExposed = sessionExposed.toList(),
                                    currentFeed = cur.feed,
                                    rng = Random.Default,
                                )
                                if (rankedLate.candidates.isNotEmpty()) {
                                    _state.update { it.copy(feed = it.feed + rankedLate.candidates) }
                                }
                            }
                        }
                    }
                }.also {
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
        // 注意：不再因 ended 提前返回。ended 现在仅表示「严格推荐暂时无新内容」，
        // 信息流语义下仍应继续循环复用，否则一旦置位就永久卡死（这是停推的另一处根因）。
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, ended = false) }
            // 池子为空时先补一次源，补到才继续；补不到就干净退出并标记 ended
            if (candidatePool.isEmpty()) {
                replenishPoolNow()
                if (candidatePool.isEmpty()) {
                    _state.update { it.copy(loadingMore = false, ended = true) }
                    return@launch
                }
            }
            val snapshot = ShortDramaHistoryStore.snapshot(
                sessionSkips = sessionSkips,
            )
            // 只取最近一段作为强去重窗口：滑过足够多条目后，较早曝光的候选可被重新推荐，
            // 从根源上避免「feed 越来越大 → 池内候选全部被排除 → 永久停推」。
            val recentFeed = current.feed.takeLast(recentDedupWindow)
            val recentExposed = sessionExposed.toList().takeLast(recentDedupWindow)
            var ranked = ShortDramaRecommendEngine.rank(
                candidates = candidatePool,
                snapshot = snapshot,
                sessionExposed = recentExposed,
                currentFeed = recentFeed,
                rng = Random.Default,
            )
            // 严格推荐取不满一批时，用「最久未曝光 + 仅避开最近几条」的循环复用补足。
            // 关键：复用窗口用 recycleGuardWindow(4) 而非 recentDedupWindow(24)，
            // 否则小池子（单源 10~20 条）会被 recentIds 全部排除，永远补不满 → 停推。
            if (ranked.candidates.size < ShortDramaRecommendEngine.BATCH_SIZE) {
                val recycled = pickRecycled(snapshot, recentFeed)
                val existing = HashSet(ranked.candidates.map { it.seriesId })
                val fill = recycled.candidates.filter { it.seriesId !in existing }
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
                    // 只要池子非空且这次填进了内容，就绝不是「结束」，清除可能的旧停推位。
                    ended = ranked.candidates.isEmpty() && candidatePool.isEmpty(),
                )
            }
            // 池子余量不足一批时后台补源，保持信息流新鲜并尽量减少循环复用的重复感。
            replenishPoolIfNeeded(current)
        }
    }

    /**
     * 循环复用：从池中挑「最久未曝光」的一批重新推，保证信息流永不因池子小而停推。
     *
     * 关键修复：
     * - 强去重只用 [recycleGuardWindow]（最近 4 条），而不是 24 条窗口。
     *   单源池子常常只有 10~20 条，用 24 条去重会把整个池子排除 → 返回空 → 停推。
     * - 两级兜底：若第一级（避开最近 4 条 + 完播冷却）填不满，就放宽为只避 seriesId 强重复，
     *   仍从「最久未曝光」开始补足，确保即使池子比一批还小也能填满（宁可稍早重复，也不能卡死）。
     * - 用 [recycledCursor] 轮转起点打散顺序，避免每次都从同一部开始。
     */
    private fun pickRecycled(
        snapshot: DramaHistorySnapshot,
        recentFeed: List<DramaCandidate>,
    ): DramaRankResult {
        if (candidatePool.isEmpty()) return DramaRankResult(emptyList(), supplement = true)
        // 曝光时间越早越优先复用；从未曝光的候选（sessionExposed 不含，idx=-1）优先排最前
        val exposureRank = sessionExposed.toList()
        val poolSorted = candidatePool.sortedBy { candidate ->
            val idx = exposureRank.indexOf(candidate.seriesId)
            if (idx < 0) -1 else idx
        }
        val batch = ShortDramaRecommendEngine.BATCH_SIZE
        // 强去重集合只看最近几条，远小于池子，保证总有候选可选
        val guard = recentFeed.takeLast(recycleGuardWindow)
        val guardIds = guard.map { it.seriesId }.toSet()
        val guardTitles = LinkedHashSet<String>().apply {
            guard.forEach { persistTitle(it.title, this) }
        }

        val picked = ArrayList<DramaCandidate>(batch)
        val pickedIds = HashSet<String>()
        val titles = LinkedHashSet<String>(guardTitles)
        var cursor = recycledCursor.coerceIn(0, poolSorted.lastIndex.coerceAtLeast(0))

        // 第一级：避开最近几条 + 标题去重 + 刚完播冷却，按轮转起点扫描
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

        // 第二级：池子太小第一级填不满时，只避开「最近几条 + 本批已选」，忽略标题与完播冷却补足。
        // 宁可让相邻两部标题相近，也绝不能返回不足一批导致信息流提前见底。
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

    /** 标题归一化，与推荐引擎口径一致（跨条目去重）。 */
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

    /**
     * 候选池余量不足一批时，后台重新拉源补充新内容。
     * 用独立协程 + 间隔节流，避免阻塞当前翻页，也避免频繁重复请求同一批源首页。
     */
    private fun replenishPoolIfNeeded(current: UiState) {
        val now = System.currentTimeMillis()
        val remaining = candidatePool.size - sessionExposed.size
        if (remaining >= ShortDramaRecommendEngine.BATCH_SIZE) return
        if (now - lastReplenishAt < replenishIntervalMs) return
        lastReplenishAt = now
        viewModelScope.launch { replenishPoolNow() }
    }

    /** 实际执行一次补源（suspend）：拉源 → 去重合并 → 写缓存；补到内容时清除停推标记。 */
    private suspend fun replenishPoolNow() {
        val sources = ShortDramaSourceStore.enabled()
        if (sources.isEmpty()) return
        val fresh = ShortDramaCandidateProvider.load(sources)
        if (fresh.isEmpty()) return
        val merged = (candidatePool + fresh)
            .distinctBy { it.seriesId }
            .take(maxPoolSize)
        val grew = merged.size > candidatePool.size
        candidatePool = merged
        cachedPool = merged
        cachedPoolAt = System.currentTimeMillis()
        // 补到新内容说明信息流可继续，清除可能因空池而置位的 ended，避免永久停推。
        if (grew && _state.value.ended) {
            _state.update { it.copy(ended = false) }
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
        val feed = _state.value.feed
        // 滑过当前批次一半就预取下一批（搜索模式由 provider 一次性返回，无需分页）
        if (_state.value.searchQuery.isEmpty()) {
            val size = feed.size
            if (size > 0 && index >= (size + 1) / 2) loadMore()
        }
        if (candidate.seriesId in sessionExposed) return
        viewModelScope.launch { recordExposure(candidate, index) }
        // 预热相邻页播放地址：仅 WiFi、仅解析直链（不下载视频），让滑到下一页时秒开
        prefetchNext(candidate, index, feed)
    }

    /**
     * 相邻页「预解析 + 预缓存开头」。
     *
     * 抖音式做法：在当前视频播放时，把后面 1~2 个视频的开头一段字节（含 moov + 首帧 I-frame /
     * m3u8 清单 + 首分片）提前下载进 ExoPlayer 的 [ExoPlayerHelper.cache]，滑到下一页时
     * 播放器直接从缓存出首帧，实现秒开。
     *
     * - 直链解析复用 [resolvePlayable]（内部已有 detail single-flight 缓存），结果写 playableCache；
     * - 开头字节预填复用 [VideoPrefiller.prefill]，它自带次数上限/字节上限/开关/去重，且不污染全局请求头；
     * - 仅 WiFi 下进行，避免消耗移动流量；[prefilledUrls] 做会话级去重，防止来回滑动反复下载；
     * - [prefetchJob] 保证任一时刻只有一次预取在跑，避免快速滑动叠加大量请求。
     */
    private fun prefetchNext(candidate: DramaCandidate, index: Int, feed: List<DramaCandidate>) {
        if (_state.value.searchQuery.isNotEmpty()) return
        if (!appCtx.isWifiConnect) return
        if (feed.isEmpty() || index >= feed.size - 1) return
        if (prefetchJob?.isActive == true) return
        // 防止长时间浏览后无限增长
        if (prefilledUrls.size > maxPoolSize * 2) prefilledUrls.clear()
        prefetchJob = viewModelScope.launch {
            val targets = feed.subList(index + 1, minOf(index + 1 + prefetchAhead, feed.size))
            for (next in targets) {
                // 解析失败（返回 null）不缓存，下次滑到会重试
                val playable = runCatching { resolvePlayable(next) }.getOrNull() ?: continue
                val url = playable.url
                if (url.isBlank() || !prefilledUrls.add(url)) continue
                val headers = HashMap(playable.headers)
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    headers["User-Agent"] = ExoPlayerHelper.BROWSER_UA
                }
                // 异步预填开头字节；失败静默，不影响当前播放
                VideoPrefiller.prefill(url, headers)
            }
        }
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