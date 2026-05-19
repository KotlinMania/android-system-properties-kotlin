import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.3.21"
    id("maven-publish")
}

group = "io.github.kotlinmania"
version = "0.1.0"

kotlin {
    applyDefaultHierarchyTemplate()

    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

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
