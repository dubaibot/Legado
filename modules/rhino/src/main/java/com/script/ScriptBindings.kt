package com.script

import com.script.rhino.RhinoScriptEngine
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeObject
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.TopLevel

/**
 * 脚本求值作用域:根标准对象的 isolate(对齐上游 TopLevel.createIsolate 范式)。
 *
 * 注入的绑定(java/source 等)写入本实例(VarScope 槽位),供裸变量名解析。
 * globalThis 为自定义 [BindingsGlobalThis],重写 get:先查自身槽位,未命中时
 * 回落到本实例 VarScope.get。脚本顶层 this 解析到 globalThis,`this.xxx`
 * 经回落取到实例槽位绑定;裸变量名经 VarScope.get 直接命中实例槽位。
 * 两套查找路径命中同一绑定,不污染 globalThis 槽位。
 * 各实例的 globalThis 相互独立,写入互不可见;
 * 父层定义(jsLib/CryptoJS 共享库或标准对象)经 globalThis 原型链回落可读。
 */
class ScriptBindings : TopLevel(createIsolateGlobal()) {

    init {
        (globalThis as BindingsGlobalThis).bindings = this
        copyBuiltins(root, false)
        copyAssociatedValue(root)
    }

    /**
     * 把本作用域挂到 [parent] 之下:父层定义(jsLib/CryptoJS 共享库或
     * 全新标准对象)对本作用域可读,写入仍落本作用域。
     */
    fun chainTo(parent: TopLevel) {
        globalThis.prototype = parent.globalThis
        copyBuiltins(parent, false)
        copyAssociatedValue(parent)
    }

    operator fun set(key: String, value: Any?) {
        Context.enter()
        try {
            val jsValue = Context.javaToJS(value, this)
            put(key, this, jsValue)
        } finally {
            Context.exit()
        }
    }

    operator fun set(index: Int, value: Any?) {
        Context.enter()
        try {
            val jsValue = Context.javaToJS(value, this)
            put(index, this, jsValue)
        } finally {
            Context.exit()
        }
    }

    fun put(key: String, value: Any?) {
        set(key, value)
    }

    companion object {

        private val root: TopLevel by lazy {
            RhinoScriptEngine // 先装配全局 ContextFactory 再进 Context
            val cx = Context.enter()
            try {
                cx.initStandardObjects()
            } finally {
                Context.exit()
            }
        }

        private fun createIsolateGlobal(): ScriptableObject {
            val global = BindingsGlobalThis()
            global.prototype = root.globalThis
            global.parentScope = null
            global.put("globalThis", global, global)
            global.setAttributes("globalThis", ScriptableObject.DONTENUM)
            global.put("global", global, global)
            global.setAttributes("global", ScriptableObject.DONTENUM)
            return global
        }
    }

}

/**
 * 自定义 globalThis:自身槽位未命中时回落到 [ScriptBindings] 实例的
 * VarScope.get,使 `this.xxx` 与裸变量名解析命中同一绑定。
 */
private class BindingsGlobalThis : NativeObject() {

    var bindings: ScriptBindings? = null

    override fun get(name: String, start: Scriptable): Any? {
        val result = super.get(name, start)
        if (result !== Scriptable.NOT_FOUND) return result
        val b = bindings
        return b?.get(name, b) ?: Scriptable.NOT_FOUND
    }
}
