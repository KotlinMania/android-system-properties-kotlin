// port-lint: source lib.rs
package io.github.kotlinmania.androidsystemproperties

/**
 * The Android KMP library variant runs on the Android JVM, where
 * `android.os.SystemProperties` is available on the runtime classpath but is
 * a hidden / non-public API. Reflection mirrors what the upstream Rust crate
 * does at the libc layer: dynamically resolve the lookup symbol at call
 * time so the binary keeps working across old and new Android property API
 * generations and against host environments where the class is missing.
 */
internal actual fun androidSystemProperty(name: String): String? =
    runCatching {
        val method = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (method.invoke(null, name) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()
