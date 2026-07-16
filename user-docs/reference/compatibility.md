# Compatibility Contract

The optional compatibility hook fills narrowly defined gaps on older Hermes
hosts. It is not part of upstream Hermes and must never become a requirement for
the default Android connection.

## Native routes win

Hermes-Relay probes capabilities and existing routes before registering a
compatibility handler. When upstream owns a route, the hook leaves it alone.

| Surface | Preferred owner | Compatibility behavior |
|---|---|---|
| `/api/sessions/*` CRUD, messages, fork and chat | Upstream | Retired from the hook; never injected |
| `/v1/skills`, `/v1/toolsets` | Upstream | Preferred discovery |
| `GET /api/skills` | Older native/legacy host | Accepted only as a read-only fallback |
| `GET /api/sessions/search` | Compatibility | Added only when absent |
| `/api/memory` | Compatibility | Older-host memory read/write bridge |
| `GET /api/skills/{name}` | Compatibility | Legacy skill detail |
| `PUT /api/skills/toggle` | Compatibility | Explicit `501 Not Implemented` stub |
| `GET/PATCH /api/config` | Compatibility | Older-host model/config bridge |
| `GET /api/available-models` | Compatibility | Older-host provider-model list |
| Slash-command middleware | Compatibility | Intercepts supported commands on stateless fallback chat routes |

::: warning Compatibility is not Relay
The hook runs inside the Hermes API Server process and uses that server's auth.
It does not start the Relay server, create Relay sessions, or unlock terminal,
voice, media, desktop or phone-control routes.
:::

## Operator commands

```bash
hermes relay compat status
hermes relay compat install   # older hosts only
hermes relay compat remove
```

`hermes relay doctor --json` reports native route reachability, compatibility
hook state and the optional Relay server independently.

## Client rules

- Probe `/v1/capabilities` before legacy routes.
- Treat compatibility routes as fallbacks, not equivalent proof of a current
  upstream version.
- Never infer Relay availability from a compatibility response.
- Do not inject session CRUD routes when the native session API exists.
- Preserve unknown fields and ignore unknown stream events.
- A missing compatibility-only route should disable that feature rather than
  redirecting a write to a different trust domain.

## Removal

Removing the hook does not remove Hermes, its state, or the plugin tree:

```bash
hermes relay compat remove --all
```

Legacy installations may also contain a systemd unit, shell shims, editable
Python package or repository clone. Those belong to the legacy installer and
are covered by [Relay server operations](./relay-server.html#legacy-cleanup-ownership).
