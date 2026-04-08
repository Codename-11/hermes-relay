# Privacy & Data Handling

Hermes Relay is a self-hosted AI agent companion app. It connects exclusively to your own servers — there are no cloud services, accounts, or third-party backends involved.

## No External Data Transmission

- The app makes **no connections** to Anthropic, Google, or any third party
- **No telemetry**, analytics, or crash reports are sent externally
- **No tracking, no ads**, no third-party SDKs that phone home
- The Hermes server may connect to AI providers (OpenAI, Anthropic, etc.) — that is server-side and outside this app's scope

## Local Storage

All data is stored on-device in the app's private sandbox:

| Data | Storage | Notes |
|------|---------|-------|
| API server URL, relay URL | DataStore preferences | Plaintext, app-private |
| API key | EncryptedSharedPreferences | AES-256-GCM via Android Tink, hardware-backed Keystore |
| Relay session token | EncryptedSharedPreferences | Same encryption as API key |
| Theme, display preferences | DataStore preferences | Tool display mode, reasoning toggle, etc. |
| Analytics counters | DataStore preferences | Response times, token counts, health stats — **local only** |

Chat messages are **not cached locally**. They are loaded from the Hermes API server on demand and exist only in memory while the app is running.

## Network Communication

The app connects only to user-configured endpoints:

- **HTTP/SSE** to your Hermes API server (default `localhost:8642`) for chat streaming
- **WSS** to your relay server (default `localhost:8767`) for terminal and bridge channels
- Cleartext (HTTP) is permitted for local/private network connections to user-configured servers; the app warns when using insecure connections
- No DNS prefetching, no background pings to external services

## Permissions

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Connect to your Hermes servers |
| `ACCESS_NETWORK_STATE` | Detect connectivity changes for auto-reconnect |
| `CAMERA` | QR code scanning for server pairing (declared as `required="false"`) |

The app does **not** request contacts, location, microphone, storage, or any other sensitive permissions. Camera is only used for the optional QR pairing feature and is not required for the app to function.

## Data Export & Reset

From Settings, users can:

- **Export** settings (server URLs, preferences) — secrets (API key, tokens) are excluded
- **Import** a previously exported configuration
- **Full reset** — wipes all local data including encrypted credentials

## Open Source

Hermes Relay is MIT licensed. All source code is publicly available and auditable at [GitHub](https://github.com/Codename-11/hermes-relay).

## Stats for Nerds

The analytics feature ("Stats for Nerds") tracks performance metrics — time to first token, completion times, token usage, health check latency. All counters are stored in local DataStore and never leave the device. There is no server-side analytics collection.
