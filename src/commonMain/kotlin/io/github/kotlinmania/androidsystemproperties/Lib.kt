// port-lint: source lib.rs
package io.github.kotlinmania.androidsystemproperties

/**
 * A thin Kotlin wrapper for Android system properties.
 *
 * The upstream Rust crate dynamically loads Android libc symbols so one binary
 * can work across old and new Android property APIs. This Kotlin port exposes
 * the same small API surface and lets platform actuals decide how properties
 * are read.
 */
class AndroidSystemProperties private constructor() {
    /** Retrieve a system property. Returns `null` if the operation fails. */
    fun get(name: String): String? =
        if (name.indexOf('\u0000') >= 0) null else androidSystemProperty(name)

    /** Retrieve a system property using a NUL-terminated C string key. */
    fun getFromCString(cname: ByteArray): String? {
        val nul = cname.indexOf(0.toByte())
        if (nul < 0 || cname.drop(nul + 1).any { it != 0.toByte() }) {
            return null
        }
        return get(cname.decodeToString(endIndex = nul))
    }

    companion object {
        /** Create an entry point for accessing Android properties. */
        fun new(): AndroidSystemProperties = AndroidSystemProperties()
    }
}

internal expect fun androidSystemProperty(name: String): String?
