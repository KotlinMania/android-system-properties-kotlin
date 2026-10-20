package io.github.kotlinmania.androidsystemproperties

import kotlin.test.Test
import kotlin.test.assertNull

class AndroidSystemPropertiesTest {
    @Test
    fun rejectsInteriorNulPropertyNames() {
        assertNull(AndroidSystemProperties.new().get("ro.product\u0000model"))
    }

    @Test
    fun rejectsNonTerminatedCStringNames() {
        assertNull(AndroidSystemProperties.new().getFromCString(byteArrayOf(114, 111)))
    }

    @Test
    fun rejectsTrailingBytesAfterCStringTerminator() {
        assertNull(AndroidSystemProperties.new().getFromCString(byteArrayOf(114, 0, 0)))
        assertNull(AndroidSystemProperties.new().getFromCString(byteArrayOf(114, 0, 111)))
    }

    @Test
    fun rejectsMalformedUtf8CStringNames() {
        assertNull(AndroidSystemProperties.new().getFromCString(byteArrayOf(0xc3.toByte(), 0)))
    }

    @Test
    fun rejectsEmptyCStringBuffer() {
        assertNull(AndroidSystemProperties.new().getFromCString(byteArrayOf()))
    }
}
