package com.script

import com.script.rhino.RhinoScriptEngine
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeObject
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.TopLevel

/**
 * 脚本求值作用域:根标准对象的 isolate(对齐上游 TopLevel.createIsolate 范式)。
 *
 * 注入的绑定(java/source 等)同时写入本实例(VarScope 槽位,供裸变量名解析)
 * 与 globalThis(ScriptableObject 槽位,供 this.xxx 属性访问解析)。
 * 脚本顶层 this 解析到 globalThis,裸变量名经 VarScope.get 解析到实例,
 * 两套查找路径均能命中绑定。各实例的 globalThis 相互独立,写入互不可见;
 * 父层定义(jsLib/CryptoJS 共享库或标准对象)经 globalThis 原型链回落可读。
 *
 * 不自定义 globalThis.get 回落:新版 Rhino TopLevel.get 已内置
 * getProperty(globalThis) 回落,自定义回落会与该内置回落形成原型链自环,
 * 导致 StackOverflowError。
 */
class ScriptBindings : TopLevel(newIsolateGlobal()) {

    init {
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
            globalThis.put(key, globalThis, jsValue)
        } finally {
            Context.exit()
        }
    }

    operator fun set(index: Int, value: Any?) {
        Context.enter()
        try {
            val jsValue = Context.javaToJS(value, this)
            put(index, this, jsValue)
            globalThis.put(index, globalThis, jsValue)
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

        private fun newIsolateGlobal(): ScriptableObject {
            val global = NativeObject()
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
