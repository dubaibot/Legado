package io.legado.app.help.python

import android.net.Uri
import com.chaquo.python.PyObject
import com.google.gson.Gson
import io.legado.app.utils.LogUtils
import splitties.init.appCtx
import java.io.File

/**
 * Python 插件加载器：管理用户安装的插件模块。
 *
 * 插件目录结构：filesDir/plugins/<pluginId>/plugin.py + meta.json
 *
 * 安装流程：SAF 读取 -> 规范化 -> 冲突检测 -> 复制 -> py_compile -> reload
 * 重载流程：clear sys.modules -> 重新导入 -> 注册解析器
 * 卸载流程：删除目录 -> reload
 */
object PyLoader {

    private const val TAG = "PyLoader"

    private val pluginsDir: File by lazy {
        File(appCtx.filesDir, "plugins").apply { mkdirs() }
    }

    private val gson = Gson()

    /** 插件元信息 */
    data class PyPluginMeta(
        val id: String = "",
        val name: String = "",
        val version: String = "1.0.0",
        val type: String = "video",
        val author: String = "",
        val desc: String = "",
        val permissions: List<String> = emptyList(),
    )

    /** 插件完整信息（元信息 + 是否已加载） */
    data class PyPlugin(
        val meta: PyPluginMeta,
        val loaded: Boolean,
    )

    /**
     * 安装插件：从内容字符串写入插件文件并 reload。
     * 返回是否成功。
     */
    fun install(pluginId: String, pyContent: String, metaJson: String): Boolean {
        return try {
            val pluginDir = File(pluginsDir, pluginId)
            pluginDir.mkdirs()

            File(pluginDir, "plugin.py").writeText(pyContent)
            File(pluginDir, "meta.json").writeText(metaJson)

            compilePlugin(pluginDir)
            reload()
        } catch (e: Exception) {
            LogUtils.d(TAG, "install failed: $pluginId: ${e.message}")
            false
        }
    }

    /**
     * 从 SAF Uri 安装插件。
     * 文件名作为 pluginId（去除 .py 扩展名）。
     */
    fun installFromUri(uri: Uri): Boolean {
        return try {
            val name = uri.lastPathSegment ?: "plugin.py"
            val pluginId = name.substringBeforeLast('.')
            val pyContent = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return false

            var metaJson = """{"id":"$pluginId","name":"$pluginId","type":"video"}"""

            // 如果 uri 指向一个目录（rar/zip），需要解压后读取 plugin.py
            // 简化实现：仅支持单个 .py 文件
            install(pluginId, pyContent, metaJson)
        } catch (e: Exception) {
            LogUtils.d(TAG, "installFromUri failed: ${e.message}")
            false
        }
    }

    /**
     * 重载所有插件。
     * 返回是否成功。
     */
    fun reload(): Boolean {
        return try {
            // 暂停任务队列，等当前任务完成
            PyTaskQueue.pause()

            val helper = PythonEnv.module("pyloader")
            if (helper == null) {
                LogUtils.d(TAG, "pyloader module not available")
                PyTaskQueue.resume()
                return false
            }

            val result = helper.callAttr("reload_all", pluginsDir.absolutePath)

            // 注册插件解析器到 PythonEnv
            PythonEnv.setPluginResolver { pluginId ->
                helper.callAttr("get_plugin", pluginId) as? PyObject
            }

            // 清空队列中的过期任务
            PyTaskQueue.clearPending()

            // 恢复任务队列
            PyTaskQueue.resume()

            LogUtils.d(TAG, "reload done: ${result?.size ?: 0} plugins")
            true
        } catch (e: Exception) {
            LogUtils.d(TAG, "reload failed: ${e.message}")
            PyTaskQueue.resume()
            false
        }
    }

    /**
     * 卸载插件。
     */
    fun uninstall(pluginId: String): Boolean {
        return try {
            val pluginDir = File(pluginsDir, pluginId)
            if (pluginDir.exists()) {
                pluginDir.deleteRecursively()
            }
            reload()
        } catch (e: Exception) {
            LogUtils.d(TAG, "uninstall failed: $pluginId: ${e.message}")
            false
        }
    }

    /**
     * 列出已安装插件。
     */
    fun listInstalled(): List<PyPlugin> {
        return pluginsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val metaFile = File(dir, "meta.json")
                if (!metaFile.exists()) return@mapNotNull null
                val meta = gson.fromJson(metaFile.readText(), PyPluginMeta::class.java)
                PyPlugin(meta, isLoaded(dir.name))
            }
            ?: emptyList()
    }

    /**
     * 检查插件是否已加载。
     */
    fun isLoaded(pluginId: String): Boolean {
        return try {
            val helper = PythonEnv.module("pyloader") ?: return false
            helper.callAttr("is_plugin_loaded", pluginId).toBoolean()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取插件模块。
     */
    fun getModule(pluginId: String): PyObject? {
        return try {
            val helper = PythonEnv.module("pyloader") ?: return null
            helper.callAttr("get_plugin", pluginId)
        } catch (e: Exception) {
            LogUtils.d(TAG, "getModule failed: $pluginId: ${e.message}")
            null
        }
    }

    /**
     * 编译插件为字节码（py_compile）。
     */
    private fun compilePlugin(pluginDir: File): Boolean {
        return try {
            val pyCompile = PythonEnv.get()?.getModule("py_compile")
            val pluginFile = File(pluginDir, "plugin.py").absolutePath
            pyCompile?.callAttr("compile", pluginFile)
            true
        } catch (e: Exception) {
            LogUtils.d(TAG, "compile failed: ${e.message}")
            false
        }
    }
}
