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
    fun submit(plugin: String, func: String, args: List<Any?> = emptyList()): Task? {
        val key = taskKey(plugin, func, args)

        // 去重：已入队或正在执行的同一任务直接返回
        activeTasks[key]?.let { return it }

        if (paused) {
            LogUtils.d("PyTaskQueue", "queue paused, reject task: $plugin.$func")
            return null
        }

        val task = Task(plugin, func, args)

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
            activeTasks.remove(taskKey(pending.plugin, pending.func, pending.args))
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

            // 通过 plugin_entry.call_func_json 调用插件函数，确保返回值序列化为 JSON 字符串
            // （直接 callAttr().toString() 会输出 Python repr，单引号，非合法 JSON）
            val pluginEntry = PythonEnv.module("plugin_entry")
            val jsonResult: String? = if (pluginEntry != null) {
                pluginEntry.callAttr("call_func_json", module, task.func, task.args)?.toString()
            } else {
                // plugin_entry 不可用时回退到直接调用（输出 Python repr，可能非 JSON）
                PythonEnv.callModuleFunc(module, task.func, task.args)
            }
            task.complete(jsonResult)
        } catch (e: Exception) {
            LogUtils.d("PyTaskQueue", "execute task error: ${e.message}")
            task.complete(null)
        } finally {
            activeTasks.remove(taskKey(task.plugin, task.func, task.args))
        }
    }

    private fun taskKey(plugin: String, func: String, args: List<Any?>): String {
        return "$plugin.$func.${args.joinToString(",")}"
    }
}

/**
 * 任务实例。latch 用于阻塞等待完成。
 */
class Task(
    val plugin: String,
    val func: String,
    val args: List<Any?>,
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
