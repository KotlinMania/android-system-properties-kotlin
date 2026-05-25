package io.github.kotlinmania.androidsystemproperties

/**
 * Apple platforms (iOS, macOS, tvOS, watchOS) have no concept of Android
 * system properties. Upstream Rust `android_system_properties` only compiles
 * on Android; the analogous Kotlin port keeps the public type buildable on
 * every workspace target but reports `null` whenever the host is not
 * Android.
 */
internal actual fun androidSystemProperty(name: String): String? = null
