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
    compileSdk = 36

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

            storeFile = file(
                System.getenv("HERMES_KEYSTORE_PATH")
                    ?: props?.getProperty("hermes.keystore.path")
                    ?: "/nonexistent"
            )
            storePassword = System.getenv("HERMES_KEYSTORE_PASSWORD")
                ?: props?.getProperty("hermes.keystore.password") ?: ""
            keyAlias = System.getenv("HERMES_KEY_ALIAS")
                ?: props?.getProperty("hermes.key.alias") ?: ""
            keyPassword = System.getenv("HERMES_KEY_PASSWORD")
                ?: props?.getProperty("hermes.key.password") ?: ""
        }
    }

    // ─── Phase 3 — Bridge channel release tracks ────────────────────────────────
    // Google Play scrutinizes AccessibilityService heavily (policy review + manual
    // appeals are common), so Phase 3 ships two distinct tracks via flavor-merged
    // manifests + flavor-scoped strings + flavor-scoped accessibility configs:
    //
    //   googlePlay  — conservative use-case description targeted at Play Store
    //                 policy review. Subset of event types + flagDefault only.
    //                 No gestures, no interactive-window reporting. Feature gates
    //                 in BuildFlavor.kt hide tier 3/4/6 surfaces in the UI.
    //
    //   sideload    — full agent-control description for users who install the
    //                 APK directly (GitHub Releases, F-Droid, ADB). typeAllMask,
    //                 gestures, interactive windows, view-id reporting. All six
    //                 tiers enabled.
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

    // Core
    implementation(libs.core.ktx)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Markdown rendering
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)

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

    // DataStore
    implementation(libs.datastore.preferences)

    // Splash Screen
    implementation(libs.splashscreen)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

