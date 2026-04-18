# Flavor Differences

Hermes-Relay ships as two product flavors from the same codebase: **googlePlay** and **sideload**. Both flavors share the core chat, voice, session, and relay features. They diverge on the bridge channel -- how much control the agent has over the phone.

This page documents the technical differences for developers and contributors.

## Build System

Two product flavors defined in `app/build.gradle.kts` under a single `track` dimension:

```kotlin
flavorDimensions += "track"
productFlavors {
    create("googlePlay") {
        dimension = "track"
        // No applicationIdSuffix — canonical Play Store install
    }
    create("sideload") {
        dimension = "track"
        applicationIdSuffix = ".sideload"
        versionNameSuffix = "-sideload"
    }
}
```

Both flavors coexist on the same device. The `googlePlay` build installs as `com.axiomlabs.hermesrelay`; the `sideload` build installs as `com.axiomlabs.hermesrelay.sideload`. Users see two launcher icons if both are installed, differentiated by a flavored label suffix in `strings.xml`.

## Source Set Layout

```
app/src/
├── main/              # Shared code — all screens, networking, auth, voice, bridge handler
├── googlePlay/
│   ���── AndroidManifest.xml          # Overlay: narrows BridgeForegroundService type
│   ├── kotlin/.../voice/
│   │   ├── VoiceBridgeIntentHandlerImpl.kt   # NoopVoiceBridgeIntentHandler
│   │   └── VoiceBridgeIntentFactory.kt
│   └── res/
│       ├── xml/accessibility_service_config.xml   # Conservative config
│       └── values/strings.xml                     # Play-track labels
└── sideload/
    ├── AndroidManifest.xml          # Overlay: adds sideload-only permissions
    ├── kotlin/.../voice/
    │   ├── VoiceIntentClassifier.kt              # Real regex classifier
    │   ├── VoiceBridgeIntentHandlerImpl.kt       # RealVoiceBridgeIntentHandler
    │   └── VoiceBridgeIntentFactory.kt
    └── res/
        ├── xml/accessibility_service_config.xml   # Full agent-control config
        └── values/strings.xml                     # Sideload-track labels
```

The `VoiceBridgeIntentHandler` interface lives in `main/` so `VoiceViewModel` can reference it at compile time. Each flavor provides a `createVoiceBridgeIntentHandler()` factory function in `VoiceBridgeIntentFactory.kt`. No reflection.

## Manifest Split

### Main manifest (both flavors)

```
INTERNET, ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS,
WAKE_LOCK, FOREGROUND_SERVICE, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW,
FOREGROUND_SERVICE_SPECIAL_USE
```

The `BridgeForegroundService` in main declares `foregroundServiceType="specialUse|mediaProjection"`.

### Sideload overlay

Adds permissions that Google Play policy forbids without a default-dialer / default-SMS-app justification:

```
SEND_SMS, CALL_PHONE, READ_CONTACTS,
ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION,
FOREGROUND_SERVICE_MEDIA_PROJECTION
```

### googlePlay overlay

Narrows the `BridgeForegroundService` foreground service type to `specialUse` only, stripping `mediaProjection` via `tools:replace="android:foregroundServiceType"`. Without this, the merged manifest would carry the `mediaProjection` type from main, and Play review would flag it against the conservative accessibility use-case description.

## Accessibility Service Config

The `AccessibilityService` is declared once in the main manifest. The flavor distinction is purely at the resource layer -- each flavor provides its own `res/xml/accessibility_service_config.xml`.

| Attribute | googlePlay | sideload |
|-----------|-----------|----------|
| `accessibilityEventTypes` | `typeWindowStateChanged\|typeWindowContentChanged\|typeViewClicked` | `typeAllMask` |
| `accessibilityFlags` | `flagDefault` | `flagDefault\|flagRetrieveInteractiveWindows\|flagReportViewIds\|flagRequestTouchExplorationMode` |
| `canRetrieveWindowContent` | `true` | `true` |
| `canPerformGestures` | absent (no gestures) | `true` |

The googlePlay config targets the "read notifications, summarize messages, reply with confirmation" use case that Play Store policy review expects. The sideload config enables the full agent-control surface.

## BridgeCommandHandler Route Gate

The googlePlay build has a **fail-closed whitelist** in `BridgeCommandHandler`. Any bridge route not explicitly listed returns `403` with `error_code: sideload_only`. The whitelist:

```
/ping, /screen, /current_app, /get_apps, /apps,
/clipboard (GET only), /return_to_hermes, /setup, /events
```

Every new route added to the `when` block defaults to sideload-only on the Play flavor unless explicitly added to this set. This prevents future routes from accidentally widening the Play APK's capability surface.

Additionally, the four Tier C tools (`/location`, `/search_contacts`, `/call`, `/send_sms`) have individual `BuildFlavor.isSideload` gates that return `403` with the same `sideload_only` error code, even though they'd also be blocked by the whitelist. Belt and braces.

Clipboard write (`POST /clipboard`) has its own secondary gate inside the `/clipboard` handler because both GET and POST share a path -- the whitelist lets `/clipboard` through for read, and the handler blocks write on googlePlay.

## UI Differences

### Bridge tab

| Element | googlePlay | sideload |
|---------|-----------|----------|
| Master toggle label | "Enable Bridge Mode" | "Allow Agent Control" |
| Accessibility subtitle | "Read screen content for chat context" | "Read screen content, dispatch taps/types" |
| Screen Capture row | Hidden | Visible (MediaProjection consent) |
| Display Over Apps row | Hidden | Visible (SYSTEM_ALERT_WINDOW) |
| Overlay permission nag banner | Hidden | Visible when bridge enabled + overlay not granted |
| Safety summary card | Hidden | Visible (blocklist, destructive verbs, auto-disable timer) |
| Unattended Access card | Hidden | Visible (gated on master toggle; credential-lock warning inlined) |
| Unattended global banner | Hidden | Visible on every tab when master + unattended are both on |

The permission checklist always shows the Accessibility Service row and the Notification Listener row on both flavors. Only the Screen Capture and Display Over Apps rows are conditionally visible based on `BuildFlavor.isSideload`. The Sideload-features checklist section (Contacts / SMS / Phone / Location) is hidden on googlePlay entirely.

## Voice Intent Classifier

| Flavor | Handler class | Behavior |
|--------|--------------|----------|
| googlePlay | `NoopVoiceBridgeIntentHandler` | Always returns `NotApplicable`. Every voice utterance goes to chat. |
| sideload | `RealVoiceBridgeIntentHandler` | Runs `VoiceIntentClassifier.classify()`. Recognized intents dispatch locally through the bridge. Unrecognized text falls through to chat. |

Both handlers implement the same `VoiceBridgeIntentHandler` interface. `VoiceViewModel` calls the factory once during `initialize()` and doesn't know which implementation it received.

## BuildFlavor Object

Compile-time flavor gating in Kotlin code uses the `BuildFlavor` object in `data/FeatureFlags.kt`:

```kotlin
object BuildFlavor {
    const val GOOGLE_PLAY = "googlePlay"
    const val SIDELOAD = "sideload"
    val current: String get() = BuildConfig.FLAVOR

    val isSideload: Boolean get() = current == SIDELOAD

    val bridgeTier1: Boolean = true                      // baseline — both
    val bridgeTier2: Boolean = true                      // notifications — both
    val bridgeTier3: Boolean get() = current == SIDELOAD // voice-first
    val bridgeTier4: Boolean get() = current == SIDELOAD // vision-first
    val bridgeTier5: Boolean = true                      // safety rails — always
    val bridgeTier6: Boolean get() = current == SIDELOAD // future ambitious
}
```

Most call sites use `BuildFlavor.isSideload` for simple checks. The `bridgeTier1`--`bridgeTier6` flags are available for finer-grained gating but aren't widely used yet. `current` resolves to a `BuildConfig.FLAVOR` compile-time constant, so R8 folds the comparisons away in release builds.

## Plugin Tool Descriptions

The `android_*` tool registrations in `plugin/android_tool.py` and `plugin/tools/android_tool.py` carry trust-model and denial-retry guidance in their description strings regardless of flavor. The plugin runs server-side and has no compile-time awareness of which flavor the phone is running. When a tool call hits a flavor gate at runtime, the phone returns a `403` with `error_code: sideload_only` and a human-readable message the agent can relay to the user.

This means the agent may attempt to call `android_send_sms` on a googlePlay phone. The structured error response tells the agent the tool isn't available on this build and suggests falling back to driving the Messages app UI -- but that fallback is also gated by the play route whitelist, so the agent will get a second `sideload_only` error if it tries `android_tap_text` to press "Send". The result is fail-safe: sideload-only capabilities can't leak through alternate tool paths on the Play build.
