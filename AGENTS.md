# Agent guide - android-system-properties-kotlin

This repo is a Kotlin Multiplatform port of the Rust crate `android_system_properties`.
The upstream source lives under `tmp/android_system_properties/` and is read-only.

Keep source Kotlin-shaped, preserve `// port-lint: source ...` headers, and do not
replace this port with unrelated Android or Java source. Android behavior is implemented
in Kotlin.
