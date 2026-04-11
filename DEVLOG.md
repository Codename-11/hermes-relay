# Hermes-Relay — Dev Log

## 2026-04-11 — Relay status flicker fix + Dev Workflow docs in CLAUDE.md

Two small follow-ups from the same session:

**1. Connection card flicker on Settings entry.**
When opening Settings with a stored session token, the Relay status row flashed through 3 rapid states: red "Disconnected" → amber "Stale — tap to reconnect" → amber "Connecting..." → green "Connected". Users saw the middle two as a flicker.

Root cause: `authState` default is `Unpaired` until the `EncryptedSharedPreferences` read finishes (~50ms async). During that gap the row shows red "Disconnected" which is actually correct. Once `authState` flips to `Paired`, `isRelayStale` becomes true and the row shows "Stale — tap to reconnect". The `LaunchedEffect(Unit)` that fires `reconnectIfStale()` then triggers `ConnectionState.Connecting`, changing the label to "Connecting...". These rapid transitions are the flicker.

Fix: added a screen-local `isAutoReconnecting` flag that's true for up to 5 seconds on screen entry (or until `ConnectionState.Connected` lands, whichever is first). During that window the status text unifies all the non-Connected sub-states into one consistent `"Reconnecting..."` label. After 5s the flag drops and the row falls through to the normal state machine — so the "Stale — tap to reconnect" affordance still works for cases where the user is genuinely sitting in a stale state (backgrounding the app, network flapping, etc.). Only the initial-entry transition gets the unified label.

The fix is purely presentational — the underlying state machine is unchanged. `isConnecting` is wired to treat `isAutoReconnecting` the same as the real `Connecting` / `Reconnecting` states so the ConnectionStatusBadge's pulse ring animates continuously through the window.

**2. Dev workflow documented in `CLAUDE.md`.**
Bailey asked for the typical dev loop to be documented in `CLAUDE.md` so Claude doesn't have to rediscover the flow each session. Added a new "Typical Dev Loop" subsection + "Server Deployment" table + "Where Python vs. Kotlin changes land" table. Covers:
- Edit-in-Windows, test-on-Linux-server split
- Python plugin edits via `pip install -e` editable path → `git pull` on server picks them up
- `hermes-gateway.service` systemctl user-unit restart when tool code changes
- Manual `nohup` / `setsid` restart for the relay process (which isn't a systemd service on this deployment)
- Running tests via `python -m unittest plugin.tests.test_<name>` (avoiding the pre-existing `conftest.py` that imports the uninstalled `responses` module)
- The hard convention: Claude never builds/installs APKs — Bailey uses Android Studio's run button
- Where sensitive info lives (`~/SYSTEM.md` on the server, `~/.hermes/.env`) — explicitly NOT in this repo
- The PATH conventions (venv, plugin symlink, relay log, config yaml, qr-secret)

Keeps the doc free of any host-specific identifiers (IP, username, SSH key) — those stay on the server side.

**Also included in this entry but landed in a separate earlier commit:** `fix(auth): extend without grants regenerates from defaults, not absolute-time clamp` (`c209c99`). The first cut of `SessionManager.update_session` tried to preserve existing grants via a `_clamp_grants_to_lifetime` helper when only TTL changed. That made sense for shortening (clip grants that exceed the new session) but produced nonsense for extending: a 1h bridge grant made 30 minutes ago, extended to 90d, would still have an absolute expiry of 30 minutes from now — the user would tap Extend and find bridge was about to expire anyway. Corrected to regenerate grants from `_default_grants(new_ttl, now)` when only TTL changes, giving fresh allocations for the new lifetime. Deleted the now-unused `_clamp_grants_to_lifetime` helper. Caught by `test_extend_ttl_zero_means_never` which asserts that extending a finite session to never-expire produces all-null (never) grants.

**Files changed:**
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` — `isAutoReconnecting` flag + updated `ConnectionStatusRow` wiring
- `CLAUDE.md` — new Dev Workflow subsections (Typical Dev Loop, Server Deployment, Where Python vs. Kotlin changes land)
- `plugin/relay/auth.py` — (separate commit `c209c99`) `update_session` semantic fix

No server side changes in this entry — the flicker fix is phone-only and the docs don't deploy.

---

## 2026-04-11 — Grant renewal action (PATCH /sessions/{prefix} + "Extend" button)

**Why this exists:**
Pairing/security overhaul shipped a Paired Devices screen with list + revoke, but no way to change a session's TTL or grants after initial pair. Users who paired with "1 day" when trying out the feature and decided to keep it had to revoke + re-pair. This closes that gap with the smallest possible surface area: one new route, one new client method, one new button, one new dialog reuse. Scope #4 from the gap list — small and concrete enough to do directly rather than via agent team.

**Semantics decision: TTL restarts the clock.**
"Extend by 30 days" = "30 days from now", not "add 30 days to the existing expiry." This matches what users mean when they tap Extend: they want the session to be valid for another 30 days starting today. It also means Extend can SHORTEN a session (pick a shorter duration or "Never" → fewer) — the button is labeled "Extend" for the common case but the semantics are really "update TTL policy".

**Grant handling:**
- If caller passes `grants`: re-materialize from scratch via `_materialize_grants` (uses defaults for channels the caller didn't specify, clamps each to the new session lifetime).
- If caller passes ONLY `ttl_seconds`: keep existing grants but re-clamp them via a new `_clamp_grants_to_lifetime` helper so a shorter TTL correctly clips any grant that would outlive the session. A longer TTL leaves grants unchanged (they were already ≤ the old expiry, which is now ≤ the new longer expiry, so no clipping needed).
- If caller passes both: apply TTL first (new session expiry), then materialize the provided grants clamped to the new expiry.
- If caller passes neither: 400. No-op calls are bugs.

**UX:**
Reuses the existing `SessionTtlPickerDialog` directly — no new component needed. The dialog's "Keep this pairing for…" title works for both the pair flow and the extend flow (tense-neutral). Preselected option is computed from the session's current remaining lifetime rounded to the nearest picker option — never-expire sessions preselect "Never", 1-hour-remaining sessions preselect "1 day" (shortest real option), 25-day-remaining sessions preselect "30 days", etc.

## Files

**Server** (`plugin/relay/`):
- `auth.py` — new `SessionManager.update_session(token, ttl_seconds?, grants?) -> Session | None` that restarts-the-clock on TTL and re-materializes/re-clamps grants. New module-level `_clamp_grants_to_lifetime(grants, ttl_seconds, now)` helper alongside the existing `_materialize_grants`. Expired sessions are NOT silently resurrected — caller should re-pair instead.
- `server.py` — new `handle_sessions_extend(request)` handler doing full validation (non-negative `ttl_seconds`, non-negative numeric grant values, at least one field present) + the prefix → session → update dance that mirrors `handle_sessions_revoke`. Route registered via `app.router.add_patch("/sessions/{token_prefix}", handle_sessions_extend)` — PATCH is a distinct method on the same pattern, no ordering collision with existing GET/DELETE.
- `plugin/tests/test_sessions_routes.py` — 10 new tests: bearer-required, nonexistent prefix → 404, empty body → 400, negative TTL → 400, bad grant shape → 400, TTL-only restarts the clock (asserts `new_expiry ≈ now + new_ttl`, NOT old_expiry + new_ttl), `ttl_seconds=0` → never (null grants), grants-only leaves expiry alone, shorter TTL clips grants, self-extend, helper `_settle()` for the 1ms async delay needed on fast machines to make timestamp assertions meaningful.

**Phone** (`app/src/main/kotlin/.../`):
- `network/RelayHttpClient.kt` — new `extendSession(tokenPrefix, ttlSeconds?, grants?)` method. Hand-rolls the small JSON body to avoid pulling in another serializer branch (two optional fields, trivial to write, auditable). 400/401/404/409/5xx are all mapped to user-facing error messages. 404 is a hard failure here (unlike revoke where "already gone" → success) — if you're extending an active session and it's gone, that's surprising and should surface.
- `viewmodel/ConnectionViewModel.kt` — new `suspend fun extendDevice(tokenPrefix, ttlSeconds): Boolean`, mirrors `revokeDevice`'s shape (refresh list on success, surface error via `pairedDevicesError`). Grants intentionally not exposed on this path — MVP UX is "pick a new duration", server-side re-clamping handles the rest. Power users can call `RelayHttpClient.extendSession` directly for grant editing.
- `ui/screens/PairedDevicesScreen.kt` — `pendingExtend: PairedDeviceInfo?` state alongside the existing `pendingRevoke`. New dialog branch renders `SessionTtlPickerDialog` with the current remaining lifetime preselected (`expires_at - now`, clamped to ≥ 0, or 0 for never-expire sessions). Confirming calls `connectionViewModel.extendDevice(...)` inside a coroutine + shows a snackbar. `DeviceCard` action row becomes a 50/50 split between "Extend" and "Revoke" buttons (instead of a single full-width revoke). Both buttons are `OutlinedButton`; the revoke button keeps the error color tint.

**Docs:**
- `docs/relay-server.md` + `user-docs/reference/relay-server.md` — new PATCH /sessions/{token_prefix} route row
- `user-docs/reference/configuration.md` — Extend button description in the Paired Devices subsection
- `CLAUDE.md` — Integration Points table row for the new route
- this DEVLOG entry

## Not changed (intentionally)

- `__version__` stays at 0.2.0 per prior direction (we never released 0.2.0)
- No grant-editing UI — power users can hit the endpoint directly via a future tool
- No "extend by increment" UX (e.g. "+30 days on top of current") — semantics are always "set new TTL from now". Easier to reason about, matches what the picker already shows.
- No ADR bump — this is a small follow-up to ADR 15's architecture, not a new decision. ADR 15 already documents the session mutation model at a high level.

## Test plan (on-device)

1. Pair with `--ttl 1d` so you have a short session
2. Open Paired Devices → find the current device → tap Extend
3. Picker should show "1 day" preselected. Pick "30 days" → confirm.
4. Verify the card now shows "Expires ~30 days from now" and the snackbar says "Session expiry updated"
5. Tap Extend again → pick "Never expire" → confirm. Verify the card now shows "Never" and all channel grant chips show "never" too
6. Tap Extend again → pick "1 day" → confirm. Verify the card now shows ~1 day from now AND the grant chips are now all ≤ 1 day (clamped by the shortened session)
7. Try to Extend a non-current session (if you have multiple paired) → should work the same way

---

## 2026-04-11 — Pairing + Security Architecture Overhaul (grants, TTL, Keystore, TOFU, devices)

**Why this exists:**
The existing pairing model has been minimal since day one: pairing codes (one-shot, 10 min TTL) → session tokens (30 days hardcoded, single expiry, no per-channel grants, stored in `EncryptedSharedPreferences`). After the inbound media work exposed the "paired phone gets rate-limited to death on relay restart" gap, Bailey flagged the entire pairing story as ready for a pass. Explicit asks:

1. Secure transport the default, insecure opt-in with clear UI
2. User-chosen TTL at pair time (1d/7d/30d/90d/1y/never)
3. Separate defaults by channel (terminal + bridge shorter than chat)
4. Never-expire ALWAYS selectable — *"don't force check, just allow based on user intent"* (per Bailey)
5. Tailscale detection — informational only, not opinionated about defaults
6. Hardware-backed token storage
7. TOFU cert pinning with explicit reset on re-pair
8. Device revocation UI (Paired Devices screen)
9. QR payload signing
10. Phase 3 bidirectional pairing foundation

Delivered by two parallel background agents (server + phone) + local docs pass. No upstream hermes-agent changes — everything lives in files we own.

---

### Server side — `plugin/relay/`

**Data model (`auth.py`):**
- `Session` gains `grants: dict[str, float]` (per-channel expiry timestamps), `transport_hint: str` (`"wss"` / `"ws"` / `"unknown"`), `first_seen: float`, and handles `math.inf` for never-expire (`is_expired` is False for inf; JSON serializes inf → `null`).
- New `PairingMetadata` dataclass carries `ttl_seconds`, `grants`, and `transport_hint` through the pairing flow. `_PairingEntry.metadata` stores it; `PairingManager.register_code(..., ttl_seconds, grants, transport_hint)` accepts it; `consume_code` returns the metadata dataclass (or `None`) instead of a bool — existing boolean callers use `is not None`.
- `SessionManager.create_session` now takes `ttl_seconds`, `grants`, `transport_hint` params (all with backwards-compatible defaults). `_materialize_grants` helper resolves seconds-from-now durations to absolute expiries and **clamps** each grant to the overall session lifetime — no terminal grant can outlive its session. Default caps: terminal 30 days, bridge 7 days, chat = session lifetime. Constants: `DEFAULT_TERMINAL_CAP`, `DEFAULT_BRIDGE_CAP`.
- `SessionManager.list_sessions`, `SessionManager.find_by_prefix` — used by the new routes below.
- `RateLimiter.clear_all_blocks()` — new method that resets `_blocked` and `_failures` dicts. Called unconditionally when `/pairing/register` succeeds, because the operator is explicitly re-pairing and stale rate-limit state is noise. **This fixes the "phone rate-limited for 5 minutes after relay restart" bug that was biting Bailey right when this session started.**

**Routes (`server.py`):**
- `handle_pairing_register` — now accepts optional `ttl_seconds` / `grants` / `transport_hint` in the JSON body and forwards them to the `_PairingEntry.metadata`. Host-registered metadata takes precedence over phone-sent metadata (operator policy is authoritative). Calls `server.rate_limiter.clear_all_blocks()` on success.
- `handle_pairing_approve` — new **`POST /pairing/approve`**, Phase 3 bidirectional pairing stub. Loopback-only, same shape as `/pairing/register`. Marked with `# TODO(Phase 3):` — full flow needs a pending-codes store so operators review rather than rubber-stamp. Route + wire shape locked in now so the Android agent has something to target.
- `handle_sessions_list` — new **`GET /sessions`**, bearer-auth'd. Returns `{"sessions": [...]}` where each entry carries `token_prefix` (first 8 chars, never the full token), `device_name`, `device_id`, `created_at`, `last_seen`, `expires_at` (null for never), `grants`, `transport_hint`, `is_current`. Full tokens are NEVER included — only the prefix — so a caller can't extract another session's credential.
- `handle_sessions_revoke` — new **`DELETE /sessions/{token_prefix}`**, bearer-auth'd. Matches on first-N-char prefix (≥ 4 chars). Returns 200 on exact match, 404 on zero matches, 409 on ambiguous (2+) matches with the count in the body. Self-revoke is allowed and flagged via `revoked_self: true` so the phone knows to wipe local state.
- `_authenticate` — extended to thread pairing metadata into the new session + include `expires_at`, `grants`, `transport_hint` in the `auth.ok` payload. `math.inf` → `null` on the wire.
- `_detect_transport_hint` helper — sniffs `request.transport.get_extra_info('ssl_object')` (non-None → `"wss"`), falls back to `request.scheme`, defaults to `"unknown"`. Runs only when pairing metadata didn't already supply a hint.

**QR signing (new `qr_sign.py`):**
- `load_or_create_secret(path)` — reads `~/.hermes/hermes-relay-qr-secret` if present (32 bytes, `0o600`, owner-only). On first run generates + writes via `secrets.token_bytes(32)` with `os.umask` safety.
- `canonicalize(payload)` — JSON-encode with `sort_keys=True, separators=(",", ":")`, `allow_nan=False` (so accidentally signing `math.inf` crashes loudly). Explicitly strips the `sig` field before canonicalization so signing a signed payload is idempotent.
- `sign_payload(payload, secret) -> str` — base64 HMAC-SHA256.
- `verify_payload(payload, sig, secret) -> bool` — constant-time compare via `hmac.compare_digest`.
- 13 test assertions covering canonicalization order-independence, `sig` exclusion, round-trip, tamper + wrong-secret + malformed-sig rejection, file perm check.

**Pair CLI (`plugin/pair.py` + `plugin/cli.py`):**
- New flags: `--ttl DURATION` (parses `1d` / `7d` / `30d` / `1y` / `never`) and `--grants SPEC` (`terminal=7d,bridge=1d`).
- `build_payload(..., sign=True)` — auto-bumps `hermes: 1 → 2` when any v2 field is present, embeds `ttl_seconds` / `grants` / `transport_hint` in the `relay` block, computes and attaches the HMAC signature.
- `read_relay_config` now reads `RELAY_SSL_CERT` to determine TLS status; `_relay_lan_base_url(tls=True)` emits `wss://` when set.
- `register_relay_code` sends the new fields so `/pairing/register`'s metadata attaches to the code.
- `render_text_block` shows `Pair: for 30 days` / `Pair: indefinitely` + per-channel grant labels.

**Tests added** (all `unittest.IsolatedAsyncioTestCase` / `AioHTTPTestCase`, runs via `python -m unittest`):
- `test_qr_sign.py` — 13 assertions covering canonicalize / sign / verify / load_or_create_secret.
- `test_session_grants.py` — default TTL + grant caps, 1-day session clamping terminal/bridge, never-expire (math.inf everywhere), explicit grants clamped to session, `grant=0` semantics, transport_hint, unknown-channel → expired, pairing metadata register/consume/one-shot/format-reject, list_sessions / find_by_prefix / revoke.
- `test_sessions_routes.py` — 401 on missing + invalid bearer, GET shape (no full-token leak, 8-char prefix, `is_current` correct, null for never-expire), DELETE 404 / 200 / 409 (ambiguous) / self-revoke.
- `test_rate_limit_clear.py` — unit `clear_all_blocks` + integration `/pairing/register` clears pre-existing blocks + metadata round-trip + invalid-ttl / bad-transport rejection + `/pairing/approve` loopback gate.

---

### Phone side — `app/src/main/kotlin/.../`

**Token storage + TOFU (new `auth/SessionTokenStore.kt`, `auth/CertPinStore.kt`):**
- `SessionTokenStore` interface with two implementations:
  - `KeystoreTokenStore` — StrongBox-preferred via `setRequestStrongBoxBacked(true)` when `FEATURE_STRONGBOX_KEYSTORE` is present (Android 9+). Best-effort: `tryCreate` swallows exceptions so broken OEM keystores don't brick pairing; falls back to the legacy store.
  - `LegacyEncryptedPrefsTokenStore` — existing `EncryptedSharedPreferences` path, TEE-backed via `MasterKey.AES256_GCM`.
- One-shot migration: on first launch post-upgrade, reads `session_token` / `device_id` / `api_server_key` / paired-metadata blob from the legacy `hermes_companion_auth` file, writes them to the new store, clears the legacy. If Keystore is unavailable, legacy stays as the active store and no migration happens — users never lose their session.
- `hasHardwareBackedStorage: Boolean` flag — true only for the StrongBox path. Legacy TEE-backed reports false. Surfaced in the Session info sheet as "Hardware (StrongBox)" vs "Hardware (TEE)".
- `CertPinStore` — DataStore-backed map of `host:port` → SHA-256 SPKI fingerprints. `recordPinIfAbsent` on first successful wss connect; `buildPinnerSnapshot` produces an OkHttp `CertificatePinner`. `ConnectionManager` takes a fresh snapshot on **every** `doConnect`, so a `removePinFor(host)` during re-pair is honored on the next connect — no coordination needed between `AuthManager` and `ConnectionManager`. Plaintext `ws://` short-circuits via `isPinnableUrl`.
- `applyServerIssuedCodeAndReset(code, relayUrl?)` now wipes the TOFU pin for the target host — a QR re-pair is explicit consent to a possibly-new cert fingerprint.

**Auth + session model (`auth/AuthManager.kt`, new `auth/PairedSession.kt`):**
- `PairedSession` data class: `token, deviceName, expiresAt: Long?, grants: Map<String, Long?>, transportHint, firstSeen, hasHardwareStorage`.
- `PairedDeviceInfo` wire model mirrors the server's `GET /sessions` response — `token_prefix, device_name, device_id, created_at, last_seen, expires_at, grants, transport_hint, is_current`.
- `AuthManager.currentPairedSession: StateFlow<PairedSession?>` — UI reads this for pair metadata without poking at prefs.
- `handleAuthOk` parses `expires_at` / `grants` / `transport_hint` from the payload, tolerates both int and float epoch seconds, persists alongside the token.
- `authenticate(ttlSeconds)` injects `ttl_seconds` + `grants` into pairing-mode auth envelopes. Session-mode re-auth (existing session token present) does NOT re-send these — the server keeps the grant table keyed on the original pair.

**QR payload v2 (`ui/components/QrPairingScanner.kt`):**
- `HermesPairingPayload` gains `sig: String?` (parsed, stored, NOT verified — the phone doesn't have the HMAC secret). `RelayPairing` gains `ttlSeconds: Long?`, `grants: Map<String, Long>?`, `transportHint: String?` — all optional with defaults.
- `parseHermesPairingQr` no longer rejects on version mismatch — any `hermes >= 1` with a non-blank host decodes. Future v3+ still parses via `ignoreUnknownKeys = true`. v1 QRs with no `hermes` field also parse.
- Signature verification is a `// TODO` in the parser — full verification requires a phone-side secret distribution path the protocol doesn't yet define.

**TTL picker (new `ui/components/SessionTtlPickerDialog.kt`):**
- Radio list: **1 day / 7 days / 30 days / 90 days / 1 year / Never expire**.
- `defaultTtlSeconds(qrTtl, transportHint, tailscale)` logic: QR operator value wins; else wss OR Tailscale → 30d; plain ws → 7d; unknown → 30d.
- **Never expire is always selectable** per Bailey's explicit "don't force check" direction. Shows an inline warning — *"This device will stay paired until you revoke it manually. Only choose this if you control the network — LAN, Tailscale, VPN, or TLS."* — but does NOT gate.
- Dialog ALWAYS opens on QR scan so the user confirms the TTL — the trust model is the user's judgment, not the QR's.
- Last user pick persists to `PairingPreferences.pairTtlSeconds` and becomes the preselected default on future pairs.

**Transport security UI (new `ui/components/TransportSecurityBadge.kt`, `ui/components/InsecureConnectionAckDialog.kt`):**
- Badge renders in 3 states (secure green 🔒 / amber insecure-with-reason / red insecure-unknown) and 3 sizes (Chip / Row / Large). Rendered in Settings → Connection, in the Session info sheet, and on each Paired Device card.
- Insecure ack dialog shown **first time** the user toggles insecure mode on. Body: plain-language threat model. Radio buttons for reason — "LAN only" / "Tailscale or VPN" / "Local development only". Only dismissible after selecting a reason + tapping "I understand". Marks `insecure_ack_seen = true`. Reason is stored for display, NOT gating.

**Tailscale detection (new `util/TailscaleDetector.kt`):**
- Checks `NetworkInterface.getNetworkInterfaces()` for `tailscale0` interface or an address in `100.64.0.0/10` (Tailscale CGNAT range), plus the configured relay URL host (`.ts.net` / 100.x.y.z).
- `StateFlow<Boolean>` refreshed on network changes via `ConnectivityManager` callback.
- Purely informational — shown as a "Tailscale detected" green chip in the Connection section. Does NOT auto-change any defaults per Bailey's #5 requirement.

**Paired Devices screen (new `ui/screens/PairedDevicesScreen.kt`):**
- Nav destination reached from Settings → Connection → "Paired Devices".
- Fetches via `RelayHttpClient.listSessions()` (new method, GET `/sessions` with bearer auth). Each card: device name + ID, "Current device" badge if `isCurrent`, transport security badge, expiry ("Expires Apr 18, 2026" or "Never"), per-channel grant chips, revoke button.
- Revoke button → confirmation dialog → `RelayHttpClient.revokeSession(tokenPrefix)`. Revoking the current device wipes local session token + redirects to pairing flow.
- Pull-to-refresh. Empty state "No paired devices". Graceful 404 handling — if the server hasn't shipped `/sessions` yet, renders empty list instead of crashing.

**`network/RelayHttpClient.kt` — two new methods:**
- `listSessions(): Result<List<PairedDeviceInfo>>` — GET `/sessions` with bearer auth. 404 treated as "endpoint not implemented yet" → empty list.
- `revokeSession(tokenPrefix): Result<Unit>` — DELETE `/sessions/{prefix}` with bearer auth. 404 treated as "already gone" → success.

**`network/ConnectionManager.kt` — TOFU integration:**
- Optional `CertPinStore` param. Rebuilds `OkHttpClient` on every connect with a fresh `CertificatePinner` snapshot (so re-pair pin wipes take effect immediately). Records peer cert fingerprint in `onOpen` when the handshake is TLS.
- Connect logic moved to IO dispatcher so the DataStore read for pin snapshot doesn't run on the caller's thread.

**`viewmodel/ConnectionViewModel.kt`:**
- Wires `AuthManager` before `ConnectionManager` so the pin store is available.
- Exposes `currentPairedSession`, `pairedDevices` + `pairedDevicesLoading` + `pairedDevicesError`, `isTailscaleDetected`, `insecureAckSeen`, `insecureReason`.
- `loadPairedDevices()`, `revokeDevice(prefix)`, `setInsecureAckComplete(reason)`.
- Shuts down Tailscale detector on `onCleared`.

**`ui/screens/SettingsScreen.kt` — integration:**
- `onNavigateToPairedDevices` callback added to the signature.
- Connection card: new TransportSecurityBadge row, Tailscale chip (if detected), hardware-storage chip, Paired Devices navigation row.
- Insecure toggle now routes through the first-time ack dialog. Cancel leaves the toggle off; confirm persists the reason and flips the mode.
- QR scan handoff now stages the payload into `pendingQrPayload` and opens `SessionTtlPickerDialog`. Picker confirm applies URLs/key/code, calls `applyServerIssuedCodeAndReset(code, relayUrl)` (wipes the TOFU pin), stores `pendingGrants` + `pendingTtlSeconds`, then tests the connection.

**`ui/components/ConnectionInfoSheet.kt`:**
- `SessionInfoSheet` now reads `currentPairedSession` and displays Expires / Channel grants / Transport / Key storage rows when paired.

**`ui/RelayApp.kt`:**
- New `Screen.PairedDevices` nav destination wired into the nav graph with back + re-pair callbacks. Reachable only from Settings — no bottom-nav slot.

**New DataStore keys (`data/PairingPreferences.kt`):**
- `pair_ttl_seconds` (Long, user's last-selected TTL)
- `insecure_ack_seen` (Boolean)
- `insecure_reason` (String: `"lan_only"` / `"tailscale_vpn"` / `"local_dev"` / `""`)
- `tofu_pins` (string-encoded map)

---

### Security review

The expansion of what a paired phone can do is:
- **Listing all sessions** (prefixes only, not full tokens) — a compromised phone was already able to see its own session, this adds visibility into other paired devices' metadata. Acceptable because the paired-phone population is explicitly trusted by the operator (they approved each QR pair).
- **Revoking other sessions** — any paired phone can revoke any other paired device. This is DoS territory: a compromised phone could lock out all other devices. Mitigation: revocation is auditable via the relay logs, and re-pairing is always possible from the host. For most deployments (one operator, 1-2 phones) this is acceptable. For multi-user deployments it'll need per-device role model (admin / user).
- **QR payload signing** — raises the bar for QR tampering (attacker can't inject their own pairing code via a modified QR photo), but it's defensive: the phone doesn't verify signatures yet. The server-side infrastructure is there so phone-side verification can land in a follow-up once the secret distribution model is defined.
- **Never-expire sessions** — Bailey's explicit call: allow based on user intent, not gated. Users who pick this are accepting the risk. The Paired Devices screen makes revocation trivial.
- **TOFU pinning** — protects against MITM of a self-signed wss cert AFTER the first connect. Does not protect the first connect itself (trust-on-first-use). Acceptable for the LAN / Tailscale / VPN deployment model.
- **StrongBox storage** — improves attacker cost for on-device token extraction on Android 9+ devices with StrongBox hardware. Best-effort; older devices fall back to TEE-backed EncryptedSharedPreferences which is still strong.

---

### Files created/modified

**Server (11 files):**
- New: `plugin/relay/qr_sign.py`, `plugin/tests/test_qr_sign.py`, `plugin/tests/test_rate_limit_clear.py`, `plugin/tests/test_session_grants.py`, `plugin/tests/test_sessions_routes.py`
- Modified: `plugin/cli.py`, `plugin/pair.py`, `plugin/relay/auth.py`, `plugin/relay/server.py`
- Unchanged: `plugin/relay/__init__.py` (version stays `0.2.0` per Bailey's direction — never released)

**Phone (17 files):**
- New: `auth/CertPinStore.kt`, `auth/PairedSession.kt`, `auth/SessionTokenStore.kt`, `data/PairingPreferences.kt`, `ui/components/InsecureConnectionAckDialog.kt`, `ui/components/SessionTtlPickerDialog.kt`, `ui/components/TransportSecurityBadge.kt`, `ui/screens/PairedDevicesScreen.kt`, `util/TailscaleDetector.kt`
- Modified: `auth/AuthManager.kt`, `network/ConnectionManager.kt`, `network/RelayHttpClient.kt`, `ui/RelayApp.kt`, `ui/components/ConnectionInfoSheet.kt`, `ui/components/QrPairingScanner.kt`, `ui/screens/SettingsScreen.kt`, `viewmodel/ConnectionViewModel.kt`

**Docs:** this DEVLOG entry, ADR 15 in `docs/decisions.md`, §3.3 / §3.3.1 / §3.4 rewrites in `docs/spec.md`, route tables in `docs/relay-server.md` + `user-docs/reference/relay-server.md`, config sections in `user-docs/reference/configuration.md`, Key Files + Integration Points in `CLAUDE.md`, pair flow in `user-docs/guide/getting-started.md`.

### Known gaps / follow-ups

- **Phone-side QR signature verification** — parsing + storing the `sig` field works; full verification requires a secret distribution mechanism (pre-shared key? on-device enrollment?) the protocol doesn't yet define.
- **Bidirectional pairing full UX** — `POST /pairing/approve` is a working stub. Real Phase 3 work needs a pending-codes store + operator approval UI.
- **Per-device role model** — all paired devices currently have equal revoke rights. Multi-user / admin-vs-user split is a future refactor.
- **Grant renewal UI** — the Paired Devices screen shows expiry but has no "extend this grant" action. Pair-again from the host or just revoke + re-pair.
- **Build verification** — no gradle run in this session. Bailey deploys from Android Studio. If a compile bug slipped through a KDoc or Compose misuse, it'll surface on the first build attempt (like the `text/*` comment bug earlier today).

---

## 2026-04-11 — Inbound Media v2: bare-path fetch + session-reload re-parse

**Why this exists:**
On-device testing of the v1 inbound media pipeline (shipped earlier today, commits `1195778` + `8f61262`) surfaced two bugs that showed up in the same screenshot:

1. **Placeholder flicker.** The `⚠️ Image unavailable` card rendered for a split second and then vanished, replaced by raw `MEDIA:/tmp/...` text in the bubble.
2. **Blank-looking attachment bubbles.** Related symptoms of the same underlying issue — messages re-rendered inconsistently during the turn-end reload.

Both root-caused to the `session_end reload` pattern documented in `CLAUDE.md`: when a streaming turn completes, `ChatViewModel.onCompleteCb` calls `client.getMessages()` → `ChatHandler.loadMessageHistory()`, which wholesale-replaces `_messages.value` with fresh `ChatMessage`s built from `item.contentText`. The streaming-time media marker parser had stripped the markers from the client-side copy and injected attachments, but NEITHER mutation was visible to `loadMessageHistory` — the server-stored text still contained the raw markers, and the client-injected attachments were gone.

**Fix 1: re-run the media marker parser on reloaded history.** `loadMessageHistory` now scans each loaded assistant message's content, strips matched lines, and queues `onMediaAttachmentRequested` / `onMediaBarePathRequested` callbacks to fire **after** the wholesale `_messages.value` assignment (so `mutateMessage` lookups hit the newly-loaded IDs). `dispatchedMediaMarkers` is cleared at the same time since pre-reload dedupe keys are meaningless against post-reload message IDs. Extracted into `extractMediaMarkersFromContent` — a pure helper that doesn't touch mutable buffer state. Shipped in commit `272a3c5`.

---

While digging into the flicker bug I noticed something bigger and more important in Bailey's screenshot:

**The LLM is emitting `MEDIA:/tmp/...` in its free-form text completions, not via the tool.** Upstream `hermes-agent/agent/prompt_builder.py:266` explicitly instructs the model in its system prompt: *"include MEDIA:/absolute/path/to/file in your response. The file..."* So the LLM treats MEDIA markers as a first-class way to request file delivery — in free-form completions, not through tool calls. Our v1 fix only intercepted markers **emitted by tools** that called `register_media()` — which covers `android_screenshot` but not the much larger set of LLM free-form emissions. For those, the marker form is always bare-path (`MEDIA:/tmp/foo.jpg`), and our phone-side handler treated bare-path as "unavailable" no matter what.

**Fix 2: `GET /media/by-path` on the relay + phone-side `fetchMediaByPath`.** Adds a second fetch route alongside the existing `/media/{token}`. Key points:

- **Shared path validation** — extracted `validate_media_path(path, allowed_roots, max_size_bytes) -> (real_path, size)` at the top of `plugin/relay/media.py`. Both `MediaRegistry.register` (the loopback-only tool path) and the new `handle_media_by_path` (the phone-auth'd direct fetch) call it, so the sandbox rules can't drift.
- **Same trust model as `/media/{token}`** — bearer auth against the existing `SessionManager`. Only a paired phone with a valid relay session token can fetch; 401 for everyone else.
- **Path sandboxing** — absolute path → `os.path.realpath` → must resolve under an allowed root (`tempfile.gettempdir()` + `HERMES_WORKSPACE` + `RELAY_MEDIA_ALLOWED_ROOTS`) → must exist → must be a regular file → must fit under `RELAY_MEDIA_MAX_SIZE_MB`. Symlink escape is blocked by the `realpath` pre-check. 403 for any violation; 404 if the file is missing; 400 if the `path` query param is absent.
- **Content-Type negotiation** — if the phone passes `?content_type=<...>` it's honored; otherwise the server guesses via Python `mimetypes.guess_type()`. Falls back to `application/octet-stream`.
- **Route ordering** — `/media/by-path` is registered **before** `/media/{token}` in `create_app` or aiohttp swallows the literal path as a token and 404s. Commented in the source as a reminder.
- **Phone-side rename for clarity** — `onUnavailableMediaMarker` → `onMediaBarePathRequested` (ChatHandler callback and ChatViewModel method). The name "unavailable" made sense in v1 when bare-path was always terminal; now bare-path is the primary LLM format, so it's a request-to-fetch like the token form. The failure branch still produces the `⚠️ Image unavailable` card, but only when the fetch actually fails.
- **Shared fetch pipeline** — `performFetch` → `performFetchWith(handler, messageId, fetchKey, settings, fetch: suspend () -> Result<FetchedMedia>)`. Takes the fetch lambda as a parameter so both the token path (`relay.fetchMedia(token)`) and the bare-path (`relay.fetchMediaByPath(path)`) share the same size-cap / cache / state-flip logic. The `fetchKey` stored in `Attachment.relayToken` disambiguates via the leading `/` — `secrets.token_urlsafe` never produces a `/`, so the prefix check is unambiguous. `manualFetchAttachment` (retry CTA) uses the same discriminator.

**Security review (in ADR 14 addendum):**
Adding `/media/by-path` widens what a paired phone can request by one degree — it can now read any file in the allowed-roots whitelist without host-local tool cooperation. This does NOT widen the trust boundary because (1) the whitelist is the same, (2) `/tmp` on Linux is already world-readable to same-user processes, (3) bearer auth still requires a valid session token, and (4) `realpath` symlink-resolves before the whitelist check so symlink escape is still blocked. Operators who want a tighter sandbox should narrow `RELAY_MEDIA_ALLOWED_ROOTS`, not disable the endpoint.

**Tests added** (`plugin/tests/test_relay_media_routes.py`):
- `test_by_path_without_authorization_returns_401`
- `test_by_path_with_invalid_bearer_returns_401`
- `test_by_path_missing_path_param_returns_400`
- `test_by_path_outside_sandbox_returns_403`
- `test_by_path_nonexistent_in_sandbox_returns_404`
- `test_by_path_relative_path_returns_403`
- `test_by_path_happy_path_streams_bytes` (verifies auto-guessed `Content-Type: image/png` from `.png` extension)
- `test_by_path_content_type_hint_overrides_guess` (verifies `?content_type=application/json` wins over extension guess)
- `test_by_path_oversized_returns_403` (uses try/finally to restore `max_size_bytes` so other tests in the class aren't affected — `AioHTTPTestCase` reuses one app instance)

**Files created/modified:**

*Server (Python):*
- `plugin/relay/media.py` — new `validate_media_path()` module-level helper + `_is_under_any_root()` private helper; `MediaRegistry.register` refactored to call the new helper; duplicate `_is_under_allowed_root` method removed.
- `plugin/relay/server.py` — new `handle_media_by_path` route handler, new imports (`mimetypes`, `os`, `validate_media_path`), route registration in `create_app` (order-sensitive — `by-path` before `{token}`).
- `plugin/tests/test_relay_media_routes.py` — 9 new tests for the by-path endpoint.

*Phone (Kotlin):*
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayHttpClient.kt` — new `fetchMediaByPath(path, contentTypeHint)` method, uses `okhttp3.HttpUrl` builder for correct query-param encoding (paths with slashes / spaces / unicode).
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/ChatHandler.kt` — rename `onUnavailableMediaMarker` → `onMediaBarePathRequested`, updated KDoc explaining the new semantics.
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ChatViewModel.kt` — rename + rewrite method body (now inserts LOADING, applies cellular gate, calls `performFetchWith { relay.fetchMediaByPath(path) }`); `performFetch` → `performFetchWith` signature change (takes `fetch: suspend () -> Result<FetchedMedia>` lambda); `manualFetchAttachment` dispatches to token-vs-path branch by `fetchKey.startsWith("/")`.

*Docs:*
- `docs/decisions.md` — ADR 14 addendum covering the bare-path fetch endpoint, upstream-prompt rationale, and security review
- `docs/relay-server.md` + `user-docs/reference/relay-server.md` — new `/media/by-path` route entry
- `docs/spec.md` — §6.2a updated with three-route listing + bare-path flow
- `user-docs/reference/configuration.md` — honest description of the bare-path-as-primary-format model
- `CLAUDE.md` — Integration Points table split into token / path fetch + tool / LLM marker rows
- `DEVLOG.md` — this entry

**Known gaps still filed for later:**
- Session replay across relay restarts (phone-side persistent cache by token/hash-indexed)
- Auto-fetch threshold slider enforcement (currently persisted-not-enforced placeholder)
- Build verification — I haven't run gradle against the phone side; relying on type-checks and by-eye review. Bailey builds from Studio.

**Next cycle discussion point:**
Bailey raised a broader design question about pairing security and TTL policy — bidirectional pairing, secure-by-default, user-selectable pair duration at initial pair, separate defaults for terminal vs bridge, opt-in never-expire for secured transports. Deferred to next design pass; not in this commit.

---

## 2026-04-11 — Inbound Media Pipeline (agent → phone, Discord-style file rendering)

**Done:**
- **Root cause surfaced.** The `android_screenshot` tool has always returned `MEDIA:/tmp/...` in its response text, assuming hermes-agent's gateway would extract and deliver the file as a native attachment. Upstream verification against `gateway/platforms/api_server.py` showed `APIServerAdapter.send()` is an explicit no-op (`"API server uses HTTP request/response, not send()"`) and `_write_sse_chat_completion` streams raw deltas without ever invoking `extract_media()`. The upstream extract-media / send_document machinery (`gateway/run.py:4570`, `4747`) is wired for push platforms only (Telegram, Feishu, WeChat). On our HTTP pull adapter, the `MEDIA:` tag has always passed through to the phone as literal text. No existing upstream path exists for delivering files over the HTTP API surface without a platform-adapter PR.
- **Workaround landed: plugin-owned file-serving on the relay.** Added a `MediaRegistry` and two new routes to the plugin's existing relay server. Media-producing tools POST to a loopback-only `POST /media/register` with a file path + content type, get back an opaque `secrets.token_urlsafe(16)` token, and emit `MEDIA:hermes-relay://<token>` in their chat response text instead of the bare path. The phone's `ChatHandler` parses the marker out of the SSE stream, fires a ViewModel callback, and `RelayHttpClient` fetches the bytes over `GET /media/{token}` with `Authorization: Bearer <session_token>` (reusing the existing `SessionManager`). Bytes land in `cacheDir/hermes-media/`, get shared via `FileProvider` (`${applicationId}.fileprovider`), and render inline via a new `InboundAttachmentCard` component. Result: zero LLM context bloat (token is ~25 chars), no upstream fork, no new auth model.
- **Registry design.** In-memory `OrderedDict` LRU with `asyncio.Lock` for thread-safety. Defaults: **24-hour TTL** (chosen to cover within-a-day session scrollback — the real human use case; anything longer is wasted since SessionManager is in-memory and relay restarts invalidate all tokens regardless), **500-entry LRU cap** (prevents runaway memory/disk under screenshot spam), **100 MB file-size cap** (guards against `/media/register` being handed a 10 GB file). Path sandboxing: file must be absolute, `os.path.realpath()` resolve under an allowed root (default: `tempfile.gettempdir()` + `HERMES_WORKSPACE` or `~/.hermes/workspace/` + any `RELAY_MEDIA_ALLOWED_ROOTS` entries), exist, be a regular file, and fit under the size cap. The token → path mapping is held server-side — the client only ever presents an opaque token on GET, so there's zero path-traversal surface on the fetch endpoint.
- **Fallback when relay isn't running.** The tool calls `register_media()` via stdlib `urllib.request` with a 5s timeout; on any failure (relay down, connection refused, non-200 response) it returns the legacy `MEDIA:<tmp_path>` form with a logger warning. The phone's `ChatHandler` recognizes the bare-path form via a second regex and fires `onUnavailableMediaMarker`, which inserts a FAILED `Attachment` placeholder rendering `⚠️ Image unavailable — relay offline`. No regression versus today's behavior; the placeholder is just tidier than raw marker text.
- **Discord-style rendering on the phone.** New `AttachmentState { LOADING, LOADED, FAILED }` and `AttachmentRenderMode { IMAGE, VIDEO, AUDIO, PDF, TEXT, GENERIC }` on the existing `Attachment` data class. `InboundAttachmentCard` dispatches by `(state × renderMode)`: images render inline from the cached URI (decoded via `BitmapFactory.decodeByteArray` + `asImageBitmap`, matching the existing outbound-attachment render path — no Coil/Glide added); video/audio/pdf/text/generic render as tap-to-open file cards that fire `ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION`. The same component now handles outbound attachments too (they default to `state=LOADED`), so `MessageBubble.kt` no longer has a separate outbound-only render branch.
- **Cellular gate.** If `autoFetchOnCellular == false` (default) and the device is on a cellular network, the attachment stays in LOADING state with `errorMessage = "Tap to download"` — the user taps to trigger `manualFetchAttachment()`, which re-issues the fetch ignoring the cellular gate. Encoded via existing enum + errorMessage slot rather than adding a new state value to keep the data class surface small.
- **Dedup.** `ChatHandler.dispatchedMediaMarkers` is a per-session set that prevents double-firing between real-time streaming scans (`scanForMediaMarkers` called from `onTextDelta`) and the post-stream reconciliation pass (`finalizeMediaMarkers` called from `onTurnComplete` / `onStreamComplete`). Marker parsing runs unconditionally — not gated on the `parseToolAnnotations` feature flag.
- **Settings UI.** New "Inbound media" subsection in Settings (between Chat and Appearance) exposes four DataStore-backed knobs: max inbound attachment size (5–100 MB, default 25), auto-fetch threshold (0–50 MB, default 2 — *persisted but not currently enforced; only the cellular toggle gates fetches today, with the threshold reserved for forward-compatibility*), auto-fetch on cellular (default off), and cached media cap (50–500 MB, default 200) with a "Clear cached media" button that calls `MediaCacheWriter.clear()` and shows a Toast with the freed byte count. LRU eviction on the cache is by file mtime.
- **Auth parity.** The media GET endpoint uses the same relay session token that gates the WSS channel itself — no stronger, no weaker. User raised the question of whether the media endpoint needed its own auth given that chat is optionally unauthenticated; answer is the relay session token (issued at pairing, stored in `EncryptedSharedPreferences`) is a separate and always-required credential, so `/media/<token>` inherits exactly the WSS trust level and adds unguessable per-file entropy on top. Opt-in insecure (ws://) mode intentionally does nothing to strengthen this — it matches the existing "trusted LAN" assumption for local dev.
- **Tests.** 11 registry tests (happy path, expiry, LRU eviction, LRU reorder on get, relative path rejection, nonexistent path rejection, directory rejection, outside-allowed-roots rejection, symlink-escape rejection [skipped on Windows without symlink priv], oversized rejection, empty content_type rejection) + 8 route tests (`/media/register` non-loopback 403, happy path 200, validation 400, bad JSON 400; `/media/{token}` no auth 401, bad bearer 401, valid + streamed 200, expired 404, unknown 404). Uses `unittest.IsolatedAsyncioTestCase` + `aiohttp.test_utils.AioHTTPTestCase` (no pytest-asyncio dep required).

**Why this wasn't Option A (inline base64 in tool output):**
- Inline base64 bloats the LLM context on every call (~135 KB per 1080p screenshot, growing with history), matters for video/audio scalability, and forces the agent to pay for bytes it's just routing to the phone. User explicitly rejected that tradeoff.
- Option B (plugin-owned file endpoint) decouples the wire format from the file bytes: tokens are ~25 chars, bytes flow out-of-band over a separate authenticated HTTP channel. Costs: new endpoint surface area, new phone-side fetch path, FileProvider plumbing — but all of it lives in files we already own.

**Files created:**

*Server (Python):*
- `plugin/relay/media.py` — `MediaRegistry`, `_MediaEntry`, `MediaRegistrationError`, `_default_allowed_roots()`
- `plugin/relay/client.py` — stdlib `urllib.request`-based `register_media()` + `_post_loopback()` helper (kept separate from `plugin/pair.py`'s existing `register_relay_code` to avoid weakening that function's narrower error surface)
- `plugin/tests/test_media_registry.py` — 11 tests, `unittest.IsolatedAsyncioTestCase`
- `plugin/tests/test_relay_media_routes.py` — 8 tests, `aiohttp.test_utils.AioHTTPTestCase`

*Phone (Kotlin):*
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayHttpClient.kt` — OkHttp GET, ws→http URL rewrite, Content-Disposition filename parse, Result<FetchedMedia>
- `app/src/main/kotlin/com/hermesandroid/relay/data/MediaSettings.kt` — DataStore-backed `MediaSettings` + `MediaSettingsRepository`
- `app/src/main/kotlin/com/hermesandroid/relay/util/MediaCacheWriter.kt` — LRU-capped cache at `cacheDir/hermes-media/`, FileProvider URI generation, MIME→ext map
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/InboundAttachmentCard.kt` — single component dispatching on `state × renderMode`
- `app/src/main/res/xml/file_provider_paths.xml` — `<cache-path name="hermes-media" path="hermes-media/"/>`

**Files modified:**

*Server:*
- `plugin/relay/config.py` — 4 new fields (`media_max_size_mb`, `media_ttl_seconds`, `media_lru_cap`, `media_allowed_roots`), `from_env()` parsing
- `plugin/relay/server.py` — `self.media = MediaRegistry(...)` in `RelayServer.__init__`, `handle_media_register` + `handle_media_get` + route registration in `create_app`
- `plugin/tools/android_tool.py` — `android_screenshot()` calls `register_media()` → emits `hermes-relay://<token>` on success, falls back to bare path with a `logging.warning` on failure
- `plugin/android_tool.py` — identical change to the top-level duplicate copy

*Phone:*
- `app/src/main/AndroidManifest.xml` — `<provider>` for `androidx.core.content.FileProvider` with authority `${applicationId}.fileprovider`
- `app/src/main/kotlin/com/hermesandroid/relay/data/ChatMessage.kt` — `AttachmentState` + `AttachmentRenderMode` enums, extended `Attachment` with `state`/`errorMessage`/`relayToken`/`cachedUri`, `textLikeMimes` companion, `renderMode` computed property
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/ChatHandler.kt` — `mediaRelayRegex` + `mediaBarePathRegex`, `onMediaAttachmentRequested` + `onUnavailableMediaMarker` as `var` callbacks (not ctor params), `mediaLineBuffer` + `dispatchedMediaMarkers` dedupe set, `scanForMediaMarkers` called unconditionally from `onTextDelta`, `finalizeMediaMarkers` called from `onTurnComplete`/`onStreamComplete`, `mutateMessage` helper exposed so the ViewModel can flip attachment state on the private `_messages` StateFlow
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ChatViewModel.kt` — new `initializeMedia(context, relayHttpClient, mediaSettingsRepo, mediaCacheWriter)`, `onMediaAttachmentRequested`, `performFetch`, `manualFetchAttachment`, `onUnavailableMediaMarker`, `MEDIA_TAP_TO_DOWNLOAD` companion constant
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` — owns media singletons (`mediaSettingsRepo`, `mediaCacheWriter`, `relayHttpClient`), shared `OkHttpClient`, `_cachedMediaCapMb` mirror loop so the writer's cap lambda is synchronous
- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt` — `chatViewModel.initializeMedia(...)` wired inside the existing `LaunchedEffect(apiClient)` block
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/MessageBubble.kt` — replaced outbound-only attachment rendering with `attachments.forEachIndexed { InboundAttachmentCard(...) }`, added `onAttachmentRetry` + `onAttachmentManualFetch` params
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ChatScreen.kt` — empty-bubble skip now respects `attachments.isNotEmpty()`, wires `manualFetchAttachment` to both retry + manual-fetch slots
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` — new `InboundMediaSection(connectionViewModel)` composable between Chat and Appearance (coexists with the other team's unified Connection section; no collision)

**Files NOT touched** (other team owns them or out-of-scope): `AuthManager.kt` (other team added `applyServerIssuedCodeAndReset` for the manual-code-entry dialog), `ConnectionInfoSheet.kt` (other team's new bottom-sheet component for Connection rows), `plugin/pair.py`, anything under `relay_server/` (thin shim — untouched), any upstream hermes-agent code.

**Next:**
- Wire the auto-fetch threshold slider to the actual fetch logic — currently only the cellular toggle gates fetches, and the threshold is persisted-but-unused as a forward-compatibility placeholder. Real enforcement would need either a HEAD preflight to get the size before committing to the fetch, or we accept the post-hoc reject (byte-count comparison after the body lands, wasted bytes on oversize).
- Phone-side persistence of fetched media so session replay works across relay restarts. Currently the `FileProvider` cache is opaque to `ChatHandler` — if the user scrolls back into a session from yesterday, the tokens in the stored message text are stale (relay registry is in-memory) and the fetch 404s. Phone-side token-or-hash-indexed cache would survive this.
- Consider wiring the same pipeline into any future tools that want to emit files (voice, plots, reports). The `MediaRegistry` + `register_media()` helper is tool-agnostic — only `android_screenshot` uses it today.
- Unit-test coverage for the Kotlin side: `ChatHandler` marker parsing, `RelayHttpClient` URL-rewrite, `MediaCacheWriter` LRU eviction. The Python side has 19 tests; the Kotlin side currently has none for the media pipeline.
- Possible upstream contribution to `hermes-agent`: make `gateway/platforms/api_server.py`'s `_write_sse_chat_completion` route deltas through `GatewayStreamConsumer` so the `_MEDIA_RE` stripper in `gateway/stream_consumer.py:188` engages. That would at least keep raw `MEDIA:` tags out of the chat display for other HTTP-API clients that don't implement their own phone-side parser. Would not solve the actual file-delivery problem (still no `send_document` impl) but would at least stop the leakage. Track in `docs/upstream-contributions.md`.

**Blockers:**
- None. The feature is ready for on-device testing.

**Test plan (for on-device smoke):**
- Start relay (`scripts/dev.bat relay` or equivalent), pair phone, open chat.
- Invoke a tool that produces a screenshot (e.g., via an agent command that triggers `android_screenshot`). Verify the screenshot renders inline as an image, not as raw text.
- Kill the relay mid-session, trigger another screenshot, verify the `⚠️ Image unavailable — relay offline` placeholder renders.
- In Settings → Inbound media: adjust the max-size slider, toggle cellular, hit "Clear cached media", verify toast with freed bytes.
- Tap a non-image attachment (test with a PDF tool result if available) and verify `ACTION_VIEW` opens an external app with a valid `content://` URI.

---

## 2026-04-11 — Install Flow Canonicalization (external_dirs + pip install -e + skill category layout)

**Done:**
- **Install flow rewritten to match Hermes canonical distribution patterns** (per `~/.hermes/hermes-agent/website/docs/user-guide/features/skills.md`). The new `install.sh` clones the repo to `~/.hermes/hermes-relay/` (override with `$HERMES_RELAY_HOME`) instead of a throwaway tmpdir, `pip install -e`s the package into the hermes-agent venv, and registers the clone's `skills/` directory under `skills.external_dirs` in `~/.hermes/config.yaml` via an idempotent YAML edit. The plugin is symlinked into `~/.hermes/plugins/hermes-relay`, and a thin `~/.local/bin/hermes-pair` shim execs `python -m plugin.pair` in the venv.
- **Updates are now `cd ~/.hermes/hermes-relay && git pull`** — one command updates plugin (editable install picks up changes automatically) + skill (`external_dirs` is scanned fresh on every hermes-agent invocation) + docs. No `hermes skills update` step — that only applies to hub-installed skills, not `external_dirs`-scanned ones.
- **Skill directory now follows canonical category layout** — `skills/devops/hermes-relay-pair/SKILL.md` (category subdir matching the `metadata.hermes.category: devops` frontmatter), not the old flat `skills/hermes-relay-pair/`.
- **`skills/hermes-pairing-qr/` deleted entirely** — the pre-plugin bash script + SKILL.md. Replaced by `skills/devops/hermes-relay-pair/` + `plugin/pair.py` (Python module) + `hermes-pair` shell shim.
- **`plugin/skill.md` deleted** — old lowercase-s flat-file artifact from before the skill system existed.
- **Documented the upstream CLI gap** — hermes-agent v0.8.0's `PluginContext.register_cli_command()` is wired on the plugin side, but `hermes_cli/main.py:5236` only reads `plugins.memory.discover_plugin_cli_commands()` and never consults the generic `_cli_commands` dict. Third-party plugin CLI commands never reach the top-level argparser. Docs no longer promise `hermes pair` (with a space) works — only `/hermes-relay-pair` (slash command via the skill) and `hermes-pair` (dashed shell shim) are documented as working entry points.

**Files changed:**
- `README.md` — Quick Start replaces `hermes pair` with `/hermes-relay-pair` + `hermes-pair`, adds update-via-`git pull` note, updates repo structure to show `skills/devops/hermes-relay-pair/`
- `docs/relay-server.md` — pairing description and `/pairing/register` row updated to reference the new entry points
- `docs/decisions.md` — new ADR 13 on skill distribution via `external_dirs`
- `user-docs/guide/getting-started.md` — full install-flow rewrite covering the 5-step canonical installer, update mechanism, slash command vs shell shim entry points, upstream CLI gap warning
- `user-docs/reference/configuration.md` — new `Skills (external_dirs)` subsection, command references updated
- `user-docs/reference/relay-server.md` — pairing model + troubleshooting updated
- `CLAUDE.md` — Repo Layout shows `skills/devops/hermes-relay-pair/`; Key Files gains `install.sh`, drops deprecated `hermes-pairing-qr` rows and `plugin/skill.md` references; integration points updated
- `AGENTS.md` — Setup steps rewritten around the canonical installer
- `DEVLOG.md` — this entry

**Files NOT touched (main session owns them):** `plugin/**`, `relay_server/**`, `app/**`, `pyproject.toml`, `skills/devops/hermes-relay-pair/SKILL.md`, `install.sh`. The deleted `skills/hermes-pairing-qr/` and `plugin/skill.md` paths are referenced only as historical deletions in this entry and ADR 13.

**Next:**
- Verify `/hermes-relay-pair` renders correctly once the skill is at `skills/devops/hermes-relay-pair/SKILL.md` and hermes-agent reloads from `external_dirs`.
- Confirm `install.sh`'s YAML edit is actually idempotent against a pre-existing `external_dirs` list with a trailing comment — regression-test with a pathological config.
- Upstream patch to `hermes_cli/main.py` that dispatches to the generic `_cli_commands` dict — would let us restore `hermes pair` as a first-class CLI verb. Track in `docs/upstream-contributions.md`.

**Blockers:**
- Upstream argparser doesn't forward to plugin CLI dict (see above). Not blocking the install flow — the slash command + shell shim cover the same surface.

---

## 2026-04-11 — Settings Connection UX Rework (QR-first, collapsible manual + bridge)

**Done:**
- **Unified Connection section on the Settings screen.** Replaced the three separate top-level cards (**API Server**, **Relay Server**, **Pairing**) with a single **Connection** section containing three stacked cards:
  - **Pair with your server** — always visible, primary entry point. Large **Scan Pairing QR** button + a unified status summary line showing API Server (Reachable / Unreachable), Relay (Connected / Disconnected), and Session (Paired / Unpaired). This is the one-button flow: scan the QR from `hermes pair` on the host and everything is configured.
  - **Manual configuration** — collapsible. Starts collapsed when the user is already paired and reachable, expanded otherwise. Holds the manual-entry fields (API Server URL, API Key, Relay URL, Insecure Mode toggle) and the **Save & Test** button. Power-user / troubleshooting path.
  - **Bridge pairing code** — collapsible, gated by the `relayEnabled` feature flag, starts collapsed. Shows the locally-generated 6-char pairing code with copy / regenerate icons. Explicitly labelled "For the Phase 3 bridge feature — the host approves this code to enable Android tool control. Not used for initial pairing." Replaces the old Pairing card, which was visually prominent but semantically misleading in the new QR-driven flow.
- **Why.** The old layout buried the QR button inside the API Server card next to **Save & Test**, so new users couldn't tell which button was the primary setup path. The old **Pairing** card prominently displayed a phone-generated code that's no longer used for initial pairing — only for the future Phase 3 bridge direction. The rework makes the happy path (one QR scan → chat + relay) the obvious default and demotes both manual config and the bridge code to collapsibles for users who actually need them.
- **User docs updated.** `user-docs/guide/getting-started.md` (Manual Pairing section now walks through Settings → Connection → Manual configuration), `user-docs/reference/configuration.md` (Onboarding Settings renamed to Connection Settings + describes the three-card layout), and the `CLAUDE.md` Key Files entry for `SettingsScreen.kt`.

**Files changed:**
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` — three-card Connection section, collapsible state, unified status summary
- `user-docs/guide/getting-started.md` — Manual Pairing section updated for Settings → Connection layout
- `user-docs/reference/configuration.md` — Onboarding Settings → Connection Settings, three-card layout described
- `CLAUDE.md` — Key Files `SettingsScreen.kt` entry updated
- `DEVLOG.md` — this entry

**Next:**
- Update splash / onboarding completion screen so the "you can change this later in Settings" hint points at the Connection section, not the old API Server card.
- Screenshot pass for Play Store listing — the old screenshots still show the three-section layout.
- Consider whether the **Bridge pairing code** card should be hidden entirely (not just collapsed) until Phase 3 lands, to avoid confusing users who enable the relay feature flag for terminal alone.

**Blockers:**
- None.

---

## 2026-04-11 — QR-Driven Relay Pairing (one scan → chat + relay)

**Done:**
- **Extended QR payload schema** — `HermesPairingPayload` (in `plugin/pair.py` + `app/.../QrPairingScanner.kt`) now carries an optional `relay` block alongside the existing API server fields: `{ "hermes": 1, "host", "port", "key", "tls", "relay": { "url": "ws://host:port", "code": "ABCD12" } }`. The `relay` field is nullable and `kotlinx.serialization` runs with `ignoreUnknownKeys = true`, so old API-only QRs still parse cleanly — no migration required.
- **New relay endpoint `POST /pairing/register`** (`plugin/relay/server.py` → `handle_pairing_register`) — Pre-registers an externally-provided pairing code with the running relay. Accepts `{"code": "ABCD12"}`, returns `{"ok": true, "code": "ABCD12"}`. Gated to loopback callers only (`127.0.0.1` / `::1`) — any non-local `request.remote` gets HTTP 403. Matches the trust model: only a process with host shell access can inject codes; a LAN attacker cannot. Validation delegates to `PairingManager.register_code()` which enforces the 6-char `A-Z / 0-9` format.
- **`hermes pair` probes + pre-registers the relay** — When invoked, the command calls `probe_relay()` against `http://127.0.0.1:RELAY_PORT/health`; on success, mints a fresh 6-char code (`random.SystemRandom`, alphabet `string.ascii_uppercase + string.digits`), posts it to `/pairing/register`, and embeds `{url, code}` in the QR. If the relay isn't running it prints an `[info]` pointing at `hermes relay start` and renders an API-only QR. If registration fails it prints a `[warn]` and also falls back. New `--no-relay` flag skips the probe entirely for operators who only want direct chat.
- **Output format** — `render_text_block()` now renders a second "Relay (terminal + bridge)" section when a relay block is present, showing the `ws://host:port` URL and the pairing code (with "expires in 10 min, one-shot" note) alongside the existing "Server" section. Unified warning at the bottom notes the QR contains credentials whenever an API key OR a relay code is present.
- **Pairing alphabet widened** — `plugin/relay/config.py` — `PAIRING_ALPHABET` went from `"ABCDEFGHJKLMNPQRSTUVWXYZ23456789"` (32 chars, no ambiguous 0/O/1/I) to `"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"` (36 chars). The phone-side `PAIRING_CODE_CHARS = ('A'..'Z') + ('0'..'9')` in `AuthManager.kt` could previously emit codes that `PairingManager.register_code()` silently rejected as "invalid format". The old restriction only mattered when a human had to retype a code from a display; with QR + HTTP the full alphabet is correct.
- **Relay config env vars** — `RELAY_HOST` / `RELAY_PORT` are now consumed by `plugin/pair.py`'s `read_relay_config()` too (in addition to `plugin/relay/config.py`), so `hermes pair` and `hermes relay start` agree on where the relay lives.
- **Phase 3 note** — Phone-side `generatePairingCode()` in `AuthManager.kt` is retained. The bridge channel (Phase 3) will use the opposite flow — phone generates, host approves — and `POST /pairing/register` is written generically enough to serve both directions.

**Files changed/added:**
- `plugin/relay/server.py` — `handle_pairing_register` + route registration on `/pairing/register`
- `plugin/relay/auth.py` — `PairingManager.register_code()` validation helper
- `plugin/relay/config.py` — widened `PAIRING_ALPHABET`, comment explaining why
- `plugin/pair.py` — `probe_relay()`, `register_relay_code()`, `_generate_relay_code()`, `_relay_lan_base_url()`, `read_relay_config()`; extended `build_payload()` / `render_text_block()` / `pair_command()`
- `plugin/cli.py` — `--no-relay` flag on `hermes pair`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/QrPairingScanner.kt` — `RelayPairing` data class + nullable `relay` field on `HermesPairingPayload`
- `README.md` — Quick Start pairing section now mentions one-scan chat + relay
- `docs/spec.md` — pairing flow section and QR wire format
- `docs/decisions.md` — new ADR entry on QR-carries-both-credentials trust model
- `docs/relay-server.md` — routes table includes `/pairing/register`, loopback restriction note
- `user-docs/guide/getting-started.md` — updated pairing steps
- `user-docs/reference/relay-server.md` — routes + pairing model
- `user-docs/reference/configuration.md` — `RELAY_HOST` / `RELAY_PORT` + alphabet note
- `CLAUDE.md` — Integration Points + Repo Layout references updated to `plugin/relay/`
- `DEVLOG.md` — this entry

**Next:**
- End-to-end test: start relay → `hermes pair` → scan QR on phone → verify both API and relay auto-configure → verify terminal tab attaches without asking for a pairing code.
- If the phone's stored relay session token is still valid from a previous pairing, the new code should be a no-op (session reconnect takes priority over pairing in `_authenticate()`). Verify that path doesn't accidentally consume the freshly-registered code.

**Blockers:**
- None.

---

## 2026-04-11 — Phase 2: Terminal Channel MVP (server + app)

**Done:**
- **Server-side `TerminalHandler` (`relay_server/channels/terminal.py`)** — Replaced the stub with a real PTY-backed shell handler. Uses `pty.openpty()` + `fork` + `TIOCSCTTY` (not `pty.fork()`) so we can set `O_NONBLOCK` on the master fd before handing it to `loop.add_reader()`. Output is batched on a ~16 ms window (via `loop.call_later`) or flushed immediately on 4 KiB buffer — that keeps 60 fps refresh from a shell dumping megabytes without flooding the WebSocket. Supports `terminal.attach`/`input`/`resize`/`detach`/`list`. Resize uses `TIOCSWINSZ` ioctl for SIGWINCH. Graceful teardown on disconnect: flush pending buffer → remove reader → SIGHUP → `waitpid(WNOHANG)` loop (up to 1 s grace) → SIGKILL fallback → `os.close`. Shell resolution checks absolute-path candidates (request → config → `$SHELL` → `/bin/bash` → `/bin/sh`) and rejects relative paths. Per-client cap of 4 concurrent sessions. Child gets `TERM=xterm-256color`, `COLORTERM=truecolor`, and `HERMES_RELAY_TERMINAL=<session_name>` as a debug marker. Unix-only: `pty`/`termios`/`fcntl` imports are guarded with `try/except ImportError` so the relay still starts on Windows — attach attempts return a clean `terminal.error` instead of crashing the whole server at import time.
- **Config** — Added `terminal_shell: str | None` to `RelayConfig` (`RELAY_TERMINAL_SHELL` env var, `None` = auto-detect). Wired into `TerminalHandler(default_shell=...)` in `relay.py`.
- **xterm.js asset bundle (`app/src/main/assets/terminal/`)** — Downloaded `@xterm/xterm@5.5.0` + `@xterm/addon-fit@0.10.0` + `@xterm/addon-web-links@0.11.0` from jsDelivr into `assets/terminal/`. Wrote `index.html` with a Hermes-themed palette (navy `#1A1A2E` background, purple `#B794F4` cursor/magenta, magenta/cyan/green ANSI mapping that matches the app's Material 3 primary). Disables autocorrect/overscroll/zoom. Uses base64-encoded output payloads (`window.writeTerminal('<b64>')`) to avoid JS string-escape headaches with control bytes and escape sequences.
- **`TerminalViewModel.kt`** — AndroidViewModel mirroring `ChatViewModel` init pattern. Registers a `ChannelMultiplexer` handler for `"terminal"`. State flow tracks attached/sessionName/pid/shell/cols/rows/tmuxAvailable/ctrlActive/altActive/error. Output flows on a `MutableSharedFlow<String>` (replay=0, buffer=256) — explicitly not a StateFlow because terminal chunks must be delivered exactly once; StateFlow would conflate rapid deltas and drop output. Sticky CTRL translates a–z/A–Z + `[\]` to their control bytes; sticky ALT prefixes ESC. Both auto-clear after the next keypress. Pending-attach queue: if the WebView signals ready before the relay connects, the cols/rows are held and the attach fires once `ConnectionState.Connected` lands.
- **`TerminalWebView.kt`** — Compose WebView wrapper. Loads `file:///android_asset/terminal/index.html`, installs `AndroidBridge` @JavascriptInterface (`onReady`/`onInput`/`onResize`/`onLink`). `viewModel.outputFlow` is collected in a `LaunchedEffect` on the UI thread and piped into `webView.evaluateJavascript("window.writeTerminal('$b64')")`. `DisposableEffect` tears down the WebView cleanly on recomposition out. Uses the modern `shouldOverrideUrlLoading(WebView, WebResourceRequest)` signature (minSdk 26), routes non-asset URLs to the system browser via `ACTION_VIEW`.
- **`ExtraKeysToolbar.kt`** — `RowScope`-extension `ToolbarKey` composable for the 8-key bottom toolbar: ESC, TAB, CTRL (sticky), ALT (sticky), ←↓↑→. Active state highlights with `primary.copy(alpha=0.22f)` background + primary border. Haptic `LongPress` feedback on every tap.
- **`TerminalScreen.kt`** — Replaced the "Coming Soon" placeholder. TopAppBar with monospace subtitle line that shows session name / "attaching…" / "relay disconnected" / error. `ConnectionStatusBadge` in the actions slot (green when attached, amber when attaching/reconnecting, red otherwise) + `Refresh` IconButton for manual reattach. WebView fills `weight(1f)`, `ExtraKeysToolbar` is anchored at the bottom with `navigationBarsPadding() + imePadding()` so it slides up with the IME. Overlay card appears when relay is disconnected or there's an error, explaining state and pointing at Settings.
- **`RelayApp.kt` wiring** — Imported `TerminalViewModel`, added `viewModel()` instance, one-time `LaunchedEffect` calls `terminalViewModel.initialize(multiplexer, relayConnectionState)` so the channel handler registers and auto-attaches on reconnect. `Screen.Terminal` composable now passes both view models into `TerminalScreen`.

**Files changed/added:**
- `relay_server/channels/terminal.py` (rewritten — 560 lines of real PTY handling)
- `relay_server/config.py` (new `terminal_shell` field + env var)
- `relay_server/relay.py` (pass `default_shell` into `TerminalHandler`)
- `app/src/main/assets/terminal/index.html` (new)
- `app/src/main/assets/terminal/xterm.js` + `xterm.css` + `addon-fit.js` + `addon-web-links.js` (new — ~300 KB bundled, no CDN dependency at runtime)
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/TerminalViewModel.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/TerminalWebView.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ExtraKeysToolbar.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/TerminalScreen.kt` (rewritten)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt` (import + instantiate + init + pass to screen)
- `DEVLOG.md` (this entry)

**Build:** `gradlew :app:assembleDebug` — BUILD SUCCESSFUL in 1m 59s. Only pre-existing deprecation warnings remain; no warnings or errors from new code. Server-side Python is not covered by CI; change is additive and gated by `_PTY_AVAILABLE` on Windows hosts so the existing chat/bridge channels remain unaffected.

**Not yet tested on real hardware.** This session produced compiling code, not verified feature behavior. Before declaring Phase 2 MVP shipped we need:
1. A Linux/macOS host running the relay server + tmux (or not — raw PTY fallback is what we actually built) with a shell the host user can actually log into.
2. Deploy the debug APK, connect the relay, open the Terminal tab, verify: prompt appears → soft keyboard typing reaches the shell → arrow keys work → CTRL+C interrupts → resize on rotation / IME show reflows prompt correctly → htop renders with box chars → disconnect/reconnect reattaches cleanly.
3. Check for WebView keyboard quirks on at least two devices (the plan flags this as the highest device-side risk).

**Deferred from the Phase 2 plan (will land in follow-up sessions):**
- **Plugin consolidation** — `relay_server/` is still a separate process; the plan wants it absorbed into `plugin/relay/` with a unified `hermes relay` CLI. Pure refactor, no user-visible change. Separate session.
- **tmux session persistence** — `self.tmux_available` is detected and surfaced in `terminal.attached` payloads but we're not using libtmux yet. Current implementation is raw PTY only. Adding tmux is additive (same envelope protocol, swap the spawn path).
- **P1/P2 polish** — pinch-to-zoom, mouse reporting (needed for htop/vim mouse), font bundling (JetBrains Mono NF), multiple themes, settings screen entries, visual bell, scroll-to-bottom FAB, URL-detection config, multi-session picker dropdown, hardware keyboard edge cases.
- **CLI commands** — `hermes relay status/sessions/kill` are spec'd but not wired. Nothing to wire them to until plugin consolidation lands.

**Next:**
- Smoke-test on a real device with the relay running against a real Linux host.
- Fix whatever that surfaces (WebView keyboard oddities, resize timing, PTY race conditions we haven't seen yet).
- Decide whether to ship MVP as-is under a feature flag or continue straight through 2B polish → tmux → consolidation before any user sees it.

**Blockers:**
- None in code. Need a Linux/macOS relay host to exercise the PTY path end-to-end.

---

## 2026-04-10 — v0.1.0 Play Store Release (Internal Testing)

**Done:**
- **Keystore** — Generated `release.keystore` (RSA 2048, SHA384withRSA, 10000-day validity, alias `hermes-relay`) via `keytool -genkey`. Certificate subject: `CN=Bailey Dixon, OU=Hermes-Relay, O=Codename-11, L=Tampa, ST=Florida, C=US`. SHA1 fingerprint `C9:8E:1B:74:A6:D8:A6:6E:0A:3A:C9:00:96:C2:0B:B7:44:B0:B7:FC`; SHA256 `A9:A4:2D:94:20:8B:94:B3:68:5B:01:93:E3:94:9B:90:50:AD:80:60:56:E7:16:3C:FC:E5:11:AF:68:0D:79:4B`. Stored at repo root as `release.keystore` (gitignored via `.gitignore:31`). Password stored in password manager; must back up file + password to a separate encrypted location before closing this session.
- **local.properties** — Added `hermes.keystore.path`, `hermes.keystore.password`, `hermes.key.alias`, `hermes.key.password` lines pointing at the absolute path (Gradle's `file()` resolves relative paths against the `app/` module, not repo root, so absolute path with forward slashes is required on Windows).
- **Local bundle build** — `gradlew bundleRelease` — BUILD SUCCESSFUL in 4m 41s, produced `app/build/outputs/bundle/release/app-release.aab` (19,071,575 bytes / 18.2 MB). Fingerprint-verified with `keytool -printcert -jarfile` — AAB is release-signed with the correct cert (serial `eaaf7de55766c57e`), not the debug fallback.
- **GitHub Secrets** — Set all four via `gh secret set` CLI so `.github/workflows/release.yml` will release-sign CI artifacts on future tags: `HERMES_KEYSTORE_BASE64` (from `base64 -w 0 release.keystore`), `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS=hermes-relay`, `HERMES_KEY_PASSWORD`. Used `printf '%s'` (no trailing newline) piped to `gh secret set` for the non-base64 values — a trailing `\n` would get baked into the secret and cause "Keystore was tampered with" failures in CI.
- **Play Console upload** — Uploaded the local `app-release.aab` to the Internal testing track on Google Play Console. `versionCode=1`, `versionName=0.1.0`. Enrolled in Play App Signing (Google re-signs installs with their HSM-held key; the keystore is now only an *upload key* with reset-via-support recovery). Release rolled out successfully. One non-blocking warning about missing native debug symbols (deferred — see Next section).
- **Git tag** — `git tag -a v0.1.0` pushed to `origin`, triggering `.github/workflows/release.yml` to build APK + AAB + `SHA256SUMS.txt` and attach them to a GitHub Release named `v0.1.0`. Tag landed on commit `089e011` (the `play-publisher 4.0.0` AGP 9 compat bump from a parallel session), which means the CI-built AAB is byte-different from the Play Console AAB (different `libs.versions.toml`) but functionally identical and signed with the same cert — acceptable for Internal testing because only the Play Console artifact reaches testers; the GitHub Release is a secondary distribution channel.

**Files changed:**
- `local.properties` (gitignored) — added release signing properties
- `release.keystore` (gitignored) — new
- `DEVLOG.md` — this entry

**Next:**
- **Back up `release.keystore` + password** to an encrypted off-machine location before closing this session. Losing both = losing ability to submit future upload keys (Play App Signing reset flow takes ~2 days).
- **Add native debug symbols for v0.1.1** — Add `ndk { debugSymbolLevel = "SYMBOL_TABLE" }` to the `release` build type in `app/build.gradle.kts`. Fixes the Play Console warning and gives readable native stack traces for crashes in transitive deps (ML Kit, CameraX, OkHttp BoringSSL, Compose/Skia).
- **Promote through tracks** — New personal Play accounts need 14 continuous days of Closed testing with ≥12 opted-in testers before production rollout. Create a Closed testing track and recruit testers ASAP if the production timeline matters.
- **Verify GitHub Release assets** — Once `release.yml` finishes, confirm the Release has APK + AAB + `SHA256SUMS.txt` and that the workflow summary says "Release-signed" (not the debug-signed warning banner).

**Blockers:**
- None.

---

## 2026-04-10 — Smooth Chat Auto-Scroll Fix + Compose Deprecation Cleanup

**Done:**
- **Smooth chat auto-scroll** — Rewrote `ChatScreen.kt` auto-scroll logic to fix five bugs surfaced while recording the demo video. (1) The `LaunchedEffect` keys only watched `messages.size` and `lastMessage?.content?.length`, so growth of the reasoning block (`thinkingContent`) and tool-card additions (`toolCalls`) silently froze auto-follow during long thinking and tool execution phases. (2) `animateScrollToItem(messages.size - 1)` defaulted to `scrollOffset = 0`, which aligns the *top* of the item with the top of the viewport — for tall streaming bubbles this snapped the user back to the start of the message instead of staying with the latest token. (3) There was no "user is reading history" gate, so any delta would yank a user reading scrollback back to the bottom. (4) The `isStreaming` flag was a snapshot key, so the stream-complete transition (true → false) re-triggered `animateScrollToItem` even when no content actually changed — producing a visible jiggle. (5) Sessions endpoint reloads the entire message list on stream complete via `loadMessageHistory()`, and the resulting `animateItem()` placement animations on every bubble fought with our concurrent `animateScrollToItem` — producing a flash where the viewport visibly settled twice.
- **The fix** — Added a `ChatScrollSnapshot` data class that captures all five streaming-state fields (message count, content length, thinking length, tool-call count, isStreaming). A `snapshotFlow` over the snapshot, debounced with `distinctUntilChanged`, drives auto-scroll via `collectLatest` (which cancels in-flight animations when newer deltas arrive — prevents pile-ups during rapid SSE bursts). The scroll target is `(totalItemsCount - 1, scrollOffset = Int.MAX_VALUE)` so Compose pins the absolute end of the list to the bottom of the viewport regardless of how tall the streaming bubble has grown. A `userScrolledAway` flag, tracked via a separate `snapshotFlow` on `listState.isScrollInProgress to isAtBottom`, gates the auto-follow — scrolling up to read history pauses it; scrolling back to the bottom (or tapping the FAB) resumes it. The FAB now uses `!listState.canScrollForward` for its `isAtBottom` derivation (replaces the off-by-one `lastVisible < messages.size - 2` arithmetic) and clears `userScrolledAway` on tap so live-follow resumes immediately even before the scroll animation settles. The `collectLatest` lambda also tracks the previous snapshot in a coroutine-scoped var, which lets it (a) skip "state-only" deltas where only the `isStreaming` flag changed (the viewport was already correct from the last content delta — re-animating causes a jiggle on stream complete) and (b) detect "list rebuild" deltas (sessions-mode `loadMessageHistory` collapsing one streaming message into multiple final messages with proper boundaries) and use the instant `scrollToItem` path instead of `animateScrollToItem` so the items can run their `animateItem()` placement animations without competing with our scroll animation.
- **Compose deprecation cleanup** — Migrated `Icons.Filled.Chat` → `Icons.AutoMirrored.Filled.Chat` in `RelayApp.kt` (auto-mirrors for RTL locales). Migrated `LocalClipboardManager` → `LocalClipboard` (suspend-based `Clipboard.setClipEntry(ClipEntry)` API) in both `ChatScreen.kt` and `SettingsScreen.kt`. Both clipboard call sites now wrap in the existing `rememberCoroutineScope().launch {}` since the new API is suspend; the underlying clipboard write now runs off the UI thread, which is a small responsiveness win on lower-end devices. Removed the now-unused `AnnotatedString` imports from both files. Build is now warning-clean.
- **Settings toggle** — Added `smoothAutoScroll` boolean preference to `ConnectionViewModel` (DataStore key `smooth_auto_scroll`, default `true`), mirroring the existing `animationEnabled`/`animationBehindChat` pattern. New row in **Settings > Chat** under "Show reasoning". When disabled, the entire auto-scroll `LaunchedEffect` early-returns and the chat is fully manual.
- **Docs** — Updated `README.md` features list, `docs/spec.md` Settings Tab section, and `user-docs/guide/chat.md` (new "Smooth Auto-Scroll" subsection explaining the pause-on-scroll-up behavior).
- **Brand rename** — "Hermes Relay" → "Hermes-Relay" across 57 files (docs, app strings, scripts, workflows, plugin, relay server). Aligns the display name with the canonical repo slug. The Android `app_name` in `strings.xml` is now `Hermes-Relay`; PascalCase code identifiers (`HermesRelayApp`, `HermesRelayTheme`, logcat tag `HermesRelay`) were intentionally left alone since they're internal symbols, not user-facing text.
- **Docs landing redesign** — Restructured `user-docs/index.md` to put install above features. Created `InstallSection.vue` (mounted via VitePress `home-hero-after` slot) and `HeroDemo.vue` (mounted via `home-hero-image` slot). Hero is now a phone-framed `<video>` that autoplays the demo, then crossfades to the brand logo after 12s for the rest of the session via Vue `<Transition mode="out-in">` — bandwidth fetches stop when the video unmounts. Fixed two raw-HTML `/guide/getting-started` hrefs that VitePress base-rewriting silently skipped (they were 404ing on the deployed site under the `/hermes-relay/` base path).
- **Demo video pipeline** — Re-encoded source `chat_demo.mp4` from 20.5 MB / 102 fps / 1080×2340 down to 1.95 MB / 30 fps / 720×1560 via ffmpeg (`-vf scale=720:-2,fps=30 -crf 28 -preset slow -an -movflags +faststart`) — 90% size reduction, mostly from dropping the wildly oversampled framerate. Extracted poster JPEG from first frame at 0.5s for instant LCP. Embedded with autoplay+muted+playsinline+preload=metadata in the docs hero, with `controls` in Getting Started's "Verify Connection" section, and as a `<video>` tag in README pointing at the GitHub raw URL. Used portable `imageio-ffmpeg` Python package since system ffmpeg wasn't installed.
- **Release infrastructure hardening** — `.github/workflows/release.yml` now builds APK + AAB in one Gradle invocation, decodes a `HERMES_KEYSTORE_BASE64` GitHub Secret to `$RUNNER_TEMP/release.keystore`, signs with the release keystore when secrets are set, falls back to debug signing with a warning banner in `$GITHUB_STEP_SUMMARY` when they're not. Generates SHA256 checksums for both artifacts and attaches all three (APK, AAB, SHA256SUMS.txt) to the GitHub Release.
- **gradle-play-publisher plugin** — Added `com.github.triplet.play` v3.13.0 (latest stable compatible with AGP 8.13.2 — v4.0.0 requires AGP 9). Configured in `app/build.gradle.kts` with `track=internal`, `releaseStatus=DRAFT`, `defaultToAppBundles=true`, reading credentials from `<repo-root>/play-service-account.json` (gitignored). Plugin is fully optional — `assembleRelease`/`bundleRelease` work without the JSON; only the explicit `publishReleaseBundle`/`promoteReleaseArtifact` tasks require it. Verified `settings.gradle.kts` already has `gradlePluginPortal()` in `pluginManagement`.
- **RELEASE.md** — New canonical doc (312 lines) covering: SemVer versioning conventions with optional `-alpha`/`-beta`/`-rc.N` prereleases and monotonic `appVersionCode`; one-time setup (keystore generation via `keytool`, `local.properties` config, base64 encoding for CI, Play Console account + 14-day closed testing rule for new personal accounts, Play Developer API service account creation in Google Cloud Console, GitHub Actions secrets); the 7-step release process (bump → notes → build → verify → tag/push → upload → post-release); CI behavior; hotfix recipe; troubleshooting. `CLAUDE.md` "Dev Workflow" section now references it.

**Files changed:**
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` (preference key + StateFlow + setter)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` (new toggle row in Chat section + LocalClipboard migration)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ChatScreen.kt` (data class, scroll-tracking effect, rewritten auto-scroll effect with previous-snapshot diffing, FAB fix, LocalClipboard migration)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt` (Icons.AutoMirrored.Filled.Chat)
- `README.md`, `docs/spec.md`, `user-docs/guide/chat.md`, `DEVLOG.md`
- **Rebrand** — 57 files across `app/src/main/res/`, `docs/`, `user-docs/`, `scripts/`, `plugin/`, `relay_server/`, `skills/`, `.github/workflows/`
- **Docs landing** — `user-docs/index.md` (frontmatter trim), `user-docs/.vitepress/theme/index.ts` (slot wiring + dead-link fix), new `user-docs/.vitepress/theme/components/{InstallSection,HeroDemo}.vue`
- **Demo video** — `assets/chat_demo.mp4` + `assets/chat_demo_poster.jpg` (canonical), `user-docs/public/chat_demo.mp4` + `user-docs/public/chat_demo_poster.jpg` (VitePress-served copies), `README.md` (`<video>` tag), `user-docs/guide/getting-started.md` (full video in Verify Connection section)
- **Release infra** — `.github/workflows/release.yml` (AAB build + keystore decode + checksums + summary), `gradle/libs.versions.toml` (play-publisher = 3.13.0), `app/build.gradle.kts` (`alias(libs.plugins.play.publisher)` + `play { }` block), `.gitignore` (play-service-account.json + keystore.properties)
- **Release docs** — new `RELEASE.md` (312 lines), `CLAUDE.md` (Release Process subsection)

**Next:**
- Test on device with a long streaming response (verify auto-follow during reasoning + tool cards + text deltas)
- Test the pause-on-scroll-up gesture and the FAB resume
- Verify the disabled-pref path leaves the chat fully manual

**Blockers:**
- None — `compileDebugKotlin` passes clean (only pre-existing `LocalClipboardManager` deprecation warnings unrelated to this change)

---

## 2026-04-09 — Play Store Prep, Plugin CLI Migration, One-Line Install

**Done:**
- **Play Store listing prep (v0.1.0)** — 512x512 hi-res icon + 1024x500 feature graphic rendered from `assets/logo.svg` via `scripts/gen-store-assets.mjs` (pure-Node, `@resvg/resvg-js`). Feature graphic shows centered logo + three channel labels (Chat, Terminal, Bridge). Listing doc at `docs/play-store-listing.md` with short/full descriptions, release notes, and category. Interactive `scripts/screenshots.bat` TUI for capturing clean device screenshots with Android demo mode enabled.
- **Privacy policy page** — `user-docs/privacy.md` published at `/privacy` for Google Play's required URL. Formal language (effective date, COPPA disclosure, contact, change policy). Added Privacy link to top nav.
- **Plugin CLI migration — `hermes-pair` → `hermes pair`** — Rewrote the standalone bash script as a Python module (`plugin/pair.py`) with `plugin/cli.py` registering `hermes pair` via the v0.8.0 `register_cli(subparser)` convention. Pure-Python QR rendering via `segno` (no `qrencode` binary). Always shows connection details as plain text alongside the QR, so `hermes pair` works inside Hermes Rich TUI and over limited SSH sessions. Cross-platform LAN IP detection via socket trick (replaces Linux-only `ip route`). Config fallback chain: `config.yaml` → `~/.hermes/.env` → env vars → defaults. Wrapped `ctx.register_cli_command()` call in try/except so 14 `android_*` tools still register cleanly on hermes-agent v0.7.0.
- **One-line server install** — `install.sh` at repo root: `curl -fsSL .../install.sh | bash` clones plugin, installs Python deps (`requests`, `aiohttp`, `segno`), and prints next steps. Deleted the old `plugin/install.sh` since its own URL comment pointed to the root. Supports `HERMES_HOME` and `HERMES_RELAY_BRANCH` env overrides.
- **README + docs restructure** — Replaced README `## Install` with `## Quick Start` leading with the `curl | bash` one-liner. Homepage (`user-docs/index.md`) gets a custom "Install in 30 seconds" block below feature cards. Guide landing page (`user-docs/guide/index.md`) leads with the same one-liner. Getting Started page restructured into 3-step Quick Start. VitePress copy buttons on code blocks already in place.
- **Tool annotations default OFF** — The chat parse tool annotations feature was causing messages to wait for stream end before displaying. Defaulted to `false` in `ChatHandler.kt` and `ConnectionViewModel.kt`; subtitle now warns about the streaming delay behavior.
- **Deprecated the standalone skill** — `skills/hermes-pairing-qr/SKILL.md` and `hermes-pair` script keep working for v0.7.0 users but print deprecation warnings pointing to the plugin.

**Files changed:**
- `plugin/pair.py` (new), `plugin/cli.py` (new), `plugin/__init__.py` (wire CLI registration), `plugin/setup.py` / `pyproject.toml` / `requirements.txt` / `plugin.yaml` (bumped to 0.3.0 + segno)
- `install.sh` (new at root, replaces `plugin/install.sh`)
- `docs/play-store-listing.md` (new), `assets/play-store-icon-512.png` (new), `assets/play-store-feature-1024x500.png` (new), `scripts/gen-store-assets.mjs` (new), `scripts/screenshots.bat` (new)
- `user-docs/privacy.md` (new), `user-docs/.vitepress/config.mts` (nav link), `user-docs/index.md` (install section), `user-docs/guide/index.md` (Quick Install), `user-docs/guide/getting-started.md` (restructured)
- `README.md` (Quick Start up top), `CLAUDE.md` (key files updated)
- `skills/hermes-pairing-qr/SKILL.md` + `hermes-pair` (deprecation notices)

**Next:**
- Test `hermes pair` against local hermes-agent v0.8.0 once available
- Finalize Play Store screenshots (curate ones without real server IPs/keys)
- Submit v0.1.0 to Google Play Console

**Blockers:**
- hermes-agent v0.8.0 not yet released at time of writing — the `register_cli_command` plumbing exists in v0.7.0 but general plugin CLI commands aren't wired into the main argparse yet. Plugin still installs and registers tools fine on v0.7.0; users fall back to the deprecated standalone script for pairing until v0.8.0 ships.

---

## 2026-04-08 — Tool Call Reload on Stream Complete, Keyboard Gap Fix, Placeholder Dedup

**Done:**
- **Tool call reload on stream complete** — Sessions endpoint doesn't emit structured tool events during streaming; tool calls only exist as `tool_calls` JSON on stored server messages. Added server history reload in `onCompleteCb` when using sessions mode — replaces the single streaming message with the server's authoritative multi-message structure (proper message boundaries + tool call cards). Queue drain deferred until reload completes.
- **Annotation finalization pass** — Added `finalizeAnnotations()` as a post-stream reconciliation hook in `onStreamComplete()` and `onTurnComplete()`. Re-scans final message content for surviving annotation text, strips it, creates missing `ToolCall` objects, and marks incomplete annotation tools as completed. Safety net for servers that emit inline annotation markers.
- **Placeholder message dedup** — When `message.started` fires with a server-assigned ID, the empty placeholder message's ID is now replaced via `replaceMessageId()` (only acts on empty+streaming messages). Prevents orphan placeholder bubbles showing duplicate streaming dots alongside the real message.
- **Keyboard gap fix** — Bottom navigation bar now hides when keyboard is visible (`WindowInsets.ime.getBottom > 0`). Eliminates the gap between input bar and keyboard caused by `innerPadding` (bottom nav height) stacking with `imePadding()` (keyboard height).

**Files changed:**
- `network/handlers/ChatHandler.kt` — Added `replaceMessageId()`, `finalizeAnnotations()`, `matchAnnotationToolName()`. Wired finalization into `onStreamComplete()` and `onTurnComplete()`.
- `viewmodel/ChatViewModel.kt` — `onMessageStartedCb` calls `replaceMessageId()`. `onCompleteCb` reloads session history for sessions mode.
- `ui/RelayApp.kt` — Bottom nav hidden when keyboard visible via IME insets check.

---

## 2026-04-07 — ASCII Morphing Sphere, Ambient Mode, Animation Settings, Polish Fixes

**Done:**
- **ASCII morphing sphere** — animated visualization on the empty chat screen, inspired by AMP Code CLI. Pure Compose Canvas rendering (no OpenGL). Characters `. : - = + * # % @` form a sphere shape with 3D lighting. Color pulses green to purple. Contained in square aspect ratio box above "Start a conversation" text.
- **Ambient mode** — toggle button (AutoAwesome icon) in chat header bar hides messages and shows the sphere fullscreen. Tap the ChatBubble icon to return to chat.
- **Animation behind messages** — sphere renders at 15% opacity behind the chat message list as a subtle ambient background. Toggleable in Settings.
- **Animation settings** — new section in Settings under Appearance: "ASCII sphere" toggle (on by default), "Behind messages" toggle (on by default, disabled when animation is off).
- **Parse tool annotations** — marked as "Experimental" badge, disabled/dimmed when streaming endpoint is "Runs" mode (only relevant for Sessions mode).
- **Empty bubble fix** — messages with blank content and no tool calls are now hidden from chat.
- **App icon fix** — adaptive icon foreground scaled to 75% via `<group>` transform for proper safe zone padding.
- **Dev scripts** — added `release`, `bundle`, `version` commands to `scripts/dev.bat`.
- **MCP tooling** — android-tools-mcp v0.1.1 (IDE/build layer) + mobile-mcp (device/runtime layer) configured as companion MCP servers. Full reference in `docs/mcp-tooling.md`.
- **Audit fixes** — MIT LICENSE added, orphaned `companion/` and `companion_relay/` removed, `FOREGROUND_SERVICE` permission removed, CHANGELOG URLs fixed, version refs updated to 0.1.0, .gitignore updated, plugin refs updated from raulvidis to Codename-11.

**New files:**
- `ui/components/MorphingSphere.kt` — ASCII morphing sphere composable (Canvas-based, 3D lighting, color pulse)

**Files changed:**
- `ui/screens/ChatScreen.kt` — Empty state sphere, ambient mode toggle, behind-messages background layer
- `ui/screens/SettingsScreen.kt` — Animation settings section (ASCII sphere toggle, behind messages toggle), parse tool annotations experimental badge
- `viewmodel/ConnectionViewModel.kt` — Animation preference DataStore keys/flows
- `app/build.gradle.kts` — Version and build config updates
- `res/mipmap-anydpi-v26/ic_launcher.xml` — 75% scale group transform on foreground
- `scripts/dev.bat` — Added release, bundle, version commands

---

## 2026-04-07 — Token Tracking Fix, Stats Enhancements, Keyboard Gap, Configurable Limits

**Done:**
- **Token tracking fix** — Root cause: OpenAI-format SSE events (e.g. `chat.completion.chunk`) have no `type`/`event` field, so the `val eventType = type ?: event.resolvedType ?: return` line exited before usage was ever checked. Moved usage extraction **before** the type resolution in both `sendChatStream()` and `sendRunStream()`. Also added `prompt_tokens`/`completion_tokens` (OpenAI naming) support in `UsageInfo` via `resolvedInputTokens`/`resolvedOutputTokens` helper properties.
- **Stats for Nerds enhancements** — Reset button with confirmation dialog, tokens per message average in summary line (`~Xk/msg`), peak TTFT and slowest completion times (tertiary color), `formatMsWithSeconds()` helper shows `1234ms (1.2s)` for all time displays >= 1s.
- **Configurable limits** — Expandable "Limits" section in Chat settings with segmented button rows for max attachment size (1/5/10/25/50 MB, default 10) and max message length (1K/2K/4K/8K/16K chars, default 4K). Persisted to DataStore, read reactively in ChatScreen.
- **Keyboard gap fix** — Set `contentWindowInsets = WindowInsets(0)` on the Scaffold in RelayApp.kt. The Scaffold was adding system bar padding to `innerPadding` that stacked with ChatScreen's `imePadding()`, causing a visible gap between input bar and keyboard.

**Files changed:**
- `network/HermesApiClient.kt` — Usage check moved before eventType resolution in both streaming methods
- `network/models/SessionModels.kt` — `UsageInfo` now accepts `prompt_tokens`/`completion_tokens`, added `resolvedInputTokens`/`resolvedOutputTokens`/`resolvedTotalTokens`
- `viewmodel/ChatViewModel.kt` — Uses `usage.resolvedInputTokens` etc
- `viewmodel/ConnectionViewModel.kt` — `maxAttachmentMb` + `maxMessageLength` DataStore keys/flows/setters
- `ui/components/StatsForNerds.kt` — Reset button+dialog, tokens/msg, peak/slowest times, `formatMsWithSeconds()`
- `ui/screens/SettingsScreen.kt` — Expandable "Limits" section with segmented buttons
- `ui/screens/ChatScreen.kt` — Reads `charLimit`/`maxAttachmentMb` from settings
- `ui/RelayApp.kt` — `contentWindowInsets = WindowInsets(0)` on Scaffold

---

## 2026-04-07 — File Attachments

**Done:**
- **Generic file attachments** — users can attach any file type via `+` button in the input bar. Uses Android `OpenMultipleDocuments` picker (accepts `*/*`). Files base64-encoded and sent in the Hermes API `attachments` array (`{contentType, content}`).
- **Attachment preview strip** — horizontal scrollable row above input bar showing pending attachments. Image attachments show decoded thumbnails, other files show document icon + filename + size. Each attachment has a remove (X) button.
- **Attachment rendering in bubbles** — user messages display attached images inline (decoded from base64), non-image attachments show as file badge with name. Forward-compatible with agent-sent images.
- **10 MB file size limit** — enforced client-side with toast warning.
- **Send with attachments only** — send button enabled when attachments are present even without text. Sends `[attachment]` as placeholder text.
- **API integration** — `attachments` parameter added to both `sendChatStream()` and `sendRunStream()` in HermesApiClient. Serialized as JSON array matching Hermes WebAPI spec.
- **Message history support** — `MessageItem.imageUrls` extracts `image_url` content blocks from OpenAI-format content arrays for future server-side image rendering.

**Files changed:**
- `data/ChatMessage.kt` — Added `Attachment` data class, `attachments` field on `ChatMessage`
- `network/models/SessionModels.kt` — Added `imageUrls` property to `MessageItem`
- `network/HermesApiClient.kt` — `attachments` param on `sendChatStream()` + `sendRunStream()`, JSON array serialization
- `viewmodel/ChatViewModel.kt` — `_pendingAttachments` StateFlow, add/remove/clear, snapshot-and-clear on send, pass through to API
- `ui/screens/ChatScreen.kt` — `+` button, `OpenMultipleDocuments` picker, attachment preview strip, `formatFileSize()` helper
- `ui/components/MessageBubble.kt` — Inline image rendering (base64 decode), file badge for non-images

---

## 2026-04-07 — Client-Side Message Queuing

**Done:**
- **Message queuing** — Users can now send messages while the agent is streaming. Messages are queued locally and auto-sent when the current stream completes. Queue drains one at a time, maintaining proper ordering.
- **Input bar redesign** — During streaming, both Stop and Send buttons are visible side by side. Send button uses `tertiary` color during streaming to indicate "queue" mode. Placeholder changes to "Queue a message..." when streaming.
- **Queue indicator** — Animated bar above the input field shows queued message count ("1 message queued" / "3 messages queued") with a Clear button to discard the queue. Uses `AnimatedVisibility` for smooth entrance/exit.
- **Queue lifecycle** — Queue is cleared on stream cancellation (Stop button) and on stream error, preventing stale messages from auto-sending after failures.

**Design decisions:**
- Client-side queuing (not server-side `/queue` command) because the Hermes HTTP API doesn't support concurrent SSE streams to the same session. The gateway's `/queue` is a CLI-level feature, not an HTTP endpoint.
- Queue drains automatically — no manual "send next" required. Provides a seamless conversation flow.
- No purple glow on Send button during streaming — visual distinction between "send now" and "queue for later".

**Files changed:**
- `viewmodel/ChatViewModel.kt` — `_queuedMessages` StateFlow, `sendMessage()` queues during streaming, `sendMessageInternal()` extracted, `drainQueue()` on complete, `clearQueue()`, queue cleared on error/cancel
- `ui/screens/ChatScreen.kt` — Queue indicator row, input bar with both Stop+Send buttons, tertiary send tint during streaming, "Queue a message..." placeholder

---

## 2026-04-07 — Feature Gating, MCP Tooling, v0.1.0 Release Prep

**Done:**
- **Feature gating system** — `FeatureFlags.kt` singleton with compile-time defaults (`BuildConfig.DEV_MODE`) and runtime DataStore overrides. Debug builds have all features unlocked; release builds gate experimental features behind Developer Options.
- **Developer Options** — Hidden settings section activated by tapping version number 7 times (same UX as Android system Developer Options). Contains relay features toggle and lock button. Uses `tertiary` color scheme for visual distinction.
- **Gated relay/pairing settings** — Relay Server and Pairing sections in Settings hidden by default in release builds. Only visible when relay feature flag is enabled via Developer Options.
- **Gated onboarding pages** — Terminal, Bridge, and Relay pages dynamically excluded from onboarding flow when relay feature is disabled. Page count and indices adjust automatically.
- **Version bump** — `0.1.0-beta` → `0.1.0` for Google Play submission.
- **BuildConfig.DEV_MODE** — `true` for debug, `false` for release. Used by FeatureFlags as compile-time default.
- **android-tools-mcp v0.1.1** — Fixed MCP server path, built plugin from fork, committed wrapper jar fix, repo cleanup (fork attribution, VM option name fix, cross-platform release script), released to GitHub.
- **mobile-mcp added** — Added `mobile-next/mobile-mcp` as companion MCP server for device/runtime testing (tap, swipe, screenshot, app management). Configured with telemetry disabled.
- **MCP tooling docs** — Created `docs/mcp-tooling.md` with full reference for both MCP servers (setup, prerequisites, 40 tools listed, when-to-use guide, overlap analysis).

**New files:**
- `data/FeatureFlags.kt` — Feature flag singleton
- `docs/mcp-tooling.md` — MCP tooling reference

**Files changed:**
- `app/build.gradle.kts` — Added `DEV_MODE` BuildConfig field, `buildConfig = true`
- `gradle/libs.versions.toml` — Version `0.1.0`
- `ui/screens/SettingsScreen.kt` — Feature-gated relay/pairing, added Developer Options section with tap-to-unlock
- `ui/onboarding/OnboardingScreen.kt` — Dynamic page list based on feature flags
- `CLAUDE.md` — Updated current state, added FeatureFlags to key files, MCP tooling section, related projects

**Next:**
- Build release APK and submit to Google Play (closed testing track)
- Test feature gating on release build (relay settings hidden, dev options tap unlock)
- Phase 2: Terminal channel
- Phase 3: Bridge channel

---

## 2026-04-07 — Session Management Audit, Play Store Release Prep

**Done:**
- **Session management audit** — Full review of session CRUD, persistence, capability detection, error handling. Implementation is complete and solid against upstream Hermes API (both `/api/sessions` non-standard and `/v1/runs` standard endpoints).
- **Fixed SessionDrawer highlight bug** — `backgroundColor` variable was computed but never applied to the Row modifier. Active sessions now properly highlighted with `secondaryContainer`.
- **Added Privacy Policy link** — New "Privacy Policy" button in Settings → About, linking to GitHub-hosted `docs/privacy.md`. Required for Google Play Store submission.
- **Fixed privacy.md inaccuracies** — Added CAMERA permission to the permissions table (used for QR scanning, declared `required="false"`). Corrected network security description to accurately reflect cleartext policy.
- **Fixed RELEASE_NOTES.md URL** — Changed generic `user/hermes-android` to actual `Codename-11/hermes-android`.
- **Improved network_security_config.xml docs** — Expanded comment explaining why cleartext is globally permitted (Android doesn't support IP range restrictions, users connect to arbitrary LAN IPs) and how security is enforced at the application layer (insecure mode toggle + warning badge).

**Session management features confirmed working:**
- List/create/switch/rename/delete sessions with optimistic updates + rollback
- Message history loading with tool call reconstruction
- Auto-session creation on first message send with auto-title
- Session ID persistence via DataStore across app restarts
- Capability detection (`detectChatMode()`) with graceful degradation
- Both Sessions and Runs streaming endpoints

**Play Store readiness:**
- Signing config loads from env vars / local.properties ✅
- ProGuard rules comprehensive ✅
- Release workflow with version validation ✅
- Privacy policy link in app ✅
- Network security documented ✅
- No hardcoded debug flags in release ✅
- Version: `0.1.0-beta` (versionCode 1) — ready for open testing track

**Files changed:**
- `ui/components/SessionDrawer.kt` — Added `background` import + applied `backgroundColor` to Row
- `ui/screens/SettingsScreen.kt` — Added Shield icon import + Privacy Policy button in About card
- `docs/privacy.md` — Added CAMERA permission, fixed network security description
- `RELEASE_NOTES.md` — Fixed issues URL
- `res/xml/network_security_config.xml` — Expanded documentation comment

---

## 2026-04-07 — Chat UI Polish, Annotation Stripping, Reasoning Extraction

**Done:**
- **Scroll-to-bottom FAB** — SmallFloatingActionButton appears when scrolled up from bottom. Animated fade + slide. Haptic on click. Positioned bottom-end of message area.
- **Message entrance animations** — `animateItem()` on all LazyColumn items (messages, spacers, streaming dots). Smooth fade + slide when items appear/reorder.
- **Date separators** — "Today", "Yesterday", or "EEE, MMM d" chips between messages from different calendar days. Subtle surfaceVariant pill style.
- **Message grouping** — Consecutive same-sender messages have tighter spacing (2dp base + 1dp vs 6dp padding), suppressed agent name on non-first messages, grouped bubble corner shapes (flat edges where messages meet).
- **Pre-first-token indicator** — Placeholder assistant message with streaming dots appears immediately after send, before any SSE delta. Fills naturally when first delta arrives.
- **Copy feedback toast** — Snackbar "Copied to clipboard" on long-press copy. Previously only haptic with no visual confirmation.
- **Annotation stripping** — When the tool annotation parser matches inline text (`` `💻 terminal` ``), it now strips that text from the message content. Previously the raw annotation text remained visible alongside the ToolCall card.
- **Inline reasoning extraction** — `<think>`/`<thinking>` tags in assistant text are detected and redirected to `thinkingContent` for the ThinkingBlock. Handles tags split across streaming deltas. Resets on stream complete.

**Files changed:**
- `ui/screens/ChatScreen.kt` — FAB, date separators, grouping, snackbar, animation modifiers, Box wrapper for message area
- `ui/components/MessageBubble.kt` — `isFirstInGroup`/`isLastInGroup` params, grouped bubble shapes, conditional agent name
- `network/handlers/ChatHandler.kt` — `addPlaceholderMessage()`, `stripLineFromContent()`, `processInlineReasoning()`, thinking tag parser, `parseAnnotationLine` returns Boolean
- `viewmodel/ChatViewModel.kt` — placeholder message before stream start

**Note:** Code block copy button already existed (`MarkdownContent.kt` → `CodeBlockWithCopyButton`).

---

## 2026-04-07 — Tool Call Rendering Fix, Runs API, SSE Architecture Correction

**Done:**
- **Fixed premature stream completion** — `assistant.completed` was calling `onComplete()`, terminating the stream before tool events arrived in multi-turn agent loops. Now only `run.completed`/`done` end the stream. `assistant.completed` calls new `onTurnComplete()` which marks one message done without stopping the stream.
- **Added `message.started` handling** — Server-assigned message IDs now tracked via `onMessageStarted` callback. Enables proper multi-turn message tracking (each assistant turn gets its own message).
- **Dynamic message ID tracking** — `ChatViewModel.startStream()` uses `currentMessageId` variable that updates when the server sends new message IDs, instead of hardcoding one UUID for the whole stream.
- **Rewrote tool annotation parser** — Regex patterns now match actual Hermes format: `` `💻 terminal` `` (any emoji + tool name in backticks). Uses state tracking: first occurrence = start, second = complete. Also handles explicit completion/failure emojis (✅/❌) and verbose format (`🔧 Running: tool_name`).
- **Fixed message history tool calls** — `loadMessageHistory()` now reconstructs `ToolCall` objects from assistant messages' `tool_calls` field and matches tool results from `role:"tool"` messages. Previously skipped all tool data.
- **Runs API event coverage** — Added `message.delta`, `reasoning.available`, `run.failed` event handling. Updated `HermesSseEvent` model with `event` field (alias for `type`), `tool` field (Runs API format), `duration`, `output`, `text`, `timestamp`. Added `resolvedType` and `resolvedToolName` helpers.
- **SSE debug logging** — All events logged with `HermesApiClient` tag. Filter with `adb logcat -s HermesApiClient` to see what the server actually sends.
- **Updated decisions.md** — Documented the two streaming endpoints (Sessions vs Runs), tool call transparency differences, upstream API notes.
- **Updated settings description** — Streaming endpoint toggle now explains the difference.

**Architecture correction (from upstream research):**
- `/api/sessions` CRUD endpoints are NOT in upstream hermes-agent source. They may be version-specific (v0.7.0). Standard endpoints are `/v1/chat/completions`, `/v1/responses`, `/v1/runs`.
- `/v1/chat/completions` streaming embeds tool calls as **inline markdown text** (`` `💻 terminal` ``), NOT as separate SSE events. The annotation parser is the primary detection path.
- `/v1/runs` + `/v1/runs/{run_id}/events` provides **structured lifecycle events** with real `tool.started`/`tool.completed` — this is the correct endpoint for rich tool display.
- Hermes has no "channels" API (Discord/Telegram-style). The `channel_directory.py` is for cross-platform message routing, not a chat API.

**Files changed:**
- `network/HermesApiClient.kt` — new callbacks, fixed completion flow, debug logging
- `network/handlers/ChatHandler.kt` — `onTurnComplete()`, annotation rewrite, history tool calls
- `network/models/SessionModels.kt` — new fields for Runs API compatibility
- `viewmodel/ChatViewModel.kt` — dynamic message ID tracking, new callback wiring
- `ui/screens/SettingsScreen.kt` — updated endpoint toggle description
- `docs/decisions.md` — corrected API architecture documentation

**Next:**
- Deploy to device and test tool call rendering with `adb logcat -s HermesApiClient`
- Test with both "Sessions" and "Runs" endpoint modes
- Verify annotation parser matches actual Hermes verbose output
- If Runs API works well, consider making it the default endpoint

**Blockers:**
- Need a running hermes-agent server with tools configured to validate tool event flow end-to-end

---

## 2026-04-06 — Personality System, Command Palette, QR Pairing, Chat Header

**Done:**
- **Personality system fix** — `getProfiles()` was reading wrong JSON path and returning empty list. Replaced with `getPersonalities()` reading `config.agent.personalities` + `config.display.personality`. Server default personality shown first in picker. Switching sends personality's system prompt via `system_message` (previous `profile` field was ignored by server).
- **Agent name on chat bubbles** — Added `agentName` field to `ChatMessage`. Active personality name displayed above assistant messages.
- **Chat header redesign** — Messaging-app style: avatar circle with initial letter + `ConnectionStatusBadge` pulse overlay, agent name (`titleMedium`), model name subtitle from `/api/config`.
- **Command palette** — Searchable bottom sheet with category filter chips (2-row limit, expandable), 29 gateway built-in commands + dynamic personality commands + 90+ server skills from `GET /api/skills`. `/` button on input bar opens palette.
- **Inline autocomplete improved** — Extracted to `InlineAutocomplete` component with `LazyColumn`, 2-line descriptions, up to 8 results.
- **QR code pairing** — ML Kit barcode scanner + CameraX. Detects `{"hermes":1,...}` payload, auto-fills server URL + API key, triggers connection test. Available in Settings and Onboarding.
- **`hermes-pair` skill** — Added to `skills/hermes-pairing-qr/` for users to install on their server. Generator script + SKILL.md.
- **ConnectionStatusBadge** — Reusable animated status indicator with pulse ring (green connected, amber connecting, red disconnected). Wired into Settings, Onboarding, and chat header.
- **Relay server docs** — `docs/relay-server.md`, `relay_server/Dockerfile`, `relay_server/hermes-relay.service`, `relay_server/SKILL.md`.
- **Upstream contributions doc** — `docs/upstream-contributions.md` — proposed `GET /api/commands`, `personality` parameter, terminal HTTP API.

**Corrections to previous session:**
- "Server profile picker" was actually fetching from wrong path — now correctly reads `config.agent.personalities`
- "Sends `profile` field" — server ignores this; now sends `system_message` with personality prompt
- "13 personality commands" were hardcoded — now generated dynamically from server config
- ProfilePicker renamed to PersonalityPicker

---

## 2026-04-06 — v0.1.0-beta Polish, Profiles, Analytics, Splash

**Done:**
- **Package rename** — `com.hermesandroid.companion` → `com.hermesandroid.relay`. All files moved, manifest updated, app name changed to "Hermes-Relay".
- **Server profile picker** — Replaced hardcoded 8-personality system with dynamic server profiles fetched from `GET /api/config`. ProfilePicker in top bar shows Default + server-configured profiles. Sends `profile` field in chat requests.
- **Personality switching** — 13 built-in Hermes personalities available via `/personality <name>` slash commands (server-side, session-level switching).
- **Slash command autocomplete** — Type `/` in chat input to see built-in commands (`/help`, `/verbose`, `/clear`, `/status`) + 13 personality commands + dynamically fetched server skills via `GET /api/skills`. Filterable dropdown overlay.
- **In-app analytics (Stats for Nerds)** — `AppAnalytics` singleton tracking response times (TTFT, completion), token usage, health check latency, stream success rates. Canvas bar charts in Settings with purple gradient. Accessible via Settings > Chat > Stats for Nerds.
- **Tool call display config** — Off/Compact/Detailed modes in Settings. `CompactToolCall` inline component for compact mode. `ToolProgressCard` auto-expands while tool is running, auto-collapses on complete.
- **App context prompt** — Toggleable system message telling the agent the user is on mobile. Enabled by default in Settings > Chat.
- **Animated splash screen** — `AnimatedVectorDrawable` with scale + overshoot + fade animation. Icon background color matches theme. Hold-while-loading (stays until DataStore ready). Smooth fade-out exit transition. Separate `splash_icon.xml` at 0.9x scale.
- **Chat empty state** — Logo + "Start a conversation" + suggestion chips that populate input.
- **Animated streaming dots** — Replaces static "streaming..." text with pulsing 3-dot animation.
- **Haptic feedback** — On send, copy, stream complete, error.
- **About section redesign** — Logo on dark background, dynamic version from `BuildConfig`, Source + Docs link buttons, credits line.
- **Hermes docs links** — In onboarding welcome page, API key help dialog, and Settings About section.
- **Release signing config** — Environment variables + `local.properties` fallback with graceful debug-signing fallback.
- **Centralized versioning** — `libs.versions.toml` as single source of truth (`appVersionName`, `appVersionCode`).
- **Logo fix** — Removed vertical H bars from ghost layer, now matches actual SVG (V-crossbar + diagonal feathers only).
- **SSE debug logging** — Unhandled event types now logged for diagnostics.
- **Release infrastructure (from ARC patterns)** — 3-job release workflow (validate → CI → release) reading from `libs.versions.toml`. Claude automation workflows (issue triage, fix, code review). Dependabot auto-merge. CHANGELOG.md + RELEASE_NOTES.md for v0.1.0-beta. Updated PR template with Android checklist.

**New files:**
- `data/AppAnalytics.kt` — In-app analytics singleton
- `ui/components/StatsForNerds.kt` — Canvas bar charts for analytics
- `ui/components/CompactToolCall.kt` — Inline compact tool call display
- `network/models/SessionModels.kt` — Session, message, SSE event models
- `res/drawable/splash_icon.xml` — Static splash icon (0.9x scale)
- `res/drawable/splash_icon_animated.xml` — Animated splash vector
- `res/animator/` — Splash animation resources
- `.github/workflows/claude.yml` — Claude automation
- `.github/workflows/claude-code-review.yml` — Claude code review
- `.github/workflows/dependabot-auto-merge.yml` — Dependabot auto-merge

**Next:**
- Build and test against running Hermes API server
- Test on emulator and physical device (S25 Ultra)
- Set up keystore/signing secrets for release CI
- Deploy docs site (GitHub Pages or similar)
- Phase 2: Terminal channel (xterm.js in WebView, tmux integration)
- Phase 3: Bridge channel migration

**Blockers:**
- None — ready for on-device testing

---

## 2026-04-05 — Project Scaffolding

**Done:**
- Wrote spec (docs/spec.md) and decisions (docs/decisions.md)
- Created CLAUDE.md handoff for agent development
- Created DEVLOG.md

## 2026-04-05 — MVP Phase 0 + Phase 1 Implementation

**Done:**
- Created Android app — full Jetpack Compose project (30+ files, 2500+ lines)
  - Bottom nav scaffold with 4 tabs (Chat, Terminal, Bridge, Settings)
  - WSS connection manager (OkHttp, auto-reconnect with exponential backoff)
  - Channel multiplexer (typed envelope protocol)
  - Auth flow (6-char pairing code → session token in EncryptedSharedPreferences)
  - Chat UI: message bubbles, streaming text, tool progress cards, profile selector
  - Settings UI: server URL, connection status, theme selector
  - Material 3 + Material You dynamic theming
  - @Preview composables for MessageBubble and ToolProgressCard
  - Terminal and Bridge tabs stubbed for Phase 2/3
- Created relay server — Python aiohttp WSS server (10 files, 1500+ lines)
  - WSS server on port 8767 with health check
  - Auth: pairing codes (10min expiry), session tokens (30-day expiry), rate limiting
  - Chat channel: proxies to Hermes WebAPI SSE, re-emits as WS envelopes
  - Terminal/bridge channel stubs
  - Runnable via `python -m relay_server`
- Created CI/CD — GitHub Actions
  - CI: lint → build (debug APK) → test, relay syntax check
  - Release: tag-triggered, version validation, signed APK → GitHub Release

## 2026-04-05 — Repo Restructure + Build Fixes

**Done:**
- Promoted Android project to repo root (Android Studio opens root directly)
- Removed `hermes-android-bridge/` (upstream absorbed into Compose rewrite)
- Renamed `hermes-android-plugin/` → `plugin/`
- Moved root `tools/`, `skills/`, `tests/` into `plugin/`
- Resolved build issues: gradle.properties, launcher icons, SDK path, compileSdk 36
- Pinned AGP 8.13.2 + Gradle 8.13 + JVM toolchain 17
- Added dev scripts (`scripts/dev.sh` + `scripts/dev.bat`)
- Updated all docs (README, CLAUDE.md, AGENTS.md, DEVLOG.md, spec directory structure)
- Build verified: debug APK builds successfully

**Current state:**
- Phase 0 + Phase 1 complete
- Android Studio opens and syncs from repo root
- Debug APK builds and deploys to emulator/device
- Dev scripts ready for build/install/run/test/relay workflows

## 2026-04-05 — Direct API Chat Refactor

**Done:**
- Refactored chat to connect directly to Hermes API Server (`/v1/chat/completions`)
  - Chat no longer routes through relay server — bypasses it entirely
  - Uses OpenAI-compatible HTTP/SSE with `X-Hermes-Session-Id` for session continuity
  - Auth via `Authorization: Bearer <API_SERVER_KEY>` stored in EncryptedSharedPreferences
- New `HermesApiClient` — OkHttp-SSE client with health check and streaming chat
- New `ApiModels.kt` — OpenAI-format request/response models (ChatCompletionChunk, etc.)
- Refactored `ChatHandler` — removed envelope-based dispatch, added typed SSE entry points
- Refactored `ConnectionViewModel` — dual connection model:
  - API Server (HTTP) for chat — URL, key, health check, reachability state
  - Relay Server (WSS) for bridge/terminal — separate URL, connect/disconnect
- Refactored `ChatViewModel` — sends via `HermesApiClient.sendChatStream()` with cancel support
- Updated Onboarding — collects API Server URL + API Key (required) + Relay URL (optional)
- Updated Settings — split into "API Server" and "Relay Server" cards
- Updated ChatScreen — gates on API reachability, added stop button for streaming
- Updated DataManager — backup format v2 with separate apiServerUrl/relayUrl fields
- Updated docs/decisions.md with ADR for direct API chat
- Updated docs/spec.md with new chat architecture

**Architecture change:**
```
Before: Phone (WSS) → Relay (:8767) → WebAPI (:8642)  [everything]
After:  Phone (HTTP/SSE) → API Server (:8642)          [chat — direct]
        Phone (WSS) → Relay (:8767)                     [bridge, terminal]
```

## 2026-04-05 — Edge Case Fixes + CI/CD Hardening

**Done:**
- Fixed SSE thread safety — all callbacks dispatched to main thread via Handler
- Fixed overlapping streams — previous stream cancelled before new send
- Fixed tool call completion — now matches by toolCallId instead of first incomplete
- Fixed onboarding test connection — client properly cleaned up on exception via try/finally
- Fixed health check loop — only runs when API client is configured
- Added `network_security_config.xml` — cleartext restricted to localhost/127.0.0.1/emulator
- Added ProGuard rules for `okhttp-sse` (okhttp3.sse.**, okhttp3.internal.sse.**)
- Added `id` field to ToolCall data class for proper matching
- SSE read timeout set to 5 minutes (was 0/infinite — detects dead connections)
- OkHttpClient.shutdown() now uses awaitTermination for clean teardown
- Used AtomicBoolean for completeCalled flag (thread-safe)
- Created CHANGELOG.md (Keep a Changelog format)
- Created RELEASE_NOTES.md (used as GitHub Release body)
- Updated release.yml — uses RELEASE_NOTES.md body, SHA256 checksums, prerelease detection
- Created .github/dependabot.yml (Gradle + GitHub Actions, weekly, grouped)
- Created .github/PULL_REQUEST_TEMPLATE.md with checklist
- Added in-app "What's New" dialog in Settings (reads from bundled whats_new.txt asset)
- Bumped versionCode=2, versionName=0.2.0

## 2026-04-05 — Session Management + What's New Auto-Show

**Done:**
- Switched chat streaming from `/v1/chat/completions` to `/api/sessions/{id}/chat/stream`
  - Proper Hermes session API — not the undocumented X-Hermes-Session-Id header
  - Parses Hermes-native SSE events (assistant.delta, tool.started, tool.completed, assistant.completed)
- Full session CRUD in HermesApiClient:
  - `listSessions()`, `createSession()`, `deleteSession()`, `renameSession()`, `getMessages()`
- Session drawer UI (ModalNavigationDrawer):
  - List sessions with title, timestamp, message count
  - New Chat button, switch sessions, rename/delete with confirmation dialogs
  - Hamburger menu icon in ChatScreen top bar
- Session lifecycle:
  - Auto-creates session on first message if none active
  - Auto-titles session from first user message (truncated to 50 chars)
  - Message history loads when switching sessions
  - Last session ID persisted to DataStore — resumes on app restart
  - Optimistic deletes and renames
- What's New auto-show:
  - Tracks last seen version in DataStore (KEY_LAST_SEEN_VERSION)
  - Shows WhatsNewDialog automatically when version changes
  - Dismisses and records current version
- New models: SessionModels.kt (SessionItem, MessageItem, HermesSseEvent, etc.)
- Updated ChatHandler with session management methods (updateSessions, removeSession, etc.)
- Updated ChatMessage.ChatSession with messageCount and updatedAt fields
- Updated whats_new.txt with session management features

## 2026-04-05 — MVP Polish: Markdown, Reasoning, Tokens, Personalities, UX

**Done:**
- **Markdown rendering** — assistant messages render with full markdown (code blocks, bold, italic, links, lists) via mikepenz multiplatform-markdown-renderer-m3
- **Message copy** — long-press on any message bubble to copy text to clipboard
- **Reasoning/thinking display** — collapsible ThinkingBlock above assistant responses when agent uses extended thinking; toggle in Settings
- **Token & cost tracking** — per-message token count (↑input ↓output) and estimated cost displayed below timestamp
- **Personality picker** — dropdown in chat top bar with 8 built-in personalities (default, concise, creative, technical, teacher, formal, pirate, kawaii); injects system_message into chat stream
- **Error retry button** — "Retry" button in error banner re-sends last failed message
- **Offline detection** — ConnectivityObserver via ConnectivityManager; shows offline banner when network is lost
- **Streaming state fix** — onStreamError now clears isStreaming/isThinkingStreaming on all affected messages
- **Haptic feedback** — on send, stream complete, error, and message copy
- **Input character limit** — 4096 char limit with counter shown near the limit
- **Responsive layout** — bubble width adapts by screen width: 300dp (phone), 480dp (medium), 600dp (tablet)
- **Enriched tool cards** — tool-type-specific icons (terminal, search, file, tap, keyboard, etc.), duration tracking ("Completed in X.Xs")
- **Accessibility** — content descriptions on message bubbles, tool cards, thinking blocks, all interactive elements
- **Dead code cleanup** — deleted unused ApiModels.kt and ChatModels.kt
- **Settings: Chat section** — new "Show reasoning" toggle
- Added ACCESS_NETWORK_STATE permission to AndroidManifest

**New files:**
- `ui/components/MarkdownContent.kt` — Compose markdown renderer wrapper
- `ui/components/ThinkingBlock.kt` — collapsible reasoning/thinking display
- `ui/components/TokenDisplay.kt` — per-message token + cost display
- `ui/components/PersonalityPicker.kt` — personality selection dropdown
- `network/ConnectivityObserver.kt` — reactive network connectivity listener

**New dependencies:**
- `com.mikepenz:multiplatform-markdown-renderer-m3:0.30.0`
- `com.mikepenz:multiplatform-markdown-renderer-code:0.30.0`
- `material3-window-size-class` (responsive layout)

## 2026-04-05 — Full Audit + Bug Fixes + VitePress Docs Site

**Audit findings fixed (9 bugs):**
- CRITICAL: Added ProGuard rules for mikepenz markdown renderer + intellij-markdown parser
- CRITICAL: Cached fallback StateFlows in ChatViewModel (was creating new instances per access)
- MAJOR: Fixed MessageItem.id type from Int? to String? (server returns string IDs)
- HIGH: Added !isStreaming guard to send button (prevented double-send)
- HIGH: Added personality fallback when ID not found (prevents null system message)
- MEDIUM: Session rename dialog remember key now includes session (prevents stale state)
- MEDIUM: ToolProgressCard duration guard against negative values
- LOW: Error banner text limited to 2 lines with ellipsis
- BUILD: Unified AGP version to 8.13.2 in libs.versions.toml

**VitePress documentation site created:**
- 20 pages across 4 sections: Guide, Features, Architecture, Reference
- Landing page with hero section and 8 feature cards
- Full user guide: getting started, chat, sessions, troubleshooting
- Feature docs: direct API, markdown, reasoning, personalities, tokens, tools
- Architecture docs: overview diagram, ADRs adapted from docs/decisions.md, security model
- API reference with all endpoints and request/response examples
- Configuration reference with all DataStore keys and settings
- VitePress config with nav, sidebar, local search
- Run with: `npm install && npm run dev:docs`

**Next:**
- Build and test against running Hermes API server
- Test on emulator and physical device (S25 Ultra)
- Set up keystore/signing secrets for release CI
- Deploy docs site (GitHub Pages or similar)
- Phase 2: Terminal channel (xterm.js in WebView, tmux integration)
- Phase 3: Bridge channel migration
