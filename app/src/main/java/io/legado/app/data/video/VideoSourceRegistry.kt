package io.legado.app.data.video

import android.content.Context
import com.google.gson.JsonObject
import io.legado.app.help.python.HomeBridge
import io.legado.app.help.python.PyLoader
import io.legado.app.utils.LogUtils
import io.legado.app.ui.main.homepage.HomeSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import splitties.init.appCtx
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
 * - TXT: "txtId:siteIndex"
 */
object VideoSourceRegistry {

    /** 源摘要信息（列表展示用） */
    data class SourceInfo(
        val id: String,
        val name: String,
        val type: SourceType,
        val parentId: String = "",  // 父源 ID（Config/TXT 展开时使用）
        val siteKey: String = if (parentId.isEmpty()) "" else id.removePrefix("$parentId:"),
        /** PY 源实际使用的 Python 插件模块 ID（TVBox 展开站点与插件 ID 不同） */
        val pluginId: String = "",
        /** TVBox 站点扩展参数（Spider.init 的 extend） */
        val extend: String = "",
    )

    /** 已创建的源实例缓存（避免重复读盘+创建） */
    private val instanceCache = ConcurrentHashMap<String, VideoSource>()

    private val preferences get() = appCtx.getSharedPreferences("video_source_registry", Context.MODE_PRIVATE)

    private fun hiddenKey(info: SourceInfo): String =
        JSONArray(listOf(info.parentId, info.siteKey)).toString()

    /** 墓碑按父配置和站点标识保存；TXT 使用名称及 API，重排后仍隐藏同一站点。 */
    fun isSourceHidden(info: SourceInfo): Boolean = info.parentId.isNotEmpty() &&
        hiddenKey(info) in preferences.getStringSet("hidden_sites", emptySet()).orEmpty()

    /** 清空所有缓存的源实例 */
    fun invalidateAll() {
        instanceCache.clear()
    }

    /** 清除指定源的实例缓存（删除/更新源时调用） */
    fun invalidateSource(id: String) {
        instanceCache.remove(id)
        JarSpiderLoader.invalidate()
    }

    /** 列出全部可用视频源 */
    fun listSources(includeHidden: Boolean = false): List<SourceInfo> {
        val sources = mutableListOf<SourceInfo>()

        // py 插件源（用户导入的 .py 插件）
        try {
            PyLoader.listInstalled().forEach { installed ->
                sources.add(
                    SourceInfo(
                        id = installed.meta.id,
                        name = installed.meta.name.ifBlank { installed.meta.id },
                        type = SourceType.PY,
                        pluginId = installed.meta.id,
                        extend = installed.meta.extend,
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
                        // 展开为多个源（CMS 站点 + 有 jar 的 jar 爬虫站点）
                        FongMiConfigParser.parse(content).forEach { site ->
                            when (site.siteKind) {
                                FongMiConfigParser.SiteKind.JAR_SPIDER -> {
                                    sources.add(
                                        SourceInfo(
                                            id = "${meta.id}:${site.key}",
                                            name = site.name,
                                            type = SourceType.JAR,
                                            parentId = meta.id,
                                        )
                                    )
                                }
                                FongMiConfigParser.SiteKind.CMS -> {
                                    sources.add(
                                        SourceInfo(
                                            id = "${meta.id}:${site.key}",
                                            name = site.name,
                                            type = SourceType.JSON,
                                            parentId = meta.id,
                                        )
                                    )
                                }
                                FongMiConfigParser.SiteKind.PY_SPIDER -> {
                                    sources.add(
                                        SourceInfo(
                                            id = "${meta.id}:${site.key}",
                                            name = site.name,
                                            type = SourceType.PY,
                                            parentId = meta.id,
                                            pluginId = resolvePyModuleId(site.key, site.name, site.api),
                                            extend = site.ext,
                                        )
                                    )
                                }
                                else -> {
                                    LogUtils.d(
                                        "VideoSourceRegistry",
                                        "skip unsupported site (${site.siteKind}): ${site.name} api=${site.api}"
                                    )
                                }
                            }
                        }
                    } else {
                        // 单站点 JSON
                        // 检测 api 字段形态（对照薯条版 TvBoxConfig api 字段判定）
                        val root = try {
                            com.google.gson.JsonParser.parseString(content).asJsonObject
                        } catch (e: Exception) { null }
                        val apiVal = root?.get("api")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        val siteKind = FongMiConfigParser.SiteKind.detect(apiVal)
                        val ext = root?.get("ext")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        when (siteKind) {
                            FongMiConfigParser.SiteKind.JAR_SPIDER -> {
                                sources.add(
                                    SourceInfo(
                                        id = meta.id,
                                        name = meta.name,
                                        type = SourceType.JAR,
                                    )
                                )
                            }
                            FongMiConfigParser.SiteKind.CMS -> {
                                sources.add(
                                    SourceInfo(
                                        id = meta.id,
                                        name = meta.name,
                                        type = SourceType.JSON,
                                    )
                                )
                            }
                            FongMiConfigParser.SiteKind.PY_SPIDER -> {
                                sources.add(
                                    SourceInfo(
                                        id = meta.id,
                                        name = meta.name,
                                        type = SourceType.PY,
                                        pluginId = resolvePyModuleId(meta.id, meta.name, apiVal),
                                        extend = ext,
                                    )
                                )
                            }
                            else -> {
                                LogUtils.d(
                                    "VideoSourceRegistry",
                                    "skip unsupported site (${siteKind}): ${meta.name} api=$apiVal"
                                )
                            }
                        }
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
                                    siteKey = JSONArray(listOf(site.name, site.api)).toString(),
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

        return if (includeHidden) sources else sources.filterNot(::isSourceHidden)
    }

    /** 按类型获取源信息 */
    fun getSourceInfo(id: String): SourceInfo? = listSources().find { it.id == id }

    /** 源对应的 Python 插件模块 ID（TVBox 展开的 py 站点需映射到已安装插件） */
    fun pyModuleId(sourceId: String): String =
        getSourceInfo(sourceId)?.pluginId?.ifBlank { sourceId } ?: sourceId

    /** 源对应的 Spider 扩展参数（来自 Config 的 ext 或插件 meta.json 的 extend） */
    fun pyExtend(sourceId: String): String = getSourceInfo(sourceId)?.extend.orEmpty()

    /** 匹配已安装插件：站点 key / 名称 / py 文件名，均未命中时退回站点 key */
    private fun resolvePyModuleId(siteKey: String, siteName: String, api: String): String {
        val installed = try {
            PyLoader.listInstalled().map { it.meta }
        } catch (e: Exception) {
            emptyList()
        }
        val apiStem = api.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').trim()
        return installed.firstOrNull { it.id == siteKey }?.id
            ?: installed.firstOrNull { it.name.isNotBlank() && it.name == siteName }?.id
            ?: installed.firstOrNull { apiStem.isNotEmpty() && it.id == apiStem }?.id
            ?: installed.firstOrNull { apiStem.isNotEmpty() && it.name == apiStem }?.id
            ?: siteKey
    }

    /** 创建 VideoSource 实例（py/json/txt/html），已缓存则直接返回 */
    fun createSource(info: SourceInfo): VideoSource? {
        // 调用方可能持有删除前的摘要，先核对当前站点再读取实例缓存。
        if (info.parentId.isNotEmpty() && getSourceInfo(info.id) == null) return null
        instanceCache[info.id]?.let { return it }
        val source = when (info.type) {
            SourceType.PY -> PyVideoSource(
                sourceId = info.id,
                sourceName = info.name,
                moduleId = info.pluginId.ifBlank { info.id },
                extend = info.extend,
            )
            SourceType.JSON -> {
                // FongMi Config 展开的源：读取原始 Config 后选择指定站点
                if (info.parentId.isNotEmpty()) {
                    val parent = HomeSourceStore.listVideoSources().find { it.id == info.parentId }
                        ?: return null
                    val content = HomeSourceStore.readVideoSource(parent.id, parent.type) ?: return null
                    if (parent.type == "txt") {
                        val index = info.id.removePrefix("${parent.id}:").toIntOrNull() ?: return null
                        val site = FongMiTxtParser.parse(content).getOrNull(index) ?: return null
                        val config = JsonObject().apply {
                            addProperty("name", site.name)
                            addProperty("type", "cms")
                            addProperty("api", site.api)
                        }
                        JsonVideoSource(info.id, config.toString())
                    } else {
                        JsonVideoSource(info.id, content)
                    }
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
            SourceType.JAR -> {
                if (info.parentId.isNotEmpty()) {
                    // FongMi Config 展开的 jar 源：父 Config + 站点 key
                    val configJson = HomeSourceStore.readVideoSource(info.parentId, "json")
                        ?: return null
                    val site = FongMiConfigParser.parse(configJson).find { it.key == info.siteKey }
                        ?: return null
                    JarVideoSource(info.id, site.name, site.jar, site.api, site.ext)
                } else {
                    // 单站点 jar 源：JSON 内含 jar + api 字段
                    val configJson = HomeSourceStore.readVideoSource(info.id, "json")
                        ?: return null
                    val root = try {
                        com.google.gson.JsonParser.parseString(configJson).asJsonObject
                    } catch (e: Exception) { null } ?: return null
                    val jar = root.get("jar")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    val api = root.get("api")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    val site = FongMiConfigParser.parse(configJson).firstOrNull()
                    JarVideoSource(info.id, info.name, jar, api, site?.ext.orEmpty())
                }
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

    /** 默认只删除指定源；展开站点写入墓碑，保留父配置与所有兄弟站点。 */
    fun deleteSource(id: String): Boolean {
        val info = getSourceInfo(id) ?: return false
        return deleteSource(info)
    }

    /** 带摘要删除时核对站点身份，防止配置更新后误删同 ID 的其他条目。 */
    @Synchronized
    fun deleteSource(info: SourceInfo): Boolean {
        val current = getSourceInfo(info.id) ?: return false
        if (current.parentId != info.parentId || current.siteKey != info.siteKey || current.type != info.type) return false
        val id = info.id
        if (info.parentId.isNotBlank()) {
            val hidden = preferences.getStringSet("hidden_sites", emptySet()).orEmpty().toMutableSet()
            hidden.add(hiddenKey(info))
            if (!preferences.edit().putStringSet("hidden_sites", hidden).commit()) return false
            invalidateSource(id)
            return true
        }
        return when (info.type) {
            SourceType.PY -> try {
                PyLoader.uninstall(id).also { if (it) invalidateSource(id) }
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
    /** Python 插件模块 ID；TVBox 展开的 py 站点与 sourceId 不同 */
    private val moduleId: String = "",
    /** 站点扩展参数，传给 Spider.init */
    private val extend: String = "",
) : VideoSource {

    override val sourceType = SourceType.PY

    private val cacheTtlMs = 5 * 60 * 1000L

    private data class CacheEntry<T>(val timestamp: Long, val value: T)
    private fun isValid(entry: CacheEntry<*>): Boolean =
        System.currentTimeMillis() - entry.timestamp < cacheTtlMs

    private var homeCache: CacheEntry<Pair<List<VideoCategory>, List<VideoItem>>>? = null
    private val categoryCache = mutableMapOf<String, CacheEntry<CategoryResult>>()
    private val searchCache = mutableMapOf<String, CacheEntry<List<VideoItem>>>()

    private val pyModule get() = moduleId.ifBlank { sourceId }

    private suspend fun call(func: String, vararg args: String): String? =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val onResult: (String?) -> Unit = { result ->
                    if (cont.isActive) cont.resume(result)
                }
                val module = pyModule
                when (func) {
                    "home" -> HomeBridge.home(module, onResult, extend)
                    "category" -> HomeBridge.category(
                        module, args[0], args[1].toIntOrNull() ?: 1, onResult,
                        args.getOrElse(2) { "{}" }, extend,
                    )
                    "search" -> HomeBridge.search(module, args[0], onResult, extend)
                    "detail" -> HomeBridge.detail(module, args[0], onResult, extend)
                    "play" -> HomeBridge.play(
                        module, args[0], flag = args.getOrElse(1) { "" },
                        onResult = onResult, extend = extend,
                    )
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
        return category(tid, pg, emptyMap())
    }

    override suspend fun category(tid: String, pg: Int, filters: Map<String, String>): CategoryResult {
        val filtersJson = org.json.JSONObject(filters.toSortedMap()).toString()
        val key = "$tid|$pg|$filtersJson"
        categoryCache[key]?.let { if (isValid(it)) return it.value }
        val json = call("category", tid, pg.toString(), filtersJson) ?: return CategoryResult()
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
        return play(url, "")
    }

    override suspend fun play(url: String, flag: String): PlayResult {
        val json = call("play", url, flag) ?: return PlayResult(url)
        return PyResultParser.parsePlay(json, url)
    }

    override suspend fun danmaku(videoId: String): DanmakuList = DanmakuList()
}
