package io.github.kotlinmania.androidsystemproperties

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

class AndroidPropertyInstrumentationRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val behavior = AndroidPropertyDeviceTest()
        val input = AndroidSystemPropertiesTest()
        val cases =
            listOf(
                "readsRealAndroidBuildProperties" to behavior::readsRealAndroidBuildProperties,
                "readsValidCStringAndMissingProperty" to behavior::readsValidCStringAndMissingProperty,
                "rejectsInteriorNulPropertyNames" to input::rejectsInteriorNulPropertyNames,
                "rejectsNonTerminatedCStringNames" to input::rejectsNonTerminatedCStringNames,
                "rejectsTrailingBytesAfterCStringTerminator" to input::rejectsTrailingBytesAfterCStringTerminator,
                "rejectsMalformedUtf8CStringNames" to input::rejectsMalformedUtf8CStringNames,
                "rejectsEmptyCStringBuffer" to input::rejectsEmptyCStringBuffer,
            )
        var failed = 0
        cases.forEachIndexed { index, (name, execute) ->
            val status =
                Bundle().apply {
                    putString("id", "InstrumentationTestRunner")
                    putString("class", AndroidPropertyDeviceTest::class.qualifiedName)
                    putString("test", name)
                    putInt("numtests", cases.size)
                    putInt("current", index + 1)
                }
            sendStatus(1, status)
            try {
                execute()
                sendStatus(0, status)
            } catch (error: Throwable) {
                failed++
                status.putString("stack", error.stackTraceToString())
                sendStatus(-2, status)
            }
        }
        val result =
            Bundle().apply {
                putString("stream", "Android property tests: ${cases.size - failed} passed, $failed failed\n")
            }
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
