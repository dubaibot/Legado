package io.legado.app.ui.book.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.video.SourceType
import io.legado.app.data.video.VideoItem
import io.legado.app.data.video.VideoSourceRegistry
import io.legado.app.help.config.AppConfig
import io.legado.app.model.rss.Rss
import io.legado.app.ui.main.homepage.HomeSourceManagementStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class SearchContentType(val title: String) {
    BOOK("书源"), RSS("RSS"), PY("PY"), TVBOX("TVBOX")
}

data class UnifiedSearchSource(
    val key: String,
    val name: String,
    val type: SearchContentType,
    val bookScope: String? = null,
    val rss: RssSource? = null,
    val video: VideoSourceRegistry.SourceInfo? = null,
)

data class UnifiedSearchResult(
    val source: UnifiedSearchSource,
    val article: RssArticle? = null,
    val video: VideoItem? = null,
) {
    val identity: String get() = article?.link ?: video?.let { it.id.ifBlank { it.url } }.orEmpty()
    val title: String get() = article?.title ?: video?.title.orEmpty()
    val cover: String? get() = article?.image ?: video?.pic
    val description: String get() = article?.description ?: video?.remarks.orEmpty()
}

/** Owns only the additional search types; book search keeps its existing model. */
class UnifiedSearchViewModel : ViewModel() {
    var type by mutableStateOf(SearchContentType.BOOK)
        private set
    var sources by mutableStateOf<List<UnifiedSearchSource>>(emptyList())
        private set
    var sourceKey by mutableStateOf("")
        private set
    var results by mutableStateOf<List<UnifiedSearchResult>>(emptyList())
        private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingSources by mutableStateOf(false)
        private set
    var submitted by mutableStateOf(false)
        private set
    var stopped by mutableStateOf(false)
        private set
    var completed by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set
    private var request: Job? = null
    private var version = 0L
    var keyword = ""
        private set

    fun selectType(value: SearchContentType) {
        if (type == value) return
        stop()
        type = value
        sourceKey = ""
        results = emptyList()
        errors = emptyMap()
        submitted = false
        stopped = false
        keyword = ""
    }

    fun selectSource(key: String) {
        if (sourceKey == key) return
        stop()
        sourceKey = key
        results = emptyList()
        errors = emptyMap()
        submitted = false
        stopped = false
    }

    fun stop() {
        ++version
        request?.cancel()
        request = null
        loading = false
        loadingSources = false
        stopped = submitted
    }

    fun refreshSources() = start(if (submitted && !stopped) keyword else null)

    fun search(key: String) {
        key.trim().takeIf(String::isNotEmpty)?.let { start(it) }
    }

    private fun start(key: String?) {
        stop()
        val token = version
        val requestedType = type
        val requestedSource = sourceKey
        errors = emptyMap()
        loadingSources = true
        loading = key != null && requestedType != SearchContentType.BOOK
        if (key != null) {
            keyword = key
            submitted = true
            stopped = false
            results = emptyList()
        }
        completed = 0
        total = 0
        request = viewModelScope.launch {
            try {
                val catalog = mutableListOf<UnifiedSearchSource>()
                // Load each provider independently so a broken catalog leaves other types usable.
                suspend fun provider(name: String, load: () -> List<UnifiedSearchSource>) {
                    try {
                        catalog += withContext(Dispatchers.IO) { load() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (token == version) errors = errors + (name to (e.localizedMessage ?: "加载失败"))
                    }
                }
                provider("书源列表") {
                    appDb.bookSourceDao.allEnabledPart.filter { it.hasSearchUrl }.map {
                        UnifiedSearchSource(it.bookSourceUrl, it.bookSourceName, SearchContentType.BOOK,
                            bookScope = SearchScope(it).toString())
                    }
                }
                provider("RSS源列表") {
                    appDb.rssSourceDao.allEnabled.filter { !it.searchUrl.isNullOrBlank() }.map {
                        UnifiedSearchSource(it.sourceUrl, it.sourceName, SearchContentType.RSS, rss = it)
                    }
                }
                provider("视频源列表") {
                    HomeSourceManagementStore.sources().filter { it.enabled }.mapNotNull {
                        val videoType = SourceType.entries.firstOrNull { type -> type.name == it.type }
                            ?: return@mapNotNull null
                        UnifiedSearchSource(it.key, it.name,
                            if (videoType == SourceType.PY) SearchContentType.PY else SearchContentType.TVBOX,
                            video = VideoSourceRegistry.SourceInfo(it.id, it.name, videoType, it.parentId))
                    }
                }
                ensureActive()
                if (token != version) return@launch
                sources = catalog.distinctBy { it.type to it.key }
                loadingSources = false
                val available = sources.filter { it.type == requestedType }
                if (requestedSource.isNotEmpty() && available.none { it.key == requestedSource }) {
                    results = emptyList()
                    errors = errors + ("搜索范围" to "所选源已禁用、移除或不支持搜索，请重新选择")
                    return@launch
                }
                if (key == null || requestedType == SearchContentType.BOOK) return@launch
                val selected = available.filter { requestedSource.isEmpty() || it.key == requestedSource }
                total = selected.size
                if (selected.isEmpty()) {
                    errors = errors + ("搜索范围" to "当前类型没有已启用且支持搜索的源")
                    return@launch
                }
                val permits = Semaphore(AppConfig.searchThreadCount.coerceIn(1, 32))
                supervisorScope {
                    selected.forEach { source ->
                        launch {
                            try {
                                val items = permits.withPermit {
                                    withTimeout(30_000L) {
                                        withContext(Dispatchers.IO) {
                                            source.rss?.let { rss ->
                                                Rss.getArticlesAwait("搜索", requireNotNull(rss.searchUrl), rss, 1, key)
                                                    .first.map { UnifiedSearchResult(source, article = it) }
                                            } ?: run {
                                                val video = VideoSourceRegistry.createSource(requireNotNull(source.video))
                                                    ?: error("源创建失败")
                                                video.search(key, 1).map { UnifiedSearchResult(source, video = it) }
                                            }
                                        }
                                    }
                                }
                                ensureActive()
                                if (token == version) {
                                    results = (results + items).distinctBy { it.source.key to it.identity }
                                }
                            } catch (e: TimeoutCancellationException) {
                                if (token == version) errors = errors + (source.key to "${source.name}：搜索超时（30秒）")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (token == version) errors = errors +
                                    (source.key to "${source.name}：${e.localizedMessage ?: "搜索失败"}")
                            } finally {
                                if (token == version) completed++
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == version) errors = errors + ("搜索" to (e.localizedMessage ?: "搜索失败"))
            } finally {
                if (token == version) {
                    loading = false
                    loadingSources = false
                }
            }
        }
    }
}
