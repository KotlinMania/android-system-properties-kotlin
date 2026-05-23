import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.3.21"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.github.kotlinmania"
version = "0.1.0"

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Project-specific target surface: this is the Android system-properties
    // FFI shim, so the matrix is jvm + androidNative* only. No Apple, no JS,
    // no Wasm, no Linux/Mingw, no Android KMP library.
    jvm()
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    // Project-specific cinterop: every androidNative* target binds the
    // libc `<sys/system_properties.h>` shim via this .def file. The cinterop
    // package is read by `src/nativeMain/kotlin/.../Lib.kt`.
    targets.withType<KotlinNativeTarget> {
        compilations.getByName("main") {
            cinterops {
                val androidsystemproperties by creating {
                    defFile(project.file("src/nativeInterop/cinterop/androidsystemproperties.def"))
                    packageName("androidsystemproperties.cinterop")
                }
            }
        }
    }

    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }

    jvmToolchain(21)
}

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(
            TestLogEvent.STARTED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
            TestLogEvent.FAILED,
            TestLogEvent.STANDARD_OUT,
            TestLogEvent.STANDARD_ERROR,
        )
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
        showStandardStreams = true
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "android-system-properties-kotlin", version.toString())

    pom {
        name.set("android-system-properties-kotlin")
        description.set("Kotlin Multiplatform port of nical/android_system_properties - Minimal Android system properties wrapper")
        inceptionYear.set("2026")
        url.set("https://github.com/KotlinMania/android-system-properties-kotlin")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("sydneyrenee")
                name.set("Sydney Renee")
                email.set("sydney@solace.ofharmony.ai")
                url.set("https://github.com/sydneyrenee")
            }
        }

        scm {
            url.set("https://github.com/KotlinMania/android-system-properties-kotlin")
            connection.set("scm:git:git://github.com/KotlinMania/android-system-properties-kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/KotlinMania/android-system-properties-kotlin.git")
        }
    }
}
