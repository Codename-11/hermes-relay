pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.3.0"
        id("com.android.library") version "9.3.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
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
