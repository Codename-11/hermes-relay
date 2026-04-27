# Hermes Quest

Spatial SDK Quest app for the Hermes relay terminal cockpit.

## Build

From the repo root:

```powershell
$env:ANDROID_HOME='C:\Users\Bailey\AppData\Local\Android\Sdk'
.\gradlew.bat :quest:assembleDebug --console=plain
```

APK:

```text
quest\build\outputs\apk\debug\quest-debug.apk
```

Install on a connected Quest:

```powershell
adb install -r quest\build\outputs\apk\debug\quest-debug.apk
adb shell monkey -p com.axiomlabs.hermesquest.debug 1
```

## Tooling

Use Android Studio for Kotlin, Compose, Gradle, logcat, and direct install/run. Open the repo root when working across `:app`, `:relay-core`, and `:relay-ui`; open `quest/` directly when focusing on Spatial SDK or Meta Spatial Editor tasks.

Use Meta Quest Developer Hub for headset setup, ADB pairing, install/uninstall, casting, logcat, and quick device state checks.

Use Meta Spatial Editor when editing/exporting `.metaspatial`/`.glxf` scenes or using Spatial SDK hot reload:

```powershell
$env:ANDROID_HOME='C:\Users\Bailey\AppData\Local\Android\Sdk'
.\gradlew.bat :quest:export -Pquest.exportScenes=true --console=plain
.\gradlew.bat :quest:hotReload -Pquest.exportScenes=true --console=plain
```

Normal `assembleDebug` does not require Meta Spatial Editor. The app creates a programmatic spatial terminal panel when exported GLXF scene assets are absent.

## Preview Boundaries

Desktop preview is useful for Compose UI iteration and Meta Spatial Editor scene layout. It is not a faithful Quest 3 passthrough, headset-camera, controller-ray, hand-tracking, or Bluetooth-keyboard validation environment.

Use the headset for final checks:

- Passthrough and environment depth
- QR scanning via Quest camera
- Controller focus and system keyboard
- Bluetooth keyboard input
- Panel grab/resize comfort
- Relay pairing and tmux persistence
