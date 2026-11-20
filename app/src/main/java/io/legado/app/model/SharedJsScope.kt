package io.legado.app.model

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext

object SharedJsScope {

    private val cacheFolder = File(appCtx.cacheDir, "shareJs")
    private val aCache = ACache.get(cacheFolder)

    private val scopeMap = LruCache<String, WeakReference<ScriptBindings>>(16)

    fun getScope(jsLib: String?, coroutineContext: CoroutineContext?): ScriptBindings? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val key = MD5Utils.md5Encode(jsLib)
        var scope = scopeMap[key]?.get()
        if (scope == null) {
            scope = RhinoScriptEngine.run {
                getRuntimeScope(ScriptBindings())
            }
            if (jsLib.isJsonObject()) {
                val jsMap: Map<String, String> = GSON.fromJson(
                    jsLib,
                    TypeToken.getParameterized(
                        Map::class.java,
                        String::class.java,
                        String::class.java
                    ).type
                )
                jsMap.values.forEach { value ->
                    if (value.isAbsUrl()) {
                        val fileName = MD5Utils.md5Encode(value)
                        var js = aCache.getAsString(fileName)
                        if (js == null) {
                            js = runBlocking {
                                okHttpClient.newCallStrResponse {
                                    url(value)
                                }.body
                            }
                            if (js != null) {
                                aCache.put(fileName, js)
                            } else {
                                throw NoStackTraceException("下载jsLib-${value}失败")
                            }
                        }
                        RhinoScriptEngine.eval(js, scope, coroutineContext)
                    }
                }
            } else {
                RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
            }
            /**
             * 阻止新全局增加（即函数内未用var的隐性全局变量创建）,会直接隐性创建失败,提示变量未定义
             */
            scope.sealObject()
            scopeMap.put(key, WeakReference(scope))
        }
        return scope
    }

    /**
     * Create an uncached library scope for isolated executions such as parallel scheduled tasks.
     * The regular [getScope] cache deliberately shares globals; workers must not mutate one another's
     * JavaScript library state.
     */
    fun createIsolatedScope(jsLib: String?, coroutineContext: CoroutineContext?): ScriptBindings {
        val scope = RhinoScriptEngine.run {
            getRuntimeScope(ScriptBindings())
        }
        if (!jsLib.isNullOrBlank()) {
            if (jsLib.isJsonObject()) {
                val jsMap: Map<String, String> = GSON.fromJson(
                    jsLib,
                    TypeToken.getParameterized(
                        Map::class.java,
                        String::class.java,
                        String::class.java
                    ).type
                )
                jsMap.values.forEach { value ->
                    if (value.isAbsUrl()) {
                        val fileName = MD5Utils.md5Encode(value)
                        var js = aCache.getAsString(fileName)
                        if (js == null) {
                            js = runBlocking {
                                okHttpClient.newCallStrResponse {
                                    url(value)
                                }.body
                            }
                            if (js != null) {
                                aCache.put(fileName, js)
                            } else {
                                throw NoStackTraceException("下载jsLib-${value}失败")
                            }
                        }
                        RhinoScriptEngine.eval(js, scope, coroutineContext)
                    }
                }
            } else {
                RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
            }
        }
        /**
         * 阻止新全局增加（即函数内未用var的隐性全局变量创建）,会直接隐性创建失败,提示变量未定义
         */
        scope.sealObject()
        return scope
    }

    /**
     * 仅做「下载 + 拼装」，把 jsLib（普通 JS 文本或 JSON 对象，含远程 URL 片段）
     * 解析成一段可整体 eval 的 JS 字符串，不构建共享作用域、不进缓存。
     * 供需要在「每次求值的绑定作用域」里现域执行 jsLib 的场景使用——
     * 这样 jsLib 内部函数被调用时 `this` 即当前绑定作用域（含 java/source/book/chapter 等），
     * 与 mozilla Rhino 的“调用作用域作为 this”语义一致。
     */
    fun getJsLibString(jsLib: String?): String? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        if (!jsLib.isJsonObject()) {
            return jsLib
        }
        val jsMap: Map<String, String> = GSON.fromJson(
            jsLib,
            TypeToken.getParameterized(
                Map::class.java,
                String::class.java,
                String::class.java
            ).type
        )
        return jsMap.values
            .mapNotNull { value ->
                if (value.isAbsUrl()) {
                    val fileName = MD5Utils.md5Encode(value)
                    aCache.getAsString(fileName) ?: runBlocking {
                        okHttpClient.newCallStrResponse {
                            url(value)
                        }.body
                    }?.also { aCache.put(fileName, it) }
                } else {
                    value.takeIf { it.isNotBlank() }
                }
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        if (jsLib.isJsonObject()) {
            val jsMap: Map<String, String> = GSON.fromJson(
                jsLib,
                TypeToken.getParameterized(
                    Map::class.java,
                    String::class.java,
                    String::class.java
                ).type
            )
            jsMap.values.forEach { value ->
                if (value.isAbsUrl()) {
                    val fileName = MD5Utils.md5Encode(value)
                    aCache.remove(fileName)
                }
            }
        }
        val key = MD5Utils.md5Encode(jsLib)
        scopeMap.remove(key)
    }

}