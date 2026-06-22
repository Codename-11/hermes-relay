# Hermes-Relay-Plugin v__VERSION__

**Release Date:** June 22, 2026
**Since the previous plugin release:** Reliability fixes for the Realtime Agent voice path — brokered Hermes turns no longer drop with `session_not_found`, and long-running Hermes work no longer times out a live voice session.

This is a focused patch for the relay's Realtime Agent. When a spoken turn reached back into Hermes for context or tool work, a session-namespace mismatch could make the API Server reject the turn, and long background tasks could let the voice session lapse mid-run. Both paths are now resilient. Provider-native voice turns and vanilla upstream (no plugin) are unaffected.

## What's changed

### Fixed
- **Brokered Hermes turns no longer fail with `session_not_found`.** When the Realtime Agent reached back to Hermes for context or tool work, it could hand the API Server a session id from a different session namespace (the gateway/client store), which the API Server rejected. The broker now mints a valid API Server session and retries the turn once when that happens, reuses an existing API Server session when the id is already valid, and reads the API Server's current nested `{"session": {"id": …}}` create-session response (previously only the legacy flat shape) so session creation no longer errors with "created a session without an id."
- **Realtime voice survives long Hermes runs.** A heartbeat now keeps the realtime voice session alive while a long-running Hermes task is in flight, so the turn no longer times out before the work finishes.

## Install

```bash
pip install hermes-relay==__VERSION__
```

## Verify

```bash
python -m relay_server --help
```

---

Tag prefixes: Android releases use `android-v*`, CLI releases use `cli-v*`. Historical
relay/plugin releases used `relay-v*` tags.
