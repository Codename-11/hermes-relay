# Flavor Differences

Hermes-Relay ships as two product flavors from the same codebase: **googlePlay** and **sideload**. Both share chat, profiles, voice, standard inbound files, relay pairing, terminal/TUI relay, Relay media enhancements, notification companion, sessions, and status. They diverge on Device Control.

## Track model

| Flavor | Install ID | Bridge scope |
|--------|------------|--------------|
| `googlePlay` | `com.axiomlabs.hermesrelay` | **Bridge Core** only. No AccessibilityService or MediaProjection Device Control, gestures, SMS/call/contact/location access, wake-lock Device Control, or unattended control. |
| `sideload` | `com.axiomlabs.hermesrelay.sideload` | **Device Control**. Adds AccessibilityService-backed screen reads, taps, typing, screenshots, app launch, clipboard/media control, phone utilities, safety rails, and unattended controls. |

## Build System

Two product flavors are defined in `app/build.gradle.kts` under the `track` dimension:

```kotlin
flavorDimensions += "track"
productFlavors {
    create("googlePlay") {
        dimension = "track"
    }
    create("sideload") {
        dimension = "track"
        applicationIdSuffix = ".sideload"
        versionNameSuffix = "-sideload"
    }
}
```

Both flavors can coexist on the same device. Their distinct application IDs
also give them completely separate Android app data: saved connections,
encrypted tokens and keysets, Dashboard cookies, selected profiles and
sessions, and composer drafts do not cross between the Play and sideload apps.
A working sideload install therefore does not validate or repair the Play
install's saved connection state, and vice versa.

## Source Set Layout

```
app/src/
├── main/              # Shared UI, chat, voice, relay, notification companion
├── googlePlay/
│   ├── AndroidManifest.xml          # Documents the Bridge Core-only Play track
│   └── kotlin/.../voice/
│       ├── VoiceBridgeIntentHandlerImpl.kt   # NoopVoiceBridgeIntentHandler
│       └── VoiceBridgeIntentFactory.kt
└── sideload/
    ├── AndroidManifest.xml          # Adds Device Control permissions/services
    ├── kotlin/.../voice/
    │   ├── VoiceIntentClassifier.kt
    │   ├── VoiceBridgeIntentHandlerImpl.kt
    │   └── VoiceBridgeIntentFactory.kt
    └── res/
        ├── xml/accessibility_service_config.xml
        └── values/strings.xml
```

The `VoiceBridgeIntentHandler` interface lives in `main/`; each flavor provides the factory implementation.

## Manifest Split

### Main manifest

The shared manifest declares the app permissions and services needed by both tracks:

```
INTERNET, ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS,
POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, FOREGROUND_SERVICE_MICROPHONE
```

It also declares the optional notification companion service and `GatewayKeepAliveService`, a `specialUse` foreground service that protects user-started active turns automatically and optionally extends to idle time through "Persistent connection." It ships on **both** flavors. It does not declare the accessibility service, the Device Control bridge foreground service, wake lock, MediaProjection foreground-service type, contacts, location, SMS, or call permissions. Both tracks declare the voice-only `SYSTEM_ALERT_WINDOW` special access and microphone foreground-service permission.

### Google Play manifest

The Play manifest adds `SYSTEM_ALERT_WINDOW` for user-started Voice Overlay, removes the transitive `WAKE_LOCK`, and adds no Device Control services. The merged Play manifest should contain no `AccessibilityService`, `BIND_ACCESSIBILITY_SERVICE`, `WAKE_LOCK`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, or `BridgeForegroundService` declaration.

> **Exception — `FOREGROUND_SERVICE_SPECIAL_USE` + `GatewayKeepAliveService` _are_ in the merged Play manifest.** They protect user-started active turns and back the opt-in idle "Persistent connection" feature, which ships on both flavors via the main manifest. This means the Play build carries one `specialUse` foreground service and therefore needs a Play Console foreground-service declaration at submission (see `docs/play-store-listing.md`). The Device Control bridge's `specialUse|mediaProjection` service stays sideload-only.

### Sideload manifest

The sideload manifest adds the Device Control surface:

```
WAKE_LOCK, FOREGROUND_SERVICE, POST_NOTIFICATIONS,
SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE_SPECIAL_USE,
FOREGROUND_SERVICE_MEDIA_PROJECTION,
READ_CONTACTS, SEND_SMS, CALL_PHONE,
ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
```

It also declares `HermesAccessibilityService`, `BridgeForegroundService`, the AccessibilityService config resource, and package visibility for launchable apps.

## Bridge Status Contract

`BridgeStatusReporter` publishes a flavor-aware `/bridge/status` payload:

```json
{
  "phone_connected": true,
  "bridge": {
    "device_control_supported": false,
    "master_enabled": false,
    "accessibility_granted": false,
    "screen_capture_granted": false,
    "overlay_granted": false,
    "notification_listener_granted": true
  },
  "unattended": {
    "supported": false,
    "enabled": false
  }
}
```

On `googlePlay`, `device_control_supported=false` and `unattended.supported=false`. On `sideload`, those fields reflect the real Device Control state.

## Bridge Command Gate

Google Play still registers the bridge channel so direct route probes fail closed quickly. Harmless probes such as `/ping`, `/events`, and `/setup` can answer, but Device Control commands return `403` with `error_code: device_control_sideload_only` before any AccessibilityService-dependent code runs.

Sideload builds dispatch commands through `BridgeCommandHandler` after the usual gates: relay session grant, in-app master toggle, AccessibilityService binding, optional MediaProjection consent, blocklist, destructive-verb confirmation, and auto-disable timing.

## UI Differences

| Surface | googlePlay | sideload |
|---------|------------|----------|
| Bridge tab | `BridgeCoreScreen`: relay status and links to Connections, Terminal, Voice, Notification Companion, Media, Relay Sessions | `BridgeScreen`: Device Control permissions, master toggle, activity log, safety rails |
| Settings | No Bridge Safety row | Bridge Safety row visible |
| Voice bridge intents | `NoopVoiceBridgeIntentHandler`: utterances go to chat | `RealVoiceBridgeIntentHandler`: recognized phone-control intents dispatch locally |
| Screen capture request | Not installed | Installed for MediaProjection screenshot flow |
| Unattended access | Not available | Available after explicit sideload opt-in |

## BuildFlavor Object

Compile-time flavor gating in Kotlin code uses `BuildFlavor` in `data/FeatureFlags.kt`. All bridge tiers now map to sideload-only Device Control:

```kotlin
val isSideload: Boolean get() = current == SIDELOAD
val bridgeTier1: Boolean get() = current == SIDELOAD
val bridgeTier2: Boolean get() = current == SIDELOAD
val bridgeTier3: Boolean get() = current == SIDELOAD
val bridgeTier4: Boolean get() = current == SIDELOAD
val bridgeTier5: Boolean get() = current == SIDELOAD
val bridgeTier6: Boolean get() = current == SIDELOAD
```

## Plugin Tool Visibility

The server-side `android_*` Device Control tools are registered with a runtime requirement check. Tools other than `android_setup` are visible only when `/bridge/status` reports both `phone_connected=true` and `bridge.device_control_supported=true`.

That means a connected Google Play Bridge Core phone can still show up in relay status and use non-device-control features, but the agent will not see phone-control tools for it.

## Voice presentation is independent of Device Control

Both flavors support in-app Voice Focus and optional voice-only system Overlay.
`BuildFlavor.voiceSystemOverlay` gates presentation independently of the sideload
bridge tiers. The overlay cannot dispatch phone-control commands on Play.

Voice Focus explains microphone, notification and display-over-other-apps access
before opening Android Settings. Returning from Settings never starts a session;
the user taps Start voice overlay while the app is visible and unlocked. A
non-exported microphone FGS must be ready before the window appears. Stop voice,
screen lock, task removal, failed startup and permission loss end the session.
Returning to Hermes retains protection until the Activity resumes. No overlay
session survives process death or starts from boot, a Relay request or wake word.

The selected Android Digital Assistant can separately receive bounded text and an
available screenshot for an explicit compatible unlocked invocation. This uses
platform Assistant APIs, not AccessibilityService or MediaProjection. User-shared
files and images likewise remain available in both builds.

`scripts/check-android-capabilities.py` validates the source split and, with
`--variant googlePlayDebug` or `googlePlayRelease`, the merged manifest. CI builds
and Play preflight check the merged Play artifact's manifest for forbidden Device
Control permissions and services while requiring the voice overlay capabilities.
