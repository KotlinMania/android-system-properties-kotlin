# android-system-properties-kotlin

Kotlin Multiplatform transliteration port of `android_system_properties`.

```kotlin
val properties = AndroidSystemProperties.new()
val timezone = properties.get("persist.sys.timezone")
```
