package com.script.rhino

import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.TopLevel
import org.htmlunit.corejs.javascript.VarScope
import java.lang.reflect.Field

/**
 * 与 htmlunit-core-js 新版 NestedScriptScope 等效的本地实现。
 *
 * 新版 Rhino 分离了 TopLevel 与 globalThis。脚本执行时若已有活跃顶层调用
 * (cx.topCallScope != null)，JSScript.exec 会跳过 doTopCall，传入的 thisObj
 * 被忽略；executeScriptWithContinuations 更会直接抛出 IllegalStateException。
 *
 * 本类临时清空 cx.topCallScope，使 doTopCall 能以正确的 thisObj 运行，
 * close 时恢复原值，保证嵌套脚本执行的 this 语义正确。
 */
internal class NestedScriptScope private constructor(
    private val cx: Context,
    private val previousTopCallScope: TopLevel?,
) : AutoCloseable {

    override fun close() {
        topCallScopeField.set(cx, previousTopCallScope)
    }

    companion object {
        private val topCallScopeField: Field =
            Context::class.java.getDeclaredField("topCallScope").apply { isAccessible = true }

        fun enter(cx: Context, scope: VarScope): NestedScriptScope {
            val previous = topCallScopeField.get(cx) as? TopLevel
            topCallScopeField.set(cx, null)
            return NestedScriptScope(cx, previous)
        }
    }
}
