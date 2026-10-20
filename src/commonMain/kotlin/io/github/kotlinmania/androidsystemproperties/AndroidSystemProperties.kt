package io.github.kotlinmania.androidsystemproperties

/** An entry point for reading Android system properties. */
class AndroidSystemProperties private constructor() {
    /** Returns the property value, or null when the property cannot be read. */
    fun get(name: String): String? =
        if ('\u0000' in name) null else androidSystemProperty(name)

    /** Reads a property whose UTF-8 name has exactly one trailing NUL byte. */
    fun getFromCString(cname: ByteArray): String? {
        if (cname.isEmpty() || cname.indexOf(0.toByte()) != cname.lastIndex) return null
        val name = runCatching { cname.decodeToString(endIndex = cname.lastIndex, throwOnInvalidSequence = true) }.getOrNull()
        return name?.let(::get)
    }

    companion object {
        /** Creates an entry point for accessing Android properties. */
        fun new(): AndroidSystemProperties = AndroidSystemProperties()
    }
}

internal expect fun androidSystemProperty(name: String): String?
