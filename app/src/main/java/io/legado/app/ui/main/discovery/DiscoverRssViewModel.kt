package io.legado.app.ui.main.discovery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.sortUrls
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.model.rss.Rss
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import com.script.rhino.runScriptWithContext
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode

class DiscoverRssViewModel : ViewModel() {
    var categories by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var selectedCategory by mutableStateOf<Pair<String, String>?>(null)
        private set
    var articles by mutableStateOf<List<RssArticle>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var startHtml by mutableStateOf<String?>(null)
        private set
    var startHtmlSource by mutableStateOf<RssSource?>(null)
        private set
    var startHtmlUserAgent by mutableStateOf<String?>(null)
        private set
    var startHtmlCookie by mutableStateOf<String?>(null)
        private set
    var page by mutableStateOf(1)
        private set
    var nextPageUrl by mutableStateOf<String?>(null)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set

    private var source: RssSource? = null
    private var sourceUrl = ""
    private var requestVersion = 0
    private var sourceJob: Job? = null
    private var articlesJob: Job? = null
    private var articlesFlowJob: Job? = null
    private var nextPageJob: Job? = null
    private var order = System.currentTimeMillis()

    fun selectStartHtml(url: String) {
        if (sourceUrl == url && startHtml != null) return
        sourceJob?.cancel()
        articlesJob?.cancel()
        articlesFlowJob?.cancel()
        nextPageJob?.cancel()
        sourceUrl = url
        source = null
        startHtmlSource = null
        startHtml = null
        startHtmlUserAgent = null
        startHtmlCookie = null
        categories = emptyList()
        selectedCategory = null
        articles = emptyList()
        page = 1
        nextPageUrl = null
        isLoadingMore = false
        error = null
        isLoading = true
        val version = ++requestVersion
        sourceJob = viewModelScope.launch {
            try {
                val loaded = withContext(IO) { appDb.rssSourceDao.getByKey(url) }
                    ?: throw IllegalStateException("订阅源不存在")
                val (processed, userAgent, cookie) = withContext(IO) {
                    Triple(
                        loaded.processStartHtml(),
                        loaded.getHeaderMap()["User-Agent"] ?: AppConfig.userAgent,
                        CookieStore.getCookie(url),
                    )
                }
                if (version != requestVersion) return@launch
                source = loaded
                startHtmlSource = loaded
                startHtml = processed
                startHtmlUserAgent = userAgent
                startHtmlCookie = cookie
                isLoading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestVersion) {
                    error = e.message ?: "订阅源首页加载失败"
                    isLoading = false
                }
            }
        }
    }

    fun selectSource(url: String, force: Boolean = false) {
        if (sourceUrl == url && source != null && !force) return
        sourceJob?.cancel()
        articlesJob?.cancel()
        articlesFlowJob?.cancel()
        nextPageJob?.cancel()
        sourceUrl = url
        source = null
        startHtmlSource = null
        startHtml = null
        startHtmlUserAgent = null
        startHtmlCookie = null
        categories = emptyList()
        selectedCategory = null
        articles = emptyList()
        page = 1
        nextPageUrl = null
        isLoadingMore = false
        error = null
        isLoading = true
        val version = ++requestVersion
        sourceJob = viewModelScope.launch {
            try {
                val loaded = withContext(IO) { appDb.rssSourceDao.getByKey(url) }
                    ?: throw IllegalStateException("订阅源不存在")
                val sorts = withContext(IO) { loaded.sortUrls() }
                if (version != requestVersion) return@launch
                source = loaded
                categories = sorts
                isLoading = false
                selectCategory(sorts.firstOrNull(), force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestVersion) {
                    error = e.message ?: "订阅源加载失败"
                    isLoading = false
                }
            }
        }
    }

    fun selectCategory(category: Pair<String, String>?, force: Boolean = false) {
        val currentSource = source ?: return
        if (!force && selectedCategory == category && (articles.isNotEmpty() || isLoading)) return
        selectedCategory = category
        articles = emptyList()
        page = 1
        nextPageUrl = null
        isLoadingMore = false
        order = System.currentTimeMillis()
        error = null
        isLoading = true
        val version = ++requestVersion
        articlesJob?.cancel()
        articlesFlowJob?.cancel()
        nextPageJob?.cancel()
        val sortName = category?.first.orEmpty()
        val sortUrl = category?.second ?: currentSource.sourceUrl
        articlesFlowJob = viewModelScope.launch {
            appDb.rssArticleDao.flowListByOriginSort(currentSource.sourceUrl, sortName)
                .collect { if (version == requestVersion) articles = it }
        }
        articlesJob = viewModelScope.launch {
            try {
                val (loadedArticles, followingUrl) = withContext(IO) {
                    Rss.getArticlesAwait(sortName, sortUrl, currentSource, 1)
                }
                if (version != requestVersion) return@launch
                nextPageUrl = followingUrl
                loadedArticles.forEach { it.order = order-- }
                withContext(IO) {
                    appDb.rssArticleDao.insert(*loadedArticles.toTypedArray())
                    // 按最新一轮 order 修剪历史缓存页，
                    // 否则翻页回来的文章命中已存在去重，表现为无法翻页
                    if (!currentSource.ruleNextPage.isNullOrEmpty()) {
                        appDb.rssArticleDao.clearOld(currentSource.sourceUrl, sortName, order)
                    }
                }
                if (version != requestVersion) return@launch
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
        if (source == null) selectSource(sourceUrl, force = true)
        else selectCategory(selectedCategory, force = true)
    }

    fun loadNextPage() {
        val currentSource = source ?: return
        val pageUrl = nextPageUrl ?: return
        if (isLoading || isLoadingMore) return
        val version = requestVersion
        val nextPage = page + 1
        val sortName = selectedCategory?.first.orEmpty()
        isLoadingMore = true
        error = null
        nextPageJob?.cancel()
        nextPageJob = viewModelScope.launch {
            try {
                val (loaded, followingUrl) = withContext(IO) {
                    Rss.getArticlesAwait(sortName, pageUrl, currentSource, nextPage)
                }
                if (version != requestVersion) return@launch
                val existing = articles.asSequence().map { "${it.origin}:${it.sort}:${it.link}" }.toHashSet()
                val additions = loaded.filter { existing.add("${it.origin}:${it.sort}:${it.link}") }
                additions.forEach { it.order = order-- }
                withContext(IO) { appDb.rssArticleDao.append(*additions.toTypedArray()) }
                if (version != requestVersion) return@launch
                articles = (articles + additions).distinctBy { "${it.origin}:${it.sort}:${it.link}" }
                nextPageUrl = if (additions.isEmpty()) null else followingUrl
                if (additions.isNotEmpty()) page = nextPage
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

private suspend fun RssSource.processStartHtml(): String {
    val rawHtml = startHtml.orEmpty().let { value ->
        when {
            value.startsWith("@js:", ignoreCase = true) -> runScriptWithContext { evalJS(value.substring(4)).toString() }
            value.startsWith("<js>", ignoreCase = true) -> runScriptWithContext {
                evalJS(value.substring(4, value.lastIndexOf("<"))).toString()
            }
            else -> value
        }
    }
    require(rawHtml.isNotBlank()) { "订阅源首页为空" }

    val htmlWithStartJs = startJs.orEmpty().takeIf { it.isNotBlank() }?.let { script ->
        val bodyEnd = rawHtml.lastIndexOf("</body>", ignoreCase = true)
        if (bodyEnd >= 0) rawHtml.substring(0, bodyEnd) + "<script>$script</script>" + rawHtml.substring(bodyEnd)
        else "<body>$rawHtml<script>$script</script></body>"
    } ?: rawHtml

    val document = Jsoup.parse(htmlWithStartJs)
    document.select("script[src]").filter { it.attr("src") == nameUrl }.forEach { it.remove() }
    val bridgeScript = document.createElement("script").appendChild(
        DataNode("(() => {$JS_INJECTION\n${basicJs.orEmpty()}\n${preloadJs.orEmpty()}\n})();"),
    )
    document.head().prependChild(bridgeScript)
    val htmlWithBridge = document.outerHtml()
    val style = startStyle?.takeIf { it.isNotBlank() } ?: this.style
    if (style.isNullOrBlank()) return htmlWithBridge
    val styleEnd = htmlWithBridge.indexOf("</style>", ignoreCase = true)
    if (styleEnd >= 0) {
        val insertAt = styleEnd + "</style>".length
        return htmlWithBridge.substring(0, insertAt) + "<style>$style</style>" + htmlWithBridge.substring(insertAt)
    }
    val headEnd = htmlWithBridge.indexOf("</head>", ignoreCase = true)
    return if (headEnd >= 0) {
        htmlWithBridge.substring(0, headEnd) + "<style>$style</style>" + htmlWithBridge.substring(headEnd)
    } else {
        "<head><style>$style</style></head>$htmlWithBridge"
    }
}
