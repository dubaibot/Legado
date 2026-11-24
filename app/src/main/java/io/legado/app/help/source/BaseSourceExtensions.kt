package io.legado.app.help.source

import com.script.rhino.RhinoClassShutter
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.SharedJsScope
import org.htmlunit.corejs.javascript.TopLevel
import org.htmlunit.corejs.javascript.VarScope
import kotlin.coroutines.CoroutineContext

/**
 * 取书源共享 jsLib 作用域。
 *
 * 书源（BookSource）额外带上「类访问策略 + 源级命名空间」，
 * 避免同一份 jsLib 在不同书源之间共享同一个可写全局对象。
 */
fun BaseSource.getShareScope(coroutineContext: CoroutineContext? = null): TopLevel? {
    val bookSource = this as? BookSource
    return SharedJsScope.getScope(
        jsLib = jsLib,
        coroutineContext = coroutineContext,
        bookSourceClassPolicy = bookSource != null,
        bookSourceLabel = bookSource?.bookSourceName,
        scopeNamespace = bookSource?.let {
            BookSourceStorageScope.namespace(it.bookSourceUrl)
        }
    )
}

/**
 * 在「书源类访问策略」下执行脚本片段。
 *
 * 声明式规则与单文件 JS 书源都必须经此执行宿主 Java 访问，保证隔离级别一致。
 */
fun <T> BaseSource?.withBookSourceClassPolicy(block: () -> T): T {
    val bookSource = this as? BookSource
    return RhinoClassShutter.withBookSourceClassPolicy(
        enabled = bookSource != null,
        sourceLabel = bookSource?.bookSourceName,
        block = block
    )
}

/**
 * 把当前书源的 jsLib 直接执行进 [scope]（通常是本次求值的绑定作用域）。
 *
 * 绑定链问题已按 Max 方案在 `SharedJsScope` 内解决（命名空间隔离 + `chainTo` 链式绑定），
 * jsLib 经 `getShareScope` 编译注入后即可在调用作用域正确取到 java/source 等绑定。
 * 保留本函数仅用于兼容其它调用者（`BaseSource.kt`、`AnalyzeUrl.kt` 等）。
 */
fun BaseSource.evalJsLibInto(scope: VarScope, coroutineContext: CoroutineContext? = null) {
    SharedJsScope.getJsLibString(jsLib)?.let {
        RhinoScriptEngine.eval(it, scope, coroutineContext)
    }
}

fun BaseSource.getSourceType(): Int {
    return when (this) {
        is BookSource -> SourceType.book
        is RssSource -> SourceType.rss
        else -> error("unknown source type: ${this::class.simpleName}.")
    }
}
