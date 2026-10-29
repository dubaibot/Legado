package io.legado.app.data.entities

import android.webkit.JavascriptInterface
import cn.hutool.crypto.symmetric.AES
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.help.CacheManager
import io.legado.app.help.ConcurrentRateLimiter.Companion.updateConcurrentRate
import io.legado.app.help.JsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.crypto.SymmetricCryptoAndroid
import io.legado.app.help.http.CookieBridge
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.TaskCookieStore
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.getShareScope
import io.legado.app.model.SharedJsScope
import io.legado.app.model.SharedJsScope.remove
import io.legado.app.model.login.LoginUiV2
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.has
import io.legado.app.utils.isMainThread
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 可在js里调用,source.xxx()
 */
@Suppress("unused")
interface BaseSource : JsExtensions {
    /**
     * 并发率
     */
    var concurrentRate: String?

    /**
     * 登录地址
     */
    var loginUrl: String?

    /**
     * 登录UI
     */
    var loginUi: String?

    /**
     * 请求头
     */
    var header: String?

    /**
     * 启用cookieJar
     */
    var enabledCookieJar: Boolean?

    /**
     * Cookie 隔离作用域 key
     */
    val cookieScopeKey: String? get() = null

    /**
     * js库
     */
    var jsLib: String?

    override fun getTag(): String

    fun getKey(): String

    override fun getSource(): BaseSource? {
        return this
    }

    fun getLoginJs(): String? {
        val loginJs = loginUrl
        return when {
            loginJs == null -> null
            loginJs.startsWith("@js:") -> loginJs.substring(4)
            loginJs.startsWith("<js>") -> loginJs.substring(4, loginJs.lastIndexOf("<"))
            else -> loginJs
        }
    }

    /** 登录UI v2:loginUi 字段为 {"version":2} 标记时启用显式状态协议 */
    fun isLoginUiV2(): Boolean {
        return LoginUiV2.isV2(loginUi)
    }

    /** 求值 loginUi(state) 渲染函数;返回 {rows:[...]} JSON 字符串 */
    fun evalLoginUiV2(
        stateJson: String,
        book: Book? = null,
        chapter: BookChapter? = null
    ): String? = evalLoginUiV2WithJava(stateJson, book, chapter, null)

    fun evalLoginUiV2WithJava(
        stateJson: String,
        book: Book? = null,
        chapter: BookChapter? = null,
        java: JsExtensions? = null
    ): String? {
        val loginJs = getLoginJs()
            ?: throw NoStackTraceException("登录UI v2 需要 loginUrl JS 承载 loginUi/loginAction 函数")
        val js = "$loginJs\nloginUi(JSON.parse(String(__loginState)))"
        val result = evalJS(js) {
            put("__loginState", stateJson)
            put("book", book)
            put("chapter", chapter)
            java?.let { put("java", it) }
        }
        return normalizeJsResult(result)
    }

    /** 求值 loginAction(action, state, form) 命令函数;返回命令对象 JSON 字符串 */
    fun evalLoginActionV2(
        action: String,
        stateJson: String,
        formJson: String,
        book: Book? = null,
        chapter: BookChapter? = null
    ): String? = evalLoginActionV2WithJava(action, stateJson, formJson, book, chapter, null)

    fun evalLoginActionV2WithJava(
        action: String,
        stateJson: String,
        formJson: String,
        book: Book? = null,
        chapter: BookChapter? = null,
        java: JsExtensions? = null
    ): String? {
        val loginJs = getLoginJs()
            ?: throw NoStackTraceException("登录UI v2 需要 loginUrl JS 承载 loginUi/loginAction 函数")
        val js = "$loginJs\n" +
            "loginAction(String(__loginAction), JSON.parse(String(__loginState)), JSON.parse(String(__loginForm)))"
        val result = evalJS(js) {
            put("__loginAction", action)
            put("__loginState", stateJson)
            put("__loginForm", formJson)
            put("book", book)
            put("chapter", chapter)
            java?.let { put("java", it) }
        }
        return normalizeJsResult(result)
    }

    /** JS 返回值归一化:跨 JS 边界统一为 JSON 字符串 */
    private fun normalizeJsResult(result: Any?): String? {
        var value = result
        if (value is Wrapper) {
            value = value.unwrap()
        }
        return when {
            value == null || value is Undefined -> null
            value is String -> value
            value is CharSequence -> value.toString()
            value is Scriptable -> try {
                RhinoScriptEngine.stringifyScriptable(value, throwOnFailure = true)
                    ?: GSON.toJson(value)
            } catch (e: Exception) {
                throw NoStackTraceException("JS返回值 JSON.stringify 失败: ${e.message}")
            }
            else -> GSON.toJson(value)
        }
    }

    /**
     * 调用login函数 实现登录请求
     */
    @JavascriptInterface
    fun login() {
        val loginJs = getLoginJs()
        if (!loginJs.isNullOrBlank()) {
            @Language("js")
            val js = """$loginJs
                if(typeof login=='function'){
                    login.apply(this);
                } else {
                    throw('Function login not implements!!!')
                }
            """.trimIndent()
            evalJS(js)
        }
    }

    /**
     * 解析header规则
     */
    fun getHeaderMap(hasLoginHeader: Boolean = false) = HashMap<String, String>().apply {
        header?.let {
            try {
                val json = when {
                    it.startsWith("@js:", true) -> evalJS(it.substring(4)).toString()
                    it.startsWith("<js>", true) -> evalJS(
                        it.substring(4, it.lastIndexOf("<"))
                    ).toString()

                    else -> it
                }
                GSONStrict.fromJsonObject<Map<String, String>>(json).getOrNull()?.let { map ->
                    putAll(map)
                } ?: GSON.fromJsonObject<Map<String, String>>(json).getOrNull()?.let { map ->
                    log("请求头规则 JSON 格式不规范，请改为规范格式")
                    putAll(map)
                }
            } catch (e: Exception) {
                AppLog.put("执行请求头规则出错\n$e", e)
            }
        }
        if (!has(AppConst.UA_NAME, true)) {
            put(AppConst.UA_NAME, AppConfig.userAgent)
        }
        if (hasLoginHeader) {
            getLoginHeaderMap()?.let {
                putAll(it)
            }
        }
    }

    /**
     * 获取用于登录的头部信息
     */
    @JavascriptInterface
    fun getLoginHeader(): String? {
        return CacheManager.get("loginHeader_${getKey()}")
    }

    fun getLoginHeaderMap(): Map<String, String>? {
        val cache = getLoginHeader() ?: return null
        return GSON.fromJsonObject<Map<String, String>>(cache).getOrNull()
    }

    /**
     * 保存登录头部信息,map格式,访问时自动添加
     */
    fun putLoginHeader(header: String) {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull()
        val cookie = headerMap?.get("Cookie") ?: headerMap?.get("cookie")
        cookie?.let {
            CookieStore.replaceCookie(getKey(), it)
        }
        CacheManager.put("loginHeader_${getKey()}", header)
    }

    fun removeLoginHeader() {
        CacheManager.delete("loginHeader_${getKey()}")
        CookieStore.removeCookie(getKey())
    }

    /**
     * 获取用户信息,可以用来登录
     * 用户信息采用aes加密存储
     */
    @JavascriptInterface
    fun getLoginInfo(): String? {
        try {
            val key = AppConst.androidId.encodeToByteArray(0, 16)
            val cache = CacheManager.get("userInfo_${getKey()}") ?: return null
            return AES(key).decryptStr(cache)
        } catch (e: Exception) {
            AppLog.put("获取登陆信息出错", e)
            return null
        }
    }

    private fun configureScriptBindings(): ScriptBindings.() -> Unit = {
        put("result", mutableMapOf<String, String>())
        put("book", null)
        put("chapter", null)
    }

    fun getLoginInfoMap(): MutableMap<String, String> {
        val json = getLoginInfo() ?: if (loginUi.isNullOrBlank()) {
            return mutableMapOf()
        } else {
            val loginUiJson = loginUi?.let {
                when {
                    it.startsWith("@js:") -> evalJS(
                        "${getLoginJs() ?: ""}\n${it.substring(4)}",
                        bindingsConfig = configureScriptBindings()
                    ).toString()

                    it.startsWith("<js>") -> evalJS(
                        "${getLoginJs() ?: ""}\n${it.substring(4, it.lastIndexOf("<"))}",
                        bindingsConfig = configureScriptBindings()
                    ).toString()

                    else -> it
                }
            }
            val longinInfo = GSON.fromJsonArray<RowUi>(loginUiJson).getOrNull()
                ?.filter { it.type != "button" }
                ?.associate { it.name to (it.default ?: "") }
                ?.takeIf { it.isNotEmpty() }?.also {
                    putLoginInfo(GSON.toJson(it))
                }
            return longinInfo?.toMutableMap() ?: mutableMapOf()
        }
        return GSON.fromJsonObject<MutableMap<String, String>>(json).getOrNull() ?: mutableMapOf()
    }

    /**
     * 保存用户信息,aes加密
     */
    @JavascriptInterface
    fun putLoginInfo(info: String): Boolean {
        return try {
            val key = (AppConst.androidId).encodeToByteArray(0, 16)
            val encodeStr = SymmetricCryptoAndroid("AES", key).encryptBase64(info)
            CacheManager.put("userInfo_${getKey()}", encodeStr)
            true
        } catch (e: Exception) {
            AppLog.put("保存登陆信息出错", e)
            false
        }
    }

    @JavascriptInterface
    fun removeLoginInfo() {
        CacheManager.delete("userInfo_${getKey()}")
    }

    /**
     * 设置自定义变量
     * @param variable 变量内容
     */
    fun setVariable(variable: String?) {
        if (variable != null) {
            CacheManager.put("sourceVariable_${getKey()}", variable)
        } else {
            CacheManager.delete("sourceVariable_${getKey()}")
        }
    }

    /**
     * 设置自定义变量
     * 新,统一为put名称存变量
     */
    @JavascriptInterface
    fun putVariable(variable: String?) {
        if (variable != null) {
            CacheManager.put("sourceVariable_${getKey()}", variable)
        } else {
            CacheManager.delete("sourceVariable_${getKey()}")
        }
    }

    /**
     * 获取自定义变量
     */
    @JavascriptInterface
    fun getVariable(): String {
        return CacheManager.get("sourceVariable_${getKey()}") ?: ""
    }

    /**
     * 保存数据
     */
    @JavascriptInterface
    fun put(key: String, value: String): String {
        val oldValue = get(key)
        CacheManager.put("v_${getKey()}_${key}", value)
        io.legado.app.data.repository.debug.FlowLogRecorder.logVariableWrite(
            source = this as? io.legado.app.data.entities.BaseSource,
            key = key,
            value = value,
            oldValue = oldValue.takeIf { it.isNotEmpty() },
            storage = io.legado.app.model.debug.VariableStorage.SOURCE
        )
        return value
    }

    /**
     * 获取保存的数据
     */
    @JavascriptInterface
    fun get(key: String): String {
        val value = CacheManager.get("v_${getKey()}_${key}") ?: ""
        if (value.isNotEmpty()) {
            io.legado.app.data.repository.debug.FlowLogRecorder.logVariableRead(
                source = this as? io.legado.app.data.entities.BaseSource,
                key = key,
                value = value,
                storage = io.legado.app.model.debug.VariableStorage.SOURCE
            )
        }
        return value
    }

    /**
     * 刷新发现
     */
    fun refreshExplore() {
        if (isMainThread) {
            error("refreshExplore must be called on a background thread")
        }
        runBlocking {
            if (this@BaseSource is BookSource) {
                this@BaseSource.clearExploreKindsCache()
            }
        }
    }

    /**
     * 刷新JSLib
     */
    fun refreshJSLib() {
        if (isMainThread) {
            error("refreshJSLib must be called on a background thread")
        }
        runBlocking {
            remove(jsLib)
        }
    }

    /**
     * 设置并发率
     */
    fun putConcurrent(value: String) {
        updateConcurrentRate(getKey(),value)
    }

    /**
     * 执行JS
     */
    @Throws(Exception::class)
    fun evalJS(
        jsStr: String,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        bindingsConfig: ScriptBindings.() -> Unit = {}
    ): Any? {
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = this
            bindings["source"] = this
            bindings["baseUrl"] = getKey()
            bindings["cookie"] = CookieStore
            bindings["cache"] = CacheManager
            bindings.apply(bindingsConfig)
        }
        val sharedScope = getShareScope(coroutineContext)
        val scope = if (sharedScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply {
                prototype = sharedScope
            }
        }
        return RhinoScriptEngine.eval(jsStr, scope, coroutineContext)
    }

    /**
     * Evaluate with a fresh JS library scope. Scheduled tasks use this entry point so parallel
     * workers never mutate the cached scope shared by ordinary book-source requests.
     */
    @Throws(Exception::class)
    fun evalJSInIsolatedScope(
        jsStr: String,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        bindingsConfig: ScriptBindings.() -> Unit = {}
    ): Any? {
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = this
            bindings["source"] = this
            bindings["baseUrl"] = getKey()
            val taskScope = cookieScopeKey
                ?.takeIf { enabledCookieJar == true }
                ?.let(CookieBridge::taskCookieScopeValue)
            bindings["cookie"] = taskScope?.let {
                TaskCookieStore(it, getKey())
            } ?: CookieStore
            bindings["cache"] = CacheManager
            taskScope?.let {
                bindings["taskCookieScope"] = it
            }
            bindings.apply(bindingsConfig)
        }
        val isolatedScope = SharedJsScope.createIsolatedScope(jsLib, coroutineContext)
        val scope = if (isolatedScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply {
                prototype = isolatedScope
            }
        }
        return RhinoScriptEngine.eval(jsStr, scope, coroutineContext)
    }
}