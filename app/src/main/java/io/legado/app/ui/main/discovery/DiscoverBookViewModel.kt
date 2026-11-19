package io.legado.app.ui.main.discovery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverBookViewModel : ViewModel() {
    var categories by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var selectedCategory by mutableStateOf<Pair<String, String>?>(null)
        private set
    var books by mutableStateOf<List<SearchBook>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var page by mutableStateOf(1)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set

    private var sourceUrl = ""
    private var requestVersion = 0
    private var sourceJob: Job? = null
    private var categoryJob: Job? = null
    private var pageJob: Job? = null

    fun selectSource(url: String, force: Boolean = false) {
        if (sourceUrl == url && !force) return
        sourceJob?.cancel()
        categoryJob?.cancel()
        pageJob?.cancel()
        sourceUrl = url
        categories = emptyList()
        selectedCategory = null
        books = emptyList()
        error = null
        page = 1
        isLoadingMore = false
        isLoading = true
        val version = ++requestVersion
        sourceJob = viewModelScope.launch {
            try {
                val source = withContext(IO) { appDb.bookSourceDao.getBookSource(url) }
                    ?: throw IllegalStateException("书源不存在")
                val options = withContext(IO) {
                    source.exploreKinds()
                        .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
                        .filterNot { it.title.startsWith("ERROR:") }
                        .map { it.title to it.url.orEmpty() }
                }
                if (version != requestVersion) return@launch
                categories = options
                isLoading = false
                val firstCategory = options.firstOrNull()
                if (firstCategory == null) {
                    error = "该书源暂无可用分类"
                } else {
                    selectCategory(firstCategory, force = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestVersion) {
                    error = e.stackTraceStr
                    isLoading = false
                }
            }
        }
    }

    fun selectCategory(category: Pair<String, String>?, force: Boolean = false) {
        if (!force && category == selectedCategory && (books.isNotEmpty() || isLoading)) return
        selectedCategory = category
        books = emptyList()
        error = null
        page = 1
        isLoadingMore = false
        isLoading = true
        val version = ++requestVersion
        val url = sourceUrl
        categoryJob?.cancel()
        pageJob?.cancel()
        categoryJob = viewModelScope.launch {
            try {
                val source = withContext(IO) { appDb.bookSourceDao.getBookSource(url) }
                    ?: throw IllegalStateException("书源不存在")
                val result = withContext(IO) {
                    WebBook.exploreBookAwait(source, category?.second ?: source.exploreUrl.orEmpty())
                }
                if (version != requestVersion) return@launch
                withContext(IO) { appDb.searchBookDao.insert(*result.toTypedArray()) }
                if (version != requestVersion) return@launch
                books = result
                page = 1
                isLoading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestVersion) {
                    error = e.stackTraceStr
                    isLoading = false
                }
            }
        }
    }

    fun retry() {
        if (categories.isEmpty()) {
            selectSource(sourceUrl, force = true)
        } else {
            selectCategory(selectedCategory, force = true)
        }
    }

    fun loadNextPage() {
        if (isLoading || isLoadingMore || sourceUrl.isBlank() || selectedCategory == null) return
        val version = requestVersion
        val currentPage = page
        val url = sourceUrl
        val categoryUrl = selectedCategory?.second.orEmpty()
        isLoadingMore = true
        error = null
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            try {
                val source = withContext(IO) { appDb.bookSourceDao.getBookSource(url) }
                    ?: throw IllegalStateException("书源不存在")
                val result = withContext(IO) {
                    WebBook.exploreBookAwait(source, categoryUrl, currentPage + 1)
                }
                if (version != requestVersion) return@launch
                val existingUrls = books.asSequence().map { it.bookUrl }.toHashSet()
                val additions = result.filter { existingUrls.add(it.bookUrl) }
                withContext(IO) { appDb.searchBookDao.insert(*additions.toTypedArray()) }
                if (version != requestVersion) return@launch
                books = books + additions
                if (result.isNotEmpty() && additions.isNotEmpty()) page = currentPage + 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestVersion) error = e.stackTraceStr
            } finally {
                if (version == requestVersion) isLoadingMore = false
            }
        }
    }
}
