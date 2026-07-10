# Hermes-Relay-Plugin v__VERSION__

**Release Date:** July 9, 2026

**Since v1.3.0:** Realtime Agent background work gains queued long requests, quick side-session answers, deterministic exact xAI delivery, stronger resume ownership, and a delivery-health report. The plugin now installs through upstream Hermes' native plugin path, handles modern virtual-environment layouts, targets multiple Android devices, protects credential paths in media delivery, and adds sharper doctor checks.

Pairs with Hermes-Relay-Android v1.4.0 for the matching background-task, model/voice selection, resume, and task-chip behavior. Standard chat and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Added

- **Queued background voice work.** Up to three additional long requests can wait behind an active Hermes task and start automatically in order; cancelling the active run also clears its queue.
- **Quick side-session answers.** A short follow-up can be answered while a background run continues, without disturbing the durable task or its eventual delivery.
- **Provider-native exact xAI delivery.** Exact non-structured results use xAI's forced speech event so the selected realtime voice reads the authoritative Hermes answer without another model inference step.
- **Multi-device Android Bridge.** Multiple Android clients can remain connected and tools can target a named device class, alias, or explicit device ID. `/bridge/devices` and `/bridge/select-active` expose current routing.
- **Delivery health report.** `python -m plugin.relay.realtime_agent.report` summarizes recent realtime-voice delivery modes and fallback reasons.

### Changed

- **Compatibility bootstrap covers only true gaps.** Current Hermes owns native session CRUD/messages and skill discovery; the optional hook now limits itself to legacy surfaces with no upstream replacement.
- **aiohttp 3.14.1 or newer.** Plugin/package requirements move to the patched dependency line covering the 2026 aiohttp security advisories.
- **Long gateway turns use liveness, not a short RPC cap.** Prompt submit can wait up to the server's long-turn ceiling while idle-progress watchdogs determine whether a turn has actually stalled.

### Fixed

- **Native `hermes plugins install` compatibility.** Runtime imports are package-relative, dashboard loading works under the upstream plugin namespace, and doctor exercises the real import chain.
- **Modern install layouts.** The installer detects classic, uv-managed, and containerized environments and points generated services/shims at the interpreter it actually found.
- **Doctor catches wrong dashboard surfaces and duplicate plugin copies.** Operators get an actionable correction instead of silently loading a stale directory or pointing Manage at a headless API server.
- **Resume ownership is generation-safe.** A stale candidate cannot detach an active phone route; confirmed replacements reject old failure/close/fatal callbacks, and failed opening candidates are never activated after their terminal callback.
- **Background results survive route loss.** Resumable sessions retain unacknowledged input and replay missed output, retry budgets start when a route is lost, and a detached durable run can still deliver by resume or notification.
- **One handoff and one ready event.** Duplicate spoken background acknowledgements and duplicate fresh-session ready telemetry are suppressed.
- **Credential files cannot be served as media.** Resolved paths under auth, token, pairing, SSH, relay-secret, and system-config locations are blocked even when general media delivery is permissive.

## Install / update

```bash
# Native upstream plugin path:
hermes plugins install Codename-11/hermes-relay/plugin --enable

# Classic install / update on a systemd host:
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
# or, if already installed:
hermes-relay-update
```

## Verify

```bash
hermes relay doctor
python scripts/check-plugin-version-sync.py --expect __VERSION__
```

---

Tag prefixes: Android releases use `android-v*`, plugin releases use `plugin-v*`, and CLI releases use `cli-v*`.
