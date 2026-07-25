# Hermes-Relay-Android v1.5.0

**Release Date:** July 25, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.5.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release makes the Hermes Dashboard and Gateway the clear standard connection, keeps active work reachable across the app and Android backgrounding, and gives profiles, voice, attachments, image generation, and approvals a more coherent native interface.

## Added

- The Agent Passport drawer combines live route and session context with explicit profile switching, personality, model, reasoning, approval-policy, chat-override, and processing-tier controls.
- Secure browser-based Dashboard sign-in is scoped to the selected host. Chat, sessions, Manage, and Standard Voice share the same authenticated Gateway route while the API server remains an automatic fallback.
- Standard and Realtime voice settings use focused provider, model, and voice cards with upstream-aware discovery, descriptions, inline previews, waveforms, and a browsable catalog. Standard replies can begin speaking completed segments before generation finishes.
- User-started turns stay protected until every concurrent session settles. Privacy-safe notifications reopen the correct chat for approvals, questions, elevated permissions, or secure responses.
- Onboarding finishes with a layered permission review: notifications are recommended deliberately, optional capabilities remain separate, and users can continue without granting phone access.
- Chat surfaces one-turn model choices, approval modes, advisor progress, queued recovery, project labels, collapsible attachments, persisted images, interim Gateway events, and image-generation activity.

## Fixed

- Gateway reconnects reactivate the original live session without resubmitting acknowledged prompts or duplicating session rows.
- Tailscale, QR, and other remote routes move Dashboard, Gateway, sessions, Manage, Standard Voice, API fallback, and optional Relay together.
- Dashboard authentication, model routing, recovery, and profile state stay scoped to the selected connection and session, including during cold start and rapid switching.
- Promoted voice and background tasks keep their owning Chat row until the work settles.
- User-installed certificate authorities work for self-hosted HTTPS/WSS while normal chain, hostname, and Relay-pin verification remain enforced.
- Malformed syntax-highlighting ranges no longer crash Markdown rendering.
- Developer Options no longer exposes the obsolete Relay feature flag; version-tap unlock, relock, backup, import, and reset actions now persist and report accurately.

## Install / Verify

- App version: **1.5.0** (versionCode **33**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
