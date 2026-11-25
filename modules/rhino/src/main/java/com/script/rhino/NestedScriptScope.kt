package com.script.rhino

import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeCall
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
 *
 * 另外必须一并清空 cx.currentActivationCall：规则脚本函数执行到一半时,
 * 若其内部调用宿主方法再次执行 jsLib(嵌套顶层调用),外层函数仍处于激活状态,
 * currentActivationCall 非空。内层 doTopCall 结束时引擎断言该字段必须为空
 * (ScriptRuntime.doTopCall 的 finally),否则抛 AssertionError(debug 包开启断言时)。
 * 进入前保存、清空,退出后恢复,保留外层函数的闭包/激活链语义。
 */
internal class NestedScriptScope private constructor(
    private val cx: Context,
    private val previousTopCallScope: TopLevel?,
    private val previousActivationCall: NativeCall?,
) : AutoCloseable {

    override fun close() {
        currentActivationCallField.set(cx, previousActivationCall)
        topCallScopeField.set(cx, previousTopCallScope)
    }

    companion object {
        private val topCallScopeField: Field =
            Context::class.java.getDeclaredField("topCallScope").apply { isAccessible = true }

        private val currentActivationCallField: Field =
            Context::class.java.getDeclaredField("currentActivationCall").apply { isAccessible = true }

        fun enter(cx: Context, scope: VarScope): NestedScriptScope {
            val previousTopCallScope = topCallScopeField.get(cx) as? TopLevel
            val previousActivationCall = currentActivationCallField.get(cx) as? NativeCall
            topCallScopeField.set(cx, null)
            currentActivationCallField.set(cx, null)
            return NestedScriptScope(cx, previousTopCallScope, previousActivationCall)
        }
    }
}
