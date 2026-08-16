# HRUI-040 / HRUI-061 / HRUI-065 Android-Relay Audit

Date: 2026-07-23

## Scope

This audit reviews the remaining upstream-impact ledger items that are relevant
to the Android app and Relay plugin lane. Desktop-only behavior remains deferred.

## HRUI-040 — interim assistant events

Status: covered for Android gateway consumption.

Evidence:

- `GatewayEventMapper` consumes `message.interim`, accepts `text`, `message`,
  `preview`, or `rendered`, and forwards the upstream `already_streamed` flag.
- `message.complete` suppresses duplicate final backfill when
  `response_previewed` indicates that the final text was already previewed.
- `GatewayEventMapperTest` covers interim text before final, streamed interim
  continuity, already-streamed interim handling, and null-payload safety.
- `docs/upstream-contributions.md` records the additive upstream contract and
  Android compatibility behavior.

No Android/Relay patch is required in this batch.

## HRUI-061 — active-turn redirect, ordering, and resume

Status: covered for Android gateway consumption; phone live smoke remains the
final acceptance gate.

Evidence:

- `GatewayChatClient.steer()` sends `session.redirect` first, falls back to
  `session.steer` only when the gateway reports method-not-found, and fails
  locally when there is no live session.
- `ChatViewModel.steerActiveTurn()` keeps accepted redirects as local user echo
  rows so the post-turn history reconcile can replace them with server-owned
  rows.
- `GatewayChatClientTest` covers accepted redirect, legacy fallback, rejection,
  and no-live-session behavior.
- `ChatViewModelGatewayInboundTurnTest` covers unsolicited/resumed gateway turns,
  queued-message drain after a rejected redirect, and cold foreground prewarm
  reloading a completion missed while disconnected.

No additional Android code patch is required before live testing from `dev`.

## HRUI-065 — desktop voice collector follow-up

Status: deferred for this batch.

Evidence:

- The ledger item is desktop-specific. The Android and Relay surfaces already
  route voice through their own standard/relay voice paths and do not consume
  the desktop collector directly.
- Pulling desktop collector work into this branch would expand beyond the
  app/Relay scope and would not improve the requested live Android validation.

Keep HRUI-065 open for a future desktop-focused pass.

