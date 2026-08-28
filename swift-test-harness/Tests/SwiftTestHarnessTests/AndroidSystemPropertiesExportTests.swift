import Testing
import AndroidSystemProperties

@Suite("AndroidSystemProperties Swift Export Tests")
struct AndroidSystemPropertiesExportTests {
    @Test("Swift module loads cleanly")
    func testSwiftModuleLoads() throws {
        #expect(Bool(true))
    }

    @Test("Get returns nil on macOS")
    func testGetReturnsNilOnMacOS() throws {
        let props = AndroidSystemProperties.Companion.shared.new()
        let value = props.get(name: "ro.product.model")
        #expect(value == nil)
    }

    @Test("Get rejects interior NUL")
    func testGetRejectsInteriorNul() throws {
        let props = AndroidSystemProperties.Companion.shared.new()
        let value = props.get(name: "ro.product\u{0000}model")
        #expect(value == nil)
    }
}
