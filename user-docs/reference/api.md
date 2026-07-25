# API & Route Contract

Hermes-Relay crosses three independent server surfaces. They may run on the
same machine, but they do not share a base URL, authentication model, or owner.
Use this page to choose the right contract before reaching for an endpoint.

<div class="contract-grid">
  <a class="contract-card contract-card--upstream" href="./upstream-hermes.html">
    <span class="contract-card__eyebrow">UPSTREAM · :8642</span>
    <strong>Hermes API Server</strong>
    <span>Chat fallback, sessions, capabilities, skills and toolsets.</span>
    <code>Authorization: Bearer API_SERVER_KEY</code>
  </a>
  <a class="contract-card contract-card--dashboard" href="./upstream-hermes.html#dashboard-gateway">
    <span class="contract-card__eyebrow">UPSTREAM · :9119</span>
    <strong>Dashboard & Gateway</strong>
    <span>Preferred chat transport, Manage, profiles and Vanilla Hermes voice.</span>
    <code>Dashboard session + WS ticket</code>
  </a>
  <a class="contract-card contract-card--relay" href="./relay-api.html">
    <span class="contract-card__eyebrow">OPTIONAL PLUGIN · :8767</span>
    <strong>Hermes Relay</strong>
    <span>Pairing, terminal, media, Relay voice, desktop tools and phone control.</span>
    <code>Relay session + per-channel grants</code>
  </a>
</div>

::: warning Keep credentials in their lane
A dashboard cookie is not an API key. An API bearer is not a Relay session.
Relay accepts the Hermes API bearer only on its explicitly documented voice
routes; all other protected Relay routes require pairing and the appropriate
session grant.
:::

## Ownership at a glance

| Surface | Owner | Base URL | Primary authentication | Relay required? |
|---|---|---|---|---|
| API Server | Upstream Hermes | `http(s)://host:8642` | Required `API_SERVER_KEY` bearer when the API server is enabled | No |
| Dashboard / Gateway | Upstream Hermes | `http(s)://host:9119` | Dashboard session; short-lived WS ticket for `/api/ws` | No |
| Relay | Hermes-Relay plugin | `ws(s)://host:8767` and matching HTTP(S) origin | Pairing code, then Relay session token and grants | Yes |
| Compatibility hook | Hermes-Relay plugin, older hosts only | Injected into the API server | Same auth as the host API server | No, but non-upstream |

## Client routing contract

Hermes-Relay chooses routes by capability and authentication state:

1. Probe API-server `GET /v1/capabilities`.
2. If Manage authentication is ready, obtain a dashboard WS ticket and prefer
   `/api/ws` for chat with live reasoning.
3. Otherwise use native `/api/sessions/{id}/chat/stream` over SSE.
4. Fall back to `/v1/chat/completions` or `/v1/runs` only when the native
   session surface is unavailable.
5. Use dashboard `/api/audio/*` for Vanilla Hermes voice.
6. Contact Relay only for paired, additive power features.

Chat, Manage, and Vanilla Hermes voice must continue to work against an
unmodified upstream Hermes installation. Relay routes never become a hidden
dependency for that default path.

## Contract labels

The reference uses these labels consistently:

| Label | Meaning |
|---|---|
| **Upstream** | Owned and served by unmodified Hermes Agent |
| **Relay** | Owned by the optional Hermes-Relay plugin/server |
| **Loopback** | Callable only from the Hermes host |
| **Paired** | Requires a Relay session token and the named grant |
| **Sideload** | Requires the Android sideload build; Google Play fails closed |
| **Experimental** | Wire shape or behavior may change between minor releases |
| **Compatibility** | Older-host fallback; never the preferred native path |

## Reference map

- [Upstream Hermes contract](./upstream-hermes.html) — API Server, Dashboard and
  Gateway routes consumed by Hermes-Relay.
- [Relay API contract](./relay-api.html) — route families, grants, loopback
  boundaries and build-flavor gates.
- [Relay server operations](./relay-server.html) — deployment, environment,
  complete route detail and troubleshooting.
- [Compatibility contract](./compatibility.html) — optional older-host routes,
  feature detection and removal.
- [Configuration](./configuration.html) — client and server settings.

## Source of truth

`GET /v1/capabilities` is authoritative for a running Hermes API Server. Relay
routes are registered in `plugin/relay/server.py`. The public reference records
only surfaces Hermes-Relay consumes or exposes; it is not a complete reference
for every endpoint in the Hermes dashboard.

<style>
.contract-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 1.25rem 0 1.5rem;
}
.contract-card {
  display: flex;
  min-height: 210px;
  padding: 18px;
  border: 1px solid var(--vp-c-divider);
  border-top-width: 3px;
  border-radius: 8px;
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-text-1);
  flex-direction: column;
  gap: 10px;
  text-decoration: none !important;
  transition: border-color 160ms ease, transform 160ms ease;
}
.contract-card * { text-decoration: none !important; }
.contract-card:hover {
  border-color: var(--vp-c-brand-1);
  transform: translateY(-2px);
}
.contract-card--upstream { border-top-color: #35d07f; }
.contract-card--dashboard { border-top-color: #46b9ff; }
.contract-card--relay { border-top-color: #7c6cff; }
.contract-card__eyebrow {
  font-family: var(--vp-font-family-mono);
  font-size: 10px;
  letter-spacing: .08em;
}
.contract-card strong { font-size: 18px; }
.contract-card > span:not(.contract-card__eyebrow) {
  color: var(--vp-c-text-2);
  font-size: 14px;
}
.contract-card code {
  margin-top: auto;
  font-size: 11px;
  line-height: 1.65;
}
@media (max-width: 760px) {
  .contract-grid { grid-template-columns: 1fr; }
  .contract-card { min-height: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .contract-card { transition: none; }
}
</style>
