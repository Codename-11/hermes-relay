# Security

## Overview

Hermes-Relay gives a remote AI agent full control of an Android device via AccessibilityService. This is powerful and inherently sensitive — treat it with the same caution as remote desktop access.

### Device Control is a build-flavor boundary

AccessibilityService-backed Device Control (screen reading, taps, typing, screenshots, overlays, unattended control) and the Tier-C `android_*` tools (call, SMS, contacts, location) ship **only in the `sideload` flavor**. The conservative `googlePlay` flavor is "Bridge Core" — relay pairing, chat, voice, terminal, notification companion, media, and session grants — and does **not** compile in any phone-control surface; gated routes fail closed with a structured `403` (`error_code: device_control_sideload_only` for Device Control commands, `sideload_only` for the Tier-C `android_*` tools) rather than acting. This flavor gate is the most conservative security boundary in the app: on a `googlePlay` build the capability is absent, not merely disabled, regardless of pairing or server configuration. It is orthogonal to relay pairing — pairing a relay on a `googlePlay` build still cannot tap or type. See `BuildFlavor` in `data/FeatureFlags.kt` and the capability matrix in `docs/path-architecture.html`.

## Current Security Model

### Authentication
- **Pairing code**: A random 6-character alphanumeric code. For the QR-driven flow, the pair command (`hermes pair`, `/hermes-relay-pair`, or compatibility `hermes-pair`) generates the code on the Hermes host and pre-registers it with the relay via the loopback-only `POST /pairing/register` endpoint; the phone-side `AuthManager.generatePairingCode()` generator is retained for the Phase 3 bridge flow.
- The phone and server must share this code to establish a connection.
- Codes use the full `A-Z / 0-9` alphabet (36 chars). The earlier "no ambiguous 0/O/1/I" restriction was dropped when the pairing flow moved from "human retypes code from display" to "code flows phone ↔ server via QR + HTTP" (see `docs/decisions.md` §6a).
- `POST /pairing/register` is gated to loopback callers only (`127.0.0.1` / `::1`) — only a process with host shell access on the relay machine can inject pairing codes. A LAN attacker cannot.
- Relay session tokens carry per-channel grants. Voice routes require explicit `voice:config`, `voice:stt`, `voice:tts`, or `voice:realtime` grants when called with a Relay session token.
- `/voice/config`, `/voice/transcribe`, `/voice/synthesize`, `/voice/output/*`, and `/voice/realtime/*` may also accept the Hermes API bearer token used by API-server clients. The Android app uses this fallback for chat+voice-only setups when no Relay session is paired. This is a narrow exception for chat/media-adjacent voice features only; the API bearer token is not accepted for sessions, media, clipboard, terminal, TUI, bridge, profile writes, or Android control routes.
- Hermes API bearer use on voice routes requires HTTPS for non-loopback callers by default. Loopback plaintext is allowed for local clients; reverse-proxy TLS is accepted only when `RELAY_TRUST_PROXY_HEADERS=1`, and plaintext LAN testing requires an explicit opt-in. Use `hermes relay insecure-api-key on` for a running relay, or `RELAY_ALLOW_INSECURE_API_BEARER=1` at startup.

### Rate Limiting
- Failed WebSocket authentication attempts are rate-limited per IP
- After 5 failed attempts in 60 seconds, the IP is blocked for 5 minutes
- Blocked IPs receive HTTP 429

### Connection Architecture
- The phone connects **out** to the server (NAT-friendly)
- The server relay only accepts one phone at a time
- All tool commands are proxied through the relay — the phone is never directly exposed

## Known Limitations

### Plaintext `ws://` legs are possible
The relay can run without TLS (`hermes relay start --no-ssl`), in which case connections use `ws://` (plaintext) instead of `wss://` (TLS). On a plaintext leg:
- Commands, screen content, and screenshots travel unencrypted
- Anyone on the network path between phone and server can intercept traffic

The clients do not accept this silently: each pairing candidate carries a `transport_hint`, and the Android app keeps plain `ws://` disabled until the operator turns on "Allow plain (unencrypted) connections" and acknowledges the one-time warning dialog. TLS connections get trust-on-first-use SPKI certificate pinning on both the Android app and the desktop CLI.
- **Mitigation**: Use plaintext only on a trusted network, or front the relay with Tailscale (`hermes-relay-tailscale enable`) or a TLS reverse proxy (nginx/caddy)

Hermes API bearer tokens on `/voice/*` are stricter than the legacy WebSocket path: non-loopback API-bearer requests are rejected unless the request is HTTPS, comes through a trusted HTTPS reverse-proxy signal, or the operator has explicitly enabled the insecure dev escape hatch with `hermes relay insecure-api-key on` or the startup env var.

### Broad device access once Device Control is enabled
On a `sideload` build with Bridge enabled, the agent can:
- Read all screen content (any app)
- Tap, type, swipe anywhere
- Open any app
- Take screenshots
- Read installed app list

This access is fenced by several shipped rails rather than a per-app Android permission model:
- **Per-channel grants with TTLs** on the relay session token — bridge access can be excluded or time-boxed at pair time and revoked from any client.
- **Bridge safety rails** (`BridgeSafetyManager`): a per-app blocklist, destructive-verb confirmation prompts (fail-closed on `/call` and `/send_sms`), and an auto-disable timer.
- **Master toggle + status overlay** so control is visible and can be cut instantly on the phone.

The rails are deny-lists and confirmations, not sandboxing — a permissive configuration still exposes sensitive apps.
- **Mitigation**: Only pair with trusted Hermes instances, keep the blocklist populated, and disconnect (or let auto-disable fire) when not in use.

### Command audit is local, not centralized
The Bridge screen keeps an on-device activity log of executed device-control commands, and the relay logs commands to its service log at INFO level. There is no centralized, tamper-evident audit trail across surfaces.
- **Mitigation**: Review the Bridge activity log on the phone and the relay service log (`journalctl`) on the host.

### Remote connectivity

Hermes-Relay does **not** ship its own application-layer crypto. The operator owns both endpoints, and the trust model assumes TLS is terminated somewhere on the path that the operator already controls — Tailscale (managed TLS + tailnet ACL identity), a reverse proxy with Let's Encrypt, a WireGuard / other VPN, or a Cloudflare Tunnel. See [`docs/remote-access.md`](remote-access.md) for the decision matrix and setup recipes per mode.

Multi-endpoint pairing (ADR 24) makes "same phone, different networks" a first-class case: a single QR carries `lan` / `tailscale` / `public` candidates in strict-priority order and the phone re-probes reachability on every network change. Per-candidate `transport_hint` drives the plaintext-`ws://` gating — an unencrypted leg is used only after the operator has enabled and acknowledged plain connections. The TOFU cert pin is keyed by `host:port`, so two endpoints pointing at the same hostname share a pin (correct — same cert, same pin) while distinct hostnames each get their own.

## Recommendations for Production Use

1. **Use Tailscale (built-in) or a reverse proxy + Let's Encrypt.** `hermes-relay-tailscale enable` is the shortest path to a managed-TLS remote-access story; Caddy + Let's Encrypt is the shortest path to a real public domain. Either works. See [`docs/remote-access.md`](remote-access.md).
2. **Use a strong pairing code**: Don't share your code publicly
3. **Disconnect when idle**: Tap Disconnect in the app when you're not actively using it
4. **Monitor the phone**: Keep the status overlay enabled to see when the bridge is active
5. **Don't pair on public WiFi without a VPN**: Plain `ws://` is vulnerable to interception — pair on a network you trust or front the relay with Tailscale / TLS first.

## Privacy

See [docs/privacy.md](privacy.md) for the full privacy and data handling policy. Key points:

- **No external data transmission** — the app connects only to the Hermes servers you configure
- **Local-only analytics** — Stats for Nerds counters are stored in DataStore on-device, never sent externally
- **Encrypted credential storage** — API keys and session tokens use EncryptedSharedPreferences (AES-256-GCM, hardware-backed Android Keystore)
- **No tracking, ads, or third-party SDKs**

## Reporting Security Issues

If you find a security vulnerability, please open an issue on GitHub or contact the maintainers directly. Do not exploit vulnerabilities on other people's devices.
