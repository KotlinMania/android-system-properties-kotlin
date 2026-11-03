import groovy.json.JsonSlurper
import io.github.kotlinmania.build.AndroidNativeDeviceTestRunner
import io.github.kotlinmania.build.BuildToolTask
import org.gradle.api.GradleException
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.plugins.signing.Sign
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    `maven-publish`
    signing
}

group = providers.gradleProperty("project.group").getOrElse("io.github.kotlinmania")
version = providers.gradleProperty("project.version").getOrElse("0.1.0-SNAPSHOT")
val projectNamespace = providers.gradleProperty("project.namespace").getOrElse("io.github.kotlinmania")
// Opt-ins shared across Kotlin targets.
val commonOptIns =
    listOf(
        "kotlin.time.ExperimentalTime",
        "kotlin.concurrent.atomics.ExperimentalAtomicApi",
        "kotlin.ExperimentalUnsignedTypes",
    )

// ============================================================================
// Android SDK installer
// ----------------------------------------------------------------------------
// The Android Gradle Plugin resolves the SDK location at configuration time,
// so the SDK must already be on disk before the `kotlin { android { ... } }`
// block evaluates. The installer is idempotent — a .install-complete marker
// short-circuits the download on every subsequent invocation, so warm runs
// pay only a directory-existence check. CI runners pay a one-time cold cost
// the first time they touch the project.
// ============================================================================

val androidCommandLineToolsRevision =
    providers
        .gradleProperty(
            "android.commandLineTools.revision",
        ).getOrElse("14742923")
val projectCompileSdk = providers.gradleProperty("android.compileSdk").getOrElse("34")
val projectAndroidBuildTools = providers.gradleProperty("android.buildTools").getOrElse("36.0.0")
val osName = providers.systemProperty("os.name").get().lowercase()
val isWindowsHost = "windows" in osName
val isMacHost = "mac" in osName
val androidSdkOsName =
    when {
        isWindowsHost -> "win"
        isMacHost -> "mac"
        "linux" in osName -> "linux"
        else -> throw GradleException("Unsupported Android SDK setup OS: ${providers.systemProperty("os.name").get()}")
    }
val projectAndroidSdkDir = layout.projectDirectory.dir(".android-sdk").asFile
val androidSdkManager =
    projectAndroidSdkDir.resolve(
        if (isWindowsHost) {
            "cmdline-tools/latest/bin/sdkmanager.bat"
        } else {
            "cmdline-tools/latest/bin/sdkmanager"
        },
    )
val androidSdkInstallMarker = projectAndroidSdkDir.resolve(".install-complete")
val requiredAndroidSdkPackageDirs =
    listOf(
        projectAndroidSdkDir.resolve("platform-tools"),
        projectAndroidSdkDir.resolve("platforms/android-$projectCompileSdk"),
        projectAndroidSdkDir.resolve("build-tools/$projectAndroidBuildTools"),
    )

fun writeAndroidLocalProperties() {
    projectAndroidSdkDir.mkdirs()
    val sdkDirPropertyValue = projectAndroidSdkDir.absolutePath.replace("\\", "/")
    layout.projectDirectory
        .file("local.properties")
        .asFile
        .writeText("sdk.dir=$sdkDirPropertyValue\n")
}

fun isProjectAndroidSdkInstalled(): Boolean =
    androidSdkInstallMarker.exists() &&
        androidSdkManager.exists() &&
        requiredAndroidSdkPackageDirs.all { it.exists() }

fun sdkManagerCommand(vararg args: String): List<String> =
    if (isWindowsHost) {
        listOf("cmd", "/c", androidSdkManager.absolutePath) + args
    } else {
        listOf(androidSdkManager.absolutePath) + args
    }

fun downloadAndroidCommandLineTools() {
    val zipName = "commandlinetools-$androidSdkOsName-${androidCommandLineToolsRevision}_latest.zip"
    val url = "https://dl.google.com/android/repository/$zipName"
    val tmpDir = projectAndroidSdkDir.resolve(".tmp/commandline-tools")
    val zipFile = tmpDir.resolve(zipName)
    val latestDir = projectAndroidSdkDir.resolve("cmdline-tools/latest")
    println("setup-android-sdk: downloading $url")
    tmpDir.deleteRecursively()
    tmpDir.mkdirs()
    try {
        URI(url).toURL().openStream().use { input ->
            Files.copy(input, zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        latestDir.deleteRecursively()
        latestDir.mkdirs()
        val canonicalLatestDir = latestDir.canonicalFile.toPath()
        ZipInputStream(zipFile.inputStream().buffered()).use { zipInput ->
            generateSequence { zipInput.nextEntry }.forEach { entry ->
                val relativeName = entry.name.removePrefix("cmdline-tools/").trimStart('/')
                if (relativeName.isNotEmpty()) {
                    val target = latestDir.resolve(relativeName).canonicalFile
                    if (!target.toPath().startsWith(canonicalLatestDir)) {
                        throw GradleException("Refusing to extract Android SDK entry outside $latestDir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile.mkdirs()
                        Files.copy(zipInput, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        if (!isWindowsHost && relativeName.startsWith("bin/")) target.setExecutable(true)
                    }
                }
                zipInput.closeEntry()
            }
        }
        if (!isWindowsHost) androidSdkManager.setExecutable(true)
    } finally {
        tmpDir.deleteRecursively()
    }
}

fun installProjectAndroidSdk(execOperations: ExecOperations) {
    if (isProjectAndroidSdkInstalled()) {
        writeAndroidLocalProperties()
        println("setup-android-sdk: SDK already installed at $projectAndroidSdkDir")
        return
    }
    if (!androidSdkManager.exists()) downloadAndroidCommandLineTools()
    println("setup-android-sdk: accepting licenses")
    val licenseAnswers = "y\n".repeat(200).toByteArray(Charsets.UTF_8)
    val licenseResult =
        execOperations.exec {
            commandLine(sdkManagerCommand("--sdk_root=${projectAndroidSdkDir.absolutePath}", "--licenses"))
            standardInput = ByteArrayInputStream(licenseAnswers)
            isIgnoreExitValue = true
        }
    if (licenseResult.exitValue != 0) {
        throw GradleException("Android SDK license acceptance failed with exit code ${licenseResult.exitValue}")
    }
    println(
        "setup-android-sdk: installing platform-tools, android-$projectCompileSdk, build-tools;$projectAndroidBuildTools",
    )
    val installLog = projectAndroidSdkDir.resolve("sdkmanager-install.log")
    installLog.parentFile.mkdirs()
    installLog.outputStream().use { output ->
        val installResult =
            execOperations.exec {
                commandLine(
                    sdkManagerCommand(
                        "--sdk_root=${projectAndroidSdkDir.absolutePath}",
                        "platform-tools",
                        "platforms;android-$projectCompileSdk",
                        "build-tools;$projectAndroidBuildTools",
                    ),
                )
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }
        if (installResult.exitValue != 0) {
            throw GradleException(
                "Android SDK package install failed with exit code ${installResult.exitValue}. " +
                    "Install log:\n${installLog.readText()}",
            )
        }
    }
    writeAndroidLocalProperties()
    androidSdkInstallMarker.writeText("")
    println("setup-android-sdk: done; SDK at $projectAndroidSdkDir")
}

// ----------------------------------------------------------------------------
// Android SDK setup is gated to follow the requested task. It must never run for
// non-Android invocations (jsTest, jvmTest, swiftExportSmokeTest, native /
// androidNative links) -- an unconditional install here is what made the SDK
// download appear on every machine and target.
//
// `writeAndroidLocalProperties()` always runs: it is cheap, hits no network, and
// only points local.properties at the project-local .android-sdk so AGP can
// resolve `sdk.dir` while the `androidLibrary {}` block evaluates.
//
// The SDK *package* download must happen at configuration time when -- and only
// when -- an Android task is in the requested build. AGP validates the packages
// while determining the dependencies of `compileAndroidMain` (task-graph
// construction, strictly before any task executes), so a plain `dependsOn`
// cannot supply them in time. We detect Android intent from the requested task
// names and install eagerly in that case. androidNative* are Kotlin/Native
// targets and need no Android SDK.
// ----------------------------------------------------------------------------
writeAndroidLocalProperties()

fun requestedTaskWantsAndroid(rawTaskName: String): Boolean {
    val taskName = rawTaskName.substringAfterLast(':')
    if (taskName.contains("AndroidNative", ignoreCase = true)) return false // Kotlin/Native, no SDK
    if (taskName.contains("Android", ignoreCase = true)) return true // direct AGP tasks
    return taskName in setOf("build", "assemble", "check", "test", "hostTests", "allTests", "assembleUnitTest", "assembleAndroidTest")
}

if (gradle.startParameter.taskNames.any(::requestedTaskWantsAndroid)) {
    installProjectAndroidSdk(serviceOf())
}

val ensureAndroidSdk =
    tasks.register("ensureAndroidSdk") {
        group = "setup"
        description = "Ensures the project-local Android SDK is installed (idempotent)."
        onlyIf("Android SDK already installed at $projectAndroidSdkDir") { !isProjectAndroidSdkInstalled() }
        doLast {
            installProjectAndroidSdk(serviceOf())
        }
    }

// Secondary net: order every AGP Android task after the installer (a no-op on
// warm runs). Excludes androidNative* (Kotlin/Native) and the installer itself.
tasks
    .matching { task ->
        val taskName = task.name
        taskName != "ensureAndroidSdk" &&
            taskName.contains("Android") &&
            !taskName.contains("AndroidNative")
    }.configureEach {
        dependsOn(ensureAndroidSdk)
    }

val jvmToolchainVersion = providers.gradleProperty("jvm.toolchain").getOrElse("21").toInt()

// ============================================================================
// kotlin { … }
// ----------------------------------------------------------------------------
// Android JVM and the two supported 64-bit Android Native ABIs.
// Every retained target is built unconditionally, with no opt-in build gates.
// ============================================================================
kotlin {
    jvmToolchain(jvmToolchainVersion)

    applyDefaultHierarchyTemplate()

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        allWarningsAsErrors.set(true)
        optIn.addAll(commonOptIns)
        freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xsuppress-version-warnings")
    }

    androidNativeArm64()
    androidNativeX64()

    // Android KMP library. Block name is `android` — `androidLibrary` is deprecated in current KGP.
    android {
        namespace = projectNamespace
        compileSdk = projectCompileSdk.toInt()
        minSdk = providers.gradleProperty("android.minSdk").getOrElse("24").toInt()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "$projectNamespace.AndroidPropertyInstrumentationRunner"
        }
    }

    sourceSets {
        androidNativeMain.dependencies {
            implementation(libs.libc.kotlin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// ============================================================================
// Test logging
// ============================================================================
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

// ============================================================================
// Static analysis: Detekt + Ktlint
// ============================================================================
detekt {
    buildUponDefaultConfig.set(true)
    allRules.set(false)
    autoCorrect.set(false)
    source.setFrom(files("src"))
    config.setFrom(files("detekt.yml"))
    parallel.set(true)
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
        checkstyle.required.set(false)
    }
}

ktlint {
    version.set(libs.versions.ktlintEngine.get())
    debug.set(false)
    verbose.set(false)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.SARIF)
    }
    filter {
        exclude("**/build/**")
        include("**/src/**/kotlin/**")
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<dev.detekt.gradle.Detekt>())
    dependsOn(tasks.named("ktlintCheck"))
    dependsOn("test")
}

// ============================================================================
// Maven Central publishing — Central Portal, first-party + bespoke upload
// ----------------------------------------------------------------------------
// OSSRH was sunset 2025-06-30; the Central Portal is the only path. Sonatype
// ships no first-party Gradle plugin, so we use Gradle's own maven-publish +
// signing (KGP populates the KMP publications) and upload the deployment
// bundle to the Portal API ourselves.
//
// Flow: publish all KMP publications into a local staging Maven layout ->
// zip it -> POST the zip to the Portal upload endpoint with a Bearer token.
// ============================================================================
val publishProjectName = providers.gradleProperty("project.name").getOrElse("unnamed-project")

// Central requires a Javadoc jar per publication; KMP produces none, so attach
// an empty one to every Maven publication.
val emptyJavadocJar =
    tasks.register<Jar>("emptyJavadocJar") {
        archiveClassifier.set("javadoc")
    }

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(emptyJavadocJar)
        pom {
            name.set(publishProjectName)
            description.set(providers.gradleProperty("project.pom.description").getOrElse(""))
            inceptionYear.set("2026")
            url.set("https://github.com/KotlinMania/$publishProjectName")
            licenses {
                license {
                    name.set(providers.gradleProperty("project.pom.licenseName").getOrElse("MIT"))
                    url.set(
                        providers
                            .gradleProperty("project.pom.licenseUrl")
                            .getOrElse("https://opensource.org/licenses/MIT"),
                    )
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
                url.set("https://github.com/KotlinMania/$publishProjectName")
                connection.set("scm:git:git://github.com/KotlinMania/$publishProjectName.git")
                developerConnection.set("scm:git:ssh://github.com/KotlinMania/$publishProjectName.git")
            }
        }
    }

    // Stage into a local Maven layout that becomes the Portal deployment bundle.
    // maven-publish auto-generates the md5/sha1/sha256/sha512 checksums Central
    // requires; signing (below) adds the .asc signatures.
    repositories {
        maven {
            name = "centralPortalStaging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
    val signingKeyId = providers.gradleProperty("signingInMemoryKeyId").orNull
    val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    val signingEnabled = project.findProperty("RELEASE_SIGNING_ENABLED") != "false" && signingKey != null
    if (signingEnabled) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(publishing.publications)
    }
}

val centralPortalPublishTasks =
    tasks.withType<PublishToMavenRepository>().matching {
        it.name.endsWith("ToCentralPortalStagingRepository")
    }

val prepareCentralPortalStaging =
    tasks.register<Delete>("prepareCentralPortalStaging") {
        delete(layout.buildDirectory.dir("staging-deploy"))
    }

centralPortalPublishTasks.configureEach {
    dependsOn(prepareCentralPortalStaging, tasks.withType<Sign>())
}

// Zip the staged Maven layout into a single Central Portal deployment bundle.
val centralPortalBundle =
    tasks.register<Zip>("centralPortalBundle") {
        group = "publishing"
        description = "Bundles the staged Maven artifacts into a Central Portal deployment zip."
        dependsOn(centralPortalPublishTasks)
        from(layout.buildDirectory.dir("staging-deploy"))
        archiveFileName.set("$publishProjectName-$version-bundle.zip")
        destinationDirectory.set(layout.buildDirectory.dir("central-portal"))
    }

// Upload the bundle to the Sonatype Central Portal Publisher API.
// publishingType: USER_MANAGED (default, safe — validates then waits for a
// manual release in the Portal UI) or AUTOMATIC (publishes after validation).
val publishToCentralPortal =
    tasks.register("publishToCentralPortal") {
        group = "publishing"
        description = "Uploads the deployment bundle to the Sonatype Central Portal."
        dependsOn(centralPortalBundle)
        doLast {
            val user =
                providers.gradleProperty("mavenCentralUsername").orNull
                    ?: error("mavenCentralUsername is required to publish to the Central Portal.")
            val password =
                providers.gradleProperty("mavenCentralPassword").orNull
                    ?: error("mavenCentralPassword is required to publish to the Central Portal.")
            val publishingType = providers.gradleProperty("centralPublishingType").getOrElse("USER_MANAGED")
            val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

            val bundle =
                centralPortalBundle
                    .get()
                    .archiveFile
                    .get()
                    .asFile
            require(bundle.exists()) { "Deployment bundle not found: $bundle" }

            val boundary = "CentralPortalBoundary" + UUID.randomUUID().toString().replace("-", "")
            val crlf = "\r\n"
            val preamble =
                (
                    "--$boundary$crlf" +
                        "Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"$crlf" +
                        "Content-Type: application/octet-stream$crlf$crlf"
                ).toByteArray(Charsets.UTF_8)
            val epilogue = "$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8)
            val body = preamble + bundle.readBytes() + epilogue

            val deploymentName = "$publishProjectName-$version"
            val uploadUri =
                URI(
                    "https://central.sonatype.com/api/v1/publisher/upload" +
                        "?name=$deploymentName&publishingType=$publishingType",
                )
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(uploadUri)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build()

            val client = HttpClient.newHttpClient()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("Central Portal upload failed: HTTP ${response.statusCode()} — ${response.body()}")
            }
            val deploymentId = response.body().trim()
            logger.lifecycle(
                "Central Portal upload accepted (deployment id: $deploymentId). " +
                    "publishingType=$publishingType.",
            )
            val automaticPublishing = publishingType.equals("AUTOMATIC", ignoreCase = true)
            val terminalStates =
                if (automaticPublishing) {
                    setOf("PUBLISHED")
                } else {
                    setOf("VALIDATED", "PUBLISHED")
                }
            val statusUri = URI("https://central.sonatype.com/api/v1/publisher/status?id=$deploymentId")
            val statusAttempts =
                providers.gradleProperty("centralPublishStatusAttempts").map(String::toInt).getOrElse(120)
            val statusDelayMillis =
                providers.gradleProperty("centralPublishStatusDelayMillis").map(String::toLong).getOrElse(10_000L)
            repeat(statusAttempts) { attempt ->
                val statusRequest =
                    HttpRequest
                        .newBuilder()
                        .uri(statusUri)
                        .header("Authorization", "Bearer $token")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build()
                val statusResponse = client.send(statusRequest, HttpResponse.BodyHandlers.ofString())
                if (statusResponse.statusCode() !in 200..299) {
                    error("Central Portal status check failed: HTTP ${statusResponse.statusCode()} — ${statusResponse.body()}")
                }
                val statusBody = JsonSlurper().parseText(statusResponse.body()) as Map<*, *>
                val deploymentState =
                    statusBody["deploymentState"]?.toString()
                        ?: error("Central Portal status response did not contain deploymentState: ${statusResponse.body()}")
                when (deploymentState) {
                    "FAILED" -> {
                        error("Central Portal deployment failed: ${statusBody["errors"] ?: statusResponse.body()}")
                    }

                    in terminalStates -> {
                        logger.lifecycle("Central Portal deployment $deploymentId reached $deploymentState.")
                        return@doLast
                    }
                }
                logger.lifecycle(
                    "Central Portal deployment $deploymentId is $deploymentState " +
                        "(${attempt + 1}/$statusAttempts).",
                )
                if (attempt + 1 < statusAttempts) {
                    Thread.sleep(statusDelayMillis)
                }
            }
            error(
                "Central Portal deployment $deploymentId did not reach " +
                    "${terminalStates.joinToString("/")} after $statusAttempts checks.",
            )
        }
    }

// ============================================================================
// Tasks
// ============================================================================

// Android host tests exercise common input validation. Device tests execute the
// platform implementation; native binaries must also execute on both ABIs.
tasks.register("hostTests") {
    group = "verification"
    dependsOn("testAndroidHostTest")
}

tasks.register("test") {
    group = "verification"
    dependsOn("hostTests", "connectedAndroidDeviceTest", "androidNativeArm64DeviceTest", "androidNativeX64DeviceTest")
}

// Compilation and APK assembly stay in the task graph; only confirmed local runtime absence is permitted.
tasks.matching { it.name == "connectedAndroidDeviceTest" }.configureEach {
    onlyIf("An Android runtime is available, or this is an ordinary/remote invocation") {
        AndroidNativeDeviceTestRunner.shouldRunConnectedTests(
            projectDir.toPath(),
            projectAndroidSdkDir.resolve("platform-tools/adb").toPath(),
            providers.gradleProperty("android.minSdk").getOrElse("24").toInt(),
            providers.gradleProperty("android.injected.device.serial").orNull,
            providers.environmentVariable("ANDROID_SERIAL").orNull,
        )
    }
}

tasks.register("setupAndroidSdk") {
    group = "setup"
    dependsOn("ensureAndroidSdk")
}

val nativeTargetNames = listOf("androidNativeArm64", "androidNativeX64")
nativeTargetNames.forEach { target ->
    tasks.register<BuildToolTask>("${target}DeviceTest") {
        group = "verification"
        description = "Executes the actual $target test binary on a configured or discovered compatible Android device."
        dependsOn(ensureAndroidSdk, "linkDebugTest${target.replaceFirstChar(Char::uppercaseChar)}")
        toolClass.set(AndroidNativeDeviceTestRunner::class.java.name)
        arguments.addAll(
            "--project-dir",
            projectDir.absolutePath,
            "--target",
            target,
            "--adb",
            projectAndroidSdkDir.resolve("platform-tools/adb").absolutePath,
        )
        providers.gradleProperty("$target.deviceSerial").orNull?.let { serial -> arguments.addAll("--serial", serial) }
    }
}

val fullTargetBuildTaskNames =
    buildSet {
        addAll(listOf("compileAndroidMain", "compileAndroidHostTest", "compileAndroidDeviceTest", "bundleAndroidMainAar", "assembleUnitTest", "assembleAndroidTest"))
        nativeTargetNames.forEach { target ->
            add("${target}Binaries")
            add("${target}TestBinaries")
        }
    }

tasks.named("build") {
    dependsOn(fullTargetBuildTaskNames)
}

val verifyAndroidArtifact =
    tasks.register("verifyAndroidArtifact") {
        group = "verification"
        description = "Checks the actual AAR payload and published Android-only variant metadata."
        dependsOn("bundleAndroidMainAar", "generateMetadataFileForKotlinMultiplatformPublication", "generateMetadataFileForAndroidPublication", "generatePomFileForAndroidPublication")
        doLast {
            val aar =
                layout.buildDirectory
                    .file("outputs/aar/$publishProjectName.aar")
                    .get()
                    .asFile
            val classNames =
                ZipFile(aar).use { archive ->
                    val manifest = archive.getInputStream(archive.getEntry("AndroidManifest.xml")).bufferedReader().use { it.readText() }
                    check(manifest.contains("android:minSdkVersion=\"${providers.gradleProperty("android.minSdk").get()}\""))
                    ZipInputStream(archive.getInputStream(archive.getEntry("classes.jar"))).use { classes ->
                        generateSequence { classes.nextEntry }.map { it.name }.toSet()
                    }
                }
            val classPrefix = projectNamespace.replace('.', '/')
            check("$classPrefix/AndroidSystemProperties.class" in classNames)
            check("$classPrefix/AndroidSystemPropertyKt.class" in classNames)
            val metadata =
                JsonSlurper().parse(
                    layout.buildDirectory
                        .file("publications/kotlinMultiplatform/module.json")
                        .get()
                        .asFile,
                ) as Map<*, *>
            val variants = metadata["variants"] as List<*>
            val modules =
                variants
                    .mapNotNull { variant ->
                        ((variant as Map<*, *>)["available-at"] as? Map<*, *>)?.get("module") as? String
                    }.toSet()
            check(modules == setOf("$publishProjectName-android", "$publishProjectName-androidnativearm64", "$publishProjectName-androidnativex64")) {
                "Unexpected platform publications: $modules"
            }
            val pom =
                layout.buildDirectory
                    .file("publications/android/pom-default.xml")
                    .get()
                    .asFile
                    .readText()
            check("<packaging>aar</packaging>" in pom)
            check("<version>$version</version>" in pom)
            val digest = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
            val report =
                layout.buildDirectory
                    .file("reports/publication/android-artifact.txt")
                    .get()
                    .asFile
            report.parentFile.mkdirs()
            report.writeText("AAR: ${aar.name}\nSHA-256: $digest\nPlatforms: ${modules.sorted()}\nClasses: ${classNames.sorted()}\n")
        }
    }

tasks.named("check") { dependsOn(verifyAndroidArtifact) }

// Template source layout; custom source-set names and dependency edges remain project-owned.
kotlin {
    sourceSets.configureEach {
        val sourceDirectories = mapOf(
            "androidDeviceTest" to "src/android/jvm/androidDeviceTest",
            "androidHostTest" to "src/android/jvm/androidHostTest",
            "androidMain" to "src/android/jvm/androidMain",
            "androidNativeArm32Main" to "src/android/native/androidNativeArm32Main",
            "androidNativeArm64Main" to "src/android/native/androidNativeArm64Main",
            "androidNativeArm64Test" to "src/android/native/androidNativeArm64Test",
            "androidNativeMain" to "src/android/native/androidNativeMain",
            "androidNativeTest" to "src/android/native/androidNativeTest",
            "androidNativeX64Main" to "src/android/native/androidNativeX64Main",
            "androidNativeX64Test" to "src/android/native/androidNativeX64Test",
            "androidNativeX86Main" to "src/android/native/androidNativeX86Main",
            "androidTest" to "src/android/jvm/androidTest",
            "apple64Main" to "src/apple/apple64Main",
            "apple64Test" to "src/apple/apple64Test",
            "appleLeafMain" to "src/apple/appleLeafMain",
            "appleMain" to "src/apple/appleMain",
            "appleTest" to "src/apple/appleTest",
            "blockingMain" to "src/common/blockingMain",
            "blockingTest" to "src/common/blockingTest",
            "browserMain" to "src/js/browserMain",
            "browserTest" to "src/js/browserTest",
            "commonBenchmark" to "src/common/commonBenchmark",
            "commonMain" to "src/common/commonMain",
            "commonTest" to "src/common/commonTest",
            "desktopMain" to "src/common/desktopMain",
            "filesystemTest" to "src/common/filesystemTest",
            "hostNativeTest" to "src/native/hostNativeTest",
            "hostSystemMain" to "src/common/hostSystemMain",
            "hostSystemTest" to "src/common/hostSystemTest",
            "iosArm64Main" to "src/apple/ios/iosArm64Main",
            "iosArm64Test" to "src/apple/ios/iosArm64Test",
            "iosMain" to "src/apple/ios/iosMain",
            "iosSimulatorArm64Main" to "src/apple/ios/iosSimulatorArm64Main",
            "iosSimulatorArm64Test" to "src/apple/ios/iosSimulatorArm64Test",
            "iosTest" to "src/apple/ios/iosTest",
            "iosX64Main" to "src/apple/ios/iosX64Main",
            "jsMain" to "src/js/jsMain",
            "jsTest" to "src/js/jsTest",
            "jvmAndAndroidMain" to "src/common/jvmAndAndroidMain",
            "jvmAndAndroidTest" to "src/common/jvmAndAndroidTest",
            "jvmBenchmark" to "src/jvm/jvmBenchmark",
            "jvmConsumerTest" to "src/jvm/jvmConsumerTest",
            "jvmMain" to "src/jvm/jvmMain",
            "jvmTest" to "src/jvm/jvmTest",
            "linuxArm64Main" to "src/native/linux/linuxArm64Main",
            "linuxArm64Test" to "src/native/linux/linuxArm64Test",
            "linuxLikeMain" to "src/native/linux/linuxLikeMain",
            "linuxMain" to "src/native/linux/linuxMain",
            "linuxTest" to "src/native/linux/linuxTest",
            "linuxX64Main" to "src/native/linux/linuxX64Main",
            "linuxX64Test" to "src/native/linux/linuxX64Test",
            "macosArm64Main" to "src/apple/macos/macosArm64Main",
            "macosArm64Test" to "src/apple/macos/macosArm64Test",
            "macosMain" to "src/apple/macos/macosMain",
            "macosTest" to "src/apple/macos/macosTest",
            "macosX64Main" to "src/apple/macos/macosX64Main",
            "mingwMain" to "src/native/mingw/mingwMain",
            "mingwTest" to "src/native/mingw/mingwTest",
            "mingwX64Main" to "src/native/mingw/mingwX64Main",
            "mingwX64Test" to "src/native/mingw/mingwX64Test",
            "nativeMain" to "src/native/nativeMain",
            "nativeTest" to "src/native/nativeTest",
            "nodeMain" to "src/js/nodeMain",
            "nodeTest" to "src/js/nodeTest",
            "nonPosixMain" to "src/native/nonPosixMain",
            "nonWinMain" to "src/native/nonWinMain",
            "posixMain" to "src/native/posix/posixMain",
            "posixTest" to "src/native/posix/posixTest",
            "tvosArm64Main" to "src/apple/tvos/tvosArm64Main",
            "tvosArm64Test" to "src/apple/tvos/tvosArm64Test",
            "tvosMain" to "src/apple/tvos/tvosMain",
            "tvosSimulatorArm64Main" to "src/apple/tvos/tvosSimulatorArm64Main",
            "tvosSimulatorArm64Test" to "src/apple/tvos/tvosSimulatorArm64Test",
            "tvosTest" to "src/apple/tvos/tvosTest",
            "wasmJsMain" to "src/js/wasm/wasmJsMain",
            "wasmJsTest" to "src/js/wasm/wasmJsTest",
            "wasmWasiMain" to "src/js/wasm/wasmWasiMain",
            "wasmWasiTest" to "src/js/wasm/wasmWasiTest",
            "watchosArm32Main" to "src/apple/watchos/watchosArm32Main",
            "watchosArm64Main" to "src/apple/watchos/watchosArm64Main",
            "watchosDeviceArm64Main" to "src/apple/watchos/watchosDeviceArm64Main",
            "watchosDeviceArm64Test" to "src/apple/watchos/watchosDeviceArm64Test",
            "watchosMain" to "src/apple/watchos/watchosMain",
            "watchosSimulatorArm64Main" to "src/apple/watchos/watchosSimulatorArm64Main",
            "watchosSimulatorArm64Test" to "src/apple/watchos/watchosSimulatorArm64Test",
            "watchosTest" to "src/apple/watchos/watchosTest",
            "webMain" to "src/js/webMain",
            "webTest" to "src/js/webTest",
        )
        val sourceRoot = sourceDirectories[name] ?: "src/$name"
        kotlin.setSrcDirs(kotlin.srcDirs.filter { it != file("src/$name/kotlin") && it != file("$sourceRoot/kotlin") } + file("$sourceRoot/kotlin"))
        resources.setSrcDirs(resources.srcDirs.filter { it != file("src/$name/resources") && it != file("$sourceRoot/resources") } + file("$sourceRoot/resources"))
    }
}
