/*
 * Decompiled with CFR 0.152.
 */
package com.script

import org.htmlunit.corejs.javascript.VarScope
import java.io.Reader
import kotlin.coroutines.CoroutineContext

interface ScriptEngine {
    var context: ScriptContext

    fun createBindings(): Bindings?

    @Throws(ScriptException::class)
    fun eval(reader: Reader, scope: VarScope): Any?

    @Throws(ScriptException::class)
    fun eval(reader: Reader, scope: VarScope, coroutineContext: CoroutineContext?): Any?

    @Throws(ScriptException::class)
    suspend fun evalSuspend(reader: Reader, scope: VarScope): Any?

    @Throws(ScriptException::class)
    fun eval(script: String, scope: VarScope): Any?

    @Throws(ScriptException::class)
    suspend fun evalSuspend(script: String, scope: VarScope): Any?

    @Throws(ScriptException::class)
    fun eval(script: String, scope: VarScope, coroutineContext: CoroutineContext?): Any?

    @Throws(ScriptException::class)
    fun eval(reader: Reader): Any?

    @Throws(ScriptException::class)
    fun eval(reader: Reader, bindings: Bindings): Any?

    @Throws(ScriptException::class)
    fun eval(reader: Reader, context: ScriptContext): Any?

    @Throws(ScriptException::class)
    fun eval(script: String): Any?

    @Throws(ScriptException::class)
    fun eval(script: String, bindings: Bindings): Any?

    @Throws(ScriptException::class)
    fun eval(script: String, bindings: ScriptBindings): Any?

    @Throws(ScriptException::class)
    fun eval(script: String, context: ScriptContext): Any?

    fun getRuntimeScope(bindings: ScriptBindings): ScriptBindings

    fun getRuntimeScope(context: ScriptContext): ScriptBindings

    fun getScriptContext(bindings: Bindings): ScriptContext

    operator fun get(key: String): Any?

    fun getBindings(scope: Int): Bindings?

    fun put(key: String, value: Any?)

    fun setBindings(bindings: Bindings?, scope: Int)

    companion object {
        const val FILENAME = "javax.script.filename"
    }
}
