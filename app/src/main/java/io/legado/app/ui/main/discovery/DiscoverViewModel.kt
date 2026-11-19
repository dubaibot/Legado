package io.legado.app.ui.main.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import splitties.init.appCtx
import io.legado.app.ui.main.homepage.HomeSourceManagementStore
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 统一发现页的源类型 */
enum class DiscoverSourceType { BOOK, RSS, HOME }

/** 统一发现页的源项，聚合书源、订阅源、首页源的展示字段 */
data class DiscoverSourceItem(
    val id: String,
    val name: String,
    val type: DiscoverSourceType,
    val url: String,
    val group: String?,
    val hasLogin: Boolean,
    val hasExplore: Boolean,
    val startHtml: String? = null,
    val sortUrl: String? = null,
    val pickerType: String = if (type == DiscoverSourceType.HOME) "TVBOX" else type.name,
    val key: String = "$type:$id",
)

/**
 * 发现页 ViewModel。
 *
 * 聚合书源、订阅源、首页源三类源，供统一发现页顶栏切换与内容网格展示。
 * 书源/订阅源走 Room Flow 实时刷新；首页源来自 HomeSourceManagementStore，
 * 由 [refresh] 在进入页面与源变更时重载。
 */
class DiscoverViewModel : ViewModel() {

    private var refreshJob: Job? = null

    private val _sources = MutableStateFlow<List<DiscoverSourceItem>>(emptyList())
    val sources = _sources.asStateFlow()

    private val _currentSourceKey = MutableStateFlow(
        appCtx.getPrefString(PreferKey.discoverSelectedSource, "").orEmpty(),
    )
    val currentSourceKey = _currentSourceKey.asStateFlow()

    /** 当前选中的源，使用类型与原始 id 组成的 key 隔离选择状态。 */
    val currentSource: StateFlow<DiscoverSourceItem?> =
        combine(_sources, _currentSourceKey) { list, key ->
            list.find { it.key == key }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 顶栏显示的当前源名称，无源时返回「发现」 */
    val currentSourceName: StateFlow<String> =
        currentSource.map { it?.name ?: "发现" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "发现")

    /** 顶栏登录按钮是否显示：当前源有登录地址时才显示 */
    val loginVisible: StateFlow<Boolean> =
        currentSource.map { it?.hasLogin == true }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        refresh()
    }

    /** 切换顶栏当前源 */
    fun selectSource(key: String) {
        _currentSourceKey.value = key
        appCtx.putPrefString(PreferKey.discoverSelectedSource, key)
    }

    /** 刷新书源、订阅源（Room Flow 自动刷新），并重载首页源 */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            combine(
                appDb.bookSourceDao.flowAll(),
                appDb.rssSourceDao.flowAll(),
            ) { bookParts, rssList ->
                val bookItems = bookParts.filter { it.enabled }.map {
                    DiscoverSourceItem(
                        id = it.bookSourceUrl,
                        name = it.bookSourceName,
                        type = DiscoverSourceType.BOOK,
                        url = it.bookSourceUrl,
                        group = it.bookSourceGroup,
                        hasLogin = it.hasLoginUrl,
                        hasExplore = it.hasExploreUrl,
                    )
                }
                val rssItems = rssList.filter { it.enabled }.map {
                    DiscoverSourceItem(
                        id = it.sourceUrl,
                        name = it.sourceName,
                        type = DiscoverSourceType.RSS,
                        url = it.sourceUrl,
                        group = it.sourceGroup,
                        hasLogin = !it.loginUrl.isNullOrBlank(),
                        hasExplore = false,
                        startHtml = it.startHtml,
                        sortUrl = it.sortUrl,
                    )
                }
                bookItems + rssItems + homeItems()
            }.collect { list ->
                _sources.value = list
                if (list.none { it.key == _currentSourceKey.value }) {
                    val fallbackKey = list.firstOrNull()?.key.orEmpty()
                    _currentSourceKey.value = fallbackKey
                    appCtx.putPrefString(PreferKey.discoverSelectedSource, fallbackKey)
                }
            }
        }
    }

    /** 与源管理共用启用状态、实际类型和分组映射。 */
    private fun homeItems(): List<DiscoverSourceItem> {
        val groups = HomeSourceManagementStore.groups().assignments
        return HomeSourceManagementStore.sources().filter { it.enabled }.map { source ->
            DiscoverSourceItem(
                id = source.id,
                name = source.name,
                type = DiscoverSourceType.HOME,
                url = source.id,
                group = groups[source.key],
                hasLogin = false,
                hasExplore = false,
                pickerType = if (source.type == "PY") "PY" else "TVBOX",
                key = source.key,
            )
        }
    }
}
