package io.github.kotlinmania.androidsystemproperties

import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidPropertyDeviceTest {
    @Test
    fun readsRealAndroidBuildProperties() {
        val properties = AndroidSystemProperties.new()
        assertEquals(Build.VERSION.SDK_INT.toString(), properties.get("ro.build.version.sdk"))
        assertEquals(Build.MODEL, properties.get("ro.product.model"))
    }

    @Test
    fun readsValidCStringAndMissingProperty() {
        val properties = AndroidSystemProperties.new()
        assertEquals(Build.MODEL, properties.getFromCString("ro.product.model\u0000".encodeToByteArray()))
        assertNull(properties.get("kotlinmania.androidsystemproperties.missing"))
    }
}
