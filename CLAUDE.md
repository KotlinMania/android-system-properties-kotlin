# Claude Code Project Instructions - android-system-properties-kotlin

This is a Kotlin Multiplatform port of the upstream Rust crate
`android_system_properties`.

The upstream Rust source is the read-only translation oracle under
`tmp/android_system_properties/`. Do not edit upstream files.

Every Kotlin file translated from Rust source must carry:

```kotlin
// port-lint: source src/lib.rs
```

Use Kotlin code for Android implementations. Do not replace translated project
dependencies with unrelated platform libraries.
