@file:Suppress("unused")

package io.legado.app.help.http

import android.text.TextUtils
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.equalsRegex
import io.legado.app.constant.AppPattern.semicolonRegex
import io.legado.app.help.http.CookieBridge.taskCookieScopePrefix
import io.legado.app.utils.MD5Utils
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cookie
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieManager.getCookieNoSession
import io.legado.app.help.http.CookieManager.mergeCookiesToMap
import io.legado.app.help.http.api.CookieManagerInterface
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.removeCookie
import io.legado.app.utils.splitNotBlank

@Keep
object CookieStore : CookieManagerInterface, TaskCookieBackend {

    /**
     *保存cookie到数据库，会自动识别url的二级域名
     */
    override fun setCookie(url: String, cookie: String?) {
        try {
            val domain = NetworkUtils.getSubDomain(url)
            CacheManager.putMemory("${domain}_cookie", cookie ?: "")
            val cookieBean = Cookie(domain, cookie ?: "")
            appDb.cookieDao.insert(cookieBean)
        } catch (e: Exception) {
            AppLog.put("保存Cookie失败\n$e", e)
        }
    }

    fun setWebCookie(url: String, cookie: String) {
        try {
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            val cookies = cookie.splitNotBlank(";")
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeSessionCookies(null)
            cookies.forEach {
                cookieManager.setCookie(baseUrl, it)
            }
        } catch (e: Exception) {
            AppLog.put("设置WebCookie失败\n$e", e)
        }
    }

    override fun replaceCookie(url: String, cookie: String) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(cookie)) {
            return
        }
        val oldCookie = getCookieNoSession(url)
        if (TextUtils.isEmpty(oldCookie)) {
            setCookie(url, cookie)
        } else {
            val cookieMap = cookieToMap(oldCookie)
            cookieMap.putAll(cookieToMap(cookie))
            val newCookie = mapToCookie(cookieMap)
            setCookie(url, newCookie)
        }
    }

    @Synchronized
    override fun setTaskCookie(scopeId: String, url: String, cookie: String?) {
        val normalized = cookie
            ?.takeIf { it.isNotBlank() }
            ?.let { formatCookie(cookieToMap(it)) }
        if (normalized.isNullOrBlank()) {
            removeTaskCookie(scopeId, url)
            return
        }
        val key = taskCookieKey(scopeId, url)
        appDb.cookieDao.insert(Cookie(key, normalized))
        CacheManager.deleteMemory("${key}_session_cookie")
    }

    @Synchronized
    override fun mergeTaskCookie(scopeId: String, url: String, cookie: String?) {
        if (cookie.isNullOrBlank()) return
        val key = taskCookieKey(scopeId, url)
        val incoming = cookieToMap(cookie)
        if (incoming.isEmpty()) return
        val persistent = cookieToMap(appDb.cookieDao.get(key)?.cookie.orEmpty())
        persistent.putAll(incoming)
        val normalized = formatCookie(persistent)
        if (normalized.isNullOrBlank()) {
            removeTaskCookie(scopeId, url)
            return
        }
        appDb.cookieDao.insert(Cookie(key, normalized))

        val sessionKey = "${key}_session_cookie"
        val session = cookieToMap(CacheManager.getFromMemory(sessionKey) as? String ?: "")
        incoming.keys.forEach(session::remove)
        val sessionCookie = mapToCookie(session)
        if (sessionCookie.isNullOrEmpty()) {
            CacheManager.deleteMemory(sessionKey)
        } else {
            CacheManager.putMemory(sessionKey, sessionCookie)
        }
    }

    @Synchronized
    fun updateTaskCookie(
        scopeId: String,
        url: String,
        cookies: List<okhttp3.Cookie>,
        now: Long = System.currentTimeMillis()
    ) {
        if (cookies.isEmpty()) return
        val key = taskCookieKey(scopeId, url)
        val persistent = cookieToMap(appDb.cookieDao.get(key)?.cookie.orEmpty())
        val sessionKey = "${key}_session_cookie"
        val session = cookieToMap(CacheManager.getFromMemory(sessionKey) as? String ?: "")
        cookies.forEach { cookie ->
            persistent.remove(cookie.name)
            session.remove(cookie.name)
            if (cookie.expiresAt > now) {
                if (cookie.persistent) persistent[cookie.name] = cookie.value
                else session[cookie.name] = cookie.value
            }
        }
        val persistentCookie = mapToCookie(persistent)
        val sessionCookie = mapToCookie(session)
        when {
            !persistentCookie.isNullOrEmpty() -> appDb.cookieDao.insert(Cookie(key, persistentCookie))
            !sessionCookie.isNullOrEmpty() -> appDb.cookieDao.insert(Cookie(key, ""))
            else -> appDb.cookieDao.delete(key)
        }
        if (sessionCookie.isNullOrEmpty()) {
            CacheManager.deleteMemory(sessionKey)
        } else {
            CacheManager.putMemory(sessionKey, sessionCookie)
        }
    }

    @Synchronized
    override fun getTaskCookie(scopeId: String, url: String): String {
        val key = taskCookieKey(scopeId, url)
        return CookieManager.mergeCookies(
            appDb.cookieDao.get(key)?.cookie,
            CacheManager.getFromMemory("${key}_session_cookie") as? String
        ).orEmpty()
    }

    @Synchronized
    fun getTaskCookiesByDomain(scopeId: String): Map<String, String> {
        val prefix = "$TASK_COOKIE_PREFIX${normalizeScope(scopeId)}:"
        return appDb.cookieDao.getByPrefix(prefix).associate { row ->
            val domain = row.url.removePrefix(prefix)
            val session = CacheManager.getFromMemory("${row.url}_session_cookie") as? String
            domain to CookieManager.mergeCookies(row.cookie, session).orEmpty()
        }
    }

    @Synchronized
    override fun removeTaskCookie(scopeId: String, url: String) {
        val key = taskCookieKey(scopeId, url)
        appDb.cookieDao.delete(key)
        CacheManager.deleteMemory("${key}_session_cookie")
    }

    @Synchronized
    fun removeTaskCookie(scopeId: String, url: String, cookieName: String) {
        val key = taskCookieKey(scopeId, url)
        val persistent = cookieToMap(appDb.cookieDao.get(key)?.cookie.orEmpty())
        persistent.remove(cookieName)
        val sessionKey = "${key}_session_cookie"
        val session = cookieToMap(CacheManager.getFromMemory(sessionKey) as? String ?: "")
        session.remove(cookieName)
        val persistentCookie = mapToCookie(persistent)
        val sessionCookie = mapToCookie(session)
        when {
            !persistentCookie.isNullOrEmpty() -> appDb.cookieDao.insert(Cookie(key, persistentCookie))
            !sessionCookie.isNullOrEmpty() -> appDb.cookieDao.insert(Cookie(key, ""))
            else -> appDb.cookieDao.delete(key)
        }
        if (sessionCookie.isNullOrEmpty()) {
            CacheManager.deleteMemory(sessionKey)
        } else {
            CacheManager.putMemory(sessionKey, sessionCookie)
        }
    }

    @Synchronized
    fun removeTaskCookies(scopeId: String) {
        val prefix = "$TASK_COOKIE_PREFIX${normalizeScope(scopeId)}:"
        val rows = appDb.cookieDao.getByPrefix(prefix)
        appDb.cookieDao.deleteByPrefix(prefix)
        rows.forEach { cookie ->
            CacheManager.deleteMemory("${cookie.url}_session_cookie")
        }
    }

    fun taskCookieKey(scopeId: String, url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        return "$TASK_COOKIE_PREFIX${normalizeScope(scopeId)}:$domain"
    }

    private fun normalizeScope(scopeId: String): String {
        return if (scopeId.startsWith(taskCookieScopePrefix)) {
            scopeId
        } else {
            taskCookieScopePrefix + MD5Utils.md5Encode16(scopeId)
        }
    }

    private const val TASK_COOKIE_PREFIX = "task_cookie:"

    /**
     *获取url所属的二级域名的cookie
     */
    override fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        return formatCookie(
            mergeCookiesToMap(
                getCookieNoSession(url),
                CookieManager.getSessionCookie(domain)
            )
        ) { key -> CookieManager.removeCookie(url, key) }
    }

    override fun getCookie(scope: String, url: String): String {
        if (!scope.startsWith(taskCookieScopePrefix)) return ""
        return formatCookie(cookieToMap(getTaskCookie(scope, url))) { key ->
            removeTaskCookie(scope, url, key)
        }
    }

    private fun formatCookie(
        cookieMap: MutableMap<String, String>,
        onRemove: ((String) -> Unit)? = null
    ): String {
        var cookie = mapToCookie(cookieMap) ?: ""
        while (cookie.length > 4096 && cookieMap.isNotEmpty()) {
            val key = cookieMap.keys.first()
            cookieMap.remove(key)
            onRemove?.invoke(key)
            cookie = mapToCookie(cookieMap) ?: ""
        }
        return cookie
    }

    override fun getKey(url: String, key: String): String {
        return cookieToMap(getCookie(url))[key].orEmpty()
    }

    override fun removeCookie(url: String) {
        val domain = NetworkUtils.getSubDomain(url)
        appDb.cookieDao.delete(domain)
        CacheManager.deleteMemory("${domain}_cookie")
        CacheManager.deleteMemory("${domain}_session_cookie")
        android.webkit.CookieManager.getInstance().removeCookie(url)
    }

    override fun cookieToMap(cookie: String): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        if (cookie.isBlank()) {
            return cookieMap
        }
        val pairArray = cookie.split(semicolonRegex).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (pair in pairArray) {
            val pairs = pair.split(equalsRegex, 2).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (pairs.size <= 1) {
                continue
            }
            val key = pairs[0].trim { it <= ' ' }
            val value = pairs[1]
            if (value.isNotBlank() || value.trim { it <= ' ' } == "null") {
                cookieMap[key] = value.trim { it <= ' ' }
            }
        }
        return cookieMap
    }

    override fun mapToCookie(cookieMap: Map<String, String>?): String? {
        if (cookieMap.isNullOrEmpty()) {
            return null
        }
        val builder = StringBuilder()
        cookieMap.keys.forEachIndexed { index, key ->
            if (index > 0) builder.append("; ")
            builder.append(key).append("=").append(cookieMap[key])
        }
        return builder.toString()
    }

    fun clear() {
        appDb.cookieDao.deleteOkHttp()
    }

}
