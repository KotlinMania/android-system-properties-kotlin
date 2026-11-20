# android-system-properties-kotlin

A Kotlin library for reading Android system properties, based on `android_system_properties`.

## Installation

Use Maven Central and add the dependency to `commonMain`:

```kotlin
repositories { mavenCentral() }

kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.kotlinmania:android-system-properties-kotlin:0.2.1")
    }
}
```

## Usage

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

Run the canonical build and verification tasks:

```shell
./gradlew build
./gradlew test
```

`build` compiles Android AAR, `androidNativeArm64`, and `androidNativeX64` targets alongside `ktlintCheck`, `detekt`, and publication checks. `test` runs `hostTests` (`testAndroidHostTest`), `verifyAndroidDeviceTestResults` (`connectedAndroidDeviceTest` against a connected Android device/emulator), and host-matching Android Native runtime tests (`validateAndroidNativeArm64Runtime` / `validateAndroidNativeX64Runtime`). Native execution logs are written under `build/reports/local-native-tests/`.
