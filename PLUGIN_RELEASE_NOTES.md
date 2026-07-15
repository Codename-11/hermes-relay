# Hermes-Relay-Plugin v__VERSION__

**Release Date:** July 11, 2026

**Since v1.4.0:** Realtime Agent result delivery is more dependable when a provider closes, stalls, or overlaps a newer response. Completed Hermes work stays authoritative through provider-native delivery where available and a single relay-TTS fallback otherwise.

Pairs with Hermes-Relay-Android v1.4.1 for the matching background-task, voice-command, and result-delivery behavior. Standard chat and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Changed

- **Provider-native delivery carries an explicit mode.** Realtime responses consistently identify forced-summary and fallback delivery so the Android client can present one authoritative result.
- **Exact delivery is more direct.** Non-structured verbatim results can use provider-native exact text while natural summaries retain delivery guidance.

### Fixed

- **A completed result survives provider failure.** If tool-result submission or a follow-up provider response fails, the relay speaks the authoritative Hermes answer through its fallback path before reporting the provider error.
- **Delivery confirmation ignores stale work.** A generation token prevents an older confirmation alarm from emitting a duplicate answer after a newer delivery or preemption.
- **Fallback completion is unambiguous.** The fallback path emits one complete result event even when the provider's audio render cannot finish.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
    # or, if already installed:
    hermes-relay-update

## Verify

    hermes relay doctor
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Server releases use server-v*, and Desktop releases use desktop-v*.
