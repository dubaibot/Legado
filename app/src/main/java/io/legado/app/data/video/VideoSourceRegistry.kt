package io.legado.app.data.video

import io.legado.app.help.python.HomeBridge
import io.legado.app.help.python.PyLoader
import io.legado.app.ui.main.homepage.HomeSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一视频源注册表：聚合 py 插件源、json/txt/html 静态源。
 *
 * - py 源：PyLoader 已安装插件，通过 HomeBridge 异步调用
 * - json 源：HomeSourceStore video_sources 目录下 type=json
 *   - 单站点 JSON（CMS 或自定义端点）
 *   - FongMi Config JSON（sites 数组，展开为多个源）
 * - txt 源：HomeSourceStore video_sources 目录下 type=txt
 *   - 静态列表格式（TxtVideoSource 直接处理）
 *   - FongMi 逐行格式（名称$接口地址，展开为多个 CMS 源）
 * - html 源：HomeSourceStore video_sources 目录下 type=html
 *   - 基于 AnalyzeRule 规则解析的 HTML VideoSource
 *
 * FongMi Config/TXT 展开后的源 ID 格式：
 * - Config: "configId:siteKey"
 * - TXT: "txtId:siteName"
 */
object VideoSourceRegistry {

    /** 源摘要信息（列表展示用） */
    data class SourceInfo(
        val id: String,
        val name: String,
        val type: SourceType,
        val parentId: String = "",  // 父源 ID（Config/TXT 展开时使用）
    )

    /** 已创建的源实例缓存（避免重复读盘+创建） */
    private val instanceCache = ConcurrentHashMap<String, VideoSource>()

    /** 清空所有缓存的源实例 */
    fun invalidateAll() {
        instanceCache.clear()
    }

    /** 清除指定源的实例缓存（删除/更新源时调用） */
    fun invalidateSource(id: String) {
        instanceCache.remove(id)
    }

    /** 列出全部可用视频源 */
    fun listSources(): List<SourceInfo> {
        val sources = mutableListOf<SourceInfo>()

        // py 插件源
        try {
            PyLoader.listInstalled().forEach { installed ->
                sources.add(
                    SourceInfo(
                        id = installed.meta.id,
                        name = installed.meta.name.ifBlank { installed.meta.id },
                        type = SourceType.PY,
                    )
                )
            }
        } catch (e: Exception) {
            // py loader 不可用时跳过
        }

        // json/txt/html 静态源
        HomeSourceStore.listVideoSources().forEach { meta ->
            val content = HomeSourceStore.readVideoSource(meta.id, meta.type) ?: return@forEach

            when (meta.type) {
                "json" -> {
                    // 检测 FongMi Config 格式（含 sites 数组）
                    if (FongMiConfigParser.isFongMiConfig(content)) {
                        // 展开为多个源
                        FongMiConfigParser.parse(content).forEach { site ->
                            sources.add(
                                SourceInfo(
                                    id = "${meta.id}:${site.key}",
                                    name = site.name,
                                    type = SourceType.JSON,
                                    parentId = meta.id,
                                )
                            )
                        }
                    } else {
                        // 单站点 JSON
                        sources.add(
                            SourceInfo(
                                id = meta.id,
                                name = meta.name,
                                type = SourceType.JSON,
                            )
                        )
                    }
                }
                "txt" -> {
                    // 检测 FongMi 逐行格式
                    if (FongMiTxtParser.isFongMiTxt(content)) {
                        // 展开为多个 CMS 源
                        FongMiTxtParser.parse(content).forEachIndexed { idx, site ->
                            sources.add(
                                SourceInfo(
                                    id = "${meta.id}:$idx",
                                    name = site.name,
                                    type = SourceType.JSON,
                                    parentId = meta.id,
                                )
                            )
                        }
                    } else {
                        // 静态列表 TXT
                        sources.add(
                            SourceInfo(
                                id = meta.id,
                                name = meta.name,
                                type = SourceType.TXT,
                            )
                        )
                    }
                }
                "html" -> {
                    sources.add(
                        SourceInfo(
                            id = meta.id,
                            name = meta.name,
                            type = SourceType.HTML,
                        )
                    )
                }
            }
        }

        return sources
    }

    /** 按类型获取源信息 */
    fun getSourceInfo(id: String): SourceInfo? = listSources().find { it.id == id }

    /** 创建 VideoSource 实例（py/json/txt/html），已缓存则直接返回 */
    fun createSource(info: SourceInfo): VideoSource? {
        instanceCache[info.id]?.let { return it }
        val source = when (info.type) {
            SourceType.PY -> PyVideoSource(info.id, info.name)
            SourceType.JSON -> {
                // FongMi Config 展开的源：读取原始 Config 后选择指定站点
                val colonIdx = info.id.indexOf(':')
                if (colonIdx > 0) {
                    val parentId = info.id.substring(0, colonIdx)
                    val configJson = HomeSourceStore.readVideoSource(parentId, "json")
                        ?: return null
                    JsonVideoSource(info.id, configJson)
                } else {
                    val configJson = HomeSourceStore.readVideoSource(info.id, "json")
                        ?: return null
                    JsonVideoSource(info.id, configJson)
                }
            }
            SourceType.TXT -> {
                val content = HomeSourceStore.readVideoSource(info.id, "txt")
                    ?: return null
                TxtVideoSource(info.id, content)
            }
            SourceType.HTML -> {
                val configJson = HomeSourceStore.readVideoSource(info.id, "html")
                    ?: return null
                HtmlVideoSource(info.id, configJson)
            }
        }
        if (source != null) instanceCache[info.id] = source
        return source
    }

    /** 删除任意类型源（py 走 PyLoader，json/txt/html 走 HomeSourceStore） */
    fun deleteSource(id: String): Boolean {
        val info = getSourceInfo(id) ?: return false
        // FongMi Config/TXT 展开的源：删除父源
        if (info.parentId.isNotBlank()) {
            invalidateSource(info.parentId)
            return HomeSourceStore.deleteVideoSource(info.parentId)
        }
        return when (info.type) {
            SourceType.PY -> try {
                PyLoader.uninstall(id)
                invalidateSource(id)
                true
            } catch (e: Exception) {
                false
            }
            else -> {
                invalidateSource(id)
                HomeSourceStore.deleteVideoSource(id)
            }
        }
    }
}

/**
 * py 源适配器：把 HomeBridge 异步回调桥接为 suspend 调用。
 * 返回 JSON 字符串后统一解析为数据模型。
 *
 * 性能优化：
 * - home/category/search 结果缓存 TTL 5 分钟（Python 解释器执行慢，缓存收益大）
 * - detail/play 不缓存（每次点播不同视频，缓存无意义）
 */
class PyVideoSource(
    override val sourceId: String,
    override val sourceName: String,
) : VideoSource {

    override val sourceType = SourceType.PY

    private val cacheTtlMs = 5 * 60 * 1000L

    private data class CacheEntry<T>(val timestamp: Long, val value: T)
    private fun isValid(entry: CacheEntry<*>): Boolean =
        System.currentTimeMillis() - entry.timestamp < cacheTtlMs

    private var homeCache: CacheEntry<Pair<List<VideoCategory>, List<VideoItem>>>? = null
    private val categoryCache = mutableMapOf<String, CacheEntry<CategoryResult>>()
    private val searchCache = mutableMapOf<String, CacheEntry<List<VideoItem>>>()

    private suspend fun call(func: String, vararg args: String): String? =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val onResult: (String?) -> Unit = { result ->
                    if (cont.isActive) cont.resume(result)
                }
                when (func) {
                    "home" -> HomeBridge.home(sourceId, onResult)
                    "category" -> HomeBridge.category(sourceId, args[0], args[1].toIntOrNull() ?: 1, onResult)
                    "search" -> HomeBridge.search(sourceId, args[0], onResult)
                    "detail" -> HomeBridge.detail(sourceId, args[0], onResult)
                    "play" -> HomeBridge.play(sourceId, args[0], onResult)
                    else -> onResult(null)
                }
            }
        }.let { raw ->
            // 检查错误 JSON：callPluginFunc 返回 {"error":"..."} 时抛出异常
            if (raw != null && raw.contains("\"error\"")) {
                throw IllegalStateException(raw)
            }
            raw
        }

    override suspend fun home(): Pair<List<VideoCategory>, List<VideoItem>> {
        homeCache?.let { if (isValid(it)) return it.value }
        val json = call("home") ?: return Pair(emptyList(), emptyList())
        val result = PyResultParser.parseHome(json)
        if (result.first.isEmpty() && result.second.isEmpty() && json.contains("\"error\"")) {
            homeCache = null
            return result
        }
        homeCache = CacheEntry(System.currentTimeMillis(), result)
        return result
    }

    override suspend fun category(tid: String, pg: Int): CategoryResult {
        val key = "$tid|$pg"
        categoryCache[key]?.let { if (isValid(it)) return it.value }
        val json = call("category", tid, pg.toString()) ?: return CategoryResult()
        val result = PyResultParser.parseCategory(json, pg)
        if (result.videos.isEmpty() && json.contains("\"error\"")) {
            categoryCache.remove(key)
            return result
        }
        categoryCache[key] = CacheEntry(System.currentTimeMillis(), result)
        return result
    }

    override suspend fun search(keyword: String, pg: Int): List<VideoItem> {
        val key = "$keyword|$pg"
        searchCache[key]?.let { if (isValid(it)) return it.value }
        val json = call("search", keyword) ?: return emptyList()
        val result = PyResultParser.parseVideoList(json)
        if (result.isEmpty() && json.contains("\"error\"")) {
            searchCache.remove(key)
            return result
        }
        searchCache[key] = CacheEntry(System.currentTimeMillis(), result)
        return result
    }

    override suspend fun detail(id: String): VideoDetail {
        val json = call("detail", id) ?: return VideoDetail(id = id, title = id)
        return PyResultParser.parseDetail(json, id)
    }

    override suspend fun play(url: String): PlayResult {
        val json = call("play", url) ?: return PlayResult(url)
        return PyResultParser.parsePlay(json, url)
    }

    override suspend fun danmaku(videoId: String): DanmakuList = DanmakuList()
}
