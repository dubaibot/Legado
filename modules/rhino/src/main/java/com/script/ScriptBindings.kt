package com.script

import android.util.Log
import com.script.rhino.RhinoScriptEngine
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.NativeObject
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.TopLevel
import org.htmlunit.corejs.javascript.VarScope

/**
 * 脚本求值作用域:根标准对象的 isolate(对齐上游 TopLevel.createIsolate 范式)。
 *
 * 注入的绑定(java/source 等)同时写入本实例(VarScope 槽位,供裸变量名解析)
 * 与 globalThis(ScriptableObject 槽位,供 this.xxx 属性访问解析)。
 * 脚本顶层 this 解析到 globalThis,裸变量名经 VarScope.get 解析到实例,
 * 两套查找路径均能命中绑定。各实例的 globalThis 相互独立,写入互不可见;
 * 父层定义(jsLib/CryptoJS 共享库或标准对象)经 globalThis 原型链回落可读。
 *
 * SharedJsScope 在 jsLib 求值后对 globalThis 调用 preventExtensions(),
 * 此后 globalThis.put 对新属性静默失败(不抛异常,不写入)。宿主注入的绑定
 * 必须始终落到 globalThis 才能保证规则脚本 `this.xxx` 解析正确,故 [set]
 * 在 globalThis 不可扩展时临时恢复可扩展性完成写入后再重新锁定。
 *
 * 不自定义 globalThis.get 回落:新版 Rhino TopLevel.get 已内置
 * getProperty(globalThis) 回落,自定义回落会与该内置回落形成原型链自环,
 * 导致 StackOverflowError。
 */
class ScriptBindings : TopLevel(newIsolateGlobal()) {

    /**
     * 本次求值注入的命名绑定(键 → 已 jsToJava 包装后的值)。
     *
     * 供 [DynamicBindingLayer] 在「共享 jsLib 函数内部」按线程取回本次求值的绑定:
     * jsLib 函数被规则脚本裸调用时,其 `this` 是共享作用域的 globalThis,取不到本次
     * 注入的 java/source 等,需经原型链上的动态层回落到本表。
     */
    private val ownBindings = HashMap<String, Any?>()

    init {
        copyBuiltins(root, false)
        copyAssociatedValue(root)
    }

    /**
     * 把本作用域挂到 [parent] 之下:父层定义(jsLib/CryptoJS 共享库或
     * 全新标准对象)对本作用域可读,写入仍落本作用域。
     */
    fun chainTo(parent: TopLevel) {
        // 若父层是另一个 ScriptBindings(书源共享 jsLib 作用域),先确保它挂有动态绑定层:
        // 共享 jsLib 函数被裸调用时其内部 `this` 是该共享 globalThis,需要一层按线程
        // 回落到本次求值注入值(java/source 等),否则 `const {java}=this` 会取到
        // NativeJavaPackage。幂等且同步,首次规则求值链上时安装一次。
        if (parent is ScriptBindings && parent !== this) {
            ensureDynamicBindingLayer(parent)
        }
        globalThis.prototype = parent.globalThis
        copyBuiltins(parent, false)
        copyAssociatedValue(parent)
    }

    private fun ensureDynamicBindingLayer(shared: ScriptBindings) {
        val sharedGlobal = shared.globalThis
        synchronized(sharedGlobal) {
            if (sharedGlobal.getPrototype() is DynamicBindingLayer) {
                return
            }
            sharedGlobal.setPrototype(
                DynamicBindingLayer(sharedGlobal.getPrototype(), shared)
            )
        }
    }

    operator fun set(key: String, value: Any?) {
        Context.enter()
        try {
            // javaToJS 经 WrapFactory 链路可能对特定 Java 类型抛异常(如 ClassShutter/
            // WrapFactory 内部状态异常),单个绑定失败不能让整轮注入中断——否则后续
            // java/source 等绑定缺失,规则脚本 this.xxx 回落到 NativeJavaPackage。
            val jsValue = wrapValue(key, value)
            // 同步记录到自有绑定表:即便反射写 globalThis 失败,共享 jsLib 函数内部的
            // this.xxx 也能经 DynamicBindingLayer 取到本次注入值。
            ownBindings[key] = jsValue
            try {
                put(key, this, jsValue)
            } catch (e: Throwable) {
                Log.e(TAG, "DIAG_set put failed: key=$key value=${value?.javaClass?.name}", e)
            }
            try {
                writeGlobalThis(key, jsValue)
            } catch (e: Throwable) {
                Log.e(TAG, "DIAG_set globalThis failed: key=$key value=${value?.javaClass?.name}", e)
            }
            if (key == "java") {
                Log.d(TAG, "DIAG_set java injected: gthisHasJava=${globalThis.has("java", globalThis)}")
            }
        } finally {
            Context.exit()
        }
    }

    operator fun set(index: Int, value: Any?) {
        Context.enter()
        try {
            val jsValue = wrapValue(index.toString(), value)
            try {
                put(index, this, jsValue)
                writeGlobalThis(index, jsValue)
            } catch (e: Throwable) {
                Log.e(TAG, "DIAG_set[$index] failed: value=${value?.javaClass?.name}", e)
            }
        } finally {
            Context.exit()
        }
    }

    private fun wrapValue(key: String, value: Any?): Any? {
        if (value == null) return null
        return try {
            Context.javaToJS(value, this)
        } catch (e: Throwable) {
            Log.e(TAG, "DIAG_set javaToJS failed: key=$key value=${value.javaClass.name}", e)
            null
        }
    }

    private fun writeGlobalThis(key: String, jsValue: Any?) {
        val gt = globalThis
        if (gt.isExtensible) {
            gt.put(key, gt, jsValue)
            return
        }
        // globalThis 已被 preventExtensions 锁定。宿主注入绑定必须落到 globalThis,
        // 否则 this.xxx 回落到原型链的 NativeJavaPackage。临时恢复可扩展性写入后重新锁定。
        // 反射在 R8 混淆下可能不可用,任何失败都不得向上抛出(否则中断整轮绑定注入)。
        val unlocked = runCatching {
            isExtensibleField().setBoolean(gt, true)
            true
        }.getOrElse {
            Log.e(TAG, "DIAG_set unlock globalThis(reflect) failed", it)
            false
        }
        try {
            gt.put(key, gt, jsValue)
        } finally {
            if (unlocked) {
                runCatching { isExtensibleField().setBoolean(gt, false) }
            }
        }
        if (!gt.has(key, gt)) {
            Log.e(TAG, "DIAG_set globalThis miss after write: key=$key extensible=${gt.isExtensible}")
        }
    }

    private fun writeGlobalThis(index: Int, jsValue: Any?) {
        val gt = globalThis
        if (gt.isExtensible) {
            gt.put(index, gt, jsValue)
            return
        }
        val unlocked = runCatching {
            isExtensibleField().setBoolean(gt, true)
            true
        }.getOrElse {
            Log.e(TAG, "DIAG_set unlock globalThis(reflect) failed (index)", it)
            false
        }
        try {
            gt.put(index, gt, jsValue)
        } finally {
            if (unlocked) {
                runCatching { isExtensibleField().setBoolean(gt, false) }
            }
        }
    }



    fun put(key: String, value: Any?) {
        set(key, value)
    }

    /**
     * 取本次求值注入的命名绑定;不存在时返回 [Scriptable.NOT_FOUND]。
     * 只查自有表,不沿原型链,避免与 DynamicBindingLayer 形成递归。
     */
    internal fun ownBinding(name: String): Any? =
        if (ownBindings.containsKey(name)) ownBindings[name] else Scriptable.NOT_FOUND

    companion object {

        private const val TAG = "ScriptBindings"

        /**
         * 当前线程正在执行的求值绑定作用域。
         *
         * 规则脚本执行时指向 per-call [ScriptBindings];jsLib 编译/加载阶段指向共享
         * 作用域自身。DynamicBindingLayer 据此在共享层缺名时回落到本次求值注入值。
         */
        private val evalScopeLocal = ThreadLocal<ScriptBindings?>()

        internal fun currentEval(): ScriptBindings? = evalScopeLocal.get()

        /** 进入一次求值:记录 [scope](仅当其本身是 [ScriptBindings]),返回旧值用于恢复 */
        internal fun pushEval(scope: VarScope): ScriptBindings? {
            val prev = evalScopeLocal.get()
            if (scope is ScriptBindings) {
                evalScopeLocal.set(scope)
            }
            return prev
        }

        internal fun popEval(prev: ScriptBindings?) {
            evalScopeLocal.set(prev)
        }

        /** 在 [scope] 作为「当前求值绑定」的语义下执行 [block],结束后恢复 */
        internal inline fun <T> withCurrentEval(scope: VarScope, block: () -> T): T {
            val prev = pushEval(scope)
            try {
                return block()
            } finally {
                popEval(prev)
            }
        }

        @Volatile
        private var isExtensibleField: java.lang.reflect.Field? = null

        private fun isExtensibleField(): java.lang.reflect.Field {
            isExtensibleField?.let { return it }
            val field = try {
                ScriptableObject::class.java.getDeclaredField("isExtensible")
                    .apply { isAccessible = true }
            } catch (e: Throwable) {
                throw IllegalStateException("Reflect isExtensible unavailable", e)
            }
            isExtensibleField = field
            return field
        }

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
