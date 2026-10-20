# android-system-properties-kotlin

A Kotlin library for reading Android system properties, based on `android_system_properties`.

```kotlin
import io.github.kotlinmania.androidsystemproperties.AndroidSystemProperties

val properties = AndroidSystemProperties.new()
val timezone = properties.get("persist.sys.timezone")
val model = properties.getFromCString("ro.product.model\u0000".encodeToByteArray())
```

The library publishes Android KMP (AAR), Android Native ARM64, and Android Native x64 variants. Android minSdk is 24. `commonMain` exposes the API; Android runtime implementations live in `androidMain` and `androidNativeMain`. There are no standalone JVM, Linux, web, Wasm, Apple, or Windows variants.

Android Native dynamically resolves the property functions from Android libc. It uses the callback API when available and the legacy getter otherwise, and closes the library handle after each lookup. The Android AAR uses runtime reflection; property access returns `null` when the Android runtime denies access or a property cannot be read. Native callback reads preserve an existing empty value; Android reflection and the legacy getter cannot distinguish empty values from missing properties.

Property names containing NUL are rejected. `getFromCString` requires valid UTF-8 with exactly one trailing NUL byte. The API reads properties; it does not list or set them.

## Verification

Host tests cover input validation. Instrumentation tests compare real property values with Android `Build` values. Android Native tests execute real dynamic lookup, callback selection, legacy reads, missing-property handling, and C-string reads on both supported ABIs.

Use connected Android devices or isolated test emulators of the matching ABIs. Supply each native device serial explicitly:

```shell
./gradlew build test \
  -PandroidNativeArm64.deviceSerial=ARM64_DEVICE_SERIAL \
  -PandroidNativeX64.deviceSerial=X64_DEVICE_SERIAL \
  --no-daemon --max-workers=2 --no-configuration-cache
```

`test` requires Android instrumentation execution and both native binaries to run. Missing devices or native serial properties fail the gate. `hostTests` runs only the common input-validation suite on the host. Native execution logs are written under `build/test-results/`.
