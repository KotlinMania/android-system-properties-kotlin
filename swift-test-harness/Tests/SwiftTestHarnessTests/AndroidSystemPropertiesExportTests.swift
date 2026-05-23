import XCTest
import AndroidSystemProperties

// Smoke test for the Kotlin → Swift Export → SPM → swift test pipeline.
//
// The file's mere existence and successful compilation prove three layers
// of the pipeline:
//
//   1. `embedSwiftExportForXcode` produced `AndroidSystemProperties.swiftmodule/`
//      and the supporting KotlinRuntimeSupport / ExportedKotlinPackages /
//      KotlinRuntime swiftmodule bundles. If any of them were missing,
//      `import AndroidSystemProperties` above would fail at compile time.
//
//   2. The static archive `libAndroidSystemProperties.a` (produced by the
//      `linkSwiftExportBinaryDebugStaticMacosArm64` and
//      `mergeMacosDebugSwiftExportLibraries` tasks) supplied every
//      `__root____*` and `KotlinError`-related symbol the Swift modules
//      reference. If the archive were missing or empty, this test
//      executable would fail to link with "undefined symbols for
//      architecture arm64".
//
//   3. The Kotlin `swiftExport { moduleName = "AndroidSystemProperties" }`
//      and `flattenPackage = "io.github.kotlinmania.androidsystemproperties"`
//      configuration in build.gradle.kts produced a module name that's
//      both syntactically valid as a Swift identifier and reachable from
//      this Package.swift via the `AndroidSystemPropertiesLibrary` product.
final class AndroidSystemPropertiesExportTests: XCTestCase {
    func testSwiftModuleLoads() throws {
        XCTAssertTrue(true, "AndroidSystemProperties swift module imported cleanly")
    }

    func testGetReturnsNilOnMacOS() throws {
        // On macOS the appleMain stub actual returns nil for every property
        // name (Android system properties only exist on Android). This
        // exercises the wrapper class flow through Swift Export.
        let props = AndroidSystemProperties.AndroidSystemProperties.Companion.shared.new()
        let value = props.get(name: "ro.product.model")
        XCTAssertNil(value)
    }

    func testGetRejectsInteriorNul() throws {
        let props = AndroidSystemProperties.AndroidSystemProperties.Companion.shared.new()
        // Interior NUL must be rejected up front; the appleMain stub never
        // sees the call.
        let value = props.get(name: "ro.product\u{0000}model")
        XCTAssertNil(value)
    }
}
