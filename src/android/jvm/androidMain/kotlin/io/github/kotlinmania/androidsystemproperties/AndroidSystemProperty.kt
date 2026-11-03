package io.github.kotlinmania.androidsystemproperties

/** Reads through the Android runtime when access to its property API is permitted. */
internal actual fun androidSystemProperty(name: String): String? =
    runCatching {
        val method = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (method.invoke(null, name) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()
