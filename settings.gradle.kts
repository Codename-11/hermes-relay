pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // JitPack — hosts com.github.gkonovalov:android-vad:silero for B2 (barge-in).
        // The android-vad library isn't published to Maven Central, only JitPack.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "hermes-relay"
include(":app")
include(":relay-core")
include(":relay-ui")
includeBuild("quest")
