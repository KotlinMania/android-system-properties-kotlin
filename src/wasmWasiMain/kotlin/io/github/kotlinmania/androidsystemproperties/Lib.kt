// port-lint: ignore — Android system-properties stub for the Wasm-WASI target.
package io.github.kotlinmania.androidsystemproperties

/**
 * Wasm-WASI hosts have no Android system-properties subsystem. Upstream
 * `android_system_properties` is a `#[cfg(target_os = "android")]` crate, so
 * the Kotlin port returns `null` everywhere outside Android.
 */
internal actual fun androidSystemProperty(name: String): String? = null
