# Android reliability and support audit

**Date:** 2026-08-04
**Scope:** Android fatal crashes, handled failures, diagnostics, symbolication,
privacy, recovery, and user-initiated support submission.

## Executive summary

Hermes-Relay already has the right product posture: crash capture is local,
reports are never uploaded automatically, the platform crash handler still runs,
and users can copy, share, or open GitHub themselves. The implementation is not
yet one reliability system, however. Fatal crashes use a private one-file JSON
format, handled errors use an in-memory diagnostics ring, issue builders duplicate
environment and truncation rules, and release mappings are not retained outside
the transient build workspace. The UI therefore asks users to review developer
traces without enough interaction context while maintainers receive obfuscated or
truncated reports that cannot always be retraced.

The foundational change should align those seams without adding telemetry:

1. use one allowlisted, versioned reliability event contract for fatal and handled
   failures;
2. redact locally before persistence, display, copy, share, or issue prefill;
3. retain a small deterministic local history and build a support export entirely
   on device;
4. make the recovery UI lead with outcome and next steps, with technical detail
   behind an explicit review action;
5. retain release mapping files outside public release assets by immutable
   version/SHA and make Android's own issue prefills request the Android area.

No automatic upload, analytics SDK, remote crash service, prompt/message capture,
or background logcat collection is justified.

## Current end-to-end architecture

### Fatal crashes

- `HermesRelayApp.onCreate()` installs `CrashReporter` before other app setup.
- The process-wide uncaught-exception handler synchronously writes
  `files/crash/last-crash.json`, then delegates to Android's previous handler.
  This preserves the platform crash path and Google Play Android vitals.
- The next main-app launch displays `CrashReportGate`. The report remains until
  the user dismisses, shares, or opens GitHub; copy alone does not acknowledge it.
- The persisted record contains time, version/code/flavor, manufacturer/model,
  Android release/API, thread, exception summary, and the full Java trace.
- GitHub prefill truncates the trace to 3,000 characters because it is encoded in
  a browser URL. Copy/share use the full persisted trace.

Failure modes:

- the raw exception message and trace are written before privacy redaction;
- a single non-atomic file means a second crash replaces the first and an
  interrupted write can leave no readable report;
- the format has no explicit schema version, retention period, size bound, report
  identifier, or migration contract;
- the primary recovery experience is a large stack trace rather than what
  happened, what recovered, and what the user can do;
- a crash in a secondary process can compete for the same file;
- fatal OOM may leave too little memory for serialization or file I/O.

### Handled errors and coroutine failures

- `classifyError()` maps common network, auth, HTTP, SSL, permission, and voice
  failures to `HumanError`, then records every non-null throwable in
  `DiagnosticsLog`.
- `DiagnosticsLog` is an in-memory 200-entry ring. List fields are capped at 180
  characters and error traces at 8,000 characters. It is cleared on process death.
- Network ownership is partially represented by `DiagnosticCategory` (`API`,
  `Relay`, `Session`, `Voice`, `Route`, `Auth`), but `send_message` defaults to
  API even when Gateway owns the turn. Dashboard and upstream Gateway do not have
  first-class categories.
- Multiple long-lived `SupervisorJob` scopes exist in services, clients, and the
  process runtime. There is no process-wide `CoroutineExceptionHandler`; failures
  are visible only where a caller catches/classifies them or where they become
  uncaught exceptions.
- Expected cancellation and user denial are not a first-class taxonomy. A caught
  cancellation passed to `classifyError()` can appear as an error and become
  reportable noise.

### Connectivity, auth, HTTP, WebSocket, and voice

- Typed DNS, connection-refused, timeout, TLS, permission, and generic I/O
  failures receive humane copy and retry/repair hints.
- HTTP status handling is message-based. 401/403/404/413/500/503 have distinct
  outcomes, but rate limiting is not first-class and ownership depends on a small
  caller-provided context string.
- Gateway, Dashboard, API fallback, optional Relay, and Voice have separate live
  checks in Diagnostics, but the captured error data contract cannot represent
  all five owners precisely.
- `isConnectivityError()` lets startup UI avoid duplicate scary snackbars, but
  those failures are still recorded as generic errors.

### OOM and ANR boundaries

- OOM is only captured if the uncaught handler has enough memory and storage to
  finish. The current reporter allocates a `StringWriter` containing the complete
  trace, which is specifically fragile during OOM.
- ANRs do not throw through the uncaught handler. Google Play can observe Play
  builds, while sideload builds currently have no local ANR signal.
- Adding a main-thread watchdog immediately would introduce false positives during
  debugger pauses, device sleep, startup, and legitimate long frames. It belongs
  in a later opt-in/bounded phase after lifecycle-aware design and device testing.

### Diagnostics, breadcrumbs, and correlation

- Diagnostics has read-only subsystem checks plus the in-memory activity ring.
- Relay envelopes and some bridge/TUI paths have request IDs, and chat has durable
  session/run/message IDs. These identifiers are not joined to crash reports.
- Raw session, profile, and connection identifiers may themselves be sensitive.
  The safe foundation is a new random per-launch app-session ID and per-event
  report ID. Product/session IDs should only be added later as short local hashes
  after a demonstrated diagnostic need.
- There is no bounded breadcrumb contract. Capturing arbitrary diagnostic detail
  would risk prompts, message text, media paths, profile names, and host data.

### R8, ProGuard, and symbolication

- Release builds enable R8 and preserve `SourceFile`/`LineNumberTable`; source file
  names are normalized to `SourceFile`.
- AGP writes `mapping.txt` under `app/build/outputs/mapping/<variant>/`. The file
  is overwritten by subsequent builds.
- The Play AAB contains its mapping, so Play reports can be deobfuscated in Play
  Console. Sideload reports need the exact locally retained variant mapping.
- The release workflow publishes the sideload APK and Play AAB but does not retain
  either release mapping as a versioned workflow artifact. A GitHub issue containing
  a sideload trace therefore may be practically irretrievable after the runner is
  gone.
- Maintainer procedure should be deterministic:
  `retrace <mapping-for-exact-version-and-flavor> <trace-file>`.

### Recent-report evidence

- #289 (`1.6.0`, Play) contains a `NoSuchElementException` trace with application
  frames reduced to names such as `gn5.g(SourceFile:2)` and is truncated mid-frame.
  The interaction that preceded the crash is absent.
- #292 (`1.6.0-sideload`) contains a duplicate Compose lazy-list key but only
  obfuscated application frames and a trace truncated by URL limits.
- #298 (`1.6.1`, Play) shows the same duplicate-key class on another device, again
  with obfuscated application frames. There is no route/session/interaction
  context to distinguish the owning list.
- #299 (`1.6.1`, Play) provides the missing human context—Focus-mode controls
  animate but taps usually do not complete—but has no diagnostic event, app-session
  correlation, or technical trail.
- All four Android issues received `area:plugin`. The former issue-triage workflow
  tested broad `relay|plugin|...` keywords before Android terms, so the
  repository/app name won before `Android app`, device, Compose, or voice context
  was considered. Current `dev` has since retired that unreliable keyword
  labeler in favor of maintainer review; it should not be reintroduced.

The reports demonstrate both halves of the gap: traces without interaction context
and interaction context without a safe technical trail.

## Privacy threat review

The following must never be collected by the reliability contract:

- authentication headers, cookies, API keys, Relay/session/pairing tokens, OAuth
  codes, or signed URLs;
- prompt, response, transcript, reasoning, tool arguments/results, or notification
  content;
- real hostnames, IP addresses, full URLs, SSIDs, proxy routes, or private
  infrastructure names;
- profile/agent/person names, raw connection/session/run/message IDs, contacts, or
  account identifiers;
- local/media/workspace paths, attachment names, clipboard contents, screenshots,
  audio, or camera data.

The contract should allow only enumerated owner/kind/status values, version/device
metadata, random local correlation IDs, bounded redacted summaries/traces, route
roles (for example `lan` or `public TLS`, never the host), and bounded allowlisted
breadcrumbs with no arbitrary payload.

Redaction is defense in depth, not permission to collect prohibited fields. It
must run before disk persistence and again when rendering/exporting legacy data.

## Shared taxonomy and data contract

### Kinds

| Kind | Persistence / UI policy |
|---|---|
| Fatal crash | Persist synchronously; show recovery once; reporting is useful |
| ANR/watchdog signal | Contract reserved; later lifecycle-aware implementation |
| Recoverable product error | Persist bounded history; show owned recovery action |
| Connectivity | Low-noise; retry/offline guidance; do not nag for reports |
| Authentication | Name owning surface; repair/sign-in guidance |
| Rate limit | Show retry timing when safely known; do not report by default |
| Service unavailable | Retry guidance; report only if repeated/unexpected |
| Expected cancellation | Do not persist or offer reporting |
| User denial | Do not persist or offer reporting; explain how to change permission |

### Owners

`Android`, `Dashboard`, `API`, `Relay`, `Upstream Gateway`, `Voice`, and `Unknown`.
Standard Dashboard/Gateway/API ownership remains upstream; Relay is optional and
must never be presented as required for standard recovery.

### Versioned record

Each record contains: schema version, random report ID, random app-session ID,
timestamp, kind, owner, severity, humane summary, recovery outcome, whether a
report is recommended, bounded redacted technical detail, and allowlisted app /
OS / device / flavor metadata. Optional context contains route role and bounded
allowlisted breadcrumbs only.

## UX recommendation

### Post-crash recovery

Lead with “Hermes-Relay restarted after an unexpected problem.” Explain that work
on the Hermes server may still be running, no report was sent, and the user can
continue. Technical information stays collapsed behind “Review report.” Copy,
share, and GitHub use the same reviewed redacted payload. Dismiss remains the
lowest-friction path and never nags again for that event.

### Inline handled errors

Use owner + outcome + action: “Dashboard sign-in expired — chat can use API
fallback” or “Relay unavailable — standard Chat and Manage are unaffected.” Do not
offer reporting for connectivity, expected cancellation, user denial, or a missing
optional Relay feature. Keep report actions in Diagnostics rather than snackbars.

### Settings / About / Diagnostics

Diagnostics should expose “Review support information,” showing exactly the
bounded text that copy/share will receive. It should work offline and include no
new probe. About should continue to show version/flavor; duplicating export entry
points there is unnecessary in the foundation.

### Accessibility and localization

The crash dialog must support narrow/foldable layouts, scrolling, screen-reader
labels, large text, and an explicit technical-detail toggle. All new visible copy
must use resources across supported locales. Clipboard/share/browser absence must
fall back without losing the local report.

## Phased plan

### Immediate coherent foundation

- Add the versioned taxonomy/record and centralized local redactor.
- Persist fatal and centrally classified handled failures in a bounded atomic
  local store; migrate the legacy one-file crash record.
- Suppress persistence/report prompting for expected cancellation and user denial.
- Build crash/support text and GitHub prefill from the same redacted contract.
- Add explicit review-before-sharing UI and a Diagnostics support export.
- Route Android crash and Diagnostics issue prefills to `area:android`; keep the
  unreliable repository-wide keyword labeler disabled.
- Retain Play and sideload `mapping.txt` files as non-release version/SHA workflow
  artifacts and document retrace.
- Add focused privacy, bounds, classification, migration, issue-prefill, and UI
  state tests.

### Later, evidence-gated follow-up

- Lifecycle-aware ANR watchdog with debugger/sleep/startup suppression and device
  false-positive testing.
- Strict allowlisted breadcrumbs at high-value transitions (screen/feature owner,
  route transition, retry outcome), never user content.
- Hashed product correlation IDs only where a concrete diagnosis requires them.
- OOM emergency record preallocation / minimal writer.
- Broader structured error adoption at WebSocket, coroutine-scope, and service
  boundaries that currently bypass `classifyError()`.
- Maintainer tooling that downloads the exact release mapping and runs retrace from
  a report's version/code/flavor tuple.

Automatic telemetry, remote upload, full logcat collection, prompt/transcript
capture, and third-party crash SDKs remain out of scope unless separately proposed
and approved.
