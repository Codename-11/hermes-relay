# Hermes-Relay Dashboard Plugin — Frontend

Four-tab React UI that runs inside the hermes-agent dashboard via the upstream
Plugin SDK. The build output (`dist/index.js`) is **committed to git** because
the dashboard `<script src=...>` loads it verbatim — operators never run the
build.

## Requirements

- Node.js 18+
- npm 9+

Only one dev dependency: `esbuild`. No React, no tailwind, no shadcn — they all
come from the dashboard's SDK global at runtime.

## Build

```bash
cd plugin/dashboard
npm install
npm run build
```

Writes a single IIFE bundle to `dist/index.js`. Expected size: ~20-80 KB
minified. If the bundle balloons past 200 KB, investigate (likely an unintended
`import` of a heavy module — remember React/UI primitives must come from the
SDK global, not be bundled).

To iterate with rebuilds on save:

```bash
npm run watch
```

The `build.sh` wrapper is a bash entry point that runs `npm install` if
`node_modules/` is missing, then `npm run build`. Useful for git hooks.

## Regenerating the committed bundle

After any change under `src/`:

```bash
npm run build
git add dist/index.js src/
git commit -m "feat(dashboard): <what changed>"
```

CI does not rebuild the bundle. Forgetting this step means the change ships
source-only and the deployed dashboard keeps serving the old behaviour.

## SDK touch points

All runtime dependencies come from two globals the dashboard shell injects:

| Global | Used for |
|--------|----------|
| `window.__HERMES_PLUGIN_SDK__.React` | React namespace (we never bundle React) |
| `window.__HERMES_PLUGIN_SDK__.hooks` | `useState`, `useEffect`, `useCallback`, `useMemo` |
| `window.__HERMES_PLUGIN_SDK__.components` | shadcn primitives — `Tabs*`, `Card*`, `Table*`, `Button`, `Badge`, `Alert*`, `Switch`, `Label` |
| `window.__HERMES_PLUGIN_SDK__.fetchJSON` | Session-token-authenticated JSON fetch |
| `window.__HERMES_PLUGINS__.register(name, Component)` | Registration hook |

Every `.jsx` file opens with:

```js
const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, ... } = SDK.hooks;
```

so that esbuild's classic JSX transform (`--jsx=transform
--jsx-factory=React.createElement`) resolves against the runtime React.

## Backend routes consumed

All proxied by `plugin_api.py` under `/api/plugins/hermes-relay/`:

- `GET /overview` — relay version, uptime, counters
- `GET /sessions` — paired device list
- `GET /bridge-activity?limit=N` — ring buffer of recent bridge commands
- `GET /media?include_expired=true|false` — MediaRegistry snapshot
- `GET /push` — FCM configuration stub

## Auto-refresh cadence

| Tab | Poll interval | Notes |
|-----|---------------|-------|
| Management | 10s | `/overview` + `/sessions` |
| Activity | 5s | `/bridge-activity` |
| Push | — | No poll (stub) |
| Media | 15s | `/media`; TTL countdown ticks every 1s independently |

Toggle persists to `localStorage['hermes-relay-autorefresh']` (default: on).
When off, each tab surfaces a manual "Refresh" button.

## Notes

- Session revocation from the dashboard is a **placeholder** — it requires
  re-pairing from the phone. Wiring a real proxy route is a future ticket.
- Every tab handles loading / empty / error states. The error state shows the
  backend's 502 detail verbatim so "relay unreachable" is debuggable without
  opening devtools.
