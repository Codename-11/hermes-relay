---
name: hermes-relay-pair
description: Generate a pairing QR for the Hermes-Relay Android app — one scan configures chat (API server) and terminal/bridge (relay) in a single step.
version: 1.0.0
author: Axiom Labs
license: MIT
platforms: [linux, macos]
metadata:
  hermes:
    tags: [pairing, qr, android, relay, setup, hermes-relay]
    category: devops
    homepage: https://github.com/Codename-11/hermes-relay
    related_skills: []
---

# Hermes-Relay Pairing

[Hermes-Relay](https://github.com/Codename-11/hermes-relay) is a native Android client for Hermes. Chat goes directly to the Hermes API server over HTTP/SSE; terminal and bridge channels go through a separate WSS relay. This skill generates a single QR code that configures both connections at once, using `plugin.pair` from the Hermes-Relay plugin.

## When to Use

Invoke this skill when any of the following happens:

- User runs the `/hermes-relay-pair` slash command.
- User asks to "pair my phone", "connect the Hermes-Relay app", "scan a QR for Hermes", or anything equivalent.
- User is setting up the Hermes-Relay Android app for the first time, or re-pairing after uninstall / token loss.
- User reports terminal tab asking for a pairing code (the app needs a fresh relay code embedded in a QR).

Do NOT use this skill to start or install the relay server itself — that is a prerequisite. Reference `hermes relay start` and stop.

### Dashboard alternative

Operators with the Hermes dashboard open can also mint the same QR from the web UI: **Relay tab → Management → "Pair new device"** (Mode + Prefer dropdowns), or **Relay tab → Remote Access → "Regenerate QR"** for the fuller preview + probe view. Both UIs call the same `handle_pairing_mint` endpoint this skill shells into. Prefer this skill when you're already in a terminal (faster + scriptable); prefer the dashboard when the operator needs to see + probe candidate endpoints before minting. The dashboard's "Advanced · API-server override" field should stay blank in almost every case — pinning a forward-auth-gated hostname there (e.g. Authelia-fronted FQDN) pairs WSS but breaks the API side. See `docs/remote-access.md` § "Forward-auth gateways".

## Prerequisites

1. **Hermes-Relay plugin installed into the Hermes venv.** Verify by running `python -m plugin.pair --help` — if it errors with `ModuleNotFoundError: No module named 'plugin'`, install it first: `pip install -e <path-to-hermes-relay-repo>`.
2. **Hermes API server reachable** on `API_SERVER_HOST:API_SERVER_PORT` (default `127.0.0.1:8642`). `plugin.pair` auto-reads this from `~/.hermes/config.yaml` → `~/.hermes/.env` → env vars → defaults.
3. **Relay server running** on `RELAY_HOST:RELAY_PORT` (default `0.0.0.0:8767`) if the user wants terminal/bridge channels. Without a live relay, the QR will configure chat only.
4. **Host is Linux or macOS.** The relay uses a real PTY backend, which is POSIX-only. Windows hosts can generate API-only QRs but the terminal channel will not work.

## Procedure

1. **Probe the relay** — `curl -sf http://127.0.0.1:8767/health` (or `$RELAY_PORT`). If it returns 200, the relay is up. If it fails, tell the user: "No relay running at localhost:8767 — the QR will only configure chat. Run `hermes relay start` first if you want terminal access." Then ask whether to proceed with API-only or start the relay first. Do NOT start the relay yourself unless the user explicitly asks.

2. **Generate the QR** — run via the `terminal` tool:

   ```bash
   python -m plugin.pair
   ```

   If `python` resolves to the wrong interpreter (plugin not found), use the Hermes venv explicitly:

   ```bash
   ~/.hermes/hermes-agent/venv/bin/python -m plugin.pair
   ```

3. **Useful flags** (pass only when needed, not by default):
   - `--png` — save PNG to `/tmp/hermes-pairing-qr.png` and skip the Unicode terminal QR. Use when the user reports the terminal QR won't scan (small font, dark mode, non-Unicode terminal).
   - `--no-qr` — text only, no QR at all. Use when the agent is running in a non-TTY context and QR output would be wasted.
   - `--no-relay` — skip relay pre-pairing, render API-only QR. Use if the relay is intentionally offline.
   - `--host <ip>` / `--port <n>` — override the API server host or port when config auto-detection picks the wrong values.
   - `--mode {auto,lan,tailscale,public}` — endpoint discovery mode (ADR 24). Default `auto` probes LAN + Tailscale (if the helper is installed) + `--public-url` (if passed) and bakes them into the QR as an ordered candidate list so the phone switches networks automatically. `lan` / `tailscale` / `public` emit just that role. Example: `python -m plugin.pair --mode auto --public-url https://hermes.example.com`.
   - `--public-url <url>` — public hostname for a reverse proxy / Cloudflare Tunnel. Must be `http://` or `https://`. Added as a `role=public` endpoint candidate. Example: `python -m plugin.pair --public-url https://hermes.example.com`.
   - `--prefer <role>` — promote the named role to priority 0 in the endpoint list. Open vocab — commonly `lan` / `tailscale` / `public`. Useful when the user wants to force a specific path during testing without re-ordering defaults. Example: `python -m plugin.pair --mode auto --prefer tailscale` emits all detected modes but with Tailscale as the first-probed endpoint. Warns (non-fatal) if the named role isn't detected.
   - `--register-code <code>` — **manual fallback**. Skip QR rendering entirely and just pre-register a 6-char code the user is reading off the phone screen. See "Manual fallback" below.

4. **Show the output verbatim.** `plugin.pair` prints, in order:
   - Text block with `Server` URL, masked `API Key`, and (if relay is up) a `Relay (terminal + bridge)` section with `URL` and `Code`.
   - Unicode half-block QR (when stdout is a TTY).
   - `PNG: /tmp/hermes-pairing-qr.png` line.
   - `WARNING: This QR contains credentials ...` line (whenever an API key or relay code is present).

   Relay the full output back to the user. Do NOT redact the QR — the user needs to scan it. DO repeat the credentials warning in your own words.

5. **Tell the user how to scan.** Give them these exact steps:
   1. Open the Hermes-Relay Android app.
   2. If onboarding: tap `Scan Pairing QR` on the Connect page.
   3. If already onboarded: go to `Settings` → `Connection` → `Scan Pairing QR`.
   4. Point the camera at the terminal (or the PNG file) until it auto-detects.
   5. Watch for the success toast and the status summary.

6. **Time constraint.** The relay pairing code expires 10 minutes after generation and is single-use. If the user won't scan within that window, re-run the skill to mint a fresh code.

## Manual fallback (`--register-code`)

Use this when QR scanning is **physically impossible**:

- The user is SSH'd into the host from their phone (the only camera-equipped device) and there's no second device to point at the screen.
- The host has no display attached (a headless server you ssh into from a terminal that can't render Unicode QR blocks, or where copying a PNG off-host is awkward).
- The user's phone is the device with the camera *and* the device that needs to pair — there's no way to scan its own screen.

**Workflow:**

1. Tell the user to open the Hermes-Relay app → **Settings** → **Connection** → **Manual pairing code (fallback)**. The app displays a locally-generated 6-char code (A-Z / 0-9). They read it to you (or paste it into the chat).
2. On the host, run:

   ```bash
   hermes-pair --register-code ABCD12
   ```

   Replace `ABCD12` with the code from the phone. The command pre-registers it with the relay via the same loopback `/pairing/register` endpoint the QR flow uses, then prints a confirmation block listing the code, transport hint, session TTL, and what the user should tap next.

3. Tell the user to tap **Connect** in that same Manual pairing code card. The relay accepts the code, mints a session token, and the phone is paired.

**TTL + grants compose with `--register-code` exactly like they do with the QR flow:**

```bash
hermes-pair --register-code ABCD12 --ttl 30d --grants chat:never,bridge:7d
hermes-pair --register-code ABCD12 --ttl never
hermes-pair --register-code ABCD12 --transport-hint wss
```

Pass `--transport-hint wss` only when you know the relay is actually running behind TLS (e.g. an external reverse proxy) but the host-side check can't tell. Otherwise it's auto-detected from `RELAY_SSL_CERT`.

**Exit codes:**

- `0` — code accepted, pairing pre-registered. Phone can now tap Connect.
- `1` — relay was unreachable, OR relay was reachable but rejected the code (loopback-only endpoint — confirm you're on the same host as the relay).
- `2` — argument validation failed (bad code format, wrong length, invalid TTL/grants spec).

**Same 10-minute expiry rules apply** — once the operator runs `hermes-pair --register-code`, the user has 10 minutes to tap Connect or the code is invalidated and they need a fresh one from the app + a fresh `--register-code` invocation.

## Pitfalls

- **Relay not running.** `plugin.pair` prints `[info] Relay not running ... QR will configure chat only` and renders an API-only QR. Terminal tab will then ask the user to paste a pairing code manually. Fix: start the relay first (`hermes relay start`) and re-run.
- **Plugin not installed.** `ModuleNotFoundError: No module named 'plugin'`. Fix: `pip install -e <hermes-relay-repo>` into the same Python environment Hermes uses. Use `which python` / `where python` to confirm you're targeting the Hermes venv.
- **Wrong venv.** If `hermes` CLI is global but plugin is in the Hermes venv, `python -m plugin.pair` may resolve to the wrong Python. Call the venv Python explicitly: `~/.hermes/hermes-agent/venv/bin/python -m plugin.pair`.
- **Pairing code expired.** 10-minute TTL, one-shot. Re-run `python -m plugin.pair` to mint a fresh code; the previous code is automatically invalidated on the next run.
- **QR won't scan on terminal.** Likely causes: terminal font too small (zoom in), dark-mode color inversion mangling the blocks, or terminal lacks Unicode half-block support. Fix: re-run with `--png` and point the camera at the saved image, or open the PNG in an image viewer on a second screen.
- **Host resolves to `127.0.0.1`.** The phone on the LAN can't reach loopback. `plugin.pair` auto-detects the outbound LAN IP via a UDP socket trick, but if that fails set `API_SERVER_HOST=0.0.0.0` in `~/.hermes/.env` or pass `--host <lan-ip>` explicitly.
- **Relay is running but `/pairing/register` rejected.** Printed as `[warn] Relay is running but /pairing/register was rejected`. The endpoint is gated to loopback callers — the relay must be on the same host as the agent running this skill. If it's on a different host, pairing has to be done there.

## Verification

After the user scans, confirm all three of the following:

1. **Phone side.** Ask the user to open `Settings` → `Connection`. They should see:
   - `API Server`: reachable, green status.
   - `Relay`: connected, green status.
   - `Session`: paired.
2. **Relay side.** Run `curl -s http://127.0.0.1:8767/health` — `clients` should be `>= 1` after the phone connects.
3. **Functional check.** Ask the user to send a test message from the chat tab and switch to the terminal tab — the terminal should attach without prompting for a pairing code.

If any of those fail, fall back to the Pitfalls section and re-run the skill with appropriate flags.
