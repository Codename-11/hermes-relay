# Security

## Overview

Hermes-Relay gives a remote AI agent full control of an Android device via AccessibilityService. This is powerful and inherently sensitive — treat it with the same caution as remote desktop access.

## Current Security Model

### Authentication
- **Pairing code**: A random 6-character alphanumeric code. For the QR-driven flow, the pair command (`/hermes-relay-pair` skill or `hermes-pair` shell shim) generates the code on the Hermes host and pre-registers it with the relay via the loopback-only `POST /pairing/register` endpoint; the phone-side `AuthManager.generatePairingCode()` generator is retained for the Phase 3 bridge flow.
- The phone and server must share this code to establish a connection.
- Codes use the full `A-Z / 0-9` alphabet (36 chars). The earlier "no ambiguous 0/O/1/I" restriction was dropped when the pairing flow moved from "human retypes code from display" to "code flows phone ↔ server via QR + HTTP" (see `docs/decisions.md` §6a).
- `POST /pairing/register` is gated to loopback callers only (`127.0.0.1` / `::1`) — only a process with host shell access on the relay machine can inject pairing codes. A LAN attacker cannot.

### Rate Limiting
- Failed WebSocket authentication attempts are rate-limited per IP
- After 5 failed attempts in 60 seconds, the IP is blocked for 5 minutes
- Blocked IPs receive HTTP 429

### Connection Architecture
- The phone connects **out** to the server (NAT-friendly)
- The server relay only accepts one phone at a time
- All tool commands are proxied through the relay — the phone is never directly exposed

## Known Limitations (Prototype)

### No Encryption
WebSocket connections use `ws://` (plaintext), not `wss://` (TLS). This means:
- Commands, screen content, and screenshots travel unencrypted
- Anyone on the network path between phone and server can intercept traffic
- **Mitigation**: Use over a trusted network, or set up a reverse proxy with TLS (nginx/caddy)

### Full Device Access
Once paired, the agent has unrestricted access to:
- Read all screen content (any app)
- Tap, type, swipe anywhere
- Open any app
- Take screenshots
- Read installed app list

There is no granular permission system — the agent can access banking apps, messages, etc.
- **Mitigation**: Only pair with trusted Hermes instances. Disconnect when not in use.

### No Command Audit Log
There is no persistent log of what commands the agent executed on the phone.
- **Mitigation**: The relay logs commands to stdout when run with INFO logging.

### Remote connectivity

Hermes-Relay does **not** ship its own application-layer crypto. The operator owns both endpoints, and the trust model assumes TLS is terminated somewhere on the path that the operator already controls — Tailscale (managed TLS + tailnet ACL identity), a reverse proxy with Let's Encrypt, a WireGuard / other VPN, or a Cloudflare Tunnel. See [`docs/remote-access.md`](remote-access.md) for the decision matrix and setup recipes per mode.

Multi-endpoint pairing (ADR 24) makes "same phone, different networks" a first-class case: a single QR carries `lan` / `tailscale` / `public` candidates in strict-priority order and the phone re-probes reachability on every network change. Per-candidate `transport_hint` drives the plaintext-`ws://` consent dialog — explicit operator consent is still required for any unencrypted leg. The TOFU cert pin is keyed by `host:port`, so two endpoints pointing at the same hostname share a pin (correct — same cert, same pin) while distinct hostnames each get their own.

## Recommendations for Production Use

1. **Use Tailscale (built-in) or a reverse proxy + Let's Encrypt.** `hermes-relay-tailscale enable` is the shortest path to a managed-TLS remote-access story; Caddy + Let's Encrypt is the shortest path to a real public domain. Either works. See [`docs/remote-access.md`](remote-access.md).
2. **Use a strong pairing code**: Don't share your code publicly
3. **Disconnect when idle**: Tap Disconnect in the app when you're not actively using it
4. **Monitor the phone**: Keep the status overlay enabled to see when the bridge is active
5. **Don't pair on public WiFi without a VPN**: Plain `ws://` is vulnerable to interception — pair on a network you trust or front the relay with Tailscale / TLS first.

## Privacy

See [docs/privacy.md](privacy.md) for the full privacy and data handling policy. Key points:

- **No external data transmission** — the app connects only to your self-hosted Hermes servers
- **Local-only analytics** — Stats for Nerds counters are stored in DataStore on-device, never sent externally
- **Encrypted credential storage** — API keys and session tokens use EncryptedSharedPreferences (AES-256-GCM, hardware-backed Android Keystore)
- **No tracking, ads, or third-party SDKs**

## Reporting Security Issues

If you find a security vulnerability, please open an issue on GitHub or contact the maintainers directly. Do not exploit vulnerabilities on other people's devices.
