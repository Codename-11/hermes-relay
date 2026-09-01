# :ui-preview — desktop UI iteration harness

A JVM-only **Compose for Desktop** module for iterating on presentational
composables **on the PC with hot reload** — edit, see it live, no
build/deploy/test loop on a device. It belongs only to the quarantined Quest
experiment and is **not shipped**: nothing in the production app, plugin, or
CLI release builds depends on it.

## Why this exists

The Android edit→build→install→test loop is slow for visual polish. This module
renders portable composables in a desktop window backed by Compose Multiplatform,
which (1.10+) bundles **Compose Hot Reload** — recompiled UI swaps into the
running window without a restart.

## Run it

**Hot reload (recommended):** in Android Studio / IntelliJ, open `Main.kt` and use
the run-gutter action **"Run 'MainKt' with Compose Hot Reload"**. Edits to this
module *or* to the shared `MorphingSphereCore.kt` reload live.

**Cold run (no hot reload):**

```bash
./gradlew -p experiments/quest :ui-preview:run
```

> **First-sync check:** this experimental tooling pins a Compose Multiplatform
> version (`org.jetbrains.compose` `1.12.0` in `build.gradle.kts`) against the
> Quest build's Kotlin (`2.4.10`). If a future Kotlin bump
> breaks the pairing, realign per the
> [Compose compatibility matrix](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html).
> The module is fully additive inside the quarantined build.

## What can live here

Only composables that take **plain values** and use **`androidx.compose.*` APIs
that exist in Compose Multiplatform** — no Android `Context`, no `@Preview` /
`androidx.*.tooling`, no Android framework types. Anything wired to a ViewModel or
the network must be fed **fake state** from the gallery controls.

### The single-source-of-truth pattern

Inside this experiment, the sphere algorithm lives in
`relay-ui/.../MorphingSphereCore.kt` (pure `kotlin.math`) and is source-shared
into this module. The production Android and web sphere implementations remain
separate sources of truth outside the experiment.

| Surface | Renderer | Lives in |
|---|---|---|
| Quest experiment | `MorphingSphere.kt` (Canvas + `@Preview`) | `:relay-ui` |
| Desktop experiment | `DesktopSphere.kt` (Canvas, no `@Preview`) | this module |

To add a component: hoist it to a value-only `@Composable`, add it to
`PreviewGallery` in `Main.kt`, and drive it from controls. If it needs logic that
isn't portable, extract that logic to a pure (Android-free) function — the same
move that made the sphere Core shareable — and call it from both sides.

## Files

| File | Purpose |
|---|---|
| `Main.kt` | `application { Window { PreviewGallery() } }` — state selector + live sliders |
| `DesktopSphere.kt` | Thin Compose-Desktop renderer calling the shared `forEachSphereCell` Core |
| `build.gradle.kts` | Kotlin/JVM + Compose Desktop; source-shares the Core, excludes the Android renderer |
