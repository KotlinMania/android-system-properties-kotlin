@file:OptIn(ExperimentalForeignApi::class)

package io.github.kotlinmania.androidsystemproperties

import io.github.kotlinmania.libc.unix.dlclose
import io.github.kotlinmania.libc.unix.dlopen
import io.github.kotlinmania.libc.unix.dlsym
import io.github.kotlinmania.libc.unix.linuxlike.android.b64.RTLD_NOW
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidNativePropertyTest {
    @Test
    fun readsRealAndroidProperty() {
        val sdk = assertNotNull(AndroidSystemProperties.new().get("ro.build.version.sdk"))
        assertTrue(assertNotNull(sdk.toIntOrNull()) >= 24)
    }

    @Test
    fun legacyAndAvailableCallbackReadTheSameProperty() {
        val name = "ro.product.model"
        val legacy = assertNotNull(readAndroidSystemProperty(name, useCallback = false))
        assertTrue(legacy.isNotEmpty())
        assertEquals(legacy, readAndroidSystemProperty(name))
        assertEquals(legacy, AndroidSystemProperties.new().getFromCString((name + "\u0000").encodeToByteArray()))
    }

    @Test
    fun missingPropertiesReturnNull() {
        val name = "kotlinmania.androidsystemproperties.missing"
        assertNull(readAndroidSystemProperty(name))
        assertNull(readAndroidSystemProperty(name, useCallback = false))
    }

    @Test
    fun dynamicallyResolvesRealLibcSymbols() {
        val handle = assertNotNull(dlopen("libc.so", RTLD_NOW))
        try {
            assertNotNull(dlsym(handle, "__system_property_get"))
            assertNull(dlsym(handle, "__kotlinmania_missing_property_symbol"))
        } finally {
            assertEquals(0, dlclose(handle))
        }
    }
}
