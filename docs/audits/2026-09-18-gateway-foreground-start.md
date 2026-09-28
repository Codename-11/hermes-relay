# Gateway foreground-service startup (#603)

## Confirmed defect and limits

Issue #603 reports `ForegroundServiceDidNotStartInTimeException` naming
`GatewayKeepAliveService` on Android 12/API 31. The report contains no preceding
lifecycle log and no comments. It does not establish an OEM-specific cause or
implicate the voice overlay.

Before this fix, demand could call `startForegroundService()` and then
`stopService()` before Android delivered `onCreate` or `onStartCommand`.
Android 12's `ActiveServices.bringDownServiceLocked` explicitly schedules a
foreground-service crash when teardown encounters `fgRequired`. Promotion in
`onStartCommand` alone therefore did not protect immediate cancellation.

Three focused API 31 regressions failed against the previous implementation:
immediate stop called `stopService` with an outstanding start; creation had no
foreground notification; and a queued persistent-only start overwrote newer
active/waiting-turn notification state. These establish client defects, not the
precise callback sequence on the reporting device.

## Lifecycle audit

| Path | Resulting contract |
| --- | --- |
| Controller | Main-thread collection combines the persistent preference, scoped turn leases, and process visibility. Socket-retention demand remains separate from service-launch eligibility. |
| First start | One pending token; channel creation, notification construction, and promotion happen synchronously in `onCreate`, without coroutine, datastore, or network work first. |
| Start then stop / overlapping demands | Coalesce current demand until the start command promotes and acknowledges its obligation. No `stopService` cancellation of pending starts. |
| Running updates | Apply current demand only to the live owner. Clear that owner before requesting teardown; a late old `onDestroy` cannot clear a replacement. |
| Delivered stale start / process loss | Promote a delivered foreground start, but never restore its old demand. A cold stale notification action does not restore idle retention. `START_NOT_STICKY` remains in effect. |
| Settlement / session switch | Existing connection/profile/session leases settle independently. This service sends no interrupt, resume, activate, or transport-routing command. |
| Always-on notification action | Identity includes the current enable cycle. Preference persistence outlives service teardown and rechecks its token inside the edit. Current turn demand determines eventual shutdown. Write failure leaves the preference and notification truthful. |
| Background eligibility | Do not request a new service from a known background lifecycle. Existing protection continues. Launch rejection is logged; a later visible transition retries remaining demand. |
| Task removal | Release local foreground protection without deleting chat-owned leases or assuming the process dies. Suppress restart until a new visible lifecycle transition. |
| Startup failure | Channel/build/promotion exceptions retire the unusable instance and clear pending launch state. They never cancel a server-owned turn. Platform-level asynchronous failures cannot be converted into successful foreground protection. |
| Configuration change | Only the current live owner refreshes the notification. |
| Notification/type policy | Existing low-importance channel, immutable intents, non-exported service, and both-flavor `specialUse` declaration remain. API 34+ uses the declared type/permission; API 31 uses the two-argument promotion. No permissions or dependencies added. |

The Gateway protocol, recovery/history contract, and session owner are unchanged,
so no new Gateway fixture scenario or upstream-conformance requirement is
introduced. The additional instrumentation exercises Android ActivityManager
and notification PendingIntent delivery without a server.

## Verification scope

`GatewayKeepAliveServiceTest` exercises API 31 and 35, including startup,
cancellation, failure, stale generations, notification persistence, task removal,
and scoped sibling settlement. It and `ActiveTurnKeepAliveRegistryTest` are in
both-flavor focused verification. `GatewayKeepAliveServiceInstrumentedTest`
targets the Standard Phone API 36 lane and observes the real platform watchdog
after rapid starts/stops, plus notification actions crossed by new turn demand.
Exact execution results belong to the PR's verification section.

Huawei firmware, physical-device behavior, and unrelated main-thread stalls
remain unverified. The change does not claim that every possible foreground-start
timeout has the same cause.

## Android sources

- [Android 12 ActiveServices](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/am/ActiveServices.java): `bringDownServiceLocked`, `setServiceForegroundInnerLocked`, and `serviceForegroundTimeout`.
- [Foreground-service troubleshooting](https://developer.android.com/develop/background-work/services/fgs/troubleshooting): startup timeout versus background-start rejection.
- [Launching a foreground service](https://developer.android.com/develop/background-work/services/fgs/launch): prompt promotion, notification priority, and API 34 type prerequisites.
- [Background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): API 31 restrictions and user-interaction exceptions.
