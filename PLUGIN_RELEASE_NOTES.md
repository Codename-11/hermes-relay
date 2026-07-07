# Hermes-Relay-Plugin v__VERSION__

**Release Date:** July 6, 2026
**Since the previous plugin release:** The Realtime Agent learns to multitask — long Hermes tasks hand off to the background while the conversation continues, results survive disconnects and are delivered when the phone comes back (or as a proactive notification), and spoken progress is milestone-based instead of a timer. Plus a typed chat stream for desktop clients.

Pairs with Hermes-Relay-Android v1.3.0, which ships the matching live progress chip and detach-on-exit behavior. Provider-native voice turns and vanilla upstream (no plugin) are unaffected.

## What's changed

### Added
- **Background runs that finish what they started (ADR 33 hardening).** A detached voice session now stays alive while a background Hermes run is in flight (instead of expiring on the 30-second resume window); a finished result found with no phone attached is held and injected on the next resume, and if the session is gone for good it falls back to a proactive notification. Runs that exceed the cap are stopped cleanly and say so.
- **Adaptive promotion.** Clearly long-running tools (cron, desktop, browser work) hand the task to the background immediately instead of waiting out the full grace window — with a short quick-finish window so fast calls stay inline.
- **Busy answer for a second task.** Asking for another task while one is running gets an explicit "still working on the earlier task" answer (wait, check status, or cancel) instead of silently orphaning the first run.
- **Typed chat stream passthrough.** The relay `chat` channel can emit structured `stream.event` envelopes (assistant deltas, tool lifecycle, artifacts, completion) for desktop/CLI consumers that advertise the capability.

### Changed
- **Milestone speech, not timer narration.** The periodic spoken status updates during a long task are off by default — the agent speaks when a task starts in the background, finishes, or fails; the client chip covers the in-between. `realtime_voice_progress_spoken_after_ms` restores timed narration if you prefer it.
- **Live progress metadata.** `hermes.run.progress` events carry the active tool, completed-step count, and elapsed time, which drive the Android app's live chip.

### Fixed
- **A benign provider cancel-notice no longer kills a live voice turn.** xAI's "cancellation failed: no active response found" was treated as fatal and closed the session right as the answer was about to be spoken — it's now filtered, and needless cancels are floor-gated so they aren't sent in the first place.
- **Provider sockets ride out idle stretches.** Realtime provider WebSockets use protocol-level heartbeats instead of a total-connection timeout, so long silent tool phases no longer sever the provider leg.

## Install / update

```bash
# Classic install / update on a systemd host (recommended):
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
# or, if already installed:
hermes-relay-update
```

> **Known issue:** the native `hermes plugins install` path currently breaks
> `hermes relay start` (#165, `ModuleNotFoundError: No module named 'plugin'`).
> The fix ships in the next plugin release — use the classic installer until then.

## Verify

```bash
hermes relay doctor
```

---

Tag prefixes: Android releases use `android-v*`, CLI releases use `cli-v*`. Historical
relay/plugin releases used `relay-v*` tags.
