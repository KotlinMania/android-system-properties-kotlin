// port-lint: source lib.rs
@file:OptIn(ExperimentalForeignApi::class)

package io.github.kotlinmania.androidsystemproperties

import androidsystemproperties.cinterop.kotlinmaniaSystemPropertyFind
import androidsystemproperties.cinterop.kotlinmaniaSystemPropertyGet
import androidsystemproperties.cinterop.kotlinmaniaSystemPropertyReadCallback
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString

private const val PROPERTY_VALUE_MAX: Int = 92

private typealias Callback =
    CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<ByteVar>?, UInt) -> Unit>>
private typealias SystemPropertyGetFn = (String, CPointer<ByteVar>) -> Int
private typealias SystemPropertyFindFn = (String) -> COpaquePointer?
private typealias SystemPropertyReadCallbackFn = (COpaquePointer?, Callback, COpaquePointer?) -> Unit

private val systemPropertyFindFn: SystemPropertyFindFn = { name ->
    kotlinmaniaSystemPropertyFind(name)?.reinterpret()
}
private val systemPropertyGetFn: SystemPropertyGetFn = ::kotlinmaniaSystemPropertyGet
private val systemPropertyReadCallbackFn: SystemPropertyReadCallbackFn = { info, callback, payload ->
    kotlinmaniaSystemPropertyReadCallback(info?.reinterpret(), callback, payload)
}

private class PropertyPayload(
    var value: String? = null,
)

private fun propertyCallback(
    payload: COpaquePointer?,
    name: CPointer<ByteVar>?,
    value: CPointer<ByteVar>?,
    serial: UInt,
) {
    if (name == null && serial == 0u && value == null) {
        return
    }
    payload?.asStableRef<PropertyPayload>()?.get()?.value = value?.toKString()
}

internal actual fun androidSystemProperty(name: String): String? {
    val info = systemPropertyFindFn(name)
    if (info != null) {
        val payload = StableRef.create(PropertyPayload())
        try {
            systemPropertyReadCallbackFn(info, staticCFunction(::propertyCallback), payload.asCPointer())
            payload.get().value?.let { return it }
        } finally {
            payload.dispose()
        }
    }

    memScoped {
        val buffer = allocArray<ByteVar>(PROPERTY_VALUE_MAX)
        val len = systemPropertyGetFn(name, buffer)
        return if (len > 0) {
            buffer.toKString()
        } else {
            null
        }
    }
}
