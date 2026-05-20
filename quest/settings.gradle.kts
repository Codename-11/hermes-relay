pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.9.1"
        id("com.android.library") version "8.9.1"
        id("org.jetbrains.kotlin.android") version "2.0.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
        id("com.meta.spatial.plugin") version "0.12.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "quest"

include(":relay-core")
include(":relay-ui")

project(":relay-core").projectDir = file("../relay-core")
project(":relay-ui").projectDir = file("../relay-ui")

gradle.beforeProject {
    plugins.withId("com.android.library") {
        pluginManager.apply("org.jetbrains.kotlin.android")
    }
}
