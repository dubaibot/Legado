package com.lanyue.reader.core.diagnostics

/**
 * 惰性单例壳：持有者只在第一次真正取用时才构造被持有对象。
 *
 * 用它而不是 `by lazy` 的原因是可观测：`isCreated` 能让调用方（以及单测）
 * 断言「冷启动阶段到底有没有把数据库拉起来」。TASK-BACKLOG 的约束是
 * onCreate 里不做重活，这条约束如果没有可断言的信号，就只能靠人肉 review。
 *
 * 构造失败不缓存：工厂抛出后 cached 仍为 null，下次 get 会重试。
 * 否则一次偶发失败（如磁盘瞬时不可用）会把整个进程钉死在坏状态上。
 */
class AppGraph<T : Any>(private val factory: () -> T) {

    @Volatile
    private var cached: T? = null

    val isCreated: Boolean
        get() = cached != null

    fun get(): T {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: factory().also { cached = it }
        }
    }
}
