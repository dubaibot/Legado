package io.legado.app.data.video

import io.legado.app.help.python.HomeBridge
import io.legado.app.help.python.PyLoader
import io.legado.app.ui.main.homepage.HomeSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 统一视频源注册表：聚合 py 插件源与 json/txt 静态源。
 *
 * - py 源：PyLoader 已安装插件，通过 HomeBridge 异步调用
 * - json 源：HomeSourceStore video_sources 目录下 type=json
 * - txt 源：HomeSourceStore video_sources 目录下 type=txt
 * - html 源：特殊类型，直接 WebView 渲染，不实现 VideoSource
 */
object VideoSourceRegistry {

    /** 源摘要信息（列表展示用） */
    data class SourceInfo(
        val id: String,
        val name: String,
        val type: SourceType,
    )

    /** 列出全部可用视频源 */
    fun listSources(): List<SourceInfo> {
        val sources = mutableListOf<SourceInfo>()
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
        HomeSourceStore.listVideoSources().forEach { meta ->
            sources.add(
                SourceInfo(
                    id = meta.id,
                    name = meta.name,
                    type = if (meta.type == "txt") SourceType.TXT else SourceType.JSON,
                )
            )
        }
        return sources
    }

    /** 按类型获取源信息 */
    fun getSourceInfo(id: String): SourceInfo? = listSources().find { it.id == id }

    /** 创建 VideoSource 实例（py/json/txt），html 源返回 null */
    fun createSource(info: SourceInfo): VideoSource? {
        return when (info.type) {
            SourceType.PY -> PyVideoSource(info.id, info.name)
            SourceType.JSON -> {
                val config = HomeSourceStore.readVideoSource(info.id, "json")
                    ?: return null
                JsonVideoSource(info.id, config)
            }
            SourceType.TXT -> {
                val content = HomeSourceStore.readVideoSource(info.id, "txt")
                    ?: return null
                TxtVideoSource(info.id, content)
            }
            SourceType.HTML -> null
        }
    }

    /** 删除任意类型源（py 走 PyLoader，json/txt 走 HomeSourceStore） */
    fun deleteSource(id: String): Boolean {
        val info = getSourceInfo(id) ?: return false
        return when (info.type) {
            SourceType.PY -> try {
                PyLoader.uninstall(id)
                true
            } catch (e: Exception) {
                false
            }
            else -> HomeSourceStore.deleteVideoSource(id)
        }
    }
}

/**
 * py 源适配器：把 HomeBridge 异步回调桥接为 suspend 调用。
 * 返回 JSON 字符串后统一解析为数据模型。
 */
class PyVideoSource(
    override val sourceId: String,
    override val sourceName: String,
) : VideoSource {

    override val sourceType = SourceType.PY

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
        }

    override suspend fun home(): Pair<List<VideoCategory>, List<VideoItem>> {
        val json = call("home") ?: return Pair(emptyList(), emptyList())
        return PyResultParser.parseHome(json)
    }

    override suspend fun category(tid: String, pg: Int): CategoryResult {
        val json = call("category", tid, pg.toString()) ?: return CategoryResult()
        return PyResultParser.parseCategory(json, pg)
    }

    override suspend fun search(keyword: String, pg: Int): List<VideoItem> {
        val json = call("search", keyword) ?: return emptyList()
        return PyResultParser.parseVideoList(json)
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
