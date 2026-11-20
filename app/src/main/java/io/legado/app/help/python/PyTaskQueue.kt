package io.legado.app.help.python

import io.legado.app.utils.LogUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Python 任务队列：单线程串行执行插件函数。
 *
 * 约束：
 * - 单工作线程串行执行（Chaquopy Python 解释器非线程安全，GIL 串行是天然约束）；
 * - 有界队列（容量 32），队列满时拒绝新任务；
 * - 任务去重：相同 plugin+func+args 视为同一任务，仅保留一个实例；
 * - 执行前校验插件已加载，未加载直接返回 null；
 * - reload 期间暂停：暂停期间不接受新任务，正在执行的任务等待完成。
 *
 * 结果通过 Task.result 获取，可配合 await() 阻塞等待。
 */
object PyTaskQueue {

    private const val QUEUE_CAPACITY = 32
    private const val WORKER_THREAD_NAME = "py-task-worker"
    private const val POLL_TIMEOUT_MS = 200L

    private val taskQueue = LinkedBlockingQueue<Task>(QUEUE_CAPACITY)

    /** 活跃任务表（去重依据）：key -> Task，执行完成后移除 */
    private val activeTasks = ConcurrentHashMap<String, Task>()

    @Volatile
    private var paused = false
    private val pauseLock = Object()

    private val workerThread = Thread(this::workerLoop, WORKER_THREAD_NAME).apply {
        isDaemon = true
    }

    init {
        workerThread.start()
    }

    /**
     * 提交任务到队列。
     * 返回 Task 实例（含 await/result）；去重命中返回已存在的 Task；队列满返回 null。
     */
    fun submit(
        plugin: String,
        func: String,
        args: List<Any?> = emptyList(),
        extend: String = "",
    ): Task? {
        val key = taskKey(plugin, func, args, extend)

        // 去重：已入队或正在执行的同一任务直接返回
        activeTasks[key]?.let { return it }

        if (paused) {
            LogUtils.d("PyTaskQueue", "queue paused, reject task: $plugin.$func")
            return null
        }

        val task = Task(plugin, func, args, extend)

        // putIfAbsent 原子操作，避免并发重复添加
        val existing = activeTasks.putIfAbsent(key, task)
        if (existing != null) return existing

        val offered = taskQueue.offer(task)
        if (!offered) {
            activeTasks.remove(key)
            LogUtils.d("PyTaskQueue", "queue full, reject task: $plugin.$func")
            return null
        }

        LogUtils.d("PyTaskQueue", "task submitted: $key")
        return task
    }

    /**
     * 阻塞等待任务完成。超时返回 null。
     */
    fun awaitCompletion(task: Task, timeoutMs: Long = 15_000L): String? {
        if (!task.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            LogUtils.d("PyTaskQueue", "await timeout: ${task.plugin}.${task.func}")
            return null
        }
        return task.result
    }

    /**
     * 暂停队列（reload 前调用）。正在执行的任务等待完成，新任务被拒绝。
     */
    fun pause() {
        synchronized(pauseLock) {
            paused = true
            LogUtils.d("PyTaskQueue", "queue paused")
        }
    }

    /**
     * 恢复队列（reload 后调用）。清空队列中的待执行任务。
     */
    fun resume() {
        synchronized(pauseLock) {
            paused = false
            LogUtils.d("PyTaskQueue", "queue resumed")
            pauseLock.notifyAll()
        }
    }

    /**
     * 清空队列中的所有待执行任务（reload 后丢弃过期任务）。
     */
    fun clearPending() {
        while (true) {
            val pending = taskQueue.poll() ?: break
            activeTasks.remove(taskKey(pending.plugin, pending.func, pending.args, pending.extend))
            LogUtils.d("PyTaskQueue", "cleared pending task: ${pending.plugin}.${pending.func}")
        }
    }

    /**
     * 获取队列中待执行任务数（调试用）。
     */
    fun pendingCount(): Int = taskQueue.size

    /**
     * 获取正在执行的任务数（调试用）。
     */
    fun runningCount(): Int = activeTasks.size

    // ---- internal ----

    private fun workerLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                // 暂停时等待恢复信号
                synchronized(pauseLock) {
                    while (paused) {
                        pauseLock.wait(POLL_TIMEOUT_MS)
                    }
                }

                val task = taskQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    ?: continue

                executeTask(task)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                LogUtils.d("PyTaskQueue", "worker loop error: ${e.message}")
            }
        }
    }

    private fun executeTask(task: Task) {
        try {
            val module = PythonEnv.module(task.plugin)
            if (module == null) {
                LogUtils.d("PyTaskQueue", "plugin not loaded: ${task.plugin}")
                task.complete(null)
                return
            }

            LogUtils.d("PyTaskQueue", "calling ${task.plugin}.${task.func}")

            val pluginEntry = PythonEnv.module("plugin_entry")
            val jsonResult: String? = if (pluginEntry != null) {
                callPluginFunc(module, pluginEntry, task.func, task.args, task.extend)
            } else {
                LogUtils.d("PyTaskQueue", "plugin_entry not available, fallback to callModuleFunc")
                PythonEnv.callModuleFunc(module, task.func, task.args)
            }
            LogUtils.d("PyTaskQueue", "result: $jsonResult")
            task.complete(jsonResult)
        } catch (e: Exception) {
            LogUtils.d("PyTaskQueue", "execute task error: ${e.message}")
            task.complete(null)
        } finally {
            activeTasks.remove(taskKey(task.plugin, task.func, task.args, task.extend))
        }
    }

    /**
     * 直接从 Java 调用插件函数，再用 plugin_entry._to_json 序列化为 JSON 字符串。
     * 绕过 Python getattr 在 Chaquopy 模块对象上的兼容性问题。
     */
    private fun callPluginFunc(
        module: com.chaquo.python.PyObject,
        pluginEntry: com.chaquo.python.PyObject,
        func: String,
        args: List<Any?>,
        extend: String,
    ): String? {
        var directResult: String? = null
        var directError: Exception? = null
        try {
            val result = module.callAttr(func, *args.toTypedArray())
            directResult = pluginEntry.callAttr("_to_json", result)?.toString()
        } catch (e: Exception) {
            directError = e
            // Installed module functions may accept fewer arguments than the Spider bridge.
            for (count in listOf(2, 1)) {
                if (args.size <= count) continue
                try {
                    val result = module.callAttr(func, *args.take(count).toTypedArray())
                    directResult = pluginEntry.callAttr("_to_json", result)?.toString()
                    directError = null
                    break
                } catch (e2: Exception) {
                    // fall through
                }
            }
        }

        // 直接调用异常、返回空结果（null/[]/{}）或错误 JSON 时，尝试 Spider 类兼容层
        if (!isEmptyResult(directResult)) return directResult
        val reason = directError?.let { it.message ?: it.toString() } ?: "empty result"
        LogUtils.d("PyTaskQueue", "callPluginFunc $func direct failed/empty: $reason, trying spider fallback")
        val spiderResult = callSpiderFunc(module, pluginEntry, func, args, extend)
        if (!isEmptyResult(spiderResult)) {
            LogUtils.d("PyTaskQueue", "spider fallback succeeded for $func")
            return spiderResult
        }
        // 保留模块级调用的原始结果：错误 JSON 比空结果更有诊断价值
        if (directResult != null) return directResult
        val finalMsg = getErrorMessage(directError ?: Exception("empty result"), func)
        LogUtils.d("PyTaskQueue", "callPluginFunc error: $finalMsg")
        return buildErrorJson(finalMsg)
    }

    /** 结果是否为空或错误（用于触发 Spider 兜底） */
    private fun isEmptyResult(json: String?): Boolean {
        val text = json?.trim().orEmpty()
        return text.isEmpty() || text == "None" || text == "null" ||
            text == "[]" || text == "{}" || text.contains("\"error\"")
    }

    /**
     * Spider 类兼容层调用：通过 plugin_entry.call_spider_func 实例化 Spider 子类并调用对应方法。
     * 用于 FongMi/TV 约定的 py 源（无模块级函数，仅 Spider 类方法）。
     */
    private fun callSpiderFunc(
        module: com.chaquo.python.PyObject,
        pluginEntry: com.chaquo.python.PyObject,
        func: String,
        args: List<Any?>,
        extend: String,
    ): String? {
        return try {
            val result = pluginEntry.callAttr(
                "call_spider_func",
                module,
                func,
                args.toTypedArray(),
                extend,
            )
            val str = result?.toString()
            // Python None → "None"，视为无 Spider 类，返回 null 走原始错误路径
            if (str == null || str == "None") null else str
        } catch (e: Exception) {
            LogUtils.d("PyTaskQueue", "call_spider_func fallback failed: ${e.message}")
            null
        }
    }

    private fun getErrorMessage(e: Exception, func: String): String {
        val msg = e.message ?: e.toString()
        return "$func: $msg"
    }

    private fun buildErrorJson(errMsg: String): String {
        val escaped = errMsg.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"error\": \"$escaped\"}"
    }

    private fun taskKey(plugin: String, func: String, args: List<Any?>, extend: String): String {
        return "$plugin.$func.${args.joinToString(",")}@$extend"
    }
}

/**
 * 任务实例。latch 用于阻塞等待完成。
 */
class Task(
    val plugin: String,
    val func: String,
    val args: List<Any?>,
    val extend: String = "",
) {
    @Volatile
    var result: String? = null
        private set

    val latch = CountDownLatch(1)

    fun complete(value: String?) {
        result = value
        latch.countDown()
    }

    fun await(timeoutMs: Long = 15_000L): String? {
        if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return null
        }
        return result
    }
}
