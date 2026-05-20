# Flavor Differences

Hermes-Relay ships as two product flavors from the same codebase: **googlePlay** and **sideload**. Both share chat, profiles, voice, relay pairing, terminal/TUI relay, media handoff, notification companion, sessions, and status. They diverge on Device Control.

## Track model

| Flavor | Install ID | Bridge scope |
|--------|------------|--------------|
| `googlePlay` | `com.axiomlabs.hermesrelay` | **Bridge Core** only. No AccessibilityService, screen reading, gestures, screenshots, SMS/call/contact/location access, overlays, wake-lock Device Control, or unattended control. |
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

Both flavors can coexist on the same device.

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

The shared manifest declares only the app permissions and services needed by both tracks:

```
INTERNET, ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS
```

It also declares the optional notification companion service. It does not declare the accessibility service, Device Control foreground service, overlay permission, wake lock, MediaProjection foreground-service type, contacts, location, SMS, or call permissions.

### Google Play manifest

The Play manifest does not add Device Control permissions or services. The merged Play manifest should contain no `AccessibilityService`, `BIND_ACCESSIBILITY_SERVICE`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, or `BridgeForegroundService` declaration.

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
