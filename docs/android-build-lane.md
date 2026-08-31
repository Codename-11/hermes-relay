# Windows Android build lane

Hermes-Relay uses one machine-wide Android build lane on Windows. Every local
worktree shares the same named operating-system mutex through
`scripts/android-lane.ps1`. When commands use the wrapper, Gradle, Kotlin
compilation, lint, tests, assemblies, connected tests, and APK installation do
not compete for the same host resources and shared caches.

This is a local developer workflow. CI jobs run on separate hosts and are not
affected.

## Normal use

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

Run the final gates serially on the exact candidate head. Keep each focused
test invocation narrow so it releases the lane promptly for other queued work.
The coordinator then runs, in order:

1. Focused sideload unit tests.
2. Focused Google Play unit and policy tests.
3. Android lint.
4. Sideload and Google Play assemblies (or the release-equivalent both-flavor gate).
5. Connected/instrumentation and physical-device checks when the change needs them.
6. The explicit APK install only after every preceding gate passes.

Do not run lint, both-flavor builds, connected checks, or installation in
parallel. A timeout from an outer agent or terminal is not evidence that Gradle
failed; use the wrapper's process exit and Gradle output as the result.

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
