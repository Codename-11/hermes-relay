# Hermes-Relay-Server v__VERSION__

**Release Date:** August 13, 2026

This release adds an operator-owned secure ingress path, promotes Tailscale as the easiest supported remote route, and introduces Hermes Reach as a disabled-by-default experimental broker for outbound-only environments.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Added

- **Hermes Secure Link.** A pinned-TLS ingress can expose Relay, API, and Dashboard namespaces through one self-hosted endpoint while retaining each service's native authentication.
- **Experimental Hermes Reach.** An optional self-hosted rendezvous broker forwards opaque inner Secure Link TLS records, with hashed credentials, replay protection, bounded streams, persistence, and revocation.
- **Remote-access status.** Dashboard and pairing metadata distinguish reachability from transport protection and show supported services without exposing certificate pins.

### Changed

- **Tailscale is recommended for remote access.** Tailscale Serve remains the simplest supported path; direct TLS and Secure Link are self-hosted alternatives, while Reach stays advanced and experimental.
- **Pairing carries reviewed transport trust.** QR payloads include the Secure Link endpoint and pin before the first network request; rotation requires explicit re-pairing.

### Fixed

- **Route credentials remain scoped and revocable.** Reach credentials are issued only through trusted Secure Link ingress, replaced atomically per Relay session, bounded by session expiry, and removed on revocation.
- **Proxy namespaces preserve credential isolation.** API and Dashboard headers, cookies, redirects, methods, sizes, timeouts, and loopback authority are constrained independently.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/server-v__VERSION__/install.sh | bash
    # or, if already installed:
    hermes-relay-update

## Verify

    hermes relay doctor
    # Agent/tool callers can use desktop_health to list desktop targets.
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Server releases use server-v*, and Desktop releases use desktop-v*.
