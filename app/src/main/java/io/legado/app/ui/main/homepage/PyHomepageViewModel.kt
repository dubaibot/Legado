package io.legado.app.ui.main.homepage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.help.python.HomeBridge
import io.legado.app.help.python.PyLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Python 首页 ViewModel：管理插件列表、搜索状态和搜索结果。
 *
 * 状态：
 * - plugins: 已安装插件列表
 * - selectedPluginId: 当前选中的插件（用于单源浏览）
 * - searchKeyword: 搜索关键词
 * - searchResults: 搜索结果 JSON 字符串
 * - searchAll: 是否搜索全部插件
 * - isSearching: 是否正在搜索
 * - showBottomSheet: 是否显示底部弹窗
 */
class PyHomepageViewModel : ViewModel() {

    private val _plugins = MutableStateFlow<List<PyLoader.PyPlugin>>(emptyList())
    val plugins = _plugins.asStateFlow()

    var selectedPluginId by mutableStateOf("")
        private set

    var searchKeyword by mutableStateOf("")
        private set

    var searchResults by mutableStateOf("")
        private set

    var searchAll by mutableStateOf(false)
        private set

    var isSearching by mutableStateOf(false)
        private set

    var showBottomSheet by mutableStateOf(false)
        private set

    init {
        loadPlugins()
    }

    /** 加载插件列表 */
    fun loadPlugins() {
        viewModelScope.launch {
            _plugins.update { PyLoader.listInstalled() }
        }
    }

    /** 选中插件 */
    fun selectPlugin(pluginId: String) {
        selectedPluginId = pluginId
        searchAll = false
        searchResults = ""
        searchKeyword = ""
    }

    /** 搜索关键词变化回调 */
    fun onSearchKeywordChange(keyword: String) {
        searchKeyword = keyword
    }

    /** 切换搜索全部模式 */
    fun toggleSearchAll() {
        searchAll = !searchAll
        if (searchAll) {
            selectedPluginId = ""
        }
    }

    /** 执行搜索 */
    fun search(keyword: String) {
        searchKeyword = keyword
        if (keyword.isBlank()) {
            searchResults = ""
            return
        }

        isSearching = true
        viewModelScope.launch {
            if (searchAll) {
                searchAllPlugins(keyword)
            } else {
                searchSinglePlugin(keyword)
            }
            isSearching = false
        }
    }

    /** 搜索单个插件 */
    private suspend fun searchSinglePlugin(keyword: String) {
        val pluginId = selectedPluginId
        if (pluginId.isBlank()) return

        val result = withContext(Dispatchers.IO) {
            var text: String? = null
            HomeBridge.search(pluginId, keyword) { text = it }
            text
        }
        searchResults = result ?: "[]"
    }

    /** 搜索全部插件 */
    private suspend fun searchAllPlugins(keyword: String) {
        val pluginList = _plugins.value
        if (pluginList.isEmpty()) return

        val allResults = mutableListOf<String>()

        for (plugin in pluginList) {
            val result = withContext(Dispatchers.IO) {
                var text: String? = null
                HomeBridge.search(plugin.meta.id, keyword) { text = it }
                text
            }
            if (result != null && result != "[]" && result != "null") {
                allResults.add(result)
            }
        }

        // 合并结果（简单拼接，实际应解析 JSON 后合并）
        searchResults = allResults.joinToString(",", "[", "]")
    }

    /** 获取插件详情 */
    fun getPluginDetail(pluginId: String): String? {
        val plugin = _plugins.value.find { it.meta.id == pluginId }
        if (plugin == null) return null
        showBottomSheet = true
        return plugin.meta.name
    }

    /** 关闭底部弹窗 */
    fun dismissBottomSheet() {
        showBottomSheet = false
    }

    /** 刷新插件列表 */
    fun refreshPlugins() {
        loadPlugins()
        HomeBridge.clearCache()
        HomeBridge.refreshAllMeta()
    }
}
