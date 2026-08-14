# Desktop tray subprocess containment audit

**Date:** 2026-08-14

**Surface:** Windows Hermes-Relay CLI UI and its local CLI probes

**Severity:** High — local resource exhaustion and loss of desktop availability

## Summary

The Windows tray created a hidden grant window at application startup. That
window requested the complete management snapshot once per second, including
while it was not visible. Its polling loop did not prevent a second request from
starting while the previous request was still pending.

Building one complete snapshot launched multiple external programs in sequence:

- `hermes-relay.exe hosts list --json`
- `hermes-relay.exe daemon status --json`
- `hermes-relay.exe grants --json`
- `hermes-relay.exe --version`
- two `reg.exe query` probes
- `adb version`

The host-list fallback could add another CLI launch. The main management window
also refreshed its snapshot every five seconds and more frequently during a
connection transition.

Under normal conditions these children exited quickly. When process startup or
one probe slowed, the hidden-window timer continued submitting work. Concurrent
snapshot tasks then accumulated, each owning or waiting to launch another set of
children. The polling path turned a transient slowdown into an unbounded local
process queue.

## Observed evidence

The reported machine showed many processes named `Bun` and
`hermes-relay.exe`, followed by Windows `0xc0000142` application-initialization
dialogs for `reg.exe`, `adb.exe`, and `hermes-relay.exe`. Windows Application
events recorded several clustered `hermes-relay.exe` failures in
`KERNELBASE.dll`. The local desktop audit then recorded a PowerShell
`System.OutOfMemoryException`.

Failures across several unrelated executables, together with the process backlog
and out-of-memory evidence, support resource exhaustion as the cause of the
application-initialization failures. They do not prove the root cause of a later
Windows stop or reboot. Attributing a blue-screen or kernel stop conclusively
requires the matching Windows crash dump and stop-code analysis.

## Corrective design

The tray process contract now applies these invariants:

1. **One refresh in flight.** Snapshot work is single-flight and coalesced across
   callers. A timer tick never queues a duplicate refresh.
2. **No full snapshot for grant discovery.** The grant window reads the bounded
   local grant bridge or receives a native event. It does not launch CLI,
   registry, or ADB probes merely to decide whether a card should appear.
3. **Static probes stay static.** CLI path/version, sign-in settings, and optional
   hardware availability are cached and invalidated by their owning actions or
   an explicit refresh.
4. **Every child is bounded.** External commands have a timeout, termination
   path, and bounded output capture. Failed probes back off instead of retrying
   at the normal polling rate.
5. **Failure degrades one field.** A missing optional probe does not discard the
   complete snapshot or make the tray unavailable.
6. **Operational failures are visible.** The tray records sanitized lifecycle,
   snapshot, timeout, launch, exit, truncation, and panic events in the bounded
   local `~/.hermes/tray.log`. It never writes credentials or sensitive command
   arguments. The separate `~/.hermes/daemon.log` remains the source for daemon
   authentication, transport, reconnect, and tool-router lifecycle.
7. **No broad process cleanup.** Recovery targets only reviewed processes whose
   executable path belongs to the installed Hermes-Relay bundle. A process name
   such as `Bun`, `adb.exe`, or `reg.exe` is not sufficient ownership evidence.

## Safe operator recovery

1. Quit **Hermes-Relay CLI UI** so it cannot create more probes.
2. Run `hermes-relay daemon stop` from a fresh PowerShell when the CLI can start.
3. If it cannot start, enumerate `Win32_Process` and review `ExecutablePath` and
   `CommandLine`. Stop only `hermes-relay.exe` and
   `hermes-relay-tray.exe` rows under `%USERPROFILE%\.hermes\bin`.
4. Do not terminate every process named `Bun`; other tools may use that runtime.
5. Inspect `~/.hermes/tray.log` and `~/.hermes/daemon.log` according to their
   ownership above.
6. If unrelated applications still fail to initialize, restart Windows before
   starting the tray again.

The copyable Windows commands live in
[`user-docs/desktop/troubleshooting.md`](../../user-docs/desktop/troubleshooting.md).

## Verification gates

Automated checks cover the following containment contracts:

- The grant window invokes only the local pending-grant context, schedules its
  next poll after completion, pauses while hidden and idle, and cannot overlap
  itself.
- The management window polls only while visible, permits one refresh in
  flight, retries failures with exponential backoff, and uses the native
  coalesced snapshot builder.
- The process runner bounds stdout and stderr, times out a hung child, reaps a
  spawned descendant through its Windows Job Object, and preserves the explicit
  direct-child mode needed by a detached daemon launcher.
- Static CLI-version, registry, and ADB probes are cached; tray-launched CLI
  commands bypass the Bun system-CA self-relauncher.
- The bounded tray log contains only allowlisted event/probe names and numeric
  process metadata, while rotation prevents unlimited growth.
- Concurrent daemon lifecycle requests elect one owner and dead lock owners are
  recovered without allowing an older owner to remove a replacement lock.
- Existing CLI, tray, daemon, grant, update, and installer tests remain green.

Packaged Windows verification should additionally cover hidden and visible tray
states, an active connection transition, and a pending grant while recording a
bounded child-process high-water mark. Fault injection should hold a probe open
past its timeout and verify that process count returns to baseline rather than
increasing on every timer tick.

These checks establish application-level containment. They do not substitute
for Windows dump analysis when the operating system itself reports a stop.
