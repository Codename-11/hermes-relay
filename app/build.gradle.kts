import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.play.publisher)
}

// Rename output artifacts to include the app version. AGP respects
// `archivesName` for both APK (assemble*) and AAB (bundle*) outputs, so
// this single line produces `hermes-relay-<version>-<flavor>-<buildType>`
// filenames across debug, release, and per-flavor variants. The version
// is pulled from libs.versions.toml so bumping via scripts/bump-version.sh
// keeps artifact names in sync with no second source of truth.
base {
    archivesName.set("hermes-relay-${libs.versions.appVersionName.get()}")
}

android {
    // Kotlin package / on-disk source layout / R class namespace. Decoupled
    // from `applicationId` below as of the Axiom-Labs org-account migration:
    // the repo's source tree stays under `com.hermesandroid.relay` (so all
    // 130+ Kotlin files and their package declarations keep working) while
    // the Play Store / Android-system identity lives under `com.axiomlabs.*`.
    // This is an AGP-supported pattern — `namespace` is a build-time concept
    // and `applicationId` is the runtime install identity; they don't have
    // to match.
    namespace = "com.hermesandroid.relay"
    compileSdk = 37

    defaultConfig {
        // Axiom-Labs, LLC Play Console listing. Changed from the original
        // `com.hermesandroid.relay` on 2026-04-13 during the org-account
        // migration. The old Internal-testing listing under Bailey's personal
        // account is being deleted; the DUNS-verified Axiom-Labs account is
        // exempt from Play's 14-day closed-testing rule. See RELEASE.md.
        applicationId = "com.axiomlabs.hermesrelay"
        minSdk = 26
        targetSdk = 35
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Feature flags — DEV_MODE enables all experimental features in debug builds
        buildConfigField("boolean", "DEV_MODE", "false")
    }

    signingConfigs {
        create("release") {
            val localProps = rootProject.file("local.properties")
            val props: Properties? = if (localProps.exists()) {
                Properties().apply { localProps.inputStream().use { stream -> load(stream) } }
            } else null

            val keystorePath = System.getenv("HERMES_KEYSTORE_PATH")
                ?: props?.getProperty("hermes.keystore.path")
                ?: "/nonexistent"
            storeFile = rootProject.file(keystorePath)
            storePassword = System.getenv("HERMES_KEYSTORE_PASSWORD")
                ?: props?.getProperty("hermes.keystore.password") ?: ""
            keyAlias = System.getenv("HERMES_KEY_ALIAS")
                ?: props?.getProperty("hermes.key.alias") ?: ""
            keyPassword = System.getenv("HERMES_KEY_PASSWORD")
                ?: props?.getProperty("hermes.key.password") ?: ""
        }
    }

    // ─── Bridge release tracks ─────────────────────────────────────────────────
    // Google Play ships Bridge Core only: pairing, chat, voice, terminal/TUI,
    // media, notification companion, relay sessions, and status. It does not
    // declare AccessibilityService, overlay, MediaProjection, wake-lock device
    // control, SMS/call/contact/location, or unattended-control permissions.
    //
    //   googlePlay  — canonical Play Store install. Bridge Core only.
    //
    //   sideload    — Device Control for users who install directly (GitHub
    //                 Releases, F-Droid, ADB). AccessibilityService, gestures,
    //                 screenshots, overlay/status chip, and phone utilities are
    //                 declared in the sideload manifest.
    //
    // applicationIdSuffix decision: sideload gets `.sideload` so both tracks can
    // coexist on the same device. The Play build keeps the base
    // `com.axiomlabs.hermesrelay` applicationId as the canonical Play Store
    // install; sideload becomes `com.axiomlabs.hermesrelay.sideload`. Cost:
    // anyone with both installed sees two launcher icons — we differentiate
    // via the flavored strings.xml label suffix.
    //
    // Note: the previous `com.hermesandroid.relay` applicationId (Internal
    // testing under Bailey's personal Play account) is being retired as part
    // of the Axiom-Labs org-account migration. Play Store package names are
    // permanently reserved once used, so the old ID can never be reclaimed —
    // existing Internal-testing installs won't auto-upgrade to the new listing
    // and will need a manual reinstall (limited blast radius, single tester).
    flavorDimensions += "track"
    productFlavors {
        create("googlePlay") {
            dimension = "track"
            // No applicationIdSuffix — this IS the canonical Play Store install.
        }
        create("sideload") {
            dimension = "track"
            applicationIdSuffix = ".sideload"
            versionNameSuffix = "-sideload"
        }
    }

    // Structural guard: the sideload flavor is distributed via GitHub Releases /
    // F-Droid / ADB and must NEVER be uploaded to Play Console (it declares the
    // unattended Device Control surface Play forbids). gradle-play-publisher
    // generates a publish task per variant, so the aggregate `publishReleaseBundle`
    // would otherwise try BOTH flavors. Disabling sideload here means only
    // `publishGooglePlayReleaseBundle` can ever reach Play — see the `play { }`
    // block below and .github/workflows/release-android.yml.
    playConfigs {
        register("sideload") {
            enabled.set(false)
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_MODE", "true")
        }
        release {
            isMinifyEnabled = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use release signing if keystore exists, otherwise fall back to debug signing
            val releaseSigningConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseSigningConfig.storeFile?.exists() == true) {
                releaseSigningConfig
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }

    // JVM unit tests run against the stubbed Android SDK jar, where every
    // platform API method throws RuntimeException("... not mocked") by
    // default. With returnDefaultValues = true, those stubs instead
    // return the Java defaults (0 / null / false / empty). This unblocks
    // tests that exercise production code calling android.util.Log (which
    // UnattendedAccessManager does defensively in catch blocks) without
    // needing every test to mockkStatic(Log::class). Regression discovered
    // when v0.5.0 CI release caught UnattendedAccessManagerTest's
    // acquireForAction_uninitialized + refreshKeyguardState_threwException
    // both failing with RuntimeException from unmocked Log.w calls.
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (VoicePlayerTest) needs merged Android resources +
        // manifest on the unit-test classpath to bootstrap its sandbox.
        unitTests.isIncludeAndroidResources = true
        // [POC] Roborazzi runs without its Gradle plugin (the plugin needs AGP's
        // removed TestedExtension). Force record mode via the test-JVM system
        // property the plugin would otherwise inject, so captureRoboImage writes.
        // Heap: the Roborazzi store renders (1080×2160 native graphics) share a
        // worker JVM with the Robolectric suites; Gradle's 512m default OOMs
        // once both are in the same run.
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.maxHeapSize = "2g"
        }
    }
}

// Google Play Publisher — optional automated upload to Play Console.
// The plugin adds tasks like `publishReleaseBundle` that talk to the Play
// Developer API. Requires a service account JSON at <repo-root>/play-service-account.json.
// Normal builds (assembleRelease, bundleRelease) work without it; only the
// publish tasks require it. See RELEASE.md for service account setup.
play {
    serviceAccountCredentials.set(rootProject.file("play-service-account.json"))
    track.set("internal")
    defaultToAppBundles.set(true)
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.DRAFT)
}

kotlin {
    jvmToolchain(17)
}

// [screenshots] Host-side screenshot tests render MessageBubble -> MarkdownContent,
// whose code-highlighter (dev.snipme.highlights) ships Java-21 bytecode. The build
// toolchain pins test execution to JDK 17, which can't load class-file v65, so run
// unit tests on a 21 JVM. Compile target stays 17; on-device (dexed) is unaffected.
// foojay (settings.gradle.kts) auto-provisions the 21 JDK if absent.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    )
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)

    // Activity
    implementation(libs.activity.compose)
    implementation(libs.appcompat)

    // Core
    implementation(libs.core.ktx)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Media3 ExoPlayer — gapless TTS queue playback (replaces MediaPlayer in VoicePlayer)
    implementation(libs.media3.exoplayer)

    // android-vad Silero — on-device VAD for barge-in (B2)
    // Bundled ONNX Silero model (~2.2 MB); pulled from JitPack.
    implementation(libs.android.vad.silero)

    // Google Play In-App Update — googlePlay flavor ONLY (FLEXIBLE flow).
    // Scoped via the `googlePlayImplementation` configuration so it never
    // ships in the sideload APK, which updates via the GitHub-releases
    // UpdateChecker instead. The `app/src/googlePlay/.../update/` impl
    // references AppUpdateManager; the `app/src/sideload/.../update/` impl
    // never touches this library.
    "googlePlayImplementation"(libs.play.app.update)
    "googlePlayImplementation"(libs.play.app.update.ktx)

    // Markdown rendering
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)

    // Coil 3 — async image loading for generated images in chat
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // QR Code scanning (ML Kit + CameraX)
    implementation(libs.mlkit.barcode)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // Haze (glassmorphism blur)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // Window size class for responsive layout
    implementation("androidx.compose.material3:material3-window-size-class")

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Security
    implementation(libs.security.crypto)
    // Force a Tink newer than security-crypto's transitive one — older Tink's
    // HybridConfig removeFirst()/removeLast() trips the Android-15 crash lint.
    implementation(libs.tink.android)

    // DataStore
    implementation(libs.datastore.preferences)

    // Splash Screen
    implementation(libs.splashscreen)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    // MockWebServer for ADR 24 EndpointResolver tests — probes HEAD /health
    // across priority groups against real local sockets so the behavior we
    // validate matches on-device.
    testImplementation(libs.okhttp.mockwebserver)
    // Konsist — enforces the ADR 34 upstream/relay/shared package fence as a JUnit test
    testImplementation(libs.konsist)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // [POC] Roborazzi host-side screenshot rendering (src/test, Robolectric).
    // Renders real composables on the JVM at an exact canvas — no device, no
    // status bar, no clipping. See StoreScreenshotTest.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.66.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.66.0")
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation("androidx.test.ext:junit:1.3.0")
}

