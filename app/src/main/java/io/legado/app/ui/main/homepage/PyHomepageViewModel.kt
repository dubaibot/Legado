package io.legado.app.ui.main.homepage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.python.HomeBridge
import io.legado.app.help.python.PyLoader
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
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
 * Python 影视首页 ViewModel。
 *
 * 职责：
 * - 管理已安装 py 源列表（IO 线程加载）
 * - 顶栏搜索（单一选中源）
 * - 搜索历史 / 收藏（SharedPreferences JSON 持久化）
 * - 自定义首页开关（开启后内容区显示原首页推荐模块）
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

    /** 详情弹窗状态 */
    data class DetailState(
        val pluginId: String,
        val id: String,
        val title: String,
        val json: String,
        val loading: Boolean = false,
    )

    private val _plugins = MutableStateFlow<List<PyLoader.PyPlugin>>(emptyList())
    val plugins = _plugins.asStateFlow()

    var selectedPluginId by mutableStateOf(AppConfig.pyHomePluginId)
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

    /** 自定义首页开关（镜像 AppConfig.customHomepageEnabled） */
    var customHomepageEnabled by mutableStateOf(AppConfig.customHomepageEnabled)
        private set

    var searchHistory by mutableStateOf<List<String>>(emptyList())
        private set

    var favorites by mutableStateOf<List<PyFavorite>>(emptyList())
        private set

    var detailState by mutableStateOf<DetailState?>(null)
        private set

    /** 源管理弹窗 */
    var showManageDialog by mutableStateOf(false)
        private set

    init {
        refreshPlugins()
        searchHistory = loadHistory()
        favorites = loadFavorites()
        // 首启自动安装内置源为异步完成，延迟后再刷一次兜底
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(8000)
            withContext(Dispatchers.Main) {
                if (_plugins.value.isEmpty()) {
                    refreshPlugins()
                }
            }
        }
    }

    /** 当前选中源显示名（标题用） */
    fun currentSourceName(): String? {
        if (selectedPluginId.isBlank()) return null
        return _plugins.value.find { it.meta.id == selectedPluginId }?.meta?.name
    }

    /** 从磁盘重载插件列表（导入/卸载后调用） */
    fun refreshPlugins() {
        viewModelScope.launch(Dispatchers.IO) {
            HomeBridge.clearCache()
            val list = PyLoader.listInstalled()
            HomeBridge.refreshAllMeta()
            withContext(Dispatchers.Main) {
                _plugins.value = list
                if (list.none { it.meta.id == selectedPluginId }) {
                    selectedPluginId = list.firstOrNull()?.meta?.id ?: ""
                    AppConfig.pyHomePluginId = selectedPluginId
                }
            }
        }
    }

    /** 切换当前源（分组选择） */
    fun selectPlugin(pluginId: String) {
        selectedPluginId = pluginId
        AppConfig.pyHomePluginId = pluginId
        searchResults = ""
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

    /** 切换自定义首页开关 */
    fun toggleCustomHomepage() {
        customHomepageEnabled = !customHomepageEnabled
        AppConfig.customHomepageEnabled = customHomepageEnabled
    }

    /** 执行搜索（记录历史） */
    fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank() || isSearching) return
        val pluginId = selectedPluginId.ifBlank { _plugins.value.firstOrNull()?.meta?.id ?: "" }
        if (pluginId.isBlank()) return
        selectedPluginId = pluginId

        searchKeyword = kw
        addHistory(kw)
        isSearching = true
        searchResults = ""
        viewModelScope.launch {
            val result = bridgeSearch(pluginId, kw)
            searchResults = result ?: "[]"
            isSearching = false
        }
    }

    /** 查看详情（搜索结果/收藏条目点击） */
    fun showDetail(pluginId: String, id: String, title: String) {
        detailState = DetailState(pluginId, id, title, "", loading = true)
        viewModelScope.launch {
            val json = bridgeDetail(pluginId, id)
            detailState = DetailState(pluginId, id, title, json ?: "{}", loading = false)
        }
    }

    fun dismissDetail() {
        detailState = null
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

    // ---- 管理 ----

    fun showManageDialog() {
        showManageDialog = true
    }

    fun dismissManageDialog() {
        showManageDialog = false
    }

    fun uninstallPlugin(pluginId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            PyLoader.uninstall(pluginId)
            withContext(Dispatchers.Main) {
                refreshPlugins()
            }
        }
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
