First release of `android-system-properties-kotlin` to Maven Central as `io.github.kotlinmania:android-system-properties-kotlin:0.1.0`.

Kotlin Multiplatform port of [nical/android_system_properties](https://crates.io/crates/android_system_properties) — minimal Android system properties wrapper.

Target matrix mirrors the http-kotlin golden template: jvm, android (KMP library), all androidNative variants, all Apple variants (iOS/macOS/tvOS/watchOS), linuxX64/Arm64, mingwX64, js (browser+node), wasmJs (browser+node), wasmWasi (node), plus the AndroidSystemProperties XCFramework + swiftExport.

Triggers `publish.yml` → `publishAndReleaseToMavenCentral`. Unblocks `iana-time-zone-kotlin`'s androidMain dep, the original driver for this work.
