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
 * 关键点：htmlunit-core-js 与 mozilla Rhino 的 `this` 语义不同——
 * mozilla 中裸调用函数时 `this` 为「调用作用域」，而 corejs 中为「定义作用域」。
 * 若把 jsLib 放进独立的共享作用域，jsLib 内部函数里的 `this.java/this.source` 会取到
 * 不含绑定的共享作用域而抛错。把 jsLib 就地注入本次绑定作用域后，
 * jsLib 函数被调用时 `this` 即为含 java/source/book/chapter 等的绑定作用域，行为与 Max 对齐。
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
