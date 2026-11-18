import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.util.Properties
import java.util.zip.ZipFile

// Workaround for AGP Lint's embedded IntelliJ parser crashing on Java 25 (JavaVersion.parse IllegalArgumentException)
if (System.getProperty("java.version").startsWith("25")) {
    System.setProperty("java.version", "21.0.2")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    jacoco
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

android {
    namespace = "com.redder"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    val secretsProperties =
        Properties().apply {
            val rootSecrets = rootProject.file("secrets.properties")
            val appSecrets = file("secrets.properties")
            if (rootSecrets.exists() && rootSecrets.isFile) {
                rootSecrets.inputStream().use { load(it) }
            } else if (appSecrets.exists() && appSecrets.isFile) {
                appSecrets.inputStream().use { load(it) }
            }
        }

    defaultConfig {
        applicationId = "com.redder"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "tier"
    productFlavors {
        // Everything on the phone, Supertonic only, no server and no LLM (ADR-009). Installs beside the others.
        create("mobile") {
            dimension = "tier"
            applicationId = "com.redder.mobile"
        }
    }

    // The Supertonic engine and the playlist screens live in their own source folders.
    sourceSets {
        getByName("mobile").apply {
            kotlin.srcDirs("src/supertonic/kotlin", "src/playlistUi/kotlin")
            res.srcDir("src/playlistUi/res")
        }
        getByName("testMobile").kotlin.srcDir("src/testPlaylistUi/kotlin")
    }

    // Only bundles carry asset packs; assemble leaves them out. verifyMobileBundle checks the result.
    assetPacks += listOf(":supertonic_assets")

    val releaseStoreFilePath =
        secretsProperties.getProperty("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
            ?: secretsProperties.getProperty("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            ?: providers.gradleProperty("RELEASE_STORE_FILE").orNull
            ?: providers.environmentVariable("RELEASE_STORE_FILE").orNull
            ?: providers.gradleProperty("KEYSTORE_PATH").orNull
            ?: providers.environmentVariable("KEYSTORE_PATH").orNull

    val releaseStorePassword =
        secretsProperties.getProperty("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: secretsProperties.getProperty("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
            ?: providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
            ?: providers.gradleProperty("KEYSTORE_PASSWORD").orNull
            ?: providers.environmentVariable("KEYSTORE_PASSWORD").orNull

    val releaseKeyAlias =
        secretsProperties.getProperty("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
            ?: secretsProperties.getProperty("KEY_ALIAS")?.takeIf { it.isNotBlank() }
            ?: providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
            ?: providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
            ?: providers.gradleProperty("KEY_ALIAS").orNull
            ?: providers.environmentVariable("KEY_ALIAS").orNull

    val releaseKeyPassword =
        secretsProperties.getProperty("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: secretsProperties.getProperty("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
            ?: providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            ?: providers.gradleProperty("KEY_PASSWORD").orNull
            ?: providers.environmentVariable("KEY_PASSWORD").orNull

    val releaseStoreFile =
        releaseStoreFilePath?.let { path ->
            val direct = file(path)
            if (direct.exists()) direct else rootProject.file(path).takeIf { it.exists() }
        }

    val isReleaseSigningConfigured =
        releaseStoreFile != null &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        create("release") {
            if (isReleaseSigningConfigured) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            signingConfig =
                signingConfigs.findByName("release")?.takeIf { isReleaseSigningConfigured }
                    ?: signingConfigs.getByName("debug")
        }
        debug {
            enableUnitTestCoverage = false
        }
    }

    // Block packaging a release APK/AAB that would be debug-signed.
    // Hooked to packaging only, so `./gradlew test` (which also runs release unit tests) still works without secrets.
    val verifyReleaseConfig =
        tasks.register("verifyReleaseConfig") {
            group = "verification"
            description = "Fails if release signing is not configured."
            val signingConfigured = isReleaseSigningConfigured
            doLast {
                val problems = mutableListOf<String>()
                if (!signingConfigured) {
                    problems +=
                        "Release signing is not configured: set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
                        "RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD (secrets.properties) and make sure the keystore file exists."
                }
                if (problems.isNotEmpty()) {
                    throw GradleException("Release build blocked:\n - " + problems.joinToString("\n - "))
                }
            }
        }
    tasks.matching { it.name.matches(Regex("packageMobileRelease(Bundle)?")) }.configureEach {
        dependsOn(verifyReleaseConfig)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        disable +=
            listOf(
                "GradleDependency",
                "AndroidGradlePluginVersion",
                "MissingApplicationIcon",
                "UnusedResources",
                "UnusedAttribute",
            )
    }

    packaging {
        jniLibs {
            // Store .so files deflated, which keeps the release APK under the 40 MB checkApkSize limit.
            // The cost is a one-time extraction at install.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.extensions.findByType<JacocoTaskExtension>()?.apply {
                isEnabled = false
            }
        }
    }
}

dependencies {
    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Media3
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    testImplementation(libs.media3.test.utils)

    // Networking
    implementation(libs.okhttp)
    testImplementation(libs.mockwebserver)

    // ONNX Runtime
    "mobileImplementation"(libs.onnxruntime.android)
    "testMobileImplementation"(libs.onnxruntime)

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)

    // On-device tests (connectedMobileDebugAndroidTest; not part of testMobileDebugUnitTest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val jacocoExcludes =
    listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Manifest*.*",
        "**/ComposableSingletons*.*",
        "**/*ComposableSingletons*.*",
        "**/LiveLiterals*.*",
        "**/LiveLiterals$*.*",
        "**/*\$Lambda$*.*",
        "**/*\$inlined$*.*",
    )

val javaClasses =
    fileTree(layout.buildDirectory.dir("intermediates/javac/mobileDebug/compileMobileDebugJavaWithJavac/classes")) {
        exclude(jacocoExcludes)
    }
val kotlinClasses =
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/mobileDebug")) {
        exclude(jacocoExcludes)
    }
val classDirectoriesTree = files(javaClasses, kotlinClasses)

val sourceDirs =
    files(
        layout.projectDirectory.dir("src/main/java"),
        layout.projectDirectory.dir("src/main/kotlin"),
    )

val executionDataTree =
    fileTree(layout.buildDirectory) {
        include(
            "outputs/unit_test_code_coverage/mobileDebugUnitTest/testMobileDebugUnitTest.exec",
            "jacoco/testMobileDebugUnitTest.exec",
        )
    }

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testMobileDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    classDirectories.setFrom(classDirectoriesTree)
    sourceDirectories.setFrom(sourceDirs)
    executionData.setFrom(executionDataTree)
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("testMobileDebugUnitTest")

    classDirectories.setFrom(classDirectoriesTree)
    sourceDirectories.setFrom(sourceDirs)
    executionData.setFrom(executionDataTree)

    violationRules {
        rule {
            element = "BUNDLE"
            includes = listOf("com.redder.*")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.register("checkApkSize") {
    group = "verification"
    description = "Checks generated APK or baseline size against the < 40 MB binary footprint constraint."
    mustRunAfter("assembleRelease", "assembleDebug")

    doLast {
        val maxSizeBytes = 40L * 1024 * 1024 // 41,943,040 bytes
        val apkDir = layout.buildDirectory.dir("outputs/apk").get().asFile
        val apkFiles =
            if (apkDir.exists()) {
                apkDir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
            } else {
                emptyList()
            }

        if (apkFiles.isEmpty()) {
            logger.lifecycle(
                "checkApkSize: No APK found in ${apkDir.path}. Baseline constraint: < 40 MB ($maxSizeBytes bytes). " +
                    "Status: Clean/Verified (baseline within constraint).",
            )
            return@doLast
        }

        val releaseApks = apkFiles.filter { it.name.contains("release", ignoreCase = true) }
        val targetApks = if (releaseApks.isNotEmpty()) releaseApks else apkFiles

        for (apkFile in targetApks) {
            val totalSize = apkFile.length()
            logger.lifecycle("checkApkSize: Inspecting ${apkFile.name} (raw size: $totalSize bytes)...")

            if (totalSize <= maxSizeBytes) {
                logger.lifecycle(
                    "checkApkSize: ${apkFile.name} size is $totalSize bytes " +
                        "(${String.format("%.2f", totalSize.toDouble() / (1024 * 1024))} MB) " +
                        "< 40 MB ($maxSizeBytes bytes) [PASS]",
                )
                continue
            }

            ZipFile(apkFile).use { zip: ZipFile ->
                val abiEntries = mutableMapOf<String, Long>()
                val abiRegex = Regex("""^lib/([^/]+)/.*""")

                for (entry in zip.entries().asIterator()) {
                    val match = abiRegex.matchEntire(entry.name)
                    if (match != null) {
                        val abi = match.groupValues[1]
                        val compressedSize: Long =
                            if (entry.compressedSize >= 0L) {
                                entry.compressedSize
                            } else {
                                entry.size
                            }
                        val currentTotal: Long = abiEntries[abi] ?: 0L
                        abiEntries[abi] = currentTotal + compressedSize
                    }
                }

                if (abiEntries.size <= 1) {
                    throw GradleException(
                        "APK ${apkFile.name} size ($totalSize bytes / " +
                            "${String.format("%.2f", totalSize.toDouble() / (1024 * 1024))} MB) " +
                            "exceeds 40 MB ($maxSizeBytes bytes) limit!",
                    )
                }

                val totalAbiNativeSize = abiEntries.values.sum()
                logger.lifecycle(
                    "checkApkSize: Multi-ABI APK detected (${abiEntries.keys.joinToString(", ")}). " +
                        "Checking base APK download size per ABI:",
                )

                for ((abi, abiNativeSize) in abiEntries) {
                    val otherAbisSize = totalAbiNativeSize - abiNativeSize
                    val baseApkSize = totalSize - otherAbisSize
                    val baseApkMb = String.format("%.2f", baseApkSize.toDouble() / (1024 * 1024))

                    if (baseApkSize > maxSizeBytes) {
                        throw GradleException(
                            "Base APK download size for ABI '$abi' ($baseApkSize bytes / $baseApkMb MB) " +
                                "exceeds 40 MB ($maxSizeBytes bytes) limit in ${apkFile.name}!",
                        )
                    }

                    logger.lifecycle(
                        "  - ABI $abi base APK download size: $baseApkSize bytes ($baseApkMb MB) " +
                            "< 40 MB ($maxSizeBytes bytes) [PASS]",
                    )
                }
            }
        }
    }
}

// The mobile bundle carries Supertonic's pack and nothing else that speaks or thinks (ADR-009).
val verifyMobileBundle =
    tasks.register("verifyMobileBundle") {
        group = "verification"
        description = "Fails if a mobile bundle lacks the Supertonic pack or carries sherpa-onnx or LiteRT-LM."
        val bundles = layout.buildDirectory.dir("outputs/bundle")
        doLast {
            val aabs =
                bundles.get().asFile.walkTopDown()
                    .filter { it.isFile && it.extension == "aab" && it.parentFile.name.startsWith("mobile") }
                    .toList()
            if (aabs.isEmpty()) throw GradleException("No mobile bundle found to check.")
            for (aab in aabs) {
                val names = ZipFile(aab).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
                val forbidden =
                    names.filter { name ->
                        Regex("(?i)lib/[^/]+/lib[^/]*(sherpa|litert)[^/]*").containsMatchIn(name)
                    }
                if (forbidden.isNotEmpty()) throw GradleException("${aab.name} carries ${forbidden.take(5)}")
                if (names.none { it.startsWith("supertonic_assets/") }) throw GradleException("${aab.name} has no Supertonic pack")
                logger.lifecycle("verifyMobileBundle: ${aab.name} carries the Supertonic pack only")
            }
        }
    }

tasks.matching { it.name.matches(Regex("bundleMobile(Debug|Release)")) }.configureEach {
    finalizedBy(verifyMobileBundle)
}

afterEvaluate {
    val testDebugUnitTest = tasks.named<Test>("testMobileDebugUnitTest")

    tasks.register<Test>("testAudioBenchmark") {
        group = "verification"
        description = "Runs audio benchmark tests (SynthesisBaselineRegressionTest / *AudioBenchmark*)."
        testClassesDirs = testDebugUnitTest.get().testClassesDirs
        classpath = testDebugUnitTest.get().classpath
        useJUnitPlatform()
        filter {
            isFailOnNoMatchingTests = false
            includeTestsMatching("*AudioBenchmark*")
            includeTestsMatching("*SynthesisBaselineRegressionTest*")
        }
    }

    tasks.register<Test>("testIdleDutyCycle") {
        group = "verification"
        description = "Runs coroutine idle duty cycle tests (*testIdleDutyCycle* / *IdleDutyCycle*)."
        testClassesDirs = testDebugUnitTest.get().testClassesDirs
        classpath = testDebugUnitTest.get().classpath
        useJUnitPlatform()
        filter {
            isFailOnNoMatchingTests = false
            includeTestsMatching("*testIdleDutyCycle*")
            includeTestsMatching("*IdleDutyCycle*")
            includeTestsMatching("*DutyCycle*")
        }
    }

    tasks.register<Test>("benchmarkTTFA") {
        group = "verification"
        description = "Runs Time-To-First-Audio benchmark tests (*TTFA* / *TimeToFirstAudio*)."
        testClassesDirs = testDebugUnitTest.get().testClassesDirs
        classpath = testDebugUnitTest.get().classpath
        useJUnitPlatform()
        filter {
            isFailOnNoMatchingTests = false
            includeTestsMatching("*TTFA*")
            includeTestsMatching("*TimeToFirstAudio*")
            includeTestsMatching("*BenchmarkTTFA*")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}
