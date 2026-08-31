# Screenshot Automation

Hermes-Relay keeps screenshot sources in `assets/screenshots/` and exports
Google Play-ready graphics into the Gradle Play Publisher metadata tree:

```text
app/src/googlePlay/play/listings/en-US/graphics/
  icon/1.png
  feature-graphic/1.png
  phone-screenshots/1.png ... 8.png
```

The scene list and export rules live in `docs/media/screenshots.json`.

## Commands

List the canonical scenes:

```bash
python scripts/screenshots.py list
```

Capture a single scene from the connected ADB device:

```bash
python scripts/screenshots.py capture 02_chat --demo --force
```

Export the committed sources to Play metadata:

```bash
python scripts/screenshots.py export --target play
```

Validate source images and exported Play graphics:

```bash
python scripts/screenshots.py validate
```

Running `python scripts/screenshots.py` with no arguments opens the original
interactive capture helper.

## Publishing

Android tag releases do not republish screenshots, title, description, icon,
or feature graphic. The release workflow keeps using the bundle-only
`publishGooglePlayReleaseBundle` task for the app upload and release notes.

Store assets (screenshots, icon, feature graphic) and listing copy are handled
by the **Play Store Listing** workflow (`.github/workflows/play-listing.yml`),
which is path-scoped to `assets/screenshots/**`, the Play graphics/listing
files, and `docs/media/screenshots.json` / `scripts/screenshots.py`:

- **PRs and `dev` pushes** that touch those paths run `validate` only.
- **`main` pushes** that touch those paths auto-`validate` then **auto-publish**
  the listing (`publishGooglePlayReleaseListing`) — so merging refreshed
  screenshots to `main` updates the live Play listing with no extra step. It
  skips gracefully (a CI notice, not a failure) if `PLAY_SERVICE_ACCOUNT_JSON`
  isn't configured.
- A **manual `workflow_dispatch`** with `publish_listing` ticked republishes on
  demand (e.g. without a content change).

App-bundle upload stays separate: pushing a stable `android-v*` tag uploads the
AAB via `publishGooglePlayReleaseBundle`; release tags never touch the listing.

For local publishing with a configured `play-service-account.json`:

```bash
python scripts/screenshots.py validate
./gradlew publishGooglePlayReleaseListing
```

## Capture Rules

- Use neutral, public-safe data only: no private hostnames, IPs, profile names,
  keys, tokens, internal server names, or personal notification content.
- Keep Android demo mode on for clean status-bar captures when the device
  supports it.
- The README sources are currently `1080x2244`. The Play export crops them to
  `1080x2160` because Google Play requires screenshot dimensions between
  320 and 3840 pixels and the long side cannot exceed twice the short side.

## Foreground-service policy video

`scripts/android-fgs-demo.py` drives a real connected phone through the Google
Play foreground-service evidence sequence and records it with Android's native
`screenrecord`. It verifies each expected control and notification through the
UI hierarchy, and aborts rather than producing misleading evidence when a step
is unavailable.

Use an explicit device serial so a connected emulator cannot receive the run by
accident:

```bash
python scripts/android-fgs-demo.py --serial <adb-serial>
```

The sequence enables Persistent connection, shows its ongoing notification,
sends and backgrounds a chat turn, returns through the notification, disables
the setting and proves the notification clears, then repeats the evidence flow
for the app-owned voice overlay and its **Stop voice** notification action.

Run `--no-record --pause-scale 0.25` first after material UI changes. Before
publishing the resulting MP4, review every notification-shade frame for private
network names and unrelated notification content; the driver does not clear or
alter the user's other notifications.
- Google Play listing upload stays on Gradle Play Publisher, matching the
  existing Android publishing toolchain. Fastlane is not required.

## Deterministic rendering (Roborazzi harness)

The scene *sources* in `assets/screenshots/` are no longer captured off a device.
They are rendered **host-side on the JVM** by Roborazzi from
`app/src/test/kotlin/com/hermesandroid/relay/screenshots/StoreScreenshotTest.kt` —
no device, no emulator, no live server, no status bar, exact resolution (so no
Play 2:1 clipping), and only mock public-safe data.

### Run

```bash
./gradlew :app:testGooglePlayDebugUnitTest --tests "*StoreScreenshotTest*" --tests "*GitWorkspaceScreenshotTest*"
# Output: app/build/store-shots/<scene>.png  (1080x2160, exactly 2:1)
```

Then visually review all eight store candidates and promote them into the
committed pipeline (rename `01_landing -> 01_startup`, copy the rest by name
into `assets/screenshots/`). Run the `export` + `validate` commands above.

The canonical marketing lineup intentionally exercises current product seams:

- `02_chat` uses the grouped production bubble layout and code renderer rather
  than the older long-answer fixture that could overflow its chrome, with the
  real app-level `FloatingPetCompanion` layered over chat exactly as `RelayApp`
  hosts it.
- `03_voice` renders the real `VoiceModeOverlay` with a fixed sphere frame,
  public-safe transcript, waveform, and session controls.
- `01_voice_conversation` renders the real `ConversationVoiceDock` inside the
  chat composer, so the Play lineup covers both Voice presentation modes.
- `04_sessions` renders the real `SessionDrawerContent` with deterministic
  `ChatSession` fixtures, including pinned and Thread states.
- `05_themes` renders the real Appearance preview and expanded customizer.
- `07_connections` renders the real `ConnectionsSettingsScreen` with
  deterministic connection and dashboard-status fixtures.
- `08_appearance` renders the real floating-pet controls with a deterministic
  pinned Teknium idle frame from Petdex passed through `PetAvatar`; provenance
  lives beside the fixture in `app/src/test/resources/marketing/`.
- Supplemental `12_theme_customizer` and `13_font_picker` expand Appearance
  coverage beyond the two Play-listing frames.
- Supplemental `14_startup` preserves the lower-information empty-chat frame
  outside the eight-slot Play lineup.
- Supplemental `15_git_workspace` renders the real native Git screen from a
  deterministic plugin API fixture. It is used by README, user docs, and the
  website but intentionally remains outside the eight-slot Play lineup.

### Render any view going forward

Add a `@Test` that calls the `capture(name, themeId) { ... }` helper:

```kotlin
@Test fun my_scene() = capture("my_scene", "midnight") { MyComposable(mockState) }
```

- **What** — any composable. Prefer reusing the *real* leaf components
  (`MessageBubble`, `MorphingSphere`) and chrome (`RelayCockpitChrome.kt`:
  `RelayModeStrip`, `RelayStatusStrip`, `RelayChromeIconButton`, `RelayMetricCard`,
  `RelayNavTile`; plus `ContextMeterBar`, `ChatInputBar`) over rebuilding — that
  is what makes the frame pixel-faithful. The shared `StoreCockpit` / `StoreSettings`
  scaffolds wrap content in real chrome.
- **Theme** — any `AppThemes.ALL` id; `capture()` wraps the body in
  `HermesRelayTheme` + provides the `Adaptive` sphere skin (the app's real default).
- **Resolution** — the class uses
  `@Config(qualifiers = "w400dp-h800dp-432dpi")`: a Galaxy Ultra-like 400x800dp
  viewport rendered at exactly 1080x2160 for Play's 2:1 limit.

### Constraints

- **Static/mock state only.** Live `ViewModel` + network/`LaunchedEffect` screens
  need the app's nullable-VM/preview pattern or a mock-fed frame wrapper. Leaf
  components render directly.
- **Animations need a fixed frame** — e.g. `MorphingSphere(fixedTime=, fixedColorPhase=)`
  disables its loop. A free-running animation is non-deterministic and hangs the test.
- **One `setContent` per test method** — for multi-output (e.g. a theme gallery),
  use one `@Test` per output, not a loop inside a single test.

### Real screens (1:1, auto-updating) vs curated frames

For a shot that stays **1:1** with the production UI as it changes, render the
**real screen composable** instead of rebuilding a frame. `ConnectionViewModel`
takes only an `Application` (Robolectric supplies it via `ApplicationProvider`):

```kotlin
val vm = ConnectionViewModel(ApplicationProvider.getApplicationContext<Application>())
capture("05_themes", "hermes-relay") { AppearanceSettingsScreen(vm, onBack = {}) }
// scroll before capture to frame a lower section:
compose.onNodeWithText("Solar").performScrollTo(); compose.onRoot().captureRoboImage(path)
```

- **Config-driven screens (e.g. Appearance)** — content comes from static
  registries (`AppThemes`, `SphereRegistry`), so the real screen renders **fully
  populated** and the shot tracks any layout change. Scenes `05`/`08` do this.
- **Data-driven screens (Chat, Sessions, Connections, Manage)** — the real screen
  renders its real layout but **empty** with a fresh VM (no paired connection, no
  messages). Prefer direct deterministic input seams where the production
  composable already exposes them: scenes `04_sessions` and `07_connections`
  now render the real populated surfaces this way. Chat still uses production
  chrome/leaf components plus the real app-level pet host; Manage remains a
  curated production-component composition until its network-owned overview
  state has a direct render seam. Those two must be visually re-checked when
  their owning screen layout changes.

### Build notes (non-obvious)

- The Roborazzi **Gradle plugin is intentionally NOT applied** — its latest release
  predates AGP 9 and calls the removed `TestedExtension`. Only the runtime
  (`roborazzi` + `roborazzi-compose`) is used; record mode is forced with the
  `roborazzi.test.record` system property in `testOptions`.
- Unit tests run on **JDK 21** (`tasks.withType<Test>` launcher in
  `app/build.gradle.kts`) because the markdown code-highlighter ships Java-21
  bytecode. Compile target stays 17; on-device (dexed) is unaffected.
