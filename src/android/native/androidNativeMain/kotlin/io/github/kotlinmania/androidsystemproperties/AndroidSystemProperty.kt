@file:OptIn(ExperimentalForeignApi::class)

package io.github.kotlinmania.androidsystemproperties

import io.github.kotlinmania.libc.unix.dlclose
import io.github.kotlinmania.libc.unix.dlopen
import io.github.kotlinmania.libc.unix.dlsym
import io.github.kotlinmania.libc.unix.linuxlike.android.PROP_VALUE_MAX
import io.github.kotlinmania.libc.unix.linuxlike.android.RTLD_NOLOAD
import io.github.kotlinmania.libc.unix.linuxlike.android.b64.RTLD_NOW
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString

private typealias PropertyCallback = CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<ByteVar>?, UInt) -> Unit>>
private typealias PropertyFind = CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>
private typealias PropertyRead = CFunction<(COpaquePointer?, PropertyCallback?, COpaquePointer?) -> Unit>
private typealias PropertyGet = CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?) -> Int>

private class PropertyValue(
    var value: String? = null,
)

private val propertyCallback: PropertyCallback =
    staticCFunction { cookie, _, value, _ ->
        cookie?.asStableRef<PropertyValue>()?.get()?.value = value?.toKString()
    }

internal actual fun androidSystemProperty(name: String): String? = readAndroidSystemProperty(name)

internal fun readAndroidSystemProperty(name: String, useCallback: Boolean = true): String? {
    val handle = dlopen("libc.so", RTLD_NOW or RTLD_NOLOAD) ?: return null
    try {
        val find = dlsym(handle, "__system_property_find")?.value?.toCPointer<PropertyFind>()
        val read = dlsym(handle, "__system_property_read_callback")?.value?.toCPointer<PropertyRead>()
        return memScoped {
            val key = name.cstr.ptr
            if (useCallback && find != null && read != null) {
                val info = find(key) ?: return@memScoped null
                val result = StableRef.create(PropertyValue())
                try {
                    read(info, propertyCallback, result.asCPointer())
                    result.get().value
                } finally {
                    result.dispose()
                }
            } else {
                val get =
                    dlsym(handle, "__system_property_get")?.value?.toCPointer<PropertyGet>()
                        ?: return@memScoped null
                val buffer = allocArray<ByteVar>(PROP_VALUE_MAX)
                val length = get(key, buffer)
                if (length <= 0 || length >= PROP_VALUE_MAX) return@memScoped null
                runCatching { buffer.readBytes(length).decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            }
        }
    } finally {
        dlclose(handle)
    }
}
