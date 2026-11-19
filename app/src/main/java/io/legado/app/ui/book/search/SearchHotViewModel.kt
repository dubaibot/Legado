package io.legado.app.ui.book.search

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.CacheManager
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.isOpenableExploreCategory
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class HotSelection(val sourceUrl: String, val title: String, val url: String)

data class HotCard(val book: SearchBook, val sourceName: String, val categoryName: String)

/** Public actions and Compose state are confined to the main thread. */
class SearchHotViewModel(application: Application) : AndroidViewModel(application) {
    var sources: List<BookSourcePart> by mutableStateOf(emptyList(), neverEqualPolicy())
        private set
    var kinds: Map<String, List<ExploreKind>> by mutableStateOf(emptyMap())
        private set
    var selections: List<HotSelection> by mutableStateOf(emptyList())
        private set
    var activeSelection: HotSelection? by mutableStateOf(null)
        private set
    var cards: List<HotCard> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var configLoading: Boolean by mutableStateOf(true)
        private set
    var loadingKinds: Set<String> by mutableStateOf(emptySet())
        private set

    private val preferences = application.defaultSharedPreferences
    var sourceOrder: List<String> by mutableStateOf(readOrder(SOURCE_ORDER_KEY))
        private set
    var addressOrders: Map<String, List<String>> by mutableStateOf(readAddressOrders())
        private set
    private val semaphore = Semaphore(4)
    private val kindJobs = mutableMapOf<String, Job>()
    private var refreshJob: Job? = null
    private var sourceVersion = 0L
    private var refreshVersion = 0L
    private var selectionVersion = 0L
    private var sourcesLoaded = false
    private var initializing = true

    init {
        selections = readSelections()
        viewModelScope.launch {
            appDb.bookSourceDao.flowExplore().catch {
                AppLog.put("Search hot: observing sources failed", it)
                initializing = false
                updateConfigLoading()
            }.collectLatest { available ->
                val version = ++sourceVersion
                sources = available.filter { it.enabled }.sortedBy {
                    sourceOrder.indexOf(it.bookSourceUrl).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
                }
                sourcesLoaded = true
                kindJobs.values.forEach { it.cancel() }
                kindJobs.clear()
                loadingKinds = emptySet()
                kinds = emptyMap()
                cancelRefresh()
                val validUrls = sources.map { it.bookSourceUrl }.toSet()
                val validSelections = selections.filter { it.sourceUrl in validUrls }
                if (activeSelection !in validSelections) activeSelection = validSelections.firstOrNull()
                if (validSelections != selections) {
                    selections = validSelections
                    persistSelections()
                }
                cards = cards.filter { it.book.origin in validUrls }
                // Start displaying persisted choices while default discovery runs.
                refresh(force = false)
                initializing = true
                updateConfigLoading()
                try {
                    if (!preferences.getBoolean(INITIALIZED_KEY, false)) {
                        val editVersion = selectionVersion
                        val defaults = mutableListOf<HotSelection>()
                        for (source in sources.filter { it.bookSourceName.contains("骨朵") }) {
                            val categories = fetchKinds(source.bookSourceUrl)
                            currentCoroutineContext().ensureActive()
                            if (version != sourceVersion) return@collectLatest
                            if (categories != null) {
                                kinds = kinds + (source.bookSourceUrl to categories)
                                defaults += categories.map {
                                    HotSelection(source.bookSourceUrl, it.title, it.url!!)
                                }
                            }
                        }
                        // An empty installation can discover defaults after sources are imported.
                        if (defaults.isNotEmpty() && editVersion == selectionVersion) {
                            selections = defaults.distinctBy { it.sourceUrl to it.url }
                            activeSelection = selections.firstOrNull()
                            persistSelections(initialized = true)
                            refresh(force = false)
                        }
                    }
                } finally {
                    if (version == sourceVersion) {
                        initializing = false
                        updateConfigLoading()
                    }
                }
            }
        }
    }

    /** Lazily loads openable categories; repeat calls reuse the current result/job. */
    fun loadKinds(sourceUrl: String) {
        if (sourceUrl in kinds || kindJobs[sourceUrl]?.isActive == true ||
            sources.none { it.bookSourceUrl == sourceUrl }
        ) return
        val version = sourceVersion
        loadingKinds = loadingKinds + sourceUrl
        updateConfigLoading()
        kindJobs[sourceUrl] = viewModelScope.launch {
            try {
                val result = fetchKinds(sourceUrl)
                currentCoroutineContext().ensureActive()
                if (version == sourceVersion && result != null) {
                    kinds = kinds + (sourceUrl to result)
                }
            } finally {
                if (version == sourceVersion) {
                    loadingKinds = loadingKinds - sourceUrl
                    kindJobs.remove(sourceUrl)
                    updateConfigLoading()
                }
            }
        }
    }

    /** Only the dialog's confirmation commits its draft ordering and choices. */
    fun saveConfiguration(
        list: List<HotSelection>, orderedSources: List<String>,
        orderedAddresses: Map<String, List<String>>
    ) {
        sourceOrder = orderedSources.distinct()
        addressOrders = orderedAddresses.mapValues { (_, urls) -> urls.distinct() }
        val addresses = JSONObject()
        addressOrders.forEach { (source, urls) -> addresses.put(source, JSONArray(urls)) }
        preferences.edit()
            .putString(SOURCE_ORDER_KEY, JSONArray(sourceOrder).toString())
            .putString(ADDRESS_ORDER_KEY, addresses.toString())
            .apply()
        sources = sources.sortedBy {
            sourceOrder.indexOf(it.bookSourceUrl).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }
        saveSelections(list)
    }

    /** Preserves UI order. Saving an empty list explicitly opts out of defaults. */
    fun saveSelections(list: List<HotSelection>) {
        selectionVersion++
        val validUrls = sources.map { it.bookSourceUrl }.toSet()
        selections = list.filter {
            it.url.isNotBlank() && it.sourceUrl.isNotBlank() &&
                (!sourcesLoaded || it.sourceUrl in validUrls)
        }.distinctBy { it.sourceUrl to it.url }
        if (activeSelection !in selections) activeSelection = selections.firstOrNull()
        persistSelections(initialized = true)
        cards = emptyList()
        refresh(force = false)
    }

    fun selectAddress(selection: HotSelection) {
        if (selection == activeSelection || selection !in selections) return
        activeSelection = selection
        cards = emptyList()
        refresh(force = false)
    }

    /** false: show unexpired disk cache then refresh; true: fetch every address directly. */
    fun refresh(force: Boolean = true) {
        cancelRefresh()
        if (!sourcesLoaded || selections.isEmpty()) {
            cards = emptyList()
            return
        }
        if (force) cards = emptyList()
        val version = refreshVersion
        val selection = activeSelection ?: selections.first().also { activeSelection = it }
        val choices = listOf(selection)
        val sourceNames = sources.associate { it.bookSourceUrl to it.bookSourceName }
        loading = true
        refreshJob = viewModelScope.launch {
            val results = mutableMapOf<HotSelection, List<SearchBook>>()
            val completionOrder = mutableMapOf<HotSelection, Long>()
            val sequence = AtomicLong()
            try {
                if (!force) {
                    val cached = withContext(Dispatchers.IO) {
                        choices.mapNotNull { choice ->
                            try {
                                // CacheManager's memory fast path does not check expiration.
                                CacheManager.get(cacheKey(choice), true)?.let { json ->
                                    choice to GSON.fromJson(json, Array<SearchBook>::class.java).toList()
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                AppLog.put("Search hot: reading cache ${choice.sourceUrl} ${choice.url}", error)
                                null
                            }
                        }
                    }
                    if (version != refreshVersion) return@launch
                    cached.forEach { (choice, books) ->
                        results[choice] = books
                        completionOrder[choice] = sequence.incrementAndGet()
                    }
                    cards = mergeCards(choices, results, completionOrder, sourceNames)
                }
                // Network completions outrank cached placeholders, including failed peers' cache.
                sequence.set(0)
                completionOrder.keys.toList().forEachIndexed { index, choice ->
                    completionOrder[choice] = Long.MAX_VALUE - choices.size + index
                }
                coroutineScope {
                    choices.forEach { choice ->
                        launch address@{
                            try {
                                val (books, completed) = semaphore.withPermit {
                                    withContext(Dispatchers.IO) {
                                        val books = withTimeout(15_000L) {
                                            val source = appDb.bookSourceDao.getBookSource(choice.sourceUrl)
                                                ?: error("Source removed: ${choice.sourceUrl}")
                                            check(source.enabled && source.enabledExplore) { "Source disabled" }
                                            WebBook.exploreBookAwait(source, choice.url, 1)
                                        }
                                        currentCoroutineContext().ensureActive()
                                        books to sequence.incrementAndGet()
                                    }
                                }
                                currentCoroutineContext().ensureActive()
                                if (version != refreshVersion) return@address
                                results[choice] = books
                                completionOrder[choice] = completed
                                cards = mergeCards(choices, results, completionOrder, sourceNames)
                                withContext(Dispatchers.IO) {
                                    currentCoroutineContext().ensureActive()
                                    CacheManager.put(cacheKey(choice), GSON.toJson(books), 30 * 60)
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                                AppLog.put("Search hot: loading ${choice.sourceUrl} ${choice.url} failed", error)
                            }
                        }
                    }
                }
            } finally {
                if (version == refreshVersion) loading = false
            }
        }
    }

    private fun cancelRefresh() {
        refreshVersion++
        refreshJob?.cancel()
        refreshJob = null
        loading = false
    }

    private suspend fun fetchKinds(sourceUrl: String): List<ExploreKind>? {
        return try {
            semaphore.withPermit {
                withContext(Dispatchers.IO) {
                    withTimeout(15_000L) {
                        val source = appDb.bookSourceDao.getBookSource(sourceUrl)
                            ?: error("Source removed: $sourceUrl")
                        check(source.enabled && source.enabledExplore) { "Source disabled" }
                        val raw = source.exploreKinds()
                        raw.filter { it.title.startsWith("ERROR:") }.forEach {
                            AppLog.put("Search hot: categories $sourceUrl ${it.title}")
                        }
                        if (raw.any { it.title.startsWith("ERROR:") }) return@withTimeout null
                        raw.filter { it.isOpenableExploreCategory() }
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            AppLog.put("Search hot: categories $sourceUrl failed", error)
            null
        }
    }

    private fun updateConfigLoading() {
        configLoading = initializing || loadingKinds.isNotEmpty()
    }

    private fun readOrder(key: String): List<String> = try {
        val array = JSONArray(preferences.getString(key, "[]") ?: "[]")
        (0 until array.length()).map { array.getString(it) }.distinct()
    } catch (error: Exception) {
        AppLog.put("Search hot: reading order failed", error)
        emptyList()
    }

    private fun readAddressOrders(): Map<String, List<String>> = try {
        val value = JSONObject(preferences.getString(ADDRESS_ORDER_KEY, "{}") ?: "{}")
        value.keys().asSequence().associateWith { source ->
            val array = value.getJSONArray(source)
            (0 until array.length()).map { array.getString(it) }.distinct()
        }
    } catch (error: Exception) {
        AppLog.put("Search hot: reading address order failed", error)
        emptyMap()
    }

    private fun readSelections(): List<HotSelection> {
        return try {
            val array = JSONArray(preferences.getString(SELECTIONS_KEY, "[]") ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val sourceUrl = item.getString("sourceUrl")
                val url = item.getString("url")
                if (sourceUrl.isBlank() || url.isBlank()) null
                else HotSelection(sourceUrl, item.optString("title"), url)
            }.distinctBy { it.sourceUrl to it.url }
        } catch (error: Exception) {
            AppLog.put("Search hot: reading selections failed", error)
            emptyList()
        }
    }

    private fun persistSelections(initialized: Boolean? = null) {
        val array = JSONArray()
        selections.forEach {
            array.put(JSONObject().put("sourceUrl", it.sourceUrl).put("title", it.title).put("url", it.url))
        }
        preferences.edit().apply {
            putString(SELECTIONS_KEY, array.toString())
            initialized?.let { putBoolean(INITIALIZED_KEY, it) }
        }.apply()
    }

    private fun cacheKey(choice: HotSelection): String =
        "search_hot_books_" + MD5Utils.md5Encode(JSONArray().put(choice.sourceUrl).put(choice.url).toString())

    private fun mergeCards(
        choices: List<HotSelection>,
        results: Map<HotSelection, List<SearchBook>>,
        completionOrder: Map<HotSelection, Long>,
        sourceNames: Map<String, String>
    ): List<HotCard> {
        val winners = mutableMapOf<String, HotSelection>()
        choices.sortedBy { completionOrder[it] ?: Long.MAX_VALUE }.forEach { choice ->
            results[choice].orEmpty().forEach { book ->
                if (book.name.isNotBlank()) winners.putIfAbsent(book.name, choice)
            }
        }
        val names = hashSetOf<String>()
        return choices.asSequence().flatMap { choice ->
            results[choice].orEmpty().asSequence().mapNotNull { book ->
                if (winners[book.name] == choice && names.add(book.name)) {
                    HotCard(book, sourceNames[choice.sourceUrl].orEmpty(), choice.title)
                } else null
            }
        }.take(20).toList()
    }

    private companion object {
        const val SELECTIONS_KEY = "search_hot_selections"
        const val INITIALIZED_KEY = "search_hot_initialized"
        const val SOURCE_ORDER_KEY = "search_hot_source_order"
        const val ADDRESS_ORDER_KEY = "search_hot_address_order"
    }
}
