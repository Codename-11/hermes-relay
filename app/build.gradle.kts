import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "com.hermesandroid.relay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hermesandroid.relay"
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

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_MODE", "true")
        }
        release {
            isMinifyEnabled = true
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
