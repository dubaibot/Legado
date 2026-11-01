package com.script.rhino

import org.htmlunit.corejs.javascript.Context

object VMBridgeReflect {

    val contextLocal: ThreadLocal<Any> by lazy {
        @Suppress("UNCHECKED_CAST")
        Context::class.java.getDeclaredField("currentContext").apply {
            isAccessible = true
        }.get(null) as ThreadLocal<Any>
    }

}
