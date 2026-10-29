package io.legado.app.help.http

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/** Runtime Cookie namespace for scheduled-task network requests. */
class TaskCookieContext(
    private val taskKey: String,
    private val cookieJarEnabled: Boolean = true
) : ThreadContextElement<TaskCookieContext.State?> {

    data class State(val taskKey: String, val cookieJarEnabled: Boolean)

    companion object Key : CoroutineContext.Key<TaskCookieContext> {
        private val current = ThreadLocal<State?>()

        fun currentTaskKey(): String? = current.get()?.taskKey

        fun currentHeaderValue(): String? {
            val state = current.get() ?: return null
            return state.taskKey
                .takeIf { state.cookieJarEnabled }
                ?.let(CookieBridge::taskCookieScopeValue)
        }

        fun headerValue(taskKey: String?): String? = taskKey?.let(CookieBridge::taskCookieScopeValue)

        fun <T> withTask(
            taskKey: String,
            cookieJarEnabled: Boolean = true,
            block: () -> T
        ): T {
            val previous = current.get()
            current.set(State(taskKey, cookieJarEnabled))
            return try {
                block()
            } finally {
                if (previous == null) current.remove() else current.set(previous)
            }
        }
    }

    override val key: CoroutineContext.Key<TaskCookieContext> get() = Key

    override fun updateThreadContext(context: CoroutineContext): State? {
        val previous = current.get()
        current.set(State(taskKey, cookieJarEnabled))
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: State?) {
        if (oldState == null) current.remove() else current.set(oldState)
    }
}
