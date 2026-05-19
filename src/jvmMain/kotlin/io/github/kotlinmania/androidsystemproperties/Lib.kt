// port-lint: source lib.rs
package io.github.kotlinmania.androidsystemproperties

internal actual fun androidSystemProperty(name: String): String? =
    runCatching {
        val method = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (method.invoke(null, name) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()
