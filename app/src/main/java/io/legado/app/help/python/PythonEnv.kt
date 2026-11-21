package io.legado.app.help.python

import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import io.legado.app.utils.LogUtils
import kotlin.concurrent.thread
import splitties.init.appCtx

/**
 * Python 引擎运行时（Chaquopy）。
 *
 * 初始化策略：
 * - 主线程启动 Chaquopy（Python.start 会创建解释器，应用启动时同步完成）；
 * - 懒启动 + 双检锁，避免多处调用重复初始化；
 * - 失败通过 LogUtils 记录，不影响主流程。
 *
 * 说明：本类只负责引擎生命周期；插件加载、任务队列、桥接由 PyLoader/PyTaskQueue 负责。
 */
object PythonEnv {

    @Volatile
    private var started = false

    @Volatile
    private var python: Python? = null

    /** 插件模块解析器：优先于标准导入查找插件 */
    @Volatile
    private var pluginResolver: ((String) -> PyObject?)? = null

    /**
     * 影视壳风格环境变量：插件在顶层用 os.environ.get 读取的功能开关。
     * FongMi/TVBox 会在加载插件前把站点配置里的环境变量注入解释器，这里补齐默认值。
     * 新增开关在此 map 里加一行即可（集中配置点）。
     */
    private val shellEnvVars: Map<String, String> = mapOf(
        // 红果等 py 源默认关闭首页推荐区，设为 1 恢复「热门推荐」
        "HG_HOME_REC" to "1",
    )

    /** 设置插件解析器，由 PyLoader 在 reload 后注册 */
    fun setPluginResolver(resolver: (String) -> PyObject?) {
        pluginResolver = resolver
    }

    /** 启动 Python 解释器，幂等。返回是否成功。 */
    fun start(): Boolean {
        if (started) return true
        synchronized(this) {
            if (started) return true
            try {
                Python.start(AndroidPlatform(appCtx))
                python = Python.getInstance()
                injectShellEnvVars()
                started = true
                return true
            } catch (e: Exception) {
                LogUtils.d("PythonEnv", "Python.start failed: ${e.message}")
                return false
            }
        }
    }

    /**
     * 把 [shellEnvVars] 注入 os.environ。
     * 用 setdefault 只补默认值，不覆盖插件自行设置的值；失败只记日志，不影响启动。
     */
    private fun injectShellEnvVars() {
        val py = python ?: return
        try {
            val environ = py.getModule("os")?.get("environ") ?: return
            shellEnvVars.forEach { (key, value) ->
                environ.callAttr("setdefault", key, value)
            }
        } catch (e: Exception) {
            LogUtils.d("PythonEnv", "inject shell env failed: ${e.message}")
        }
    }

    /** 获取已启动的解释器；未启动则尝试启动。失败返回 null。 */
    fun get(): Python? {
        if (python == null) {
            start()
        }
        return python
    }

    /** 获取（或导入）模块，未启动返回 null。插件优先走 pluginResolver。 */
    fun module(name: String): PyObject? {
        // 插件解析器优先
        pluginResolver?.let { resolver ->
            val plugin = resolver(name)
            if (plugin != null) return plugin
        }
        return try {
            val module = get()?.getModule(name)
            if (module == null) {
                LogUtils.d("PythonEnv", "module not found: $name")
            }
            module
        } catch (e: Exception) {
            LogUtils.d("PythonEnv", "getModule $name error: ${e.message}")
            null
        }
    }

    /**
     * 在后台线程执行 Python 代码，避免阻塞主线程。
     * 异常已记录日志，返回 null。
     */
    fun execAsync(code: String, onResult: (String?) -> Unit) {
        thread(name = "chaquopy-exec") {
            val result = try {
                get()?.getBuiltins()?.callAttr("exec", code)?.toString()
            } catch (e: Exception) {
                LogUtils.d("PythonEnv", "exec error: ${e.message}")
                null
            }
            onResult(result)
        }
    }

    /**
     * 调用模块函数并转为字符串。
     * Java 对象会自动转换为 Python 对象；异常已记录，返回 null。
     */
    fun callModuleFunc(module: PyObject, func: String, args: List<Any?> = emptyList()): String? {
        return try {
            module.callAttr(func, *args.toTypedArray()).toString()
        } catch (e: Exception) {
            LogUtils.d("PythonEnv", "callModuleFunc $func error: ${e.message}")
            null
        }
    }
}
