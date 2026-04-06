# v0.2.0 — Direct API Chat

Chat now connects directly to your Hermes API Server — no relay server needed for conversations.

## What's New

- **Direct API chat** — uses the Hermes Sessions API (`/api/sessions/{id}/chat/stream`) with SSE streaming
- **API key auth (optional)** — `Authorization: Bearer <key>` stored securely on device, only needed if Hermes is configured with `API_SERVER_KEY`
- **Session management** — create, list, switch, rename, and delete chat sessions via the Sessions API
- **Test Connection** — verify your API server is reachable before chatting
- **Cancel streaming** — stop button to cancel in-flight responses

## What Changed

- Onboarding now asks for API Server URL + API Key (relay URL is optional)
- Settings split into "API Server" and "Relay Server" sections
- The relay server is only needed for Bridge and Terminal features

## Install

```bash
# Build and install debug APK
scripts/dev.bat run
```

Or download the APK from the release assets.
