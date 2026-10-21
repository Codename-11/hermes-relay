# Hermes-Relay Plugin v__VERSION__

**Release Date:** October 20, 2026

## Summary

Secure Link handles certificate chains and paired-session management more reliably. Profile voice output uses the selected renderer's defaults, and exact desktop targets cannot fall back to another computer.

## Fixed

- Use the selected speech renderer's model and voice defaults when a profile changes provider, while preserving explicit selections.
- Accept Secure Link PEM certificate chains, export only the leaf certificate for pairing, and report invalid certificate/key configurations without exposing private file paths.
- Support paired-device listing, session shortening, and revocation over Secure Link with the existing Relay authorization rules.
- Route desktop operations addressed with device_id only to the exact authenticated device. Missing, duplicate, or conflicting targets fail without name matching or fallback to another computer.

## Compatibility

Standard Android Chat and Voice remain upstream-only. This release updates the optional Plugin and does not publish a CLI+UI companion. Friendly desktop names remain supported through the `device` field; `device_id` requires the authenticated identity and fails when that exact target is unavailable or ambiguous.

Screenshot failure-result masking remains tracked separately in [#743](https://github.com/Codename-11/hermes-relay/issues/743); this release does not claim to resolve every screenshot failure.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/server-v__VERSION__/install.sh | bash
    # or, if already installed:
    hermes-relay-update

Reload the Plugin and restart Relay after updating so the new server code and tool registrations are active. Existing agent sessions may retain their current tool catalog until the next session.

## Verify

    hermes relay doctor
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Plugin releases use server-v*, and CLI+UI releases use desktop-v*.
