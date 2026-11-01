package io.legado.app.help.http.api

interface CookieManagerInterface {

    /**
     * 保存cookie
     */
    fun setCookie(url: String, cookie: String?)

    /**
     * 替换cookie
     */
    fun replaceCookie(url: String, cookie: String)

    /**
     * 获取cookie
     */
    fun getCookie(url: String): String

    fun getCookie(scope: String, url: String): String = getCookie(url)

    /** 获取指定键的 Cookie 值 */
    fun getKey(url: String, key: String): String = cookieToMap(getCookie(url))[key].orEmpty()

    /**
     * 移除cookie
     */
    fun removeCookie(url: String)

    fun cookieToMap(cookie: String): MutableMap<String, String>

    fun mapToCookie(cookieMap: Map<String, String>?): String?
}
