pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.3.2"
        id("com.android.library") version "9.3.2"
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
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

rootProject.name = "hermes-quest-experiment"

include(":relay-core")
include(":relay-ui")
include(":ui-preview")
