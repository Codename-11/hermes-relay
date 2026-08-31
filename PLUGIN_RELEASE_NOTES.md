# Hermes-Relay Plugin v__VERSION__

**Release Date:** August 30, 2026

## Summary

This release lets one authenticated Hermes Dashboard origin carry Gateway plus optional Relay extensions, adds a bounded Git workspace, and reorganizes the Dashboard plugin around operator tasks. Standard chat, session history, profiles, Manage, and standard voice remain upstream-owned and do not require this plugin.

## Added

- **Dashboard same-origin Relay ingress.** Fixed allowlisted HTTP and WebSocket routes proxy to the local Relay while Dashboard admission and Relay session authentication remain independent. (Related: #399)
- **Bounded Git workspace.** Configured roots, path containment, line totals, diffs, branches, staging, commits, remotes, grants, and explicit confirmations protect repository operations.

## Changed

- **Task-oriented Dashboard UI.** Overview, Devices, Activity, Remote Access, Git, and Settings now have dedicated surfaces with QR-first pairing, responsive device cards, and honest media diagnostics. (#486)
- **One explicit route topology.** Dashboard, CLI, and TUI pairing advertise Dashboard, Relay, and optional API surfaces with stable priorities across Tailscale, public HTTPS, and LAN.
- **Dedicated Tailscale listener.** Recommended setup uses tailnet HTTPS `:10443` to local Dashboard `:9119`, avoiding ownership of a reverse proxy's `:443`. Existing `:443`, `:9119`, and direct `:8767` routes remain migration compatibility.

## Fixed

- Public and roaming invites no longer synthesize closed direct Relay `:8767` or wrong Dashboard `:9119` routes.
- Ambiguous, credential-bearing, or plaintext public candidates fail closed before an invite is exposed.
- Dense pairing QRs use integer-sized modules and a full quiet zone.
- Inactive optional API routes are omitted; protected Dashboard-ingress `401/403` responses display as authentication-required while direct Relay and API failures remain failures.
- Default Tailscale disable actions remove only owned listeners, and explicit migration cleanup accepts only the bounded supported ports.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/server-v__VERSION__/install.sh | bash
    # or, if already installed:
    hermes-relay-update

Restart or reload the Hermes Dashboard and Relay after updating so the new manifest, routes, and committed Dashboard bundle are active.

## Verify

    hermes relay doctor
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Plugin releases use server-v*, and CLI+UI releases use desktop-v*.
