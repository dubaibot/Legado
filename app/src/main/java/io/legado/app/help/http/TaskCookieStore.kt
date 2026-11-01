package io.legado.app.help.http

import androidx.annotation.Keep
import io.legado.app.help.http.api.CookieManagerInterface

interface TaskCookieBackend {
    fun setTaskCookie(scope: String, url: String, cookie: String?)
    fun mergeTaskCookie(scope: String, url: String, cookie: String?)
    fun getTaskCookie(scope: String, url: String): String
    fun removeTaskCookie(scope: String, url: String)
}

/** Cookie facade exposed to a scheduled task's JavaScript environment. */
@Keep
class TaskCookieStore(
    private val scope: String,
    private val sourceKey: String,
    private val backend: TaskCookieBackend = CookieStore
) : CookieManagerInterface {

    override fun setCookie(url: String, cookie: String?) {
        if (cookie.isNullOrBlank()) {
            backend.removeTaskCookie(scope, url)
        } else {
            backend.setTaskCookie(scope, url, cookie)
        }
    }

    override fun replaceCookie(url: String, cookie: String) {
        if (cookie.isBlank()) {
            backend.removeTaskCookie(scope, url)
        } else {
            backend.mergeTaskCookie(scope, url, cookie)
        }
    }

    override fun getCookie(url: String): String = backend.getTaskCookie(scope, url)

    override fun getCookie(scope: String, url: String): String = getCookie(url)

    override fun getKey(url: String, key: String): String = cookieToMap(getCookie(url))[key].orEmpty()

    override fun removeCookie(url: String) {
        backend.removeTaskCookie(scope, url)
    }

    override fun cookieToMap(cookie: String): MutableMap<String, String> = CookieStore.cookieToMap(cookie)

    override fun mapToCookie(cookieMap: Map<String, String>?): String? = CookieStore.mapToCookie(cookieMap)
}
