package io.github.kotlinmania.androidsystemproperties

/**
 * Linux has no Android system-properties subsystem. Upstream
 * `android_system_properties` is a `#[cfg(target_os = "android")]` crate, so
 * the Kotlin port returns `null` everywhere outside Android.
 */
internal actual fun androidSystemProperty(name: String): String? = null
