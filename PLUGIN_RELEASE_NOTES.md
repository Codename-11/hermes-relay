# Hermes-Relay Plugin v__VERSION__

**Release Date:** August 21, 2026

## Summary

This release makes delayed phone delivery and active Bridge access easier to understand. Relay now identifies messages flushed after reconnect, emits one completion signal for the backlog, and reports permanent, timed, and unlimited phone capabilities through status surfaces.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## Added

- **Reconnect backlog context.** Messages flushed from the bounded offline queue carry an explicit delayed-delivery marker, followed by one ordered completion event with the delivered count.
- **Granular phone capability status.** Relay status and `android_phone_status` report permanent, timed, and unlimited Bridge capabilities alongside existing Android permissions and safety state.

## Changed

- **Phone surfacing semantics are explicit.** Default delivery persists to Threads and notifies, Inbox delivery remains silent, and Session delivery targets an available active conversation before falling back to a notification.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/server-v__VERSION__/install.sh | bash
    # or, if already installed:
    hermes-relay-update

## Verify

    hermes relay doctor
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Plugin releases use server-v*, and CLI+UI releases use desktop-v*.
