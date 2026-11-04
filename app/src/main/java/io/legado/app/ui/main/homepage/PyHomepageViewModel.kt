package io.legado.app.ui.main.homepage

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.PreferKey
import io.legado.app.data.video.VideoSource
import io.legado.app.data.video.VideoSourceRegistry
import io.legado.app.help.config.AppConfig
import io.legado.app.help.python.PyLoader
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

/**
 * 影视首页 ViewModel。
 *
 * 职责：
 * - 统一管理首页源（py 插件源 + HTML 自定义页面 + JSON API 源 + TXT 列表源）
 * - 顶栏搜索（py/json/txt 源）
 * - 搜索历史 / 收藏（SharedPreferences JSON 持久化）
 * - 导入 py/html/json/txt 源（SAF 回调后在此执行文件 IO）
 * - 分类浏览滚动翻页
 */
class PyHomepageViewModel : ViewModel() {

    /** 收藏条目 */
    data class PyFavorite(
        val pluginId: String,
        val id: String,
        val title: String,
        val url: String,
        val time: Long,
    )

    /** 首页分类 */
    data class Category(
        val id: String,
        val name: String,
    )

    /** 视频条目 */
    data class VideoItem(
        val id: String,
        val title: String,
        val pic: String,
        val remarks: String,
    )

    /** 首页数据 */
    data class HomeData(
        val categories: List<Category>,
        val videos: List<VideoItem>,
    )

    /** 分类浏览数据 */
    data class CategoryData(
        val page: Int,
        val pagecount: Int,
        val videos: List<VideoItem>,
    )

    /** 详情弹窗状态 */
    data class DetailState(
        val pluginId: String,
        val id: String,
        val title: String,
        val json: String,
        val loading: Boolean = false,
    )

    /** 首页源（py 插件 / HTML 页面 / JSON API / TXT 列表统一模型） */
    data class HomeSource(
        val id: String,
        val name: String,
        val type: String,     // "py" | "html" | "json" | "txt"
        val loaded: Boolean,  // py 源加载状态；html/json/txt 恒为 true
    )

    private val _sources = MutableStateFlow<List<HomeSource>>(emptyList())
    val sources = _sources.asStateFlow()

    var selectedSourceId by mutableStateOf("")
        private set

    var searchKeyword by mutableStateOf("")
        private set

    var searchResults by mutableStateOf("")
        private set

    var isSearching by mutableStateOf(false)
        private set

    /** 顶栏搜索模式（点击搜索按钮展开输入行） */
    var searchMode by mutableStateOf(false)
        private set

    var searchHistory by mutableStateOf<List<String>>(emptyList())
        private set

    var favorites by mutableStateOf<List<PyFavorite>>(emptyList())
        private set

    var detailState by mutableStateOf<DetailState?>(null)
        private set

    /** 当前选中 HTML 页面的内容（WebView 渲染用） */
    var htmlContent by mutableStateOf<String?>(null)
        private set

    /** 首页数据（分类 + 推荐视频） */
    var homeData by mutableStateOf<HomeData?>(null)
        private set

    /** 当前分类页码（首页推荐时为 0） */
    var currentPage by mutableStateOf(1)
        private set

    /** 分类总页数 */
    var totalPages by mutableStateOf(1)
        private set

    /** 是否正在加载更多（滚动翻页防重复触发） */
    var isLoadingMore by mutableStateOf(false)
        private set

    /** 首页/分类加载错误信息（非空时 UI 显示） */
    var homeError by mutableStateOf<String?>(null)
        private set

    /** 分类浏览数据 */
    var categoryData by mutableStateOf<CategoryData?>(null)
        private set

    /** 首页结果缓存（key=pluginId），切换源时清空 */
    private val homeCache = mutableMapOf<String, HomeData>()

    /** 分类结果缓存（key=pluginId|tid|pg），切换源时清空 */
    private val categoryCache = mutableMapOf<String, CategoryData>()

    /** 生成分类缓存键 */
    private fun categoryCacheKey(pluginId: String, tid: String, pg: Int): String = "$pluginId|$tid|$pg"

    /** 当前选中分类 ID（空 = 首页推荐） */
    var selectedCategoryId by mutableStateOf("")
        private set

    // ---- 导入流程状态 ----

    /** 已选择的待导入文件 Uri */
    var importUri by mutableStateOf<Uri?>(null)
        private set

    /** 待导入文件的默认名称（文件名） */
    var importDefaultName by mutableStateOf("")
        private set

    /** 导入命名弹窗 */
    var showImportDialog by mutableStateOf(false)
        private set

    /** 源管理弹窗 */
    var showManageDialog by mutableStateOf(false)
        private set

    /** 自定义弹幕 API URL */
    var danmakuApiUrl by mutableStateOf("")
        private set

    /** 弹幕 API 输入弹窗 */
    var showDanmakuApiDialog by mutableStateOf(false)

    /** TVbox 订阅 URL 输入弹窗 */
    var showTvboxDialog by mutableStateOf(false)

    /** TVbox 订阅 URL 输入值 */
    var tvboxUrlInput by mutableStateOf("")
        private set

    init {
        refreshSources()
        searchHistory = loadHistory()
        favorites = loadFavorites()
        danmakuApiUrl = appCtx.getPrefString(io.legado.app.constant.PreferKey.pyDanmakuApiUrl, "") ?: ""
        // 首启自动安装内置源为异步完成，延迟后再刷一次兜底
        viewModelScope.launch(Dispatchers.IO) {
            delay(8000)
            withContext(Dispatchers.Main) {
                if (_sources.value.isEmpty()) {
                    refreshSources()
                }
            }
        }
    }

    /** 当前选中源类型（"py" | "html" | "json" | "txt" | null） */
    fun currentSourceType(): String? {
        return _sources.value.find { it.id == selectedSourceId }?.type
    }

    /** 当前选中源显示名（标题用） */
    fun currentSourceName(): String? {
        return _sources.value.find { it.id == selectedSourceId }?.name
    }

    /** 当前源类型是否支持列表式浏览（py/json/txt/html 走统一数据模型） */
    fun isListSource(type: String?): Boolean = type == "py" || type == "json" || type == "txt" || type == "html"

    /** 获取指定 py 源的加载错误信息 */
    fun loadErrorFor(id: String): String? {
        return if (_sources.value.any { it.id == id && it.type == "py" }) {
            PyLoader.getLoadError(id)
        } else null
    }

    /** 从磁盘重载源列表（导入/删除后调用） */
    fun refreshSources() {
        viewModelScope.launch(Dispatchers.IO) {
            val pyList = PyLoader.listInstalled().map {
                HomeSource(it.meta.id, it.meta.name, "py", it.loaded)
            }
            // WebView HTML 页面（type=web）
            val webList = HomeSourceStore.listHtml().map {
                HomeSource(it.id, it.name, "web", true)
            }
            // JSON/TXT/HTML 配置源（统一 VideoSource 接口）
            val videoSources = HomeSourceStore.listVideoSources().map {
                HomeSource(it.id, it.name, it.type, true)
            }
            withContext(Dispatchers.Main) {
                _sources.value = pyList + videoSources + webList
                if (_sources.value.none { it.id == selectedSourceId }) {
                    selectedSourceId = _sources.value.firstOrNull()?.id ?: ""
                    if (selectedSourceId.isNotEmpty()) {
                        AppConfig.pyHomePluginId = selectedSourceId
                    }
                }
                // 选中源时加载对应内容
                when (currentSourceType()) {
                    "py", "json", "txt", "html" -> loadHome(selectedSourceId)
                    "web" -> loadHtmlContent(selectedSourceId)
                }
            }
            // 后台预加载所有列表源的首页数据
            preloadSources()
        }
    }

    /** 后台预加载所有列表源的首页数据（切换源时直接命中缓存） */
    private fun preloadSources() {
        val sources = _sources.value
        viewModelScope.launch(Dispatchers.IO) {
            sources.filter { isListSource(it.type) }.forEach { source ->
                if (homeCache.containsKey(source.id)) return@forEach
                try {
                    val info = VideoSourceRegistry.getSourceInfo(source.id) ?: return@forEach
                    val src = VideoSourceRegistry.createSource(info) ?: return@forEach
                    val (cats, vids) = src.home()
                    // 只缓存非空结果（避免把"源未加载"缓存为空数据）
                    if (cats.isNotEmpty() || vids.isNotEmpty()) {
                        homeCache[source.id] = HomeData(
                            categories = cats.map { Category(it.id, it.name) },
                            videos = vids.map { VideoItem(it.id, it.title, it.pic, it.remarks) },
                        )
                    }
                } catch (e: Exception) {
                    // 忽略预热错误
                }
            }
        }
    }

    /** 切换当前源（不清空缓存，切换回来直接命中） */
    fun selectSource(sourceId: String) {
        selectedSourceId = sourceId
        AppConfig.pyHomePluginId = sourceId
        searchResults = ""
        htmlContent = null
        homeData = null
        categoryData = null
        selectedCategoryId = ""
        currentPage = 1
        totalPages = 1
        isLoadingMore = false
        when (currentSourceType()) {
            "py", "json", "txt", "html" -> loadHome(sourceId)
            "web" -> loadHtmlContent(sourceId)
        }
    }

    /** 顶栏搜索按钮：切换搜索模式 */
    fun toggleSearchMode() {
        searchMode = !searchMode
        if (!searchMode) {
            searchKeyword = ""
            searchResults = ""
        }
    }

    fun onSearchKeywordChange(keyword: String) {
        searchKeyword = keyword
    }

    /** 执行搜索（记录历史，统一走 VideoSource 接口） */
    fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank() || isSearching) return
        val pluginId = selectedSourceId.ifBlank { _sources.value.firstOrNull()?.id ?: "" }
        if (pluginId.isBlank() || !isListSource(currentSourceType())) return
        selectedSourceId = pluginId

        searchKeyword = kw
        addHistory(kw)
        isSearching = true
        searchResults = ""
        viewModelScope.launch {
            val source = sourceFor(pluginId)
            val items = source?.search(kw, 1) ?: emptyList()
            searchResults = serializeSearchItems(items)
            isSearching = false
        }
    }

    /** 查看详情（搜索结果/收藏条目点击，统一走 VideoSource 接口） */
    fun showDetail(pluginId: String, id: String, title: String) {
        detailState = DetailState(pluginId, id, title, "", loading = true)
        viewModelScope.launch {
            val source = sourceFor(pluginId)
            val detail = source?.detail(id)
            val json = detail?.let { serializeDetail(it) } ?: "{}"
            detailState = DetailState(pluginId, id, title, json, loading = false)
        }
    }

    fun dismissDetail() {
        detailState = null
    }

    /** 播放：统一走 VideoSource 接口解析播放地址，启动 VideoPlayerActivity，传完整集数列表 */
    fun playVideo(pluginId: String, playUrls: List<Pair<String, String>>, title: String, initialIndex: Int = 0) {
        viewModelScope.launch {
            val initialPair = playUrls.getOrNull(initialIndex)
            val initialUrl = initialPair?.second ?: ""
            val source = sourceFor(pluginId)
            val result = source?.play(initialUrl)
            val resolvedUrl = result?.url ?: initialUrl
            if (resolvedUrl.isNotEmpty()) {
                // 构建集数列表 JSON（传完整列表给播放器，支持集数切换）
                val episodesJson = JSONArray().apply {
                    playUrls.forEach { (name, url) ->
                        put(JSONObject().apply {
                            put("title", name)
                            put("url", url)
                        })
                    }
                }.toString()
                appCtx.startActivity<VideoPlayerActivity> {
                    putExtra("videoUrl", resolvedUrl)
                    putExtra("videoTitle", title)
                    putExtra("episodesJson", episodesJson)
                    putExtra("pyPluginId", pluginId)
                    putExtra("initialEpisodeIndex", initialIndex)
                }
            }
        }
    }

    // ---- 首页数据 ----

    /** 加载首页数据（分类 + 推荐视频，统一走 VideoSource 接口） */
    fun loadHome(pluginId: String) {
        homeData = null
        homeError = null
        categoryData = null
        selectedCategoryId = ""
        currentPage = 1
        totalPages = 1
        isLoadingMore = false
        // 缓存命中直接返回
        homeCache[pluginId]?.let { cached ->
            homeData = cached
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val source = sourceFor(pluginId)
            if (source == null) {
                withContext(Dispatchers.Main) { homeError = "源不存在" }
                return@launch
            }
            try {
                val (cats, vids) = source.home()
                withContext(Dispatchers.Main) {
                    if (cats.isEmpty() && vids.isEmpty()) {
                        homeError = "该源暂无数据，请检查网络或切换其他源"
                    } else {
                        val data = HomeData(
                            categories = cats.map { Category(it.id, it.name) },
                            videos = vids.map { VideoItem(it.id, it.title, it.pic, it.remarks) },
                        )
                        homeData = data
                        homeCache[pluginId] = data
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { homeError = e.message ?: "源加载失败" }
            }
        }
    }

    /** 加载分类视频列表（统一走 VideoSource 接口） */
    fun loadCategory(pluginId: String, tid: String, pg: Int) {
        selectedCategoryId = tid
        homeError = null
        currentPage = pg
        totalPages = 1
        categoryData = CategoryData(pg, 1, emptyList())
        isLoadingMore = false
        // 缓存命中直接返回
        val cacheKey = categoryCacheKey(pluginId, tid, pg)
        categoryCache[cacheKey]?.let { cached ->
            categoryData = cached
            currentPage = cached.page
            totalPages = cached.pagecount
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val source = sourceFor(pluginId)
            if (source == null) {
                withContext(Dispatchers.Main) { homeError = "源不存在" }
                return@launch
            }
            try {
                val result = source.category(tid, pg)
                withContext(Dispatchers.Main) {
                    val data = CategoryData(
                        result.page,
                        result.pageCount,
                        result.videos.map { v -> VideoItem(v.id, v.title, v.pic, v.remarks) },
                    )
                    categoryData = data
                    currentPage = data.page
                    totalPages = data.pagecount
                    categoryCache[cacheKey] = data
                    if (data.videos.isEmpty()) {
                        homeError = "该分类暂无数据"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { homeError = e.message ?: "分类加载失败" }
            }
        }
    }

    /** 加载下一页 */
    fun loadNextPage() {
        if (currentPage < totalPages) {
            loadCategory(selectedSourceId, selectedCategoryId, currentPage + 1)
        }
    }

    /** 加载上一页 */
    fun loadPrevPage() {
        if (currentPage > 1) {
            loadCategory(selectedSourceId, selectedCategoryId, currentPage - 1)
        }
    }

    /** 滚动翻页：加载下一页并追加到现有列表（统一走 VideoSource 接口） */
    fun loadMorePage() {
        if (isLoadingMore || currentPage >= totalPages || selectedCategoryId.isEmpty()) return
        isLoadingMore = true
        viewModelScope.launch {
            try {
                val nextPg = currentPage + 1
                val cacheKey = categoryCacheKey(selectedSourceId, selectedCategoryId, nextPg)
                val result = withContext(Dispatchers.IO) {
                    try {
                        sourceFor(selectedSourceId)?.category(selectedCategoryId, nextPg)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (result != null && result.videos.isNotEmpty()) {
                    val existing = categoryData
                    if (existing != null) {
                        categoryData = CategoryData(
                            result.page,
                            result.pageCount,
                            existing.videos + result.videos.map { v ->
                                VideoItem(v.id, v.title, v.pic, v.remarks)
                            }
                        )
                    }
                    currentPage = result.page
                    totalPages = result.pageCount
                    categoryCache[cacheKey] = CategoryData(
                        result.page,
                        result.pageCount,
                        result.videos.map { v -> VideoItem(v.id, v.title, v.pic, v.remarks) },
                    )
                }
            } finally {
                isLoadingMore = false
            }
        }
    }

    /** 选择分类标签 */
    fun selectCategory(tid: String) {
        if (tid == selectedCategoryId) return
        if (tid.isEmpty()) {
            selectedCategoryId = ""
            categoryData = null
            currentPage = 1
            totalPages = 1
            isLoadingMore = false
        } else {
            loadCategory(selectedSourceId, tid, 1)
        }
    }

    // ---- 导入 ----

    /** SAF 文件选择回调：记录 Uri 并弹出命名弹窗 */
    fun onFilePicked(uri: Uri, defaultName: String) {
        importUri = uri
        importDefaultName = defaultName
        showImportDialog = true
    }

    /** SAF 多选文件回调：批量导入，使用文件名作为默认名称 */
    fun onFilesPicked(uris: List<Pair<Uri, String>>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0
            uris.forEach { (uri, defaultName) ->
                val displayName = defaultName.ifBlank { "imported_${System.currentTimeMillis()}" }
                val pluginId = displayName.substringBeforeLast('.')
                val content = appCtx.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: ""
                val fileName = defaultName.lowercase()
                val detect = detectSourceType(fileName, content)
                val ok = when (detect) {
                    "py" -> PyLoader.installFromUri(uri, displayName)
                    "json" -> HomeSourceStore.importVideoSource(uri, displayName, "json")
                    "txt" -> HomeSourceStore.importVideoSource(uri, displayName, "txt")
                    "html" -> HomeSourceStore.importVideoSource(uri, displayName, "html")
                    else -> HomeSourceStore.importHtml(uri, displayName)
                }
                if (ok) {
                    successCount++
                } else {
                    failCount++
                }
            }
            val msg = if (successCount > 0 && failCount == 0) {
                "成功导入 $successCount 个源"
            } else if (successCount > 0 && failCount > 0) {
                "成功 $successCount 个，失败 $failCount 个"
            } else {
                "导入失败"
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                refreshSources()
            }
        }
    }

    /** 命名确认后执行导入（py/html/json/txt 按扩展名 + 内容检测） */
    fun confirmImportName(name: String) {
        val uri = importUri
        if (uri == null) return
        showImportDialog = false
        val displayName = name.trim().ifBlank { importDefaultName }
        val pluginId = displayName.substringBeforeLast('.')
        viewModelScope.launch(Dispatchers.IO) {
            // 读取文件内容以检测类型（SAF content:// URI 的 lastPathSegment 不可靠）
            val content = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val fileName = importDefaultName.lowercase()
            val detect = detectSourceType(fileName, content)
            val ok = when (detect) {
                "py" -> PyLoader.installFromUri(uri, displayName)
                "json" -> HomeSourceStore.importVideoSource(uri, displayName, "json")
                "txt" -> HomeSourceStore.importVideoSource(uri, displayName, "txt")
                "html" -> HomeSourceStore.importVideoSource(uri, displayName, "html")
                else -> HomeSourceStore.importHtml(uri, displayName)
            }
            val msg = if (!ok) {
                "导入失败"
            } else when (detect) {
                "py" -> PyLoader.getLoadError(pluginId)?.let {
                    "py 源已导入，但未能加载：$it"
                } ?: "py 源导入成功"
                "json" -> "JSON 源导入成功"
                "txt" -> "TXT 源导入成功"
                "html" -> "HTML 源导入成功"
                else -> "HTML 页面导入成功"
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                importUri = null
                refreshSources()
            }
        }
    }

    /**
     * 检测源文件类型：扩展名优先，内容特征兜底。
     * 返回 "py" | "json" | "txt" | "html" | "web"。
     * "html" = HTML 配置源（JSON 含 rules，走 VideoSource 接口）
     * "web" = WebView HTML 页面（直接渲染）
     */
    private fun detectSourceType(fileName: String, content: String): String {
        val trimmed = content.trimStart()
        return when {
            fileName.endsWith(".py") -> "py"
            fileName.endsWith(".html") || fileName.endsWith(".htm") -> "web"
            fileName.endsWith(".txt") -> "txt"
            fileName.endsWith(".json") -> {
                if (isHtmlConfig(content)) "html" else "json"
            }
            trimmed.startsWith("{") || trimmed.startsWith("[") -> {
                if (isHtmlConfig(content)) "html" else "json"
            }
            else -> if (content.isPythonSource()) "py" else "web"
        }
    }

    /** 判断是否为 HTML 配置源（JSON 含 "type":"html" 或 "rules" 字段） */
    private fun isHtmlConfig(content: String): Boolean {
        return content.contains("\"type\":\"html\"") || content.contains("\"type\": \"html\"") ||
            content.contains("\"rules\"")
    }

    /** 判断文件内容是否为 Python 源码 */
    private fun String.isPythonSource(): Boolean {
        if (isBlank()) return false
        // Python 文件通常包含 def/import/class/@ 等关键字
        // HTML 文件包含 <html>/<!DOCTYPE/<body 等标签
        val hasPython = contains("def ") || contains("import ") || contains("class ") ||
            contains("@app") || contains("#!/usr/bin/env python") || startsWith("#!")
        val hasHtml = contains("<html") || contains("<!DOCTYPE") || contains("<body") ||
            contains("<head") || contains("<!doctype")
        // 如果同时包含（如 HTML 中嵌入 Python），优先判断 Python
        if (hasPython) return true
        // 纯 HTML 特征 → 不是 Python
        if (hasHtml) return false
        // 既不像 Python 也不像 HTML，保守判断为非 Python（HTML 兜底）
        return false
    }

    fun cancelImport() {
        importUri = null
        importDefaultName = ""
        showImportDialog = false
    }

    // ---- TVbox 订阅导入 ----

    /** 从 TVbox 订阅 URL 导入：fetch JSON → FongMiConfigParser 解析 → 每个 CMS 站点存为 JSON 源 */
    fun importTvboxSource(url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 使用 VideoHttpClient 获取订阅内容
                val content = io.legado.app.data.video.VideoHttpClient.get(trimmedUrl, forceRefresh = true)
                    ?: ""
                if (content.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "订阅接口获取失败", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val sites = io.legado.app.data.video.FongMiConfigParser.parse(content)
                if (sites.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "订阅内容为空或格式错误", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                var successCount = 0
                sites.forEach { site ->
                    if (site.api.isBlank()) return@forEach
                    val sourceId = "tvbox_${site.key.ifBlank { System.currentTimeMillis().toString() }}"
                    val configJson = org.json.JSONObject().apply {
                        put("name", site.name)
                        put("type", "cms")
                        put("api", site.api)
                        site.searchApi.takeIf { it.isNotBlank() }?.let { put("searchApi", it) }
                    }.toString()
                    val ok = HomeSourceStore.saveVideoSourceFromContent(
                        sourceId, site.name, "json", configJson
                    )
                    if (ok) successCount++
                }
                // 保存订阅 URL 到偏好
                val subs = loadTvboxSubscriptions()
                if (!subs.contains(trimmedUrl)) {
                    saveTvboxSubscriptions(subs + trimmedUrl)
                }
                withContext(Dispatchers.Main) {
                    val msg = if (successCount > 0) {
                        "成功导入 $successCount 个 TVbox 源"
                    } else {
                        "订阅内容无可用站点"
                    }
                    Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                    refreshSources()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "TVbox 导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadTvboxSubscriptions(): List<String> {
        return try {
            val json = appCtx.getPrefString(io.legado.app.constant.PreferKey.pyTvboxSubscriptions, "") ?: ""
            if (json.isBlank()) emptyList()
            else org.json.JSONArray(json).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveTvboxSubscriptions(list: List<String>) {
        try {
            val arr = org.json.JSONArray()
            list.forEach { arr.put(it) }
            appCtx.putPrefString(io.legado.app.constant.PreferKey.pyTvboxSubscriptions, arr.toString())
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---- 自定义弹幕 API ----

    /** 保存自定义弹幕 API URL */
    fun saveDanmakuApiUrl(url: String) {
        val trimmed = url.trim()
        danmakuApiUrl = trimmed
        appCtx.putPrefString(io.legado.app.constant.PreferKey.pyDanmakuApiUrl, trimmed)
    }

    // ---- 管理 ----

    fun openManageDialog() {
        showManageDialog = true
    }

    fun dismissManageDialog() {
        showManageDialog = false
    }

    /** 删除源（py 卸载 / html 配置删除 / json-txt 删除 / web 页面删除） */
    fun deleteSource(source: HomeSource) {
        viewModelScope.launch(Dispatchers.IO) {
            when (source.type) {
                "py" -> PyLoader.uninstall(source.id)
                "json", "txt", "html" -> HomeSourceStore.deleteVideoSource(source.id)
                else -> HomeSourceStore.deleteHtml(source.id)
            }
            withContext(Dispatchers.Main) {
                refreshSources()
            }
        }
    }

    /** 加载选中 HTML 页面内容 */
    fun loadHtmlContent(sourceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            htmlContent = HomeSourceStore.readHtml(sourceId)
        }
    }

    // ---- 收藏 ----

    fun isFavorite(pluginId: String, id: String): Boolean {
        return favorites.any { it.pluginId == pluginId && it.id == id }
    }

    fun toggleFavorite(pluginId: String, id: String, title: String, url: String) {
        val item = PyFavorite(pluginId, id, title, url, System.currentTimeMillis())
        val updated = if (favorites.any { it.pluginId == pluginId && it.id == id }) {
            favorites.filterNot { it.pluginId == pluginId && it.id == id }
        } else {
            listOf(item) + favorites
        }
        favorites = updated
        saveFavorites(updated)
    }

    // ---- 历史 ----

    fun clearHistory() {
        searchHistory = emptyList()
        saveHistory(emptyList())
    }

    private fun addHistory(word: String) {
        val updated = (listOf(word) + searchHistory.filter { it != word }).take(20)
        searchHistory = updated
        saveHistory(updated)
    }

    // ---- 统一视频源辅助 ----

    /** 创建统一源实例（py/json/txt/html，利用 VideoSourceRegistry 实例缓存） */
    private suspend fun sourceFor(sourceId: String): VideoSource? {
        return withContext(Dispatchers.IO) {
            val info = VideoSourceRegistry.getSourceInfo(sourceId) ?: return@withContext null
            VideoSourceRegistry.createSource(info)
        }
    }

    /** 搜索结果列表 → JSON 字符串（UI parseSearchResults 兼容格式） */
    private fun serializeSearchItems(items: List<io.legado.app.data.video.VideoItem>): String {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("url", it.url)
                }
            )
        }
        return arr.toString()
    }

    /** VideoDetail → JSON 字符串（UI parsePlayUrls 兼容 play_urls 格式） */
    private fun serializeDetail(detail: io.legado.app.data.video.VideoDetail): String {
        return JSONObject().apply {
            put("id", detail.id)
            put("title", detail.title)
            put("pic", detail.pic)
            put("desc", detail.desc)
            put(
                "play_urls",
                JSONArray().apply {
                    detail.playLines.forEach {
                        put(JSONObject().apply {
                            put("name", it.name)
                            put("url", it.url)
                        })
                    }
                },
            )
        }.toString()
    }

    // ---- 持久化 ----

    private fun loadHistory(): List<String> {
        return try {
            val json = appCtx.getPrefString(PreferKey.pySearchHistory, "") ?: ""
            if (json.isBlank()) emptyList()
            else {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(list: List<String>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            appCtx.putPrefString(PreferKey.pySearchHistory, arr.toString())
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun loadFavorites(): List<PyFavorite> {
        return try {
            val json = appCtx.getPrefString(PreferKey.pyFavorites, "") ?: ""
            if (json.isBlank()) emptyList()
            else {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    PyFavorite(
                        pluginId = obj.optString("pluginId"),
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        url = obj.optString("url"),
                        time = obj.optLong("time"),
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveFavorites(list: List<PyFavorite>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject().apply {
                        put("pluginId", it.pluginId)
                        put("id", it.id)
                        put("title", it.title)
                        put("url", it.url)
                        put("time", it.time)
                    }
                )
            }
            appCtx.putPrefString(PreferKey.pyFavorites, arr.toString())
        } catch (e: Exception) {
            // ignore
        }
    }
}
