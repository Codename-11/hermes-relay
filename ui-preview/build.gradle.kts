// ─── :ui-preview — desktop Compose Hot Reload harness (NOT shipped) ──────────
//
// A JVM-only Compose for Desktop module that renders presentational composables
// in a window on the PC, with hot reload (edit → see it live, no device round-trip).
// It is excluded from every release artifact — the app, plugin, and CLI builds
// don't depend on it. See ui-preview/README.md.
//
// Compose Multiplatform 1.10+ bundles stable Compose Hot Reload and enables it by
// default for any module with a desktop target, so no separate hot-reload plugin
// is needed. Requirements met by this repo: Kotlin >= 2.1.20 (here 2.3.21) and a
// JVM target on Java <= 21 (here 17).
//
// Version note: `org.jetbrains.compose` is pinned here because it isn't declared
// in the root plugins block. If a future Kotlin bump breaks the pairing, align
// this version per https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
plugins {
    kotlin("jvm")
    // Compose compiler — version inherited from the root plugins {} block.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose") version "1.11.1"
}

kotlin {
    jvmToolchain(17)
}

sourceSets.main {
    // Share the sphere ALGORITHM source from :relay-ui — single source of truth,
    // so edits to the Core hot-reload here too. Exclude the Android renderer
    // (MorphingSphere.kt): its @Preview / androidx.*.tooling imports don't exist
    // on desktop; this module ships its own renderer in DesktopSphere.kt.
    kotlin.srcDir("../relay-ui/src/main/kotlin/com/axiomlabs/hermesrelay/ui/sphere")
    kotlin.exclude("**/MorphingSphere.kt")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
}

compose.desktop {
    application {
        mainClass = "com.hermesandroid.relay.preview.MainKt"
    }
}
