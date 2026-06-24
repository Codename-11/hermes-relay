# Connection Security Indicator — Surfacing, Wording & Docs Plan

**Status:** Draft for review (no implementation yet — placement decisions pending)
**Date:** 2026-06-24
**Owner surface:** Android app (UI), user docs, engineering docs
**Companion to:** [`docs/plans/2026-06-18-native-secure-routes.md`](2026-06-18-native-secure-routes.md) (Features-vs-Routes split + plugin secure proxy mechanics). That plan owns *how routes work*; **this plan owns how security is communicated** to the user across every surface.
**Goal:** Let a user tell, at a glance and without ambiguity, whether their connection to Hermes is encrypted — and by what (TLS, Tailscale/WireGuard, or not at all) — without the app lying or scaring people who are already secure.

---

## Bottom line

Users keep asking "is this secure?" The honest answer today is *"yes, but the app barely tells you, and where it does, it sometimes lies."* The security **model already exists** in code — it's just (a) buried in `Manage → Connections → Advanced`, (b) mislabelled (a Tailscale route is reported as **"Secure — TLS"** when it's actually WireGuard, not TLS), and (c) absent from every at-a-glance surface (the chat status chip, the connection header, the route picker).

This is a **surfacing + wording + docs** task, not a greenfield feature. We promote the existing computation to a single source of truth, correct the copy, place a glanceable badge on the high-traffic surfaces, add a tap-through "Connection security" explainer, and fix the docs that conflate "Tailscale" with "TLS."

---

## What already exists (do not rebuild)

| Asset | File | What it does |
|---|---|---|
| Tri-state model | `ui/components/TransportSecurityBadge.kt` — `TransportSecurityState { AllSecure, Mixed, AllInsecure }` | Badge with lock/shield/lock-open icons + 3 size variants. |
| Overlay-aware "is this route encrypted" | `ActiveConnectionSections.kt:1233` `isSelectedRouteUrlSecure()` → `isEncryptedOverlayRoute()` (`:1242`) | **Already** treats `role=="tailscale"`, `plugin_proxy`, WireGuard/HTTPS security hints, and `hasSecureProxy()` as encrypted — not just `wss`/`https`. |
| Security posture strip | `ActiveConnectionSections.kt:1109` `ActiveCardSecurityPosture` | Renders the badge + "Tailscale detected" + "hardware keystore" + relay-sessions row. **Buried** under the Advanced section. |
| Insecure consent | `ui/components/InsecureConnectionAckDialog.kt`; `ConnectionManager.kt:156–325` (`insecureMode`/`isInsecureConnection`, ws:// block) | Threat-model dialog + reason picker; blocks `ws://` unless insecure mode is on. |
| TOFU cert pinning | `auth/CertPinStore.kt` (TLS-only, per `host:port`) | Pins on first `wss`/`https` connect. Not surfaced to users. |
| Per-endpoint label | `data/Endpoint.kt:136` `displayLabel()` | "LAN" / "Tailscale" / "HTTPS" / "Plugin proxy". |

**The three concrete defects to fix:**
1. **Buried** — the only real security readout lives below `Advanced` on the Manage tab. Most users never see it.
2. **The "TLS lie"** — `resolveStateAppearance(AllSecure)` hardcodes the label **"Secure — TLS"** even when the secure-ness comes from Tailscale/WireGuard (`isEncryptedOverlayRoute` returned true for a `ws://` Tailscale route). Saying "TLS" for a non-TLS link is wrong and erodes trust.
3. **No glanceable surface** — the chat status chip (`RelayApp.kt` ~`920–975`, `ChatTransportStatusBadge.kt`), the connection card header, and the route picker (`EndpointsCard.kt`) show the *route name* but never its *security*.

---

## The hard question: 3 transports → is "secure" even well-defined?

**You asked: does having 3 potential transports make this hard to call "secure"? Yes — and that's the core design problem.** A single paired connection fans out to several surfaces, each with an **independent** scheme (confirmed in `Endpoint.kt:37–94` + `ConnectionViewModel.kt:746–820`):

| Surface | Client | Scheme source | Can be plain while others are TLS? |
|---|---|---|---|
| Gateway chat (`/api/ws`) | `GatewayChatClient` | dashboard URL scheme | yes |
| API / sessions (SSE) | `HermesApiClient` | `endpoint.api.tls` | yes |
| Dashboard (Manage/voice) | `DashboardApiClient` | `endpoint.dashboard.url` ∨ derived from `api.tls` | yes |
| Relay (terminal/bridge/tools) | `ConnectionManager` | `endpoint.relay.url` (`ws`/`wss`) | yes |

So **a connection is not uniformly secure** — relay can be `ws://` while the API is `https://`. (Concretely: one paired connection can carry API `https://host:8642`, dashboard derived to `https://host:9119`, and relay `ws://host:8767` — secure chat/Manage, plain relay — at the same time.) A single binary "Secure" badge would lie. The existing `AllSecure / Mixed / AllInsecure` rollup is the right instinct; we keep it but make it **honest and overlay-aware**.

**Decision (proposed):** show a **connection-level rollup for the glance, per-surface truth on tap.**
- **Glance badge** = worst-case across the surfaces *actually in use*: all encrypted → secure; some plain → "Mixed"; all plain with no overlay → "Not encrypted."
- **Tap → detail sheet** = the per-surface breakdown (Chat/API: 🔒, Relay: ⚠️, …) so power users get the truth without the chip having to.
- Crucially, **"encrypted" includes overlay transports** (Tailscale/WireGuard/plugin proxy), not just TLS — because for the user those *are* secure end-to-end.

---

## The wording model (the part that fixes the trust problem)

Reframe from a binary "Secure/Insecure" to **mechanism-first, 4 outcomes**. The key correction: **Tailscale is secure** — WireGuard gives end-to-end encryption + device identity, arguably stronger than TOFU-pinned TLS. Telling a Tailscale user they're "insecure/plain" is both wrong and the likely reason they keep asking.

| State | When | Icon | Chip copy | Tone |
|---|---|---|---|---|
| **TLS** | every in-use surface is `wss`/`https` | 🔒 Lock | `Encrypted · TLS` | green |
| **Private network** | plain scheme, but route is Tailscale / WireGuard / plugin proxy | 🛡️ Shield | `Encrypted · Tailscale` (or `· WireGuard` / `· Proxy`) | green |
| **Mixed** | some surfaces encrypted, some plain (a secure fallback exists) | 🛡️ Shield | `Mixed routes` | amber |
| **Not encrypted** | plain `ws`/`http`, no overlay | ⚠️ Lock-open | `Not encrypted · LAN` | amber→red by context |

Notes:
- Both 🔒 and 🛡️ are **green/"secure"** — only true plaintext-without-overlay is a warning. This is the single most important copy change.
- Keep "Plain"/"Not encrypted" (never a blank); avoid the word "Insecure" in the chip (reserve it for the consent dialog where the threat model is explained).
- The detail sheet spells out the distinction in one line each: *"TLS — encrypted to this server's certificate (pinned on first connect)."* / *"Tailscale — encrypted by your tailnet (WireGuard), not TLS."* / *"Not encrypted — only safe on a network you fully trust."*
- **Code change:** replace the hardcoded `"Secure — TLS"` label (`TransportSecurityBadge.kt:219`) with mechanism-derived copy, and split `AllSecure` into `Tls` vs `Overlay` so the badge can say which.

---

## Placement audit & recommendation

Full surface inventory in the appendix. Recommended placements, highest-traffic first:

### P1 — Chat bottom status chip (the one everyone sees)
`RelayApp.kt` ~`920–975`, beside `ChatTransportStatusBadge` + route label. Today: `⚡ Gateway · Tailscale  gpt-5.5 / profile: default`. Add a leading security glyph:

```
 ┌─────────────────────────────────────────────────────────┐
 │ ⚡ Gateway   🛡️ Tailscale          gpt-5.5 / profile: default │   ← encrypted via Tailscale (green shield)
 └─────────────────────────────────────────────────────────┘
 ┌─────────────────────────────────────────────────────────┐
 │ ⚡ Gateway   🔒 TLS                 gpt-5.5 / profile: default │   ← encrypted via TLS (green lock)
 └─────────────────────────────────────────────────────────┘
 ┌─────────────────────────────────────────────────────────┐
 │ ⚡ Gateway   ⚠️ Not encrypted       gpt-5.5 / profile: default │   ← plain LAN, no overlay (amber)
 └─────────────────────────────────────────────────────────┘
```
Glyph replaces/precedes the bare route word so "Tailscale" now reads as *secure-Tailscale*. Tap the chip → **Connection security** detail sheet.

### P2 — Connection card header (Manage → Connections)
`ActiveConnectionSections.kt` card header — add the same badge next to the `Active` pill so the connection list communicates security without expanding Advanced. Promotes the existing `ActiveCardSecurityPosture` logic up out of the Advanced fold.

### P3 — Route picker (`EndpointsCard.kt`)
Per-route security glyph on each candidate row, so when a user switches routes they see which are encrypted *before* committing:
```
 ○ LAN        ⚠️ Not encrypted     192.168.x.x   ·  Probe ✓
 ● Tailscale  🛡️ Encrypted         100.x.y.z     ·  Active
 ○ Public     🔒 TLS               <host>.ts.net ·  Probe ✓
```

### P4 — "Connection security" detail sheet (new, the tap target for P1/P2)
A small bottom sheet that is the single place the per-surface truth + the explainer lives:
```
 Connection security — <your server>
 ────────────────────────────────────
 Overall   🛡️ Encrypted (Tailscale)

 Chat (gateway)   🛡️ Tailscale     http://100.x.y.z:9119
 API / sessions   🛡️ Tailscale     http://100.x.y.z:8642
 Relay tools      🛡️ Tailscale     ws://100.x.y.z:8767
 ────────────────────────────────────
 🛡️ Tailscale encrypts this with WireGuard (not TLS).
    Cert pinning applies only to TLS routes.
 [ Learn about connection security → ]   (docs link)
```

> **For your review:** P1 + P4 are the must-haves (glance + truth-on-tap). P2/P3 are high-value but optional for a first cut. The detail sheet is also the natural home for the **TOFU pin** ("Server identity pinned ✓") and the hardware-keystore line that currently sit in the buried posture strip.

---

## Secure proxy: status and how it fits

The **plugin secure proxy** (the "Secure proxy — Not advertised" row) is a **stub today**: the Android side models it (`Endpoint.kt` `ProxyEndpoint`, `plugin_proxy` role, `hasSecureProxy()`), and `isEncryptedOverlayRoute()` already treats it as encrypted — but **the relay has no proxy-forward implementation, pairing never emits a `plugin_proxy` candidate, and no cert/pin is generated.** Enabling it end-to-end is the unbuilt **Phase 4** of `2026-06-18-native-secure-routes.md` (relay HTTP-forward routes + `RELAY_SSL_*` cert + pairing emission; ~2–3 wk).

**Implication for this plan:** the indicator must **not block** on the proxy. We design the wording/placement so that *when* a `plugin_proxy` route is advertised it slots in as a 🔒 **TLS (pinned)** route automatically (it already would, via `isEncryptedOverlayRoute`). Until then it stays honestly "Not advertised." Recommend a separate spike to stand it up + test on the server (tracked in `TODO.md`), independent of this UX work.

---

## Documentation plan

The docs currently **conflate "Tailscale" with "TLS/secure"** in several places — fixing this is half the user-facing win.

**New page:** `user-docs/architecture/connection-security.md` — "Is my connection secure?" Covers: `ws`/`wss` & `http`/`https`; what Tailscale actually does (WireGuard VPN, encrypted + identity, *plus* optional Serve-HTTPS); TLS + TOFU pinning; the per-surface model; how to read the in-app badge; how to get a TLS route (Tailscale Serve `--https`, reverse proxy, or the future plugin proxy). Add to the `/architecture/` sidebar after `security.md`. (Pairs 1:1 with the in-app detail-sheet "Learn more" link.)

**Conflation fixes (call out "WireGuard ≠ TLS, both are secure"):**
- `docs/decisions.md` §15 (`:441` TTL `wss or Tailscale → 30d`) and §24 — annotate that Tailscale's security is WireGuard, separate from `wss`.
- `user-docs/architecture/security.md:62–69` — split "Tailscale (VPN + optional managed TLS)" from "reverse proxy (TLS only)."
- `user-docs/guide/remote-access.md:27–32, 70–84` — distinguish *who terminates TLS* from *Tailscale provides the network*.
- Document TOFU pinning for users for the first time (currently code-only).

---

## Open decisions (your call before implementation)

1. **Is "plain over Tailscale" green or amber?** Recommendation: **green 🛡️ "Encrypted · Tailscale"** (WireGuard is genuinely secure). This is the crux of the trust fix. (Alternative: amber, treating only TLS as fully green — more conservative, but keeps confusing Tailscale users.)
2. **Glance scope:** connection-rollup badge + per-surface on tap (recommended), vs. always show per-surface inline (busier).
3. **First-cut scope:** P1 (chat chip) + P4 (detail sheet) + wording fix + docs page — vs. also P2/P3 in the same PR.
4. **Word choice:** "Encrypted" vs "Secure" vs "Private" for the overlay state. Recommendation: **"Encrypted · <mechanism>"** (concrete, non-marketing).
5. **Proxy:** confirm we keep it out of scope here (separate Phase-4 spike).

---

## Implementation tiers (after decisions land)

> Scope: **A** = ship-now UX · **Doc** = docs · effort **S/M/L**.

### A1 — Single source of truth: `ConnectionSecurity` model · M
Lift `isSelectedRouteUrlSecure`/`isEncryptedOverlayRoute` + the per-surface URL scheme reads into a ViewModel-exposed `StateFlow<ConnectionSecurity>` (`{ overall: Tls|Overlay|Mixed|Plain, perSurface: Map<Surface, SecurityKind>, mechanism: String }`). Every surface reads this one flow.
**Files:** new `viewmodel/ConnectionSecurity.kt`; `ConnectionViewModel.kt`; refactor `ActiveConnectionSections.kt:1109–1254`.

### A2 — Fix the wording / split `AllSecure` into Tls vs Overlay · S
Replace hardcoded `"Secure — TLS"`; mechanism-derived copy; new state for overlay. Pure `TransportSecurityBadge.kt` change + tests.

### A3 — P1 chat status chip glyph · S
Add the security glyph to the chat bottom strip; tap → detail sheet. **Files:** `RelayApp.kt`, `ChatTransportStatusBadge.kt`.

### A4 — P4 "Connection security" detail sheet · M
New bottom sheet; per-surface rows + explainer + TOFU/keystore lines + docs link. **Files:** new `ui/components/ConnectionSecuritySheet.kt`.

### A5 — P2 header badge + P3 route-picker glyphs · M (optional first cut)
**Files:** `ActiveConnectionSections.kt`, `EndpointsCard.kt`.

### Doc1 — `connection-security.md` + conflation fixes · M
New user-docs page + the four conflation edits + TOFU documentation.

### Spike — stand up & test the plugin secure proxy · L (separate, not blocking)
Phase 4 of `2026-06-18-native-secure-routes.md`. Tracked in `TODO.md`.

---

## Appendix — full UI surface inventory

| Surface | File:area | Shows today | Security data available |
|---|---|---|---|
| Chat status chip | `RelayApp.kt` ~920–975; `ChatTransportStatusBadge.kt` | transport tier + route + model | route role + per-surface URL schemes |
| Connection card header | `ActiveConnectionSections.kt` (card header) | name + Active + route summary | full per-surface |
| Status rows (API/Dashboard/Relay/Session) | `ActiveConnectionSections.kt:108–219` | reachable/connected + "Connected · Tailscale" | role known, security not rendered |
| Feature rows (incl. "Secure proxy") | `ActiveConnectionSections.kt:227–380` | Ready/Configured/Not advertised | proxy advertise flag |
| Security posture strip | `ActiveConnectionSections.kt:1109–1231` | **the existing badge** (buried under Advanced) | full (this is the source to promote) |
| Route picker | `EndpointsCard.kt:79–194` | per-route role + health | per-candidate scheme |
| Insecure toggle + ack | `ActiveConnectionSections.kt:806–866`; `InsecureConnectionAckDialog.kt` | warning + reason picker | `isInsecureConnection` |
| Connection info sheet | `ConnectionInfoSheet.kt` | session state | session relay URL |
| Pair wizard confirm | `OnboardingScreen.kt` / pairing flow | route candidates | candidate schemes (good place for per-route glyph at commit time) |
