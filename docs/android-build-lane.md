# Android build execution

Hermes-Relay prefers isolated GitHub-hosted runners for heavy Android
verification after an exact commit is pushed. Windows retains one machine-wide
local Android build lane for narrow feedback, explicit full-local verification,
device work, and cloud outages. Every local worktree shares the same named
operating-system mutex through
`scripts/android-lane.ps1`. When commands use the wrapper, Gradle, Kotlin
compilation, lint, tests, assemblies, connected tests, and APK installation do
not compete for the same host resources and shared caches.

## Cloud-preferred verification

`Android On-Demand` accepts a full 40-character pushed commit SHA and one of
five presets:

| Preset | Work |
|---|---|
| `focused` | Repository checks plus focused sideload and Google Play unit tests |
| `lint` | Repository checks plus full Android lint |
| `assemble-debug` | Both debug flavors, native compatibility scan, APK artifacts |
| `release-smoke` | Both release flavors, DEX/native scans, APK/AAB artifacts |
| `all-final` | All four compute jobs concurrently on isolated runners |

Check recent runs before dispatching so another task does not duplicate the
same SHA and preset:

```powershell
gh run list --workflow android-on-demand.yml --event workflow_dispatch --limit 20
$sha = git rev-parse HEAD
gh workflow run android-on-demand.yml --ref dev -f head_sha=$sha -f preset=focused
gh run list --workflow android-on-demand.yml --event workflow_dispatch --limit 5
```

The SHA must already exist on GitHub. Do not push solely to obtain cloud compute
without push authorization. On-demand jobs read shared Gradle cache state but
do not write it, so task commits cannot replace the cache populated by trusted
`dev`/`main` CI. The on-demand result supplements rather than replaces required
PR checks.

## Local use

The Windows `scripts/dev.bat` commands and `scripts/android-prepush.py` acquire
the lane automatically. For an ad hoc Gradle command, use:

```powershell
.\scripts\android-lane.ps1 gradle `
  :app:testSideloadDebugUnitTest `
  --tests "*ChatViewModelTest*" `
  --console=plain
```

The wrapper waits until the current owner exits and releases the mutex. Windows
also releases the mutex if its owner crashes; the next waiter reports that it
recovered an abandoned lane. Use Ctrl+C to cancel a waiter without stopping the
owner. A bounded wait is available when a caller has its own deadline:

```powershell
.\scripts\android-lane.ps1 --timeout-seconds 900 gradle :app:lintSideloadDebug
```

Check the lane without starting work:

```powershell
.\scripts\android-lane.ps1 status
```

`status` exits 0 when idle and 1 when busy. It intentionally does not expose the
owning process's arguments because Gradle properties can contain credentials.

Use `exec` for a connected/device workflow that must exclude every
wrapper-managed Gradle lane, including explicit APK installation:

```powershell
.\scripts\android-lane.ps1 exec python scripts/android-gateway-certify.py <arguments>
.\scripts\android-lane.ps1 exec adb -t <transport-id> install -r <apk>
```

Raw `gradlew`, Android Studio sync/build/run, and raw `adb install` do not pass
through the mutex. Before using one of those paths, check that the lane is idle
and keep all queued wrappers idle until it finishes. During final integration,
close or pause Android Studio's automatic Gradle activity and let one
coordinator own the sequence.

## Final coordinator gate

For an exact candidate commit that is already pushed, the coordinator normally
dispatches `all-final`. Focused tests, lint, debug assemblies, and release smoke
may run concurrently because each owns an isolated GitHub-hosted runner. After
all selected cloud jobs and required PR checks pass against that exact SHA, the
coordinator runs any required connected/instrumentation or physical-device
checks locally, then performs the explicit APK install.

Full local verification remains available when explicitly wanted or when cloud
execution is unavailable. In that mode, keep the following sequence serial on
the machine-wide lane:

```powershell
scripts\dev.bat prepush
```

That command runs repository checks, full Android lint, and both focused flavor
shards in one local Gradle invocation. The full local sequence remains:

1. Focused sideload unit tests.
2. Focused Google Play unit and policy tests.
3. Android lint.
4. Sideload and Google Play assemblies (or the release-equivalent both-flavor gate).
5. Connected/instrumentation and physical-device checks when the change needs them.
6. The explicit APK install only after every preceding gate passes.

Do not run these heavy gates in parallel on one local host. A timeout from an
outer agent or terminal is not evidence that Gradle failed; use the wrapper's
process exit and Gradle output as the result. Cloud concurrency is safe because
the jobs do not share a daemon, Gradle user home, project cache, or device.

## Resource policy

The normal serialized path retains the repository defaults:

- the shared Gradle user home, wrapper distributions, dependency cache, build
  cache, configuration cache, and compatible warm daemon;
- `org.gradle.jvmargs=-Xmx4g`, `org.gradle.daemon=true`,
  `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, and
  `org.gradle.parallel=true`;
- Gradle's normal worker selection inside the one active invocation.

`--max-workers` limits workers only inside one Gradle invocation. It does not
prevent another worktree, Android Studio, or an APK-install lane from running.
Likewise, `--no-daemon` can still start a single-use daemon to honor the required
JVM settings; it is not a cross-process isolation mechanism.

Use a worktree-specific `GRADLE_USER_HOME`, `--no-build-cache`,
`--max-workers=1`, or `-Pkotlin.compiler.execution.strategy=in-process` only to
recover from a confirmed daemon/cache failure or for an explicitly isolated
exception. Those modes discard normal cache and warm-daemon benefits, can use
more disk and memory, and must still acquire the Android lane.

If recovery is required, first preserve the failing output and verify that no
other owner is active. Do not kill unrelated Java, Gradle, Kotlin, Android
Studio, or ADB processes merely to clear the lane; an OS-abandoned mutex is
recoverable automatically.
