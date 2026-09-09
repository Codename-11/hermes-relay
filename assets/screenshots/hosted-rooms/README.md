# Hosted-room UI evidence

These images are fixture-based UI evidence, not a production deployment or a physical-phone acceptance claim.

The dark history/action captures come from the managed API 36 Android emulator (`HostedRoomHistoryInstrumentedTest`). The light shared-read/fallback captures come from production Compose screenshot tests (`HostedRoomScreenshotTest`). Capture source: `28b9d879b776d45fcbaf07dda7c29ed9218d906f`, before replay onto the newer `dev` build toolchain. All 11 tracked `HostedRoom*.kt` production/test files are byte-identical between that pinned capture source and the submitted `7f4da68ee1c1a7cc5156f55aa7983da2160d1a88` source. This does not relabel the images as fresh current-toolchain renders.

| State | Evidence |
| --- | --- |
| Canonical history search | [Search](search-dark-api36.png) |
| Exact-message edit | [Edit](edit-dark-api36.png) |
| Canonical reaction present | [Reaction](reaction-present-dark-api36.png) |
| Revision drift blocks mutation | [Conflict](revision-conflict-api36.png) |
| Shared read cursor | [Shared read](shared-read.png) |
| Gateway lacks shared reads/responder service | [Unavailable capability](read-unavailable.png) |

The fixtures use synthetic room/member identities. No production account data is included.

Reproduce the relevant checks from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android-lane.ps1 gradle :app:testSideloadDebugUnitTest --tests '*HostedRoomScreenshotTest*' :app:testGooglePlayDebugUnitTest --tests '*HostedRoomScreenshotTest*' --console=plain
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android-lane.ps1 gradle :app:standardPhoneApi36SideloadDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.hermesandroid.relay.viewmodel.HostedRoomHistoryInstrumentedTest,com.hermesandroid.relay.viewmodel.HostedRoomInstrumentedTest' --console=plain
```

The first command executes screenshot tests; use the repository's Roborazzi recording configuration when deliberately regenerating images. No live model/provider call is required for these fixtures.
