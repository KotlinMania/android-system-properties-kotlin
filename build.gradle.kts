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

// ---------------------------------------------------------------------------
// CodeQL Java/Kotlin extraction task
//
// .github/workflows/codeql.yml invokes `./gradlew codeqlCompileJvm` to feed
// kotlinc-compiled commonMain through the CodeQL Java agent. This repo has
// no Android KMP library target, so there is no `codeqlAndroidAar`
// configuration or AAR-extraction step — only the kotlinc + stdlib
// classpath are needed.
val codeqlKotlinc: Configuration by configurations.creating {
    description = "Kotlin compiler (CodeQL extraction target only - not published)"
    isCanBeResolved = true
    isCanBeConsumed = false
}

val codeqlSourceClasspath: Configuration by configurations.creating {
    description = "Runtime classpath for CodeQL extraction of commonMain sources"
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    codeqlKotlinc("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21")
    codeqlSourceClasspath("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}

val codeqlCompileJvm = tasks.register<JavaExec>("codeqlCompileJvm") {
    description =
        "Compile commonMain Kotlin sources with kotlinc 2.3.21 for CodeQL Java/Kotlin extraction."
    group = "verification"

    classpath(codeqlKotlinc)
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")

    val outDir = layout.buildDirectory.dir("classes/kotlin/codeql-jvm")
    val commonSources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }
    val platformSources = fileTree("src/jvmMain/kotlin") { include("**/*.kt") }
    val sources = files(commonSources, platformSources)
    val sentinelDir = layout.buildDirectory.dir("generated/codeql-empty-source")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(codeqlSourceClasspath).withNormalizer(ClasspathNormalizer::class.java)
    outputs.dir(outDir)
    outputs.dir(sentinelDir)

    doFirst {
        outDir.get().asFile.mkdirs()
        val fullClasspath =
            codeqlSourceClasspath.resolve()
                .joinToString(File.pathSeparator) { it.absolutePath }
        val commonSourceFiles = commonSources.files.toMutableList()
        val sourceFiles = sources.files.toMutableList()
        if (sourceFiles.isEmpty()) {
            val sentinelFile = sentinelDir.get().asFile.resolve(
                "io/github/kotlinmania/androidsystemproperties/CodeqlEmptySentinel.kt",
            )
            sentinelFile.parentFile.mkdirs()
            sentinelFile.writeText(
                """
                // Auto-generated. Present so codeqlCompileJvm has at least
                // one Kotlin source to feed kotlinc; replaced by real
                // commonMain content once porting begins.
                package io.github.kotlinmania.androidsystemproperties

                private object CodeqlEmptySentinel
                """.trimIndent(),
            )
            commonSourceFiles += sentinelFile
            sourceFiles += sentinelFile
        }
        args = listOf(
            "-d", outDir.get().asFile.absolutePath,
            "-classpath", fullClasspath,
            "-jvm-target", "21",
            "-no-stdlib",
            "-no-reflect",
            "-language-version", "2.3",
            "-api-version", "2.3",
            "-Xmulti-platform",
            "-Xcommon-sources=${commonSourceFiles.joinToString(",") { it.absolutePath }}",
            "-Xexpect-actual-classes",
            "-opt-in", "kotlin.time.ExperimentalTime",
            "-opt-in", "kotlin.concurrent.atomics.ExperimentalAtomicApi",
        ) + sourceFiles.map { it.absolutePath }
    }
}

tasks.register("test") {
    group = "verification"
    description =
        "Runs the host-portable test suite (jvm). Non-host native targets " +
        "(androidNative*) only run on their own host (an Android device)."

    val defaultTestTasks = listOf("jvmTest")

    dependsOn(defaultTestTasks.mapNotNull { taskName -> tasks.findByName(taskName) })
}

val fullTargetBuildTasks = listOf(
    "jvmMainClasses",
    "jvmTestClasses",
    "jvmTest",
    "androidNativeArm32Binaries",
    "androidNativeArm32TestBinaries",
    "androidNativeArm64Binaries",
    "androidNativeArm64TestBinaries",
    "androidNativeX64Binaries",
    "androidNativeX64TestBinaries",
    "androidNativeX86Binaries",
    "androidNativeX86TestBinaries",
    "exportCommonSourceSetsMetadataLocationsForMetadataApiElements",
    "exportRootPublicationCoordinatesForMetadataApiElements",
    "exportCrossCompilationMetadataForAndroidNativeArm32ApiElements",
    "exportCrossCompilationMetadataForAndroidNativeArm64ApiElements",
    "exportCrossCompilationMetadataForAndroidNativeX64ApiElements",
    "exportCrossCompilationMetadataForAndroidNativeX86ApiElements",
    "exportTargetPublicationCoordinatesForAndroidNativeArm32ApiElements",
    "exportTargetPublicationCoordinatesForAndroidNativeArm64ApiElements",
    "exportTargetPublicationCoordinatesForAndroidNativeX64ApiElements",
    "exportTargetPublicationCoordinatesForAndroidNativeX86ApiElements",
    "exportTargetPublicationCoordinatesForJvmApiElements",
    "exportTargetPublicationCoordinatesForJvmRuntimeElements",
)

tasks.named("build") {
    dependsOn(fullTargetBuildTasks)
}

afterEvaluate {
    tasks.named("build") {
        dependsOn(
            tasks.matching {
                name.endsWith("MainClasses") ||
                    name.endsWith("TestClasses") ||
                    name.endsWith("Binaries") ||
                    name.startsWith("exportCommonSourceSetsMetadataLocationsFor") ||
                    name.startsWith("exportRootPublicationCoordinatesFor") ||
                    name.startsWith("exportCrossCompilationMetadataFor") ||
                    name.startsWith("exportTargetPublicationCoordinatesFor")
            },
        )
    }
}
