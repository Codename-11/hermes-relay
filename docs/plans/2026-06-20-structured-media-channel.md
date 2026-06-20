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

1. **Tool-based delivery** — a relay tool `relay_send_media(path, sensitive, caption, alt)`
   the agent calls (generalizing `android_screenshot(sensitive)`). The tool registers with
   the relay (carrying the bit), returns a token; the client renders structured, blurred per
   the flag. Pro: fully structured, no prose parsing, sensitivity native. Con: the agent must
   *choose* the tool over typing a path (prompt/tooling nudge needed).
2. **Structured marker extension** — keep `MEDIA:` but define a JSON-tail form
   (`MEDIA:{"path":...,"sensitive":true}`) the relay/client parse strictly. Pro: incremental.
   Con: still a text marker; the `MEDIA:` strip/deliver logic lives in the agent core
   (`gateway/run.py`) — extending it cleanly may need an upstream change, not a fork patch.
3. **Relay media event over the gateway** — a structured `media.deliver` event on the
   `/api/ws` gateway carrying the descriptor. Pro: best UX (live, structured, gateway-native).
   Con: largest change; depends on the gateway surface.

## Recommendation

Lead with **Option 1** (relay tool) — it reuses the proven `android_screenshot(sensitive)`
pattern, is fully in our plugin, removable, and carries sensitivity natively. The
enhancement-layer context block can nudge the agent to prefer it for file delivery. Keep the
text-marker parsing as the back-compat fallback (now hardened for spaces). Revisit Option 3
if/when we want live structured media on the gateway.

## Constraints

- Vanilla-safe + removable (relay plugin only). No fork patches — structured marker /
  gateway-event forms that need core changes go through upstream PRs with graceful
  degradation, per the standard-path rule.
- Sensitivity stays **model-emitted** (no relay-side or on-device classifier).
