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
}
