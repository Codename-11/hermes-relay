# Structured Media Channel (plan)

> Design-only. Tracked follow-up from the Relay Enhancement Layer work
> (`docs/plans/2026-06-20-relay-enhancement-layer.md`). NOT implemented yet.

## Problem

The agent delivers media to the phone by emitting **text markers** the client parses out
of prose: inline Markdown `![alt](path)`, bare `MEDIA:/abs/path`, and
`MEDIA:hermes-relay://<token>`. This is fragile — three render bugs were fixed on
2026-06-20 alone, all from a single space in a path:
- `MEDIA:/path` bare-marker regex used `/\S+` (broke at the space) → rendered as raw text;
- `![](<path with spaces>)` angle-bracket form kept the `<` → "Unsupported image path";
- `![](/path%20encoded)` wasn't percent-decoded → relay 404.

Sensitivity has the same shape: the only channel for path/markdown media is the markdown
spoiler convention (`||…||` / sentinel alt). It works (the enhancement-layer injection now
teaches the agent to use it) but it's a string convention, not structured data.

## Goal

Replace the fragile, parse-from-prose media path with a **structured delivery channel** so
media intent (path, mime, caption, **sensitive flag**, alt) flows as data — not text the
model types and we regex.

## Options (to evaluate)

1. **Upstream-compatible descriptor** — generalize the upstream media shape
   (`media_kind`, `source_url`, `content`, `filename`) for a capability-gated
   Dashboard/Gateway event, with additive `sensitive` and `alt` fields for the
   Android use case. Pro: one media vocabulary across Hermes clients and native
   platform delivery. Con: requires an upstream contract before Android can
   consume it; the connector's authenticated upload route and credentials must
   never be exposed to the phone.
2. **Structured marker extension** — keep `MEDIA:` but define a JSON-tail form
   (`MEDIA:{"path":...,"sensitive":true}`) the relay/client parse strictly. Pro: incremental.
   Con: still a text marker; the `MEDIA:` strip/deliver logic lives in the agent core
   (`gateway/run.py`) — extending it cleanly may need an upstream change, not a fork patch.
3. **Plugin tool fallback** — a relay tool
   `relay_send_media(path, sensitive, caption, alt)` can register a local file
   and return the existing bearer-gated Relay token. Pro: removable and fully
   structured within the optional plugin. Con: creates a second agent-facing
   media protocol and relies on the model choosing the tool instead of the
   upstream delivery path.

## HRUI-075 upstream interoperability baseline

Current upstream Hermes defines a separate gateway-to-connector `send_media`
operation. It sends media by reference with `media_kind`, `source_url`, optional
caption in `content`, and optional `filename`; it is gated by `supported_ops`,
uses an authenticated re-host for local files, caps uploads at 25 MB, and falls
back safely when an older connector does not advertise the operation. Inbound
expiring or authenticated platform URLs are localized connector-side so
platform credentials do not cross the wire.

Hermes-Relay Android and the optional phone plugin do not implement that
gateway-to-connector protocol. They must not call its `/relay/media` route or
reuse its connector credentials. The descriptor is nevertheless the canonical
interoperability precedent for any future Dashboard/Gateway event. A design
review must map ownership, authentication, expiry, size, `sensitive`, and `alt`
before implementation.

## Recommendation

Lead with **Option 1**, but only after a disposable upstream prototype proves a
generic Dashboard/Gateway event and capability fallback. Keep the current
`MEDIA:`/Markdown/token parsing as compatibility behavior. Do not implement the
old tool-first proposal or duplicate the connector's authenticated media plane
merely because both projects use the Relay name.

The prototype gate covers image and document egress, inbound image
localization, expired references, the 25 MB limit, sensitive rendering, an
unsupported-operation fallback, and an older Hermes baseline. No implementation
ticket should open until the phone credential boundary and additive
`sensitive`/`alt` shape are proven.

## Constraints

- Vanilla-safe + removable. No fork patches — structured marker or gateway-event
  forms that need core changes go through upstream PRs with graceful degradation,
  per the standard-path rule.
- Sensitivity stays **model-emitted** (no relay-side or on-device classifier).
