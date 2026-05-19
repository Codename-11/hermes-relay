plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.meta.spatial.plugin")
}

android {
    namespace = "com.axiomlabs.hermesquest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.axiomlabs.hermesquest"
        minSdk = 34
        targetSdk = 34
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = "${libs.versions.appVersionName.get()}-quest"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        resources.excludes.add("META-INF/LICENSE")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":relay-core"))
    implementation(project(":relay-ui"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.meta.spatial.sdk.base)
    implementation(libs.meta.spatial.sdk.compose)
    implementation(libs.meta.spatial.sdk.ovrmetrics)
    implementation(libs.meta.spatial.sdk.toolkit)
    implementation(libs.meta.spatial.sdk.vr)
    implementation(libs.meta.spatial.sdk.castinputforward)
    implementation(libs.meta.spatial.sdk.hotreload)
    implementation(libs.meta.spatial.sdk.datamodelinspector)
    implementation(libs.meta.spatial.sdk.uiset)
    implementation(libs.meta.spatial.sdk.mruk)

    debugImplementation(libs.compose.ui.tooling)
}

val questPackage = "com.axiomlabs.hermesquest"
val sceneDirectory = layout.projectDirectory.dir("scenes")
val exportSpatialScenes = providers.gradleProperty("quest.exportScenes")
    .map(String::toBoolean)
    .orElse(false)

spatial {
    allowUsageDataCollection.set(false)
    if (exportSpatialScenes.get()) {
        scenes {
            exportItems {
                item {
                    projectPath.set(sceneDirectory.file("Main.metaspatial"))
                    outputPath.set(layout.projectDirectory.dir("src/main/assets/scenes"))
                }
            }
            hotReload {
                appPackage.set("$questPackage.debug")
                appMainActivity.set("com.axiomlabs.hermesquest.MainQuestActivity")
                assetsDir.set(File("src/main/assets"))
            }
        }
    }
}
