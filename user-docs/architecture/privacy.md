# Privacy & Data Handling

Hermes-Relay is a self-hosted app. It connects only to your own servers — no cloud accounts, no third-party backends.

## What stays on your phone

- **Server URLs and preferences** — stored in Android DataStore (app-private)
- **API key and session tokens** — encrypted with AES-256-GCM via EncryptedSharedPreferences
- **Analytics counters** (Stats for Nerds) — response times, token counts, health stats — stored locally, never sent externally

Chat messages are **not cached** on device. They load from your Hermes server on demand.

## What the app does NOT do

- Send telemetry, analytics, or crash reports to any external service
- Include tracking, advertising, or third-party SDKs
- Connect to Anthropic, Google, or any service beyond your configured servers
- Request camera, contacts, location, or other sensitive permissions

## Network connections

| Destination | Protocol | Purpose |
|-------------|----------|---------|
| Your Hermes API server | HTTP/SSE | Chat streaming |
| Your relay server | WSS | Terminal and bridge channels |

HTTPS is enforced for all non-localhost connections. No background pings or DNS prefetching to external services.

## Data export and reset

From **Settings**, you can export your configuration (secrets excluded), import a backup, or perform a full reset that wipes all local data including encrypted credentials.

## Open source

All code is [MIT licensed](https://github.com/Codename-11/hermes-relay) and publicly auditable. See the full privacy document at [`docs/privacy.md`](https://github.com/Codename-11/hermes-relay/blob/main/docs/privacy.md).
