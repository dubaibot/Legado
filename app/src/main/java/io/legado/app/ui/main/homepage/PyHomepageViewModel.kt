package io.legado.app.ui.main.homepage

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.PreferKey
import io.legado.app.data.video.DanmakuItem
import io.legado.app.data.video.VideoSource
import io.legado.app.data.video.VideoSourceRegistry
import io.legado.app.help.config.AppConfig
import io.legado.app.help.python.HomeBridge
import io.legado.app.help.python.PyLoader
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import kotlin.coroutines.resume

/**
 * 影视首页 ViewModel。
 *
 * 职责：
 * - 统一管理首页源（py 插件源 + HTML 自定义页面 + JSON API 源 + TXT 列表源）
 * - 顶栏搜索（py/json/txt 源）
 * - 搜索历史 / 收藏（SharedPreferences JSON 持久化）
 * - 导入 py/html/json/txt 源（SAF 回调后在此执行文件 IO）
 * - 弹幕加载与开关
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

    /** 播放解析后的 m3u8 URL（非空时显示播放器浮层） */
    var playUrl by mutableStateOf<String?>(null)
        private set

    /** 首页数据（分类 + 推荐视频） */
    var homeData by mutableStateOf<HomeData?>(null)
        private set

    /** 首页/分类加载错误信息（非空时 UI 显示） */
    var homeError by mutableStateOf<String?>(null)
        private set

    /** 分类浏览数据 */
    var categoryData by mutableStateOf<CategoryData?>(null)
        private set

    /** 当前选中分类 ID（空 = 首页推荐） */
    var selectedCategoryId by mutableStateOf("")
        private set

    /** 当前播放的弹幕列表（随播放器加载/清空） */
    var danmakuItems by mutableStateOf<List<DanmakuItem>>(emptyList())
        private set

    /** 弹幕显示开关 */
    var danmakuEnabled by mutableStateOf(true)
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

    init {
        refreshSources()
        searchHistory = loadHistory()
        favorites = loadFavorites()
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

    /** 当前源类型是否支持列表式浏览（py/json/txt 走统一数据模型） */
    fun isListSource(type: String?): Boolean = type == "py" || type == "json" || type == "txt"

    /** 获取指定 py 源的加载错误信息 */
    fun loadErrorFor(id: String): String? {
        return if (_sources.value.any { it.id == id && it.type == "py" }) {
            PyLoader.getLoadError(id)
        } else null
    }

    /** 从磁盘重载源列表（导入/删除后调用） */
    fun refreshSources() {
        viewModelScope.launch(Dispatchers.IO) {
            HomeBridge.clearCache()
            val pyList = PyLoader.listInstalled().map {
                HomeSource(it.meta.id, it.meta.name, "py", it.loaded)
            }
            HomeBridge.refreshAllMeta()
            val htmlList = HomeSourceStore.listHtml().map {
                HomeSource(it.id, it.name, "html", true)
            }
            val videoSources = HomeSourceStore.listVideoSources().map {
                HomeSource(it.id, it.name, it.type, true)
            }
            withContext(Dispatchers.Main) {
                _sources.value = pyList + videoSources + htmlList
                if (_sources.value.none { it.id == selectedSourceId }) {
                    selectedSourceId = _sources.value.firstOrNull()?.id ?: ""
                    if (selectedSourceId.isNotEmpty()) {
                        AppConfig.pyHomePluginId = selectedSourceId
                    }
                }
                // 选中源时加载对应内容
                when (currentSourceType()) {
                    "py", "json", "txt" -> loadHome(selectedSourceId)
                    "html" -> loadHtmlContent(selectedSourceId)
                }
            }
        }
    }

    /** 切换当前源 */
    fun selectSource(sourceId: String) {
        selectedSourceId = sourceId
        AppConfig.pyHomePluginId = sourceId
        searchResults = ""
        htmlContent = null
        homeData = null
        categoryData = null
        selectedCategoryId = ""
        when (currentSourceType()) {
            "py", "json", "txt" -> loadHome(sourceId)
            "html" -> loadHtmlContent(sourceId)
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

    /** 执行搜索（记录历史，py/json/txt 源） */
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
            val result = when (currentSourceType()) {
                "py" -> bridgeSearch(pluginId, kw)
                else -> {
                    val source = sourceFor(pluginId)
                    val items = source?.search(kw, 1) ?: emptyList()
                    serializeSearchItems(items)
                }
            }
            searchResults = result ?: "[]"
            isSearching = false
        }
    }

    /** 查看详情（搜索结果/收藏条目点击，py/json/txt 源） */
    fun showDetail(pluginId: String, id: String, title: String) {
        detailState = DetailState(pluginId, id, title, "", loading = true)
        viewModelScope.launch {
            val json = when (sourceTypeOf(pluginId)) {
                "py" -> bridgeDetail(pluginId, id)
                else -> {
                    val detail = sourceFor(pluginId)?.detail(id)
                    detail?.let { serializeDetail(it) }
                }
            }
            detailState = DetailState(pluginId, id, title, json ?: "{}", loading = false)
        }
    }

    fun dismissDetail() {
        detailState = null
    }

    /** 播放：解析播放地址（py 走插件 play，json/txt 直连），并加载弹幕 */
    fun playVideo(pluginId: String, playPageUrl: String) {
        viewModelScope.launch {
            val type = sourceTypeOf(pluginId)
            val url = when (type) {
                "py" -> {
                    val result = bridgePlay(pluginId, playPageUrl)
                    try {
                        JSONObject(result ?: "").optString("url", "")
                    } catch (e: Exception) {
                        ""
                    }
                }
                else -> {
                    val source = sourceFor(pluginId)
                    val result = source?.play(playPageUrl)
                    result?.url ?: ""
                }
            }
            if (url.isNotEmpty()) {
                playUrl = url
                danmakuItems = emptyList()
                loadDanmaku(pluginId, playPageUrl)
            }
        }
    }

    fun dismissPlayer() {
        playUrl = null
        danmakuItems = emptyList()
    }

    /** 切换弹幕显示 */
    fun toggleDanmaku() {
        danmakuEnabled = !danmakuEnabled
    }

    /** 加载弹幕：json/txt 源走统一接口，py 源暂不支持 */
    private fun loadDanmaku(sourceId: String, videoId: String) {
        if (sourceTypeOf(sourceId) == "py") return
        viewModelScope.launch(Dispatchers.IO) {
            val list = try {
                sourceFor(sourceId)?.danmaku(videoId)
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                danmakuItems = list?.items ?: emptyList()
            }
        }
    }

    // ---- 首页数据 ----

    /** 加载首页数据（分类 + 推荐视频，py/json/txt 源） */
    fun loadHome(pluginId: String) {
        homeData = null
        homeError = null
        categoryData = null
        selectedCategoryId = ""
        when (sourceTypeOf(pluginId)) {
            "py" -> {
                viewModelScope.launch {
                    val result = bridgeHome(pluginId)
                    homeData = parseHomeData(result ?: "") ?: HomeData(emptyList(), emptyList())
                }
            }
            else -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val (cats, vids) = try {
                        sourceFor(pluginId)?.home() ?: Pair(emptyList(), emptyList())
                    } catch (e: Exception) {
                        homeError = e.message ?: "源加载失败"
                        Pair(emptyList(), emptyList())
                    }
                    withContext(Dispatchers.Main) {
                        homeData = HomeData(
                            categories = cats.map { Category(it.id, it.name) },
                            videos = vids.map {
                                VideoItem(it.id, it.title, it.pic, it.remarks)
                            },
                        )
                    }
                }
            }
        }
    }

    /** 加载分类视频列表（py/json/txt 源） */
    fun loadCategory(pluginId: String, tid: String, pg: Int) {
        selectedCategoryId = tid
        homeError = null
        categoryData = CategoryData(pg, 1, emptyList())
        when (sourceTypeOf(pluginId)) {
            "py" -> {
                viewModelScope.launch {
                    val result = bridgeCategory(pluginId, tid, pg)
                    if (result != null) {
                        categoryData = parseCategoryData(result, pg)
                    }
                }
            }
            else -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val result = try {
                        sourceFor(pluginId)?.category(tid, pg)
                    } catch (e: Exception) {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        categoryData = result?.let {
                            CategoryData(
                                it.page,
                                it.pageCount,
                                it.videos.map { v -> VideoItem(v.id, v.title, v.pic, v.remarks) },
                            )
                        } ?: CategoryData(pg, 1, emptyList())
                    }
                }
            }
        }
    }

    /** 选择分类标签 */
    fun selectCategory(tid: String) {
        if (tid == selectedCategoryId) return
        if (tid.isEmpty()) {
            selectedCategoryId = ""
            categoryData = null
        } else {
            loadCategory(selectedSourceId, tid, 1)
        }
    }

    private suspend fun bridgeHome(pluginId: String): String? =
        suspendCancellableCoroutine { cont ->
            HomeBridge.home(pluginId) { cont.resume(it) }
        }

    private suspend fun bridgeCategory(pluginId: String, tid: String, pg: Int): String? =
        suspendCancellableCoroutine { cont ->
            HomeBridge.category(pluginId, tid, pg) { cont.resume(it) }
        }

    private fun parseHomeData(json: String): HomeData? {
        return try {
            val obj = JSONObject(json)
            val errorMsg = obj.optString("error", "")
            if (errorMsg.isNotEmpty()) {
                homeError = errorMsg
                return null
            }
            val cats = obj.optJSONArray("categories") ?: return null
            val categories = (0 until cats.length()).mapNotNull { i ->
                val c = cats.optJSONObject(i) ?: return@mapNotNull null
                Category(c.optString("id"), c.optString("name"))
            }
            val vids = obj.optJSONArray("videos") ?: return null
            val videos = (0 until vids.length()).mapNotNull { i ->
                val v = vids.optJSONObject(i) ?: return@mapNotNull null
                VideoItem(v.optString("id"), v.optString("title"), v.optString("pic"), v.optString("remarks"))
            }
            HomeData(categories, videos)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCategoryData(json: String, defaultPg: Int): CategoryData {
        return try {
            val obj = JSONObject(json)
            val errorMsg = obj.optString("error", "")
            if (errorMsg.isNotEmpty()) {
                homeError = errorMsg
                return CategoryData(defaultPg, 1, emptyList())
            }
            val pg = obj.optInt("page", defaultPg)
            val pc = obj.optInt("pagecount", 1)
            val vids = obj.optJSONArray("videos") ?: return CategoryData(pg, pc, emptyList())
            val videos = (0 until vids.length()).mapNotNull { i ->
                val v = vids.optJSONObject(i) ?: return@mapNotNull null
                VideoItem(v.optString("id"), v.optString("title"), v.optString("pic"), v.optString("remarks"))
            }
            CategoryData(pg, pc, videos)
        } catch (e: Exception) {
            CategoryData(defaultPg, 1, emptyList())
        }
    }

    // ---- 导入 ----

    /** SAF 文件选择回调：记录 Uri 并弹出命名弹窗 */
    fun onFilePicked(uri: Uri, defaultName: String) {
        importUri = uri
        importDefaultName = defaultName
        showImportDialog = true
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
     * 返回 "py" | "json" | "txt" | "html"。
     */
    private fun detectSourceType(fileName: String, content: String): String {
        val trimmed = content.trimStart()
        return when {
            fileName.endsWith(".py") -> "py"
            fileName.endsWith(".json") -> "json"
            fileName.endsWith(".txt") -> "txt"
            fileName.endsWith(".html") || fileName.endsWith(".htm") -> "html"
            trimmed.startsWith("{") || trimmed.startsWith("[") -> "json"
            else -> if (content.isPythonSource()) "py" else "html"
        }
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

    // ---- 管理 ----

    fun showManageDialog() {
        showManageDialog = true
    }

    fun dismissManageDialog() {
        showManageDialog = false
    }

    /** 删除源（py 卸载 / html 删除 / json-txt 删除） */
    fun deleteSource(source: HomeSource) {
        viewModelScope.launch(Dispatchers.IO) {
            when (source.type) {
                "py" -> PyLoader.uninstall(source.id)
                "json", "txt" -> HomeSourceStore.deleteVideoSource(source.id)
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

    // ---- HomeBridge 异步包装（回调 -> 挂起） ----

    private suspend fun bridgeSearch(pluginId: String, keyword: String): String? =
        suspendCancellableCoroutine { cont ->
            HomeBridge.search(pluginId, keyword) { cont.resume(it) }
        }

    private suspend fun bridgeDetail(pluginId: String, id: String): String? =
        suspendCancellableCoroutine { cont ->
            HomeBridge.detail(pluginId, id) { cont.resume(it) }
        }

    private suspend fun bridgePlay(pluginId: String, url: String): String? =
        suspendCancellableCoroutine { cont ->
            HomeBridge.play(pluginId, url) { cont.resume(it) }
        }

    // ---- 统一视频源辅助 ----

    /** 获取指定源的类型（"py" | "html" | "json" | "txt" | null） */
    private fun sourceTypeOf(sourceId: String): String? {
        return _sources.value.find { it.id == sourceId }?.type
    }

    /** 创建统一源实例（py/json/txt，html 返回 null） */
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
