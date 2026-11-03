package io.legado.app.help.python

import com.google.gson.JsonParser
import io.legado.app.utils.LogUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 首页桥接层：封装 Python 插件调用，提供 Promise 模式异步接口。
 *
 * 特性：
 * - Promise 15s 超时：任务超过 15s 未完成视为失败
 * - ConcurrentHashMap 缓存：插件能力信息缓存
 * - 权限分级：按插件元信息 permissions 字段校验调用权限
 */
object HomeBridge {

    private const val TIMEOUT_MS = 30_000L
    private const val TAG = "HomeBridge"

    /** 插件能力缓存：pluginId -> {func: [paramNames]} */
    private val capabilities = ConcurrentHashMap<String, Map<String, List<String>>>()

    /** 插件元信息缓存：pluginId -> PyPluginMeta */
    private val metaCache = ConcurrentHashMap<String, PyLoader.PyPluginMeta>()

    private val callbackExecutor = Executors.newCachedThreadPool()

    /**
     * 搜索：调用插件 search 函数。
     */
    fun search(pluginId: String, keyword: String, onResult: (String?) -> Unit) {
        callPlugin(pluginId, "search", listOf(keyword), onResult)
    }

    /**
     * 详情：调用插件 detail 函数。
     */
    fun detail(pluginId: String, id: String, onResult: (String?) -> Unit) {
        callPlugin(pluginId, "detail", listOf(id), onResult)
    }

    /**
     * 播放：调用插件 play 函数。
     */
    fun play(pluginId: String, url: String, onResult: (String?) -> Unit) {
        callPlugin(pluginId, "play", listOf(url), onResult)
    }

    /**
     * 首页：调用插件 home 函数，返回分类与推荐视频。
     */
    fun home(pluginId: String, onResult: (String?) -> Unit) {
        callPlugin(pluginId, "home", emptyList(), onResult)
    }

    /**
     * 分类：调用插件 category 函数，返回分类视频列表。
     */
    fun category(pluginId: String, tid: String, pg: Int, onResult: (String?) -> Unit) {
        callPlugin(pluginId, "category", listOf(tid, pg.toString()), onResult)
    }

    /**
     * 检测插件能力并缓存。
     */
    fun detectCapabilities(pluginId: String): Map<String, List<String>> {
        return capabilities.computeIfAbsent(pluginId) {
            val module = PyLoader.getModule(pluginId)
            if (module == null) return@computeIfAbsent emptyMap()
            try {
                val helper = PythonEnv.module("plugin_entry")
                val result = helper?.callAttr("detect_capabilities", module)
                parseCapabilities(result?.toString() ?: "{}")
            } catch (e: Exception) {
                LogUtils.d(TAG, "detect capabilities failed: ${e.message}")
                emptyMap()
            }
        }
    }

    /**
     * 检查插件是否拥有某函数的调用权限。
     */
    fun checkPermission(pluginId: String, func: String): Boolean {
        val meta = metaCache[pluginId] ?: PyLoader.listInstalled()
            .find { it.meta.id == pluginId }?.meta
            ?: return false

        if (meta.permissions.isEmpty()) return true
        return meta.permissions.contains(func)
    }

    /**
     * 刷新插件元信息缓存。
     */
    fun refreshMeta(pluginId: String) {
        PyLoader.listInstalled()
            .find { it.meta.id == pluginId }
            ?.let { metaCache[pluginId] = it.meta }
    }

    /**
     * 刷新所有插件元信息缓存。
     */
    fun refreshAllMeta() {
        metaCache.clear()
        PyLoader.listInstalled().forEach { metaCache[it.meta.id] = it.meta }
    }

    /**
     * 清空缓存（reload 后调用）。
     */
    fun clearCache() {
        capabilities.clear()
        metaCache.clear()
    }

    // ---- internal ----

    private fun callPlugin(
        pluginId: String,
        func: String,
        args: List<Any?>,
        onResult: (String?) -> Unit,
    ) {
        // 权限检查
        if (!checkPermission(pluginId, func)) {
            LogUtils.d(TAG, "permission denied: $pluginId.$func")
            callbackExecutor.execute { onResult(null) }
            return
        }

        // 能力缓存检查
        val caps = detectCapabilities(pluginId)
        if (!caps.containsKey(func)) {
            // 能力检测可能因 Chaquopy 兼容问题失败，尝试直接调用作为 fallback
            val module = PyLoader.getModule(pluginId)
            if (module != null) {
                LogUtils.d(TAG, "capability check failed, calling directly: $pluginId.$func")
                val task = PyTaskQueue.submit(pluginId, func, args)
                if (task != null) {
                    callbackExecutor.execute {
                        val result = task.await(TIMEOUT_MS)
                        onResult(result)
                    }
                    return
                }
            }
            LogUtils.d(TAG, "function not in capabilities and fallback failed: $pluginId.$func")
            callbackExecutor.execute { onResult(null) }
            return
        }

        // 提交任务
        val task = PyTaskQueue.submit(pluginId, func, args)
        if (task == null) {
            LogUtils.d(TAG, "task submit failed: $pluginId.$func")
            callbackExecutor.execute { onResult(null) }
            return
        }

        // 后台线程等待结果，避免阻塞调用线程
        callbackExecutor.execute {
            val result = task.await(TIMEOUT_MS)
            onResult(result)
        }
    }

    private fun parseCapabilities(jsonStr: String): Map<String, List<String>> {
        return try {
            val json = JsonParser.parseString(jsonStr).asJsonObject
            val result = mutableMapOf<String, List<String>>()
            for ((key, value) in json.entrySet()) {
                val paramList = mutableListOf<String>()
                value.asJsonArray.forEach { paramList.add(it.asString) }
                result[key] = paramList
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
