package com.script

import org.htmlunit.corejs.javascript.NativeObject
import org.htmlunit.corejs.javascript.Scriptable

/**
 * 动态绑定层:插在「共享 jsLib 作用域 globalThis」与它的父层之间的一层只读原型。
 *
 * 背景:书源 jsLib 被编译进共享作用域并 `preventExtensions`。规则脚本裸调用 jsLib
 * 中的函数(如 `S(...)`)时,函数内部的 `this` 是共享作用域 realm 的 globalThis,
 * 上面没有本次注入的 `java`/`source` 等绑定,于是沿原型链回落到标准对象的
 * `[JavaPackage java]`,表现为 `getString is not a function, it is object`。
 *
 * 本层在共享层找不到某个名字时,按**当前线程**取回本次求值注入的绑定(见
 * [ScriptBindings.currentEval]),命中即返回;未命中返回 `NOT_FOUND`,由引擎继续
 * 沿原型链(本层 prototype 指向原父层)查找标准对象。
 *
 * 只读:不覆写 put,亦不写入共享对象,天然线程隔离(线程本地),不污染共享层。
 */
class DynamicBindingLayer(
    parent: Scriptable?,
    private val owner: ScriptBindings,
) : NativeObject() {

    init {
        prototype = parent
    }

    override fun getClassName(): String = "DynamicBindingLayer"

    override fun get(name: String, start: Scriptable): Any? {
        val current = ScriptBindings.currentEval()
        // 跳过 owner 自身(jsLib 加载阶段 current 即共享作用域),避免无谓查询
        if (current != null && current !== owner) {
            val value = current.ownBinding(name)
            if (value !== Scriptable.NOT_FOUND) return value
        }
        return super.get(name, start)
    }

    override fun has(name: String, start: Scriptable): Boolean {
        val current = ScriptBindings.currentEval()
        if (current != null && current !== owner) {
            if (current.ownBinding(name) !== Scriptable.NOT_FOUND) return true
        }
        return super.has(name, start)
    }

    /**
     * 只读层:脚本内 `this.xxx = ...` 时,引擎用 getBase 找到本层作为写入 base
     * (因为 has 命中),这里直接丢弃,避免把赋值写到动态层自身污染后续求值。
     */
    override fun put(name: String, start: Scriptable, value: Any?) {
        // intentionally read-only
    }
}
