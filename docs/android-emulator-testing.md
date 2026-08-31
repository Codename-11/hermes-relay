# Android emulator testing

Hermes-Relay Android uses individually selected Gradle Managed Devices for
repeatable, on-demand instrumentation. The routine virtual baseline is API 36.
There is deliberately no aggregate matrix task and no scheduled emulator job:
choose the smallest lane that can prove the behavior under review.

## Lanes

| Evidence lane | Gradle device | Hardware profile | Use it for |
|---|---|---|---|
| Real Device | None | Explicitly selected physical hardware | Firmware, radio, audio, camera, biometrics, background limits, accessibility, and release-candidate claims |
| Compact Phone | `compactPhoneApi36` | Pixel 2 | Narrow phone layouts, compact height, keyboard pressure |
| Standard Phone | `standardPhoneApi36` | Pixel 6 | Default functional and regression instrumentation |
| Large Phone | `largePhoneApi36` | Pixel 7 Pro | Large handset layout and reachability |
| Foldable | `foldableApi36` | Pixel Fold | Fold/unfold, posture, continuity, and width-class changes |
| Tablet | `tabletApi36` | Pixel Tablet | Expanded layout, panes, and large-window behavior |
| Future platform / native canary | `futureApi37Ps16k` | Pixel 7 Pro, API 37, forced 16 KB pages | On-demand platform and native-library compatibility only |

Routine API 36 lanes use the AOSP x86_64 image so deterministic app tests do not
spend host capacity on unrelated Google-service startup. The API 37/16 KB device
is not a screen-size lane and is not part of routine testing. Gradle Managed
Devices may download a missing image on first use; that setup can be large and
slow.

## Commands

List the registered tasks:

```powershell
.\scripts\android-lane.ps1 gradle :app:tasks --all |
  Select-String 'Api36|Ps16k'
```

Compile the app and instrumentation APK without starting an emulator:

```powershell
.\scripts\android-lane.ps1 gradle `
  :app:assembleSideloadDebug `
  :app:assembleSideloadDebugAndroidTest
```

Run one complete lane, normally Standard Phone first:

```powershell
.\scripts\android-lane.ps1 gradle `
  :app:standardPhoneApi36SideloadDebugAndroidTest
```

Run one test class on one lane:

```powershell
.\scripts\android-lane.ps1 gradle `
  :app:standardPhoneApi36SideloadDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.hermesandroid.relay.viewmodel.GatewayForegroundRecoveryInstrumentedTest'
```

Run the other virtual lanes only when their form factor is relevant:

```powershell
.\scripts\android-lane.ps1 gradle :app:compactPhoneApi36SideloadDebugAndroidTest
.\scripts\android-lane.ps1 gradle :app:largePhoneApi36SideloadDebugAndroidTest
.\scripts\android-lane.ps1 gradle :app:foldableApi36SideloadDebugAndroidTest
.\scripts\android-lane.ps1 gradle :app:tabletApi36SideloadDebugAndroidTest
```

Run the future-platform/native canary explicitly:

```powershell
.\scripts\android-lane.ps1 gradle `
  :app:futureApi37Ps16kSideloadDebugAndroidTest
```

All Windows commands use the repository's machine-wide build lane; see
[`docs/android-build-lane.md`](android-build-lane.md). Check the lane without
starting work with:

```powershell
.\scripts\android-lane.ps1 status
```

Do not invoke every device task as one command. Run lanes serially, record each
result, and stop when the relevant evidence is complete or the host reaches a
capacity limit.

## Configuration coverage

Form factor is only one axis. Select additional states according to the change:

- Test dark mode first; also cover light mode when colors, contrast, system bars,
  or theme persistence changed.
- Cover portrait and landscape when layout, keyboard, media, drawers, or panes
  changed. Foldable work must include a posture or width-class transition.
- Check default font scale and at least one enlarged scale for text-heavy or
  accessibility-sensitive UI.
- Use the default locale for functional regressions; add a long-string locale
  and an RTL locale when copy, formatting, or layout direction changed.
- Record gesture versus three-button navigation when bottom insets, edge-to-edge,
  back handling, sheets, or overlays changed.

These dimensions are selected test conditions, not permanent duplicated device
definitions. Record any non-default setting in the evidence.

## Deterministic fixtures and live servers

Embedded MockWebServer tests own deterministic transport regressions. They use
production clients and view models against loopback HTTP/WebSocket boundaries,
require no credentials, mutate no real sessions, and are the correct lane for
authentication loss, reconnect gaps, malformed frames, profile isolation, and
repeatable lifecycle assertions.

Live-server testing is separate and on demand. Use a disposable test or staging
Hermes server with disposable profiles and sessions. Normally run only the
Standard Phone emulator plus one explicitly selected real device when physical
evidence is required. Never multiply live mutation testing across the full size
matrix, use a production server, or use personal conversation data. Sanitize
logs and exports before attaching them to a pull request.

## Evidence

For each executed lane, record:

```text
Commit: <exact SHA>
Artifact/variant: sideloadDebug app + androidTest
Lane: Standard Phone (standardPhoneApi36), API 36
Test selection: <class or package>
Configuration: dark/light, orientation/posture, font scale, locale, navigation
Result: pass/fail/blocked, test count, report path
Notes: retries, emulator/image limitation, relevant sanitized observation
```

Keep claims lane-specific. Emulator proof is not physical-device proof. A
passing API 37/16 KB canary proves only that selected platform/native lane; it
does not replace API 36 form-factor coverage or physical firmware evidence.
