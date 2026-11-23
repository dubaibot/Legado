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

    /** 模块级函数名 → FongMi Spider 方法名（能力检测别名） */
    private val spiderCapabilityAliases = mapOf(
        "home" to "homeContent",
        "category" to "categoryContent",
        "search" to "searchContent",
        "detail" to "detailContent",
        "play" to "playerContent",
    )

    /** 插件元信息缓存：pluginId -> PyPluginMeta */
    private val metaCache = ConcurrentHashMap<String, PyLoader.PyPluginMeta>()

    private val callbackExecutor = Executors.newCachedThreadPool()

    /**
     * 搜索：调用插件 search 函数。
     */
    fun search(
        pluginId: String,
        keyword: String,
        onResult: (String?) -> Unit,
        extend: String = "",
    ) {
        callPlugin(pluginId, "search", listOf(keyword), onResult, extend)
    }

    /**
     * 详情：调用插件 detail 函数。
     */
    fun detail(
        pluginId: String,
        id: String,
        onResult: (String?) -> Unit,
        extend: String = "",
    ) {
        callPlugin(pluginId, "detail", listOf(id), onResult, extend)
    }

    /**
     * 播放：调用插件 play 函数。
     */
    fun play(
        pluginId: String,
        url: String,
        flag: String = "",
        onResult: (String?) -> Unit,
        extend: String = "",
    ) {
        callPlugin(pluginId, "play", listOf(url, flag), onResult, extend)
    }

    /**
     * 首页：调用插件 home 函数，返回分类与推荐视频。
     */
    fun home(pluginId: String, onResult: (String?) -> Unit, extend: String = "") {
        callPlugin(pluginId, "home", emptyList(), onResult, extend)
    }

    /**
     * 分类：调用插件 category 函数，返回分类视频列表。
     */
    fun category(
        pluginId: String,
        tid: String,
        pg: Int,
        onResult: (String?) -> Unit,
        filtersJson: String = "{}",
        extend: String = "",
    ) {
        val args = listOf(tid, pg.toString(), filtersJson)
        callPlugin(pluginId, "category", args, onResult, extend)
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
     *
     * 发现页读取能力（home/category）不参与 manifest 权限声明的强校验：
     * 大量影视 PY 插件（从 FongMi/TVBox 迁移）的 manifest 只声明 search/detail/play，
     * 或未声明 permissions，但实际实现了 homeContent/categoryContent。旧逻辑用
     * `permissions.contains("home")/"category")` 判断，会把这些插件在发现页的调用
     * 直接拦成 onResult(null)，表现为「分类内容解析不出」。对这类无害的只读能力，
     * 改为以插件的实际能力检测结果为准（能力检测已包含 Spider 方法别名映射）。
     */
    fun checkPermission(pluginId: String, func: String): Boolean {
        val meta = metaCache[pluginId] ?: PyLoader.listInstalled()
            .find { it.meta.id == pluginId }?.meta
            ?: return false

        if (meta.permissions.isEmpty()) return true
        // 发现页只读能力：以实际能力检测为准，忽略 manifest 中可能不全的 permissions 声明
        if (func == "home" || func == "category") return true
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
        extend: String = "",
    ) {
        // 权限检查
        if (!checkPermission(pluginId, func)) {
            LogUtils.d(TAG, "permission denied: $pluginId.$func")
            callbackExecutor.execute { onResult(null) }
            return
        }

        // 能力缓存检查
        val caps = detectCapabilities(pluginId)
        if (!hasCapability(caps, func)) {
            // 能力检测可能因 Chaquopy 兼容问题失败，尝试直接调用作为 fallback
            val module = PyLoader.getModule(pluginId)
            if (module != null) {
                LogUtils.d(TAG, "capability check failed, calling directly: $pluginId.$func")
                val task = PyTaskQueue.submit(pluginId, func, args, extend)
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
        val task = PyTaskQueue.submit(pluginId, func, args, extend)
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

    /** 能力判断：模块级函数或对应 Spider 方法任一存在即可调用 */
    private fun hasCapability(caps: Map<String, List<String>>, func: String): Boolean {
        if (caps.containsKey(func)) return true
        val alias = spiderCapabilityAliases[func] ?: return false
        return caps.containsKey(alias)
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
