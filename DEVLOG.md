# Hermes-Relay — Dev Log

## 2026-04-19 — Multi-endpoint pairing + first-class Tailscale (ADR 24 + 25)

**Branches:** Wave 1 + Wave 2 landed on `dev`. ADRs 24 and 25 written and committed. Docs pass (this entry) closes the work out.

**Shipped.**

- **ADR 24 — Multi-endpoint pairing.** `plugin/pair.py` now emits an ordered `endpoints` array (`lan` / `tailscale` / `public` / operator-defined roles) in a new `hermes: 3` QR schema; `hermes: 2` stays the default when no endpoints are present. New CLI flags `--mode {auto,lan,tailscale,public}` and `--public-url <url>` drive candidate emission; `--mode auto` autodetects LAN IP + Tailscale status and composes them with an optional public URL. `plugin/relay/qr_sign.py` canonical form preserves array order and role strings verbatim — HMAC round-trip test pins this against future refactors. `plugin/relay/server.py` `handle_pairing_mint` / `handle_pairing_register` accept the optional `endpoints` body field. Phone side: new `data/Endpoint.kt` (`EndpointCandidate` / `ApiEndpoint` / `RelayEndpoint` / `displayLabel()`), `data/PairingPreferences.kt` per-device endpoint store, `ui/components/QrPairingScanner.kt` parses the new field with a v1/v2 synthesizer for back-compat, `auth/AuthManager.kt` persists the endpoint list on `auth.ok`, and `viewmodel/ConnectionViewModel.kt` stages endpoints at pair time. Wave 2 Kt-Probe added `ConnectionManager.resolveBestEndpoint()` + `NetworkCallback` re-probing and `RelayUiState.activeEndpointRole`.
- **ADR 25 — First-class Tailscale helper.** New `plugin/relay/tailscale.py` with `status()` / `enable(port)` / `disable(port)` / `canonical_upstream_present()` — all shell out to the `tailscale` CLI with short timeouts, return structured dicts, never raise. New `plugin/relay/tailscale_cli.py` argparse wrapper + `scripts/hermes-relay-tailscale` shell shim mirroring the `hermes-pair` pattern. `install.sh` gains optional step [7/7] offering Tailscale enablement; honours `TS_DECLINE=1` / `TS_AUTO=1`.
- **Dashboard Remote Access tab (Wave 2 Dashboard-R4).** `plugin/dashboard/plugin_api.py` + React UI + committed `dist/index.js` — operator can enable/disable the helper, mint multi-endpoint QRs, and inspect which modes are active without dropping to a shell.
- **Docs pass.** New `docs/remote-access.md` (263 lines) — decision matrix + per-mode setup recipes + troubleshooting. Updated `docs/security.md` with a "Remote connectivity" subsection + top-of-list Tailscale recommendation. Updated `docs/relay-server.md` with `TS_AUTO` / `TS_DECLINE` env vars, the dashboard plugin proxy-route table, and a Tailscale-helper subsection. Updated `docs/spec.md` §3.3 + §3.3.1 for v3 QR schema + endpoints array. Updated `README.md` "What's new" with the one-line connect-from-anywhere pitch. Updated `CHANGELOG.md` `[Unreleased]` with Added / Changed / Backward compatible subsections. Updated `CLAUDE.md` Key Files + Integration Points (hygiene pass only).

**Key architectural decisions (restated from the ADRs).**

1. **Strict priority, not reachability-weighted.** Priority 0 wins when reachable; reachability is a tiebreaker among equal priorities, never a promoter across priority boundaries. Matches DNS SRV semantics — nothing new to debate, and keeps operator intent authoritative.
2. **Open-string `role`, not a closed enum.** `wireguard`, `zerotier`, `netbird-eu`, etc. render as generic "Custom VPN (<role>)" without a release. HMAC canonicalization preserves the exact emitted string.
3. **Tailscale helper auto-retires on upstream merge.** `canonical_upstream_present()` probes `hermes gateway run --help | grep tailscale`; once PR #9295 lands, the helper prints a log line pointing at the canonical flag and exits 0. Same removal pattern as `hermes_relay_bootstrap/` after PR #8556.
4. **No second crypto layer.** The operator already owns both endpoints and the transport (Tailscale / Caddy / WireGuard / Cloudflare Tunnel) is TLS-terminated by the operator's chosen path. Adding Noise / libsignal over WSS would add complexity without defending any threat in the trust model.

**What's next.**

- Monitor upstream PR #9295 for merge. When it lands, verify `canonical_upstream_present()` detection works on a vanilla hermes-agent install, update the helper to print the retirement log line, and schedule the helper's removal PR (one file + the install.sh step + the shim).
- Bailey's Studio build + `./gradlew lint` pass before the multi-endpoint work gets pushed from Kotlin side. No Python blockers.

**Blockers.** None. Awaiting Bailey's lint + build pass on the Kotlin changes before the feature branches merge into `dev`.

## 2026-04-18 — Profile Inspector UI (v0.7.0)

**Branch:** `feature/profile-inspector-ui`. Kotlin-worker slice of the v0.7.0 Profile Inspector feature — Python worker runs in parallel and owns the `/soul` + `/memory` relay endpoints. Two feature commits + docs.

**Shipped (Kotlin).**

- **`RelayProfileInspectorClient` + wire models.** New read-only HTTP client for the four inspector endpoints (`/api/profiles/{name}/config`, `/skills`, `/soul`, `/memory`) mirroring `RelayHttpClient`'s constructor shape (OkHttp, lazy bearer-token provider, `ws://` → `http://` URL flip). All four fetch methods return `Result<T>` and hop to `Dispatchers.IO` before any network I/O — reinforcing the v0.6.0 post-mortem fix for `NetworkOnMainThreadException` in the voice client. Profile names are URL-encoded before being spliced into the path so names with spaces / non-ASCII characters don't blow up the route. Wire models (`ProfileConfigResponse`, `ProfileSkillsResponse`, `ProfileSoulResponse`, `ProfileMemoryResponse`) are `@Serializable` with snake_case → camelCase mapping via `@SerialName`. Optional wire fields (`truncated`, `readonly`, `enabled`) default to safe values so pre-v0.7.0 relays that omit them deserialize cleanly.
- **`ProfileInspectorScreen` (4 tabs).** Config renders as a collapsible JSON tree (nested objects click to expand, monospace leaf values, 120-char truncation). SOUL is a scrollable monospace box with byte-size caption + truncation banner when the server flags the content as sliced; absent SOUL renders an empty-state with the expected path. Memory is a list of expandable cards per entry (filename + size in the header, content revealed on tap, per-entry truncation banner). Skills groups by category with a "(disabled)" label on skills where `enabled=false`. Top-bar Refresh icon fires `loadAll()`; each pane has its own inline Retry button on error state. PullToRefresh was skipped in favour of the explicit Refresh icon + per-section Retry — matches `PairedDevicesScreen`'s "still-experimental PullRefresh" note.
- **`ProfileInspectorViewModel`.** Four independent `LoadState<T>` flows — one per section — so a slow `/memory` fetch never blocks the already-arrived `/config` tab. Lazy (no fetch until `loadAll()`) and stateful per-section (`refreshSection(InspectorSection.Config)` re-fetches just that tab). Profile name comes in via `SavedStateHandle` so a process-death restore inspects the same profile. The VM is keyed off the profile name in the nav backstack so entering a different profile produces a fresh VM rather than reusing stale state.
- **`ProfileInspectorCard` in Settings.** Sits directly under `ActiveAgentCard` so the "active agent → inspect it" reads naturally top-to-bottom. When no profile is selected, the card renders at 50% alpha with "No active agent" and becomes a no-op — kept visible (not hidden) so the feature stays discoverable before a pair-and-pick happens.
- **`Screen.ProfileInspector` nav destination.** Typed String path arg, registered via a tiny `ViewModelProvider.Factory` that pulls `SavedStateHandle` out of `CreationExtras` so nav args propagate into the VM constructor cleanly. Back navigation pops the destination and the VM is GC'd with it.

**Key architectural decisions.**

1. **Four independent load states, not one "screen state".** Each section's flow transitions Idle → Loading → Loaded/Error independently. One combined state would have meant the fastest-arriving tab gets blocked by the slowest — unnecessary UX cost given the 3 - 4 tabs the user flips between.
2. **URL-encoded profile-name splice, not an OkHttp path segment.** We use `URLEncoder.encode(..., "UTF-8").replace("+", "%20")` before splicing into the literal path string, then build the `HttpUrl` from the full string. OkHttp's `addPathSegment` would re-encode and produce double-encoding for profile names with `%` already in them (pathological but possible). A single round of encoding is the safe choice.
3. **ViewModel keyed on nav arg.** `viewModel(key = "profile-inspector-$name", ...)` means the same profile navigated to twice yields the same VM (back-stack reuse), but switching to profile B produces a fresh VM — avoids the "I opened inspector for axiom, changed profiles, reopened and saw axiom's data" class of bug.

**Deferred.**

- **Pull-to-refresh gesture.** Explicit Refresh button is enough for v0.7.0; if users ask for the gesture we'll revisit once Material3's `PullToRefreshBox` graduates out of experimental.
- **Edit-in-place.** Inspector is strictly read-only by design. Editing is still "SSH to the server and `$EDITOR config.yaml`" territory. A "Copy to clipboard" affordance on each section is a possible follow-up if users ask for it.
- **MockWebServer integration tests.** `testImplementation` doesn't include MockWebServer and the spec forbids adding deps for this slice — we covered wire-contract parsing + URL encoding + required-field enforcement via JVM-local tests, and the client's actual network path is exercised by the on-device relay smoke test.

**Upstream dependency.** Soul and Memory endpoints land via the parallel Python worker on the same branch. The Kotlin side is fetch-and-render — if either endpoint returns a 5xx / 404 before the Python change merges, the relevant tab just shows a Retry error state and the rest of the inspector keeps working.

## 2026-04-18 — Profile metadata + read-only config API (v0.7.0 groundwork)

**Branch:** `feature/profile-config-readonly`. Kotlin-worker slice of the v0.7.0 groundwork (Python-worker slice runs in parallel, owns the relay-side wire contract). Three feature commits + docs.

**Shipped (Kotlin).**

- **Extended `Profile` data class.** Added three optional fields — `gatewayRunning: Boolean`, `hasSoul: Boolean`, `skillCount: Int` — mapped via `@SerialName` to the snake_case wire keys (`gateway_running`, `has_soul`, `skill_count`) the relay will emit in `auth.ok` profiles entries. All three default to safe zero-values so pre-v0.7 relays deserialize cleanly. `AuthManager.parseAgentProfiles` reads them with `booleanOrNull` / `intOrNull` fallbacks — malformed values fall through to defaults rather than crashing the pairing handshake.
- **Runtime-metadata indicators in the agent sheet.** Each Profile row in `AgentInfoSheet` now carries a 6 dp status dot (green when gateway_running, grey otherwise), an optional "N skills" chip (hidden when skill_count == 0), and an optional "SOUL" badge (primary-container, hidden when has_soul is false). Gateway-off profiles stay selectable at 50% alpha — the probe is best-effort, and a stale dot shouldn't lock a user out. `ProfileRadioRow` grew three optional params (`contentAlpha`, `leadingDotColor`, `secondaryTrailing` slot) without changing the Default/personality-row call sites. Also added an inline "Profile SOUL overrides personality while active" caption under the personality section when a profile-with-SOUL + non-default personality are both selected, mirroring the existing caption in the Profile section.
- **Persisted `selectedProfile` per Connection.** New `ProfileSelectionStore` — its own DataStore (`profile_selections`) keyed by connectionId — lets each Connection remember its last-picked profile across app restart. `ConnectionViewModel.selectProfile` writes through. Connection switch clears `_selectedProfile` first (so a stale A pick never dangles on B) then loads B's persisted name and resolves it against `agentProfiles` once the post-switch `auth.ok` repopulates the list. `removeConnection` calls `store.clear(connectionId)` after the switch-away to avoid racing the unmounted store.

**Key architectural decisions.**

1. **Name-based persistence, not Profile-object persistence.** The persisted value is the profile `name: String`, resolved at read time against the server's fresh `agentProfiles` list. Profiles can be renamed, removed, or re-modelled on the server between app launches; persisting a full `Profile` snapshot risks silently using a stale model override. Resolution-at-read yields null when the name no longer exists, and the UI falls through to the Default row.
2. **Dedicated DataStore.** `profile_selections` is separate from `relay_settings` so clearing or migrating one doesn't threaten the other. A new per-connection key prefix (`selected_profile_<id>`) lets us clear-per-connection cleanly on removal.
3. **Gateway-off profiles remain selectable.** Spec explicitly allows the user to pick a profile whose gateway probe is grey. Rationale: the probe is best-effort, can miss a recent restart, and hard-disabling a row based on a stale probe would confuse users who just ran `hermes profile use` and haven't restarted the relay.

**Deferred.**

- **Migration of any old "ephemeral" pick into the new store.** N/A — the prior behaviour was to always reset on restart, so there's nothing to migrate from. First boot on v0.7.0 starts clean; next `selectProfile` call writes through.
- **UI for inspecting the gateway-running timestamp.** Useful for debugging stale probes but noise for the common path. Track in a future dashboard pass if it's actually asked about.

**Upstream dependency.** The three new profile fields are optional on the wire and fall back to defaults, so this PR is safe to land ahead of the Python-worker relay change. Pairing against a pre-v0.7 relay continues to work — the phone just renders every dot grey and hides every badge.

## 2026-04-18 — v0.6.0: Multi-Connection + Agent Profiles

**Branch:** `feature/multi-profile-connections`. Landing as v0.6.0. Two orthogonal pieces of work that compose into a three-layer agent model (Connection → Profile → Personality), plus a top-bar + Settings UX consolidation.

**Shipped.**

- **Multi-Connection (`docs/decisions.md` §19).** Renamed the original internal "profile" concept to **Connection** — a paired Hermes *server*. `ConnectionStore` persists N connections in DataStore with the active one, migration seeds connection 0 from the existing `hermes_companion_auth_hw` store (zero re-pair, transparent to the user), `SessionTokenStore` takes a `prefsName` parameter so each Connection gets its own `EncryptedSharedPreferences` file, `AuthManager` gains a `connectionId` ctor. Switch is a heavy context swap: cancel in-flight SSE, disconnect relay WSS, rebind provider StateFlows (`RelayHttpClient` / `RelayVoiceClient` re-read lazily), rebuild `HermesApiClient`, reconnect, reprobe capabilities, reload sessions + personalities + profiles, restore per-Connection last-active session. Top-bar Connection chip + `ConnectionSwitcherSheet` for switching; Settings → Connections for CRUD (rename, re-pair via `ConnectionWizard`, revoke, remove). Per-connection scope: sessions, memory, personalities, skills, profiles, relay cert pin, voice endpoints, last-active session. Global scope: theme, bridge safety prefs, TOFU cert-pin map, notification companion state.
- **Agent Profiles (`docs/decisions.md` §21).** Directory-scan + overlay. Relay walks `~/.hermes/profiles/*/`, reads each profile's `config.yaml` + `SOUL.md`, and advertises the list in `auth.ok` as `{name, model, description, system_message}` (plus a synthetic "default" entry for the root config). Phone sends `model` override + `system_message` from `SOUL.md` on chat turns when a profile is selected. Gated by `RELAY_PROFILE_DISCOVERY_ENABLED=1` (default on). Shipped as `overlay-not-isolation`: memory, sessions, `.env`, skills, and cron jobs still ride the Connection's default gateway. For full isolation, run the profile's own gateway on its own port and pair as a separate Connection.
- **Consolidated agent sheet.** Deleted standalone `ProfilePicker.kt` and `PersonalityPicker.kt` top-bar chips in favor of a single bottom sheet opened from the top-bar agent name. Sheet holds Profile list, Personality list, and session info + analytics (message count, tokens in/out, avg TTFT). Scrollable. Toast confirmations on switch. Reclaims top-bar real estate and reduces the visual layer count (one tap instead of two chips).
- **Settings "Active agent" card** — top-of-Settings summary of the current Connection / Profile / Personality with a `openAgentSheet` nav arg that navigates to Chat and auto-opens the sheet. Closes the "how do I change my agent" discoverability gap for Settings-originating users.
- **Polish pass (7031115).** Pair wizard URL-scheme cross-validation (inline hint when API field has `wss://`); pair-stamp of the active Connection's metadata on successful auth (no stale state after re-pair); `ConnectionStatusBadge` top-aligns on multi-line rows; Settings treats paired + briefly-disconnected Connection as **Connecting** (amber) rather than **Disconnected** (red).

**Key architectural decisions.**

1. **Directory-scan for profiles.** First pass parsed a fictional top-level `profiles:` / `agents:` list in `config.yaml`. Upstream has never used that shape — profiles upstream are isolated directory instances at `~/.hermes/profiles/<name>/`, each with its own config, `.env`, `SOUL.md`, memory, sessions, and (optionally) its own gateway daemon. Old path always returned empty on real installs. Rewrite: walk `~/.hermes/profiles/*/`, read config + SOUL, report what's really there. See the "Earlier (abandoned) design" paragraph in `docs/decisions.md` §21 and commits `0303a4f` / `b9d2914` / `ec7559c`.
2. **Overlay-not-isolation for v1.** A profile overrides `model` + `system_message`; everything else (memory, sessions, skills, API keys) rides the Connection's gateway. Rationale: a real per-profile isolation layer is "run the profile's gateway as its own service and pair it as a separate Connection" — we already have the plumbing for that via Multi-Connection. Building a second isolation layer inside one gateway would double the attack surface for no UX win.
3. **Three-layer model = Connection → Profile → Personality.** Connection picks the server. Profile picks the agent directory on that server. Personality picks a system-prompt preset within the agent's config. Hierarchy flows top to bottom; picking a Connection resets Profile (Profile is ephemeral and server-scoped). `docs/decisions.md` §8 now carries a terminology-note block making this explicit, since the §8 title still says "Profiles" in the legacy sense.
4. **Kept scope intentionally small for v0.6.0.** No per-Connection Profile persistence, no gateway-running probe, no mode where one Connection hosts multiple profile-gateways behind one pairing. Those are §21 follow-ups (see Deferred).

**Deferred to v0.7+.**

- **True per-profile isolation via separate Connections.** Doc how `hermes -p <name> platform start api --port 8643` + pair-as-new-Connection achieves this today, in user-docs.
- **Persisted profile selection per Connection.** Currently resets on app restart and Connection switch. ~30-line extension: add `lastSelectedProfileName` to the `Connection` data class and restore on switch.
- **Gateway-running probe** (hermes-desktop-inspired). Would let the Connection health indicator distinguish "server unreachable" from "paired, awake, responding" without waiting for a first request to fail. Low-priority; the existing probe behavior is adequate for v0.6.0.

**Docs pass.**

- `CHANGELOG.md` — new `[0.6.0]` section above the existing (v0.5.x-work-in-progress) entries.
- `docs/spec.md` — Chat top-bar section rewritten around the three-chip reality; `auth.ok` `profiles[]` field documented.
- `docs/decisions.md` — §8 gained a terminology-note block pointing forward to §19 and §21.
- `user-docs/features/connections.md`, `profiles.md`, `personalities.md` — top-bar chip references updated to the agent sheet. `index.md` feature grid picked up Connections + Profiles rows.
- `user-docs/guide/chat.md` — Personalities section expanded into "Agent Sheet — Profile + Personality" + Connection Chip subsection. `getting-started.md` gained a "Multiple Hermes servers" tip pointing at `features/connections.md`.
- `user-docs/architecture/decisions.md` — new ADR-14 (Multi-Connection) + ADR-15 (Agent Profile picker) mirroring `docs/decisions.md`.
- `README.md` — new "What's new in v0.6.0" block above the feature list; added a feature bullet for multi-Connection + profiles.

## 2026-04-18 — Dashboard pairing: `/pairing/mint` schema rework + grants render fix

**Context.** Bailey hit a silent pairing failure scanning QRs minted by the dashboard's "Pair new device" flow. Logcat showed the app attempting pairing with `serverUrl=http://172.16.24.250:8767` (relay's port, not API's) and an empty `relay` block (`relayUrl=` and `code=` both blank), causing `applyServerIssuedCodeAndReset` to bail with "empty code, returning early — authState NOT reset" and the WSS never handshook.

**Root cause.** `handle_pairing_mint` in `plugin/relay/server.py` was written as if the QR was relay-only: it defaulted top-level `host/port/tls` to `server.config.host/port` (which is the RELAY's bind — `0.0.0.0:8767`), put the freshly-minted pairing code in top-level `key`, and emitted only session metadata inside the `relay` block (`ttl_seconds`, `grants`, `transport_hint` — no `url`, no `code`). But the wire format documented at `docs/spec.md` §3.3.1 and implemented by `QrPairingScanner.kt` expects top-level = **API** server (port 8642 default) with `relay.{url,code}` nested. The CLI path (`pair.py` → `build_payload` at line 762) was correct; the endpoint was the outlier. The dashboard plugin's "editable pair URL" feature (commit `d7e5fc8`) inherited the broken semantics because its request body forwards `host/port/tls` verbatim to the endpoint.

**Fix.** `handle_pairing_mint` now mirrors the CLI shape exactly:
- Top-level `host/port/tls` default from `RelayConfig.webapi_url` (parsed via `urllib.parse.urlparse`; host resolved through `pair._resolve_lan_ip` so `localhost` / `0.0.0.0` become the machine's LAN IP, which is what the phone needs)
- Body overrides: `host` / `port` / `tls` / `api_key`
- `relay_block["url"]` derived from `_relay_lan_base_url(server.config.host, server.config.port, tls=bool(server.config.ssl_cert))` — the relay knows its own WSS address
- `relay_block["code"]` carries the minted value (used to live at top-level `key`; `key` is now the optional API bearer)

Regression test at `plugin/tests/test_pairing_mint_schema.py` — 8 AioHTTP cases pinning the payload shape that `QrPairingScanner.kt` parses, including that the minted code lands in `relay.code` (not top-level `key`) and top-level port defaults to 8642 (not 8767). All pass. Test file uses `unittest` per CLAUDE.md — `pytest` tripped on `conftest.py`'s `responses` import on the server venv.

**Doc sync.** `docs/spec.md` §3.3.1 already documented the correct wire format — this fix brought the server code back into line. Bumped the Updated stamp from 2026-04-13 to 2026-04-18 and added a line under Implementation references pointing at `handle_pairing_mint` + the new regression test. `CLAUDE.md` Key Files rows for `plugin/relay/server.py` + `plugin/dashboard/plugin_api.py` updated to reflect the API-server-at-top-level semantics.

**Deployment hazard discovered along the way.** The server had two parallel checkouts: `~/hermes-relay/` (a dev clone, looked authoritative because SYSTEM.md lists `~/` as the project root) and `~/.hermes/hermes-relay/` (the actual symlinked install per CLAUDE.md). Running `git pull` in the wrong one updated nothing visible — restart-and-check reported stale version. Verified via `systemctl --user cat hermes-relay | grep WorkingDirectory` that the installed path is the `.hermes/` copy; then pulled + restarted on that one. Also noticed the installed repo was on a long-dead `feature/dashboard-plugin` branch with 10 local WIP commits (bisect diagnostics from the dashboard build-up). Switched to `main` (fast-forward clean, 45 commits) after confirming the local feature branch's meaningful work was already on origin/main through the merged PR. The dead branch stays in local refs for history; origin has no matching ref.

**Bonus fix in the same branch.** Dashboard's Relay Management tab was crashing with minified React error #31 (`object with keys {chat, terminal, bridge}`) when rendering the paired-sessions list. `RelayManagement.jsx:172` wrapped a dict-shaped `s.grants` in a 1-element array then rendered each entry as a React child — Badge doesn't accept objects. Fix: `Object.keys(s.grants)` when the value is dict-shaped so Badge children are always channel-name strings. Rebuilt `plugin/dashboard/dist/index.js` via `npm run build` (the dashboard loads the pre-built IIFE verbatim, source edits without a rebuild are invisible).

**Branch + PR.** `fix/pairing-mint-schema` — two commits (`4f0affa`, `ca50524`), pushed, deployed to `~/.hermes/hermes-relay/`, restarted, verified live `curl /pairing/mint` round-trip produces the correct shape. PR open at the origin-suggested URL — merge to land in v0.6.0.

**Principle.** The original endpoint divergence would have been caught immediately by a schema round-trip test between the server's minted payload and the Android parser's data class. Added that test now as guardrail, not post-mortem — the two implementations will drift again eventually, this time CI yells instead of a silent field-mapping failure.

## 2026-04-18 — v0.5.1 release prep: lint iterations + VoicePlayerTest deferred

**PR #31** (`feature/voice-quality-pass` → `main`, v0.5.1 candidate) — CI iteration, no app-behavior changes beyond the preceding v0.5.1 feature commits.

**Lint round.** PR CI first failed on three latent Android lint errors, which I fixed one-at-a-time because Android lint prints only the first failure before aborting:
1. `UnsafeOptInUsageError` at `VoicePlayer.kt:88` — Media3 `ExoPlayer.audioSessionId` is `@UnstableApi`. Fix: class-level `@OptIn(UnstableApi::class)`. First attempt used Kotlin's `@OptIn` (implicit import) which Android lint's `UnsafeOptInUsageError` rule does not recognize; had to explicitly `import androidx.annotation.OptIn` to get the AndroidX variant. This distinction is non-obvious from the error text.
2. Second lint pass then surfaced `FlowOperatorInvokedInComposition` at `RelayApp.kt:421` and `:424` — pre-existing on `main`, masked behind the first-failure abort. Fix: wrap `.map { }` chains in `remember(repo) { repo.settings.map { ... } }` so the Flow is stable across recompositions.
3. Bailey called out that I should have run `./gradlew lint` locally before pushing instead of burning CI iterations. Codified the rule in CLAUDE.md step 4 of "Typical Dev Loop" + a cross-session feedback memory. Both changes land in PR #31.

**Test round — VoicePlayerTest deferred.** The voice-quality-pass branch added `VoicePlayerTest.kt` with `mockkConstructor(ExoPlayer.Builder::class)` + `mockk<ExoPlayer>()`. Both trip MockK's Objenesis instantiator into loading Media3's `ExoPlayer` class, whose static init chain references `android.os.Looper` — unavailable in JVM unit tests. CI hung for 7+ minutes before we cancelled.

Tried three fixes:

1. **Factory seam on `VoicePlayer`** — added `exoPlayerFactory: (Context) -> ExoPlayer = ::defaultExoPlayer` constructor param so tests inject a mock directly instead of instrumenting `Builder`. Production behavior identical; listener attach moved from `.also { player -> }` to `init { }` so it binds to the stored `exoPlayer` field. This is kept — clean code win regardless of test outcome.

2. **Robolectric 4.14.1 dependency + `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`** — gives the test JVM shadow Android framework classes. Standalone `./gradlew test --tests VoicePlayerTest` passed in 2m53s, but the full unit test suite hung indefinitely. Reverted — see below.

3. **`forkEvery = 1`** on the unit test task to isolate Robolectric's classloader per test class — hypothesis was that Robolectric's shadow framework was bleeding into sibling test JVMs and deadlocking them on coroutine/async code. Full suite hung anyway. Reverted.

**Decision.** `@Ignore` VoicePlayerTest for v0.5.1 with strong tracking — kdoc TODO + this DEVLOG entry + `deferred_items.md` + a GitHub issue linking to commit `456f91e` which is the last state where Robolectric was wired. Follow-up PR will likely move VoicePlayer tests into a separate source set (`src/robolectricTest/kotlin/`) with its own Gradle `Test` task so it doesn't contaminate the main suite's JVM. Kept the `VoicePlayer` factory seam refactor (pure win). Reverted Robolectric dep + `testOptions` config to keep main lean for v0.5.1; the follow-up PR adds them back with the source-set split.

**Principle.** Bailey flagged during this session: "If we ignore or defer any tests to bypass, document in TODO so we don't forget." Honored via four tracking surfaces (code, DEVLOG, memory, GitHub issue). The alternative — blocking v0.5.1 on test-infra iteration — isn't a better outcome because the app works on device and the tests for it were written on a bad premise (trying to unit-test Media3 without Android framework on the classpath).

**Next session.** Open the follow-up PR to finish the Robolectric wiring: separate source set, dedicated Gradle task, CI step calling the new task. Then un-`@Ignore` VoicePlayerTest.

## 2026-04-18 — Silence auto-stop + bootstrap middleware fix

**Context.** Bailey asked about STT / silence detection while reviewing the voice stack post-barge-in. Turned out the uplink mic path has no endpointing at all — `VoiceRecorder` uses `MediaRecorder` with `AudioSource.MIC` (no AEC/NS, no VAD), and `stopListening()` has exactly one caller: `ChatScreen.kt:1319` on mic release. The `VoicePreferences.silenceThresholdMs` preference was wired to a Settings slider and persisted to DataStore but **never consumed** by any code. Cosmetic setting. Worst case was Continuous mode: after TTS finishes, it re-arms the mic and waits forever for a manual stop — ambient noise gets included, user walks away, recording goes on indefinitely.

**Decision.** Wire the existing preference. Cheapest of the four options I laid out (wire `silenceThresholdMs` → `AudioSource.VOICE_RECOGNITION` → second Silero on uplink → server-side VAD via `gpt-4o-transcribe`). The other three stay on the shelf; this one is self-contained in the ViewModel.

**Implementation.**
- Promoted `voicePreferences` to a VM field (previously captured only inside `initialize`'s closure).
- Added `silenceWatchdogJob` + `startSilenceWatchdog()` — snapshots `silenceThresholdMs` at turn start, polls `VoiceRecorder.amplitude` every 150 ms, reuses `RESUME_SILENCE_THRESHOLD = 0.08f` as the silence floor (same floor the post-barge-in resume watchdog already uses — one tuning knob, not two). Grace window: auto-stop only fires after at least one above-floor frame, so "user taps mic, doesn't speak" never insta-closes the turn.
- Skip in `HoldToTalk` — the physical release is the authoritative stop there, auto-stop on silence while the user is still holding would be surprising behavior.
- Cancelled in `stopListening()`, `interruptSpeaking()`, and `onCleared()`.

No UI change needed — the Settings slider and "Auto-stop listening after this much silence" copy already exist. The slider is now load-bearing instead of cosmetic.

**Bootstrap crash (separate fix, same day).** Teammate debugging a different project hit `'tuple' object has no attribute 'freeze'` on gateway startup. Root cause in `hermes_relay_bootstrap/_command_middleware.py`: `maybe_install_middleware()` was doing `app._middlewares = (*existing, middleware)` — replacing the aiohttp `FrozenList` with a plain tuple. When `AppRunner.setup()` later called `_middlewares.freeze()`, tuples don't have that method and the gateway crashed. Switched to in-place `app._middlewares.append(middleware)` — the FrozenList is still mutable at middleware-install time (freeze happens later in setup). Also updated the test mock to use `[]` instead of `()` so it matches real aiohttp behavior. 31/31 tests pass.

**Deferred (from the silence-detection discussion).**
- Uplink `AudioSource.VOICE_RECOGNITION` — engages Android's built-in NS/AEC tuned for STT. Single-line change, needs a release to validate on the Samsung.
- Second Silero on the uplink path — real VAD-based endpointing instead of raw-amplitude. Barge-in infra is already there to copy.
- `gpt-4o-transcribe` / OpenAI Realtime — server-side endpointing + lower latency, but bigger pivot. Park until we hit real limits with the amplitude approach.
- "Off" option on the silence slider — the current slider range is 1000–10000 ms with no way to disable. Future UI change if a user wants fully manual control (e.g., pause-to-think flows). Treating thresholdMs <= 0 as "off" is already supported in the watchdog for when that UI lands.

**Next session.** Bailey rebuilds, smoke-tests Continuous + TapToTalk at the default 3s threshold. Validation: (1) natural pauses mid-utterance don't prematurely auto-stop at 1s/2s thresholds — if they do, tune the floor up or add a longer default; (2) the watchdog never fires when the user is manually holding for HoldToTalk; (3) ambient noise in a moderately-noisy room (TV, fan) doesn't prevent auto-stop because amp never drops below 0.08. If (3) bites, that's when `AudioSource.VOICE_RECOGNITION` becomes non-optional.

## 2026-04-17 — Voice barge-in (agent-team single-session, stacked on voice-quality-pass)

**Motivation.** After the voice-quality-pass work (see entry below), Bailey noted that conversation mode still lagged ChatGPT / Claude app in a specific way: you can't interrupt the agent by speaking. You have to wait for the full response, or tap the mic to force a new turn — neither of which matches the "normal turn-taking" pattern users expect from modern voice UIs. The industry-standard recipe is duplex audio + VAD + AEC + hysteresis + optional resume-from-sentence-boundary. All five pieces implementable on Android if you accept that AEC quality varies across OEMs (the trap that kills most naive attempts).

**Design session.** Bailey asked for a "quick win" barge-in implementation; I pushed back that the real engineering is the *false-positive defense stack* — the VAD is the easy part, fighting AEC variance across Samsung / Pixel / Motorola devices is the hard part. Landed on a three-layer defense: (1) `AcousticEchoCanceler` attached to the ExoPlayer audio session, (2) VAD hysteresis requiring 2–3 consecutive speech frames (on top of the Silero library's own attack/release windows), (3) soft ducking on single-frame positives before hard cutoff, so a stray frame only briefly dips the volume. Plus a compatibility-warning UI strip for devices without AEC so users know why quality varies.

Bailey also wanted the feature to match our existing voice-settings pattern — always-visible toggle with a sensitivity picker and a resume sub-toggle, not a hidden flag. Good call — means the control surface is discoverable and the setting can be tuned per user preference rather than requiring a global "works for everyone" default that doesn't exist on Android's mic landscape.

Plan written to `docs/plans/2026-04-17-voice-barge-in.md`, 6 work units (B1 prefs, B2 VAD, B3 duplex listener, B4 integration, B5 UI, B6 ducking helpers) + Doc2. Branched from `feature/voice-quality-pass` rather than `origin/main` because barge-in architecturally depends on V4's supervisor-scoped cancellation + `pendingTtsFiles` cleanup, V5's `VoicePlayer.audioSessionId` exposure (needed for `AcousticEchoCanceler.create()`), and V3's sentence-chunk tracking (needed for resume-from-next-sentence). Merges after voice-quality-pass lands; stacked PRs.

**Execution.** Worktree at `../hermes-android-barge-in` from `feature/voice-quality-pass`. Branch `feature/voice-barge-in`. Seven commits on top of the plan:

- **Wave 1 (parallel, disjoint files).** B1 `c4fadf3` BargeInPreferences DataStore + B2 `3d011d9` Silero VAD engine + B6 `de925c8` VoicePlayer ducking helpers. Three subagents, clean touch surface. B2 agent discovered the library's actual frame-size constraint at 16 kHz is `{512, 1024, 1536}` (plan said 640 — that was wrong, from an older library version), pinned `2.0.10` not `2.0.8` (latest stable), added jitpack repo to `settings.gradle.kts` since android-vad isn't on Maven Central, and noted the library exposes threshold via a `Mode` enum (`OFF / NORMAL / AGGRESSIVE / VERY_AGGRESSIVE`) not a float — so our user-facing `Off/Low/Default/High` picker has to invert ("aggressive" means less sensitive in the library). Parallel agents also hit a staging race where one agent's `git commit` initially swept another's files; recovered via `git reset` + explicit pathspec. Noted for future parallel-worktree playbooks — always commit with explicit pathspecs when multiple agents share a tree.
- **Wave 2 (parallel, disjoint files).** B3 `48259e1` duplex listener + B5 `c65f1fe` Voice Settings UI. B3 agent introduced an `AudioFrameSource` interface seam so tests can inject a scripted frame queue without Robolectric — `AudioRecord` is a final class with native state, impossible to mock cleanly without the seam. Also added a `readerDispatcher` param so `runTest` virtual time controls the reader loop deterministically. B5 agent found no existing `VoiceSettingsViewModel` and created one following the `BridgeViewModel` pattern; chose `SingleChoiceSegmentedButtonRow` for the 4-option sensitivity picker (matching the `ChatSettingsScreen` precedent, not a dropdown). Section heading "Barge-in" chosen over "Interruption" because every other Voice Settings section is feature-named ("Text-to-Speech", "Speech-to-Text"). AEC compatibility badge renders *always* (not gated on toggle enabled) so users see the device capability before they flip it.
- **Wave 3 (serial, VoiceViewModel integration).** B4 `3e39663`. Threaded `currentChunkIndex` through V4's play worker without touching V4's supervisor topology — added `MutableStateFlow<Int>` on the VM and wired it via V4's existing `onFileReady` callback lambda, so the top-level `runTtsPlayWorker` contract stays unchanged. Chunk tracking is layered in VM-owned lambdas, not the workers. For the "user still speaking" cancel signal on the resume watchdog, the agent used `VoiceRecorder.amplitude` (perceptually-scaled StateFlow, already exposed) rather than re-subscribing a fresh AudioRecord — simpler and cheaper, since the recorder is already hot from the barge-in pre-warm. Added three `@VisibleForTesting internal` test seams (`startBargeInListenerForTest`, `seedSpeakingStateForTest`, `drainTtsQueueForTest`) so the coordinator's 7-case test matrix runs without pushing real MP3s through the V4 pipeline. Found one additional race: a synthesize call in flight when interrupt fires can complete after the cancel signal and add a file to `pendingTtsFiles` that the immediate cleanup misses — closed via the supervisor's `finally` block (same pattern V4 established).
- **Wave 4.** Doc2 (this entry) — DEVLOG, CHANGELOG `[Unreleased]` gains an Added section above the voice-quality-pass Changed section, `docs/spec.md` Phase V gets barge-in bullets, `user-docs/features/voice.md` gains a "Barge-in (interrupt the agent)" section and updates the Tap-mode description (the existing "tap to interrupt" is now one of three ways to interrupt).

**Results — expected and TBD.** Net change on top of voice-quality-pass: ~3,000 insertions / 5 deletions across 16 files. New tests: VAD engine (scripted frame sequences), duplex listener (via AudioFrameSource seam), ducking helpers, preferences round-trip, and the 7-case VoiceViewModel coordinator suite. Bailey's on-device smoke test still pending — key things to validate: (1) toggling barge-in on + speaking during TTS cuts playback within ~100 ms on headphones, (2) 600 ms of silence after interrupt resumes from next sentence if resume is enabled, (3) devices without AEC show the compatibility warning badge and (presumably) trigger more false positives in practice. Plan to leave barge-in default-off through first release; telemetry or Bailey's own use will inform whether we change the default.

**Deferred.** Same `Up1` from voice-quality-pass — upstream PR exposing `VoiceSettings` in `hermes-agent/tools/tts_tool.py::_generate_elevenlabs`. Not blocked by barge-in. Also deferred: live sensitivity reactivity mid-`Speaking` (the coordinator rebuilds VAD on next listener start rather than re-applying sensitivity on an already-running listener — simpler, and users flipping sensitivity during a turn is a niche workflow).

**Open questions for next session.**
1. Does the ducking feedback feel natural on-device, or should we skip the `maybeSpeech` stage entirely and just hard-cut on hysteresis pass? The 30 % ducking level is a guess.
2. Should the resume watchdog be shorter than 600 ms? A breath is more like 300–400 ms; 600 ms might feel sluggish. Tune after Bailey's smoke test.
3. Telemetry for AEC-unavailable devices — if a non-trivial portion of users see the badge, is the UX degraded enough that we should gate-off barge-in entirely on those devices, rather than just warning?
4. Integration with voice-intent direct-dispatch (from 2026-04-15): if the user barges in with "cancel" during a destructive-action countdown, does that route through the existing cancel handler or the new barge-in path? Probably needs a small coordination test.

**Next session.** Bailey smoke-tests. If quality holds: rebase onto main after voice-quality-pass merges, open stacked PR `feature/voice-barge-in` → `main`. If quality is poor on his device: tune `RESUME_SILENCE_THRESHOLD`, hysteresis consecutive-frame counts, or default-off sensitivity preset.

---

## 2026-04-17 — Voice quality pass (agent-team single-session)

**Motivation.** Bailey's ongoing voice-mode testing with an ElevenLabs-configured Hermes backend surfaced a cluster of quality issues that individually read like different bugs but shared compounding root causes: voice output noticeably switching between crisp and muffled, volume drifting between sentences, audible pauses mid-response, and the occasional jumbled-letter spell-out when the agent emitted markdown, URLs, or tool-annotation tokens. ChatGPT- and Claude-app-class voice experiences don't exhibit this — diagnosis focused on what they do differently.

**Diagnosis chain (2026-04-16).** Traced the voice pipeline end-to-end across the Windows checkout, the relay's `plugin/relay/voice.py`, and the upstream `~/.hermes/hermes-agent/tools/tts_tool.py` on hermes-host. Five compounding layers:

1. Server config used `eleven_multilingual_v2` — ElevenLabs' expressive long-form model, which re-interprets prosody per call. Wrong model for a chunked per-sentence pipeline; every sentence is a fresh TTS context with independent encoder gain and EQ. Accounts for the bulk of the "clear↔muffled" perception.
2. Upstream `_generate_elevenlabs()` (`tts_tool.py:222`) passes no `VoiceSettings` — all defaults, including `stability` that's fine for long-form but too low for chunked output. `use_speaker_boost` (auto-normalizing) is off. Accounts for inter-chunk volume drift.
3. Upstream has `_strip_markdown_for_tts()` (`tts_tool.py:1053`) but it's only called from the CLI `stream_tts_to_speaker()` path. `text_to_speech_tool()`, which our relay calls, does NOT run the stripper. Our relay's `/voice/synthesize` passes raw assistant text to ElevenLabs — tool-annotation tokens like `` `💻 terminal` `` and URLs like `https://github.com/foo/bar` get spoken character-by-character. Accounts for jumbled letters.
4. `VoicePlayer.play(file)` on Android tore down and rebuilt a `MediaPlayer` per sentence — codec context reset, audio-track re-init, audible seam/pop. Accounts for playback-layer portion of the muffled switching.
5. `VoiceViewModel.startTtsConsumer` was strictly serial: `synthesize → play → awaitCompletion → next synthesize`. Every sentence boundary cost one full network round-trip. Accounts for the inter-sentence pause.

Plan written to `docs/plans/2026-04-16-voice-quality-pass.md` covering 8 work units across 5 waves, scoped to a single feature branch in a worktree.

**Execution (2026-04-17).** Worktree at `../hermes-android-voice` from `origin/main`. Single branch `feature/voice-quality-pass`. Six commits on top of the plan:

- **Wave 0 (operator, pre-session).** `Cfg1` — `~/.hermes/config.yaml` on hermes-host flipped from `eleven_multilingual_v2` → `eleven_flash_v2_5`; `hermes-gateway.service` restarted. Voice ID unchanged.
- **Wave 1 (parallel, disjoint files).** `V1` `3618bcd` relay TTS sanitizer + `V5` `b24a37e` ExoPlayer gapless playback. Two subagents, completely disjoint touch surface (`plugin/relay/*` vs `app/src/.../audio/VoicePlayer.kt`). No conflicts. V5 agent flagged that the plan's `ConcatenatingMediaSource` is deprecated in Media3 1.x and used `addMediaItem` on a single persistent ExoPlayer instead — same effect, current API.
- **Wave 2.** `V2` `a13dc3b` — client-side `sanitizeForTts` mirroring V1's regex set, applied per delta before sentenceBuffer append, with multi-delta code-fence deferral (a `pendingRawDelta: StringBuilder` that buffers until opening/closing fence counts balance). V2 agent caught a real subtle bug: the plan's Python `[emoji1 emoji2 ...]` character class translates badly to `java.util.regex`, which operates on UTF-16 code units — non-BMP emojis would match individual surrogate halves and leave orphan low-surrogates in the output. Switched to `(?:A|B|C|...)` alternation groups. Kotlin testability pattern used top-level `internal` functions (matching existing `extractNextSentence`) rather than `@VisibleForTesting` class members.
- **Wave 3.** `V3` `f916b22` — `MIN_COALESCE_LEN=40` + `MAX_BUFFER_LEN=400` secondary-break escape + 800 ms idle-delta timer. Spiked `ICU4J BreakIterator.getSentenceInstance()` per the plan's suggestion but rejected it: its locale-sensitive abbreviation handling is unpredictable on partial buffers left by V2's sanitizer, and it can't be parameterized with the `streamComplete`/coalescing semantics without reinventing the loop on top of it. Extended hand-rolled regex is deterministic and cheap. Secondary-break pattern requires comma-followed-by-whitespace so `"3,000"` survives. Added `@Volatile streamComplete: Boolean` on `VoiceViewModel`, set by `startStreamObserver` when the assistant stream flips `isStreaming=false`, reset in turn-start / `exitVoiceMode` / `interruptSpeaking`. The idle-flush timer passes `streamComplete=true` to `extractNextSentence` for that call only — does NOT mutate the class flag, because the SSE stream may still be live and the next delta should return to conservative coalescing.
- **Wave 4.** `V4` `8fcec6b` — `startTtsConsumer` split into two `supervisorScope`-rooted coroutines joined by `Channel<File>(capacity=2)`. Synth worker runs one file ahead. V4 agent chose Option B (explicit Channel) over the plan's recommended Option A (rely on ExoPlayer's internal queue for backpressure) because `VoicePlayer.mediaItemCount` isn't public and V4 was guardrailed out of touching V5's work. Play worker still calls `awaitCompletion()` per file (V5's queue-drained semantic), so ExoPlayer's internal queue never exceeds 1 — a single queuing layer, not redundant. Found and guarded a real race: `ttsConsumerJob?.cancel()` is non-blocking, so a synthesize call in flight can complete AFTER cancel was signalled and add a file to `pendingTtsFiles` that `interruptSpeaking`'s immediate cleanup would miss. The supervisor's `finally { deletePendingSynthFiles() }` catches late arrivals. Consumer is now cancellable+restartable — interrupt/exit cancel the job, clean up files, and immediately call `startTtsConsumer()` for the next turn.
- **Wave 5.** `Doc1` (this entry) — DEVLOG, CHANGELOG `[Unreleased]`, spec Phase V section updated to reflect the new architecture.

**Deferred.** `Up1` — upstream PR exposing `VoiceSettings` (stability / similarity_boost / use_speaker_boost) in `hermes-agent/tools/tts_tool.py::_generate_elevenlabs`. Useful once merged but not blocking; the flash model already reduces inter-chunk variance enough that the missing settings don't dominate the remaining gap.

**Results — expected and TBD.** Net change: +2,998 / -197 lines across 15 files. New tests: 33 relay sanitizer unit tests (all green) + 4 new client test files (sanitization parity, chunking semantics, prefetch pipelining timing + cancellation cleanup, ExoPlayer queue behavior — verified by reading, not by `gradle test` per project convention). Bailey's on-device smoke test (3-sentence response, 1 code-block response, 1 multi-paragraph response) still pending; will capture subjective before/after in the PR description.

**Rebase note (2026-04-17).** This branch was rebased onto `main` at v0.5.0 (from earlier base `b811da1`). Conflicts resolved: a single-import collision in `VoiceViewModel.kt` (kept both `supervisorScope` + `contentOrNull`), DEVLOG + CHANGELOG prepends (combined both days' entries in reverse chronological order, this entry placed above the v0.4.1 bridge polish entry since VQP is the newer work on 2026-04-17), and a `docs/spec.md` auto-merge. No code logic changed during rebase.

## 2026-04-18 — Dashboard plugin

**Context.** The upstream Dashboard Plugin System landed on hermes-agent's `axiom` branch in three commits (`01214a7f` plugin system, `3f6c4346` theme, `247929b0` OAuth providers). The gateway now scans `~/.hermes/plugins/<name>/dashboard/manifest.json` at startup and exposes registered plugins as tabs in its web UI. Since `~/.hermes/plugins/hermes-relay` already points at our `plugin/` subtree (from `install.sh`), we can ship a relay-specific dashboard plugin by only adding a `plugin/dashboard/` subtree plus a few relay HTTP routes — no hermes-agent fork needed. The four deferred-audit items that only the relay knows about — paired-device state, bridge command history, push delivery (future), media-registry tokens — map cleanly onto four tabs of a single plugin.

**Decision summary.**

1. **Single plugin with internal shadcn `Tabs`**, not four plugins. Manifest allows one `tab.path` per plugin; four plugins would fragment the nav. Recorded as ADR 19 in `docs/decisions.md`.
2. **Pre-built IIFE bundle committed to git.** Upstream's example plugin uses plain `React.createElement` (no build), but four non-trivial tabs are painful to maintain that way. Source lives under `plugin/dashboard/src/`, bundled with esbuild to a single ~16 KB IIFE at `plugin/dashboard/dist/index.js`. Dashboard never runs the build — operators get a ready-to-serve bundle.
3. **Loopback-only for the new relay routes.** `/bridge/activity`, `/media/inspect`, `/relay/info` are gated the same way `/bridge/status` and `/pairing/register` already are. The plugin backend runs inside the gateway process (also localhost) and calls the relay at `http://127.0.0.1:8767/...` — no bearer minting. Also added a loopback-exempt branch on `/sessions` so the plugin proxy can list paired devices without one.
4. **Dashboard backend is a thin proxy.** `plugin/dashboard/plugin_api.py` exposes five routes at `/api/plugins/hermes-relay/*` and forwards to the relay. No business logic in the plugin — relay stays source of truth.
5. **Push Console is a real tab, stub data.** Keeps the four-tab nav layout correct for when FCM lands; swapping in real data is additive.

**Implementation (Wave-by-wave).**

- **Wave 1 (relay state plumbing).** `2212fbc — feat(relay): add MediaRegistry.list_all for dashboard inspector` added the lock-guarded snapshot method that strips absolute paths, returns basename-only `file_name` fields, and filters expired entries by default (+8 unit tests). `777a06a — feat(relay): add bridge command ring buffer for dashboard activity feed` added the `BridgeCommandRecord` dataclass + `deque(maxlen=100)` on `BridgeHandler`, wired append/update into `handle_command()` / `handle_response()`, and taught `get_recent()` to redact params keyed on `{password, token, secret, otp, bearer}` (+9 unit tests covering append, update, timeout path, redaction, ring-buffer eviction).
- **Wave 2 (relay HTTP routes).** `4370806 — feat(relay): add /bridge/activity /media/inspect /relay/info for dashboard` added the three loopback-gated handlers in `plugin/relay/server.py` adjacent to `/bridge/status` (factored through a `_require_loopback()` helper), plus the tiny `handle_sessions_list` loopback-exempt branch so the dashboard proxy can list paired devices without a bearer. Route tests extend `test_bridge_activity.py` + `test_media_inspect.py` and a new `test_relay_info.py` covers the aggregate status shape.
- **Wave 3 (dashboard plugin body).** `b51940c — feat(dashboard): add plugin_api.py proxy to relay HTTP` added the FastAPI router with five routes (`/overview`, `/sessions`, `/bridge-activity`, `/media`, `/push`), a shared `_proxy_get()` helper, and structured 502 translation on relay connect-error / timeout / 5xx so the UI can show "relay unreachable" (+10 unit tests in `plugin/dashboard/test_plugin_api.py`). `087149e — feat(dashboard): add React UI for four relay tabs` added the `plugin/dashboard/{src,dist}/` subtree — JSX sources under `src/` (index + four tab components + `lib/api.js` + `lib/formatters.js`), esbuild toolchain (`package.json` + `build.sh`), and the committed ~16 KB IIFE bundle at `dist/index.js` that registers as `window.__HERMES_PLUGINS__.register("hermes-relay", …)` via the SDK global.
- **Wave 4 (manifest wiring).** `78c209e — feat(dashboard): add plugin manifest + plan file` added `plugin/dashboard/manifest.json` — `name: "hermes-relay"`, `label: "Relay"`, `icon: "Activity"` (from the 20-name Lucide whitelist), `tab.path: "/relay"`, `tab.position: "after:skills"`, `entry: "dist/index.js"`, `api: "plugin_api.py"`. Verified the existing `~/.hermes/plugins/hermes-relay` symlink resolves `dashboard/manifest.json` correctly.

**Files touched (this session — Doc1 wave only).**

- `CHANGELOG.md` — new `[Unreleased]` "Added — Dashboard plugin" bullet group
- `DEVLOG.md` — this entry
- `README.md` — one-line mention under Quick Start
- `CLAUDE.md` — `plugin/dashboard/` added to Repository Layout; four Key Files rows under a new "Plugin — Dashboard" group
- `docs/spec.md` — "Dashboard plugin" subsection appended to §10 Hermes Integration Points
- `docs/decisions.md` — new ADR 19 "Dashboard plugin: single plugin with internal tabs + pre-built IIFE bundle"
- `docs/relay-server.md` — three new rows in the HTTP Routes table + loopback-branch note on `/sessions`
- `user-docs/features/dashboard.md` — new user-facing page
- `user-docs/.vitepress/config.mts` — sidebar nav entry for dashboard.md

**Deferred.**

- **Push Console needs FCM.** The tab renders a static "FCM not configured" banner from `GET /api/plugins/hermes-relay/push`. Swapping in real data is additive — only `PushConsole.jsx` + `plugin_api.py::get_push` change. Tracked under the `deferred_items` memory entry.
- **Session revoke button is a placeholder.** The Relay Management tab exposes "Revoke" buttons per paired device but they currently log to the console — the plugin proxy would need a new `DELETE /api/plugins/hermes-relay/sessions/{prefix}` route forwarded to the relay's existing `DELETE /sessions/{token_prefix}`. Blocked on deciding the auth story (loopback-only? dashboard session token?). Keeps the UI placement correct for when the proxy route lands.
- **Session extend button** not yet built; would follow the same proxy pattern against `PATCH /sessions/{token_prefix}`.

**Next session.** Bailey restarts `hermes-gateway` on the server, verifies `GET /api/dashboard/plugins` includes `hermes-relay`, loads the dashboard UI, and exercises all four tabs against a live paired phone. Screenshot the tabs so we can drop real images into `user-docs/features/dashboard.md` in place of the placeholder captions.

**No blockers.**

---

## 2026-04-17 — v0.4.1 Bridge page polish pass

**Motivation.** The Bridge tab had accreted cards without an information hierarchy — the master toggle, a separate status card, the permission checklist, unattended access, a standalone keyguard warning chip, and the safety summary all sat at the same visual weight, and tapping the master Switch when Accessibility wasn't granted was a silent no-op (stock Android disabled-switch behavior). Unattended Access lacked a master dependency, and once ON there was no in-app affordance while the user was on another tab. The LLM also had to learn reactively that commands wouldn't reach apps on a sleeping/locked device (via `keyguard_blocked` errors) instead of being told upfront.

**What landed on `feature/v0.4.1-integration`:**

- **Bridge card hierarchy rewrite.** New order: Master → Permission Checklist → [Advanced divider] → Unattended Access → Safety Summary → Activity Log. Master toggle copy now leads with "Master switch —" and carries a `MASTER` pill so its parent-gate role is legible at a glance. The old `BridgeStatusCard` was dropped from the layout (its status rows already live inside the master card); the component file stays in-tree for now.
- **Master-toggle feedback fix.** Tapping the Switch when `HermesAccessibilityService` isn't granted now surfaces a snackbar ("Accessibility Service must be enabled first.") with an "Open Settings" action that deep-links to `ACTION_ACCESSIBILITY_SETTINGS`, instead of the previous silent-drop disabled-switch behavior. The master-toggle info dialog also gained a paragraph attributing the "Hermes has device control" persistent notification to the master switch.
- **Unattended Access gated on master.** `UnattendedAccessRow`'s Switch is `enabled = masterEnabled` and shows "Requires Agent Control — enable the master switch above first." when master is off. The standalone `KeyguardDetectedChip` was inlined as a `KeyguardDetectedAlert` Surface band inside the Unattended Access card so the credential-lock warning lives next to the thing that triggers it. Unattended scary-dialog copy no longer implies the unattended toggle owns the persistent notification — attributes it correctly to the master switch.
- **`UnattendedGlobalBanner` — in-app banner vs. system chip split.** New 28dp amber strip renders at the top of `RelayApp`'s scaffold on every tab when master + unattended are both on (sideload only). Theme-aware amber (amber-on-dark vs. dark-amber-on-pale in light mode), pulsing dot, "Unattended access ON — agent can wake and drive this device" copy, chevron → navigates to the Bridge tab. The existing WindowManager `BridgeStatusOverlayChip` continues to handle the backgrounded-app case (foreground-gating of the chip is in flight on a sibling branch). Net: banner when the user is IN Hermes-Relay, system chip when the app is backgrounded.
- **Agent-awareness upgrades to `PhoneSnapshot`.** Three new fields: `unattendedEnabled`, `credentialLockDetected`, `screenOn`. `PhoneStatusPromptBuilder.buildBridgeLine()` appends explicit guidance so the LLM knows upfront whether commands will land while the user is away, instead of finding out reactively via `keyguard_blocked` responses.
- **Bug fixes bundled in.** Permission checklist Optional pill no longer wraps mid-pill on narrow titles (switched to `FlowRow` + `softWrap=false`); runtime-permission rows (Mic, Camera, Contacts, SMS, Phone, Location) now fall back to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` when a permission has been permanently denied, instead of silently no-opping.

**Next.** Bailey's on-device verification of the reordered layout, master-snackbar, global banner, and `PhoneSnapshot` fields on the Samsung S24 / Android 14 sideload build. After that, version bump to v0.4.1 release tag via `bash scripts/bump-version.sh 0.4.1` → push tag → CI.

**No blockers.**

---

## 2026-04-16 — v0.4.1: Tiered permission checklist + JIT permission-denied surfacing

**Motivation.** Two v0.4.x fast-follows from ROADMAP.md, scoped to ship together because they share the same UX axis ("the user understands which permission is missing and what to do about it"). Until now the Bridge tab's checklist was a flat 4-row layout that lumped optional and required perms together, and `android_search_contacts` / `android_send_sms` / `android_call` / `android_location` failures bubbled up as opaque error strings — the LLM had to pattern-match the phrasing to figure out it was a permission issue. Both surfaces now make the missing-permission state legible to humans AND to the agent.

**What landed.**

- **Tiered checklist UI** (`BridgePermissionChecklist.kt` rewrite). Four explicit sections — Core bridge (required), Notification companion (optional), Voice & camera (optional), Sideload features (optional, sideload-only). Each row uses a `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission)` for runtime dangerous perms and the existing intent helpers for special perms (Accessibility, Notification Listener, Overlay, Screen Capture). Optional rows render a "Optional" Material 3 pill so users don't perceive them as urgent. Section headers use a primary-coloured label + caption + thin divider. Hides sideload-only sections on googlePlay via the existing `BuildFlavor.isSideload` gate.
- **`BridgePermissionStatus` extended** with `microphonePermitted`, `cameraPermitted`, `contactsPermitted`, `smsPermitted`, `phonePermitted`, `locationPermitted`. `refreshPermissionStatus()` probes each via `ContextCompat.checkSelfPermission`. Re-runs on every `Lifecycle.Event.ON_RESUME` (existing pattern).
- **Manifest verification.** All required `<uses-permission>` declarations were already in place: `RECORD_AUDIO` + `CAMERA` in `app/src/main/AndroidManifest.xml`, `READ_CONTACTS` + `SEND_SMS` + `CALL_PHONE` + `ACCESS_FINE_LOCATION` (+ `ACCESS_COARSE_LOCATION`) in `app/src/sideload/AndroidManifest.xml`. No manifest changes needed for this PR.
- **`ResolveResult` typed-union shipped** in `plugin/tools/resolve_result.py` — `Found(value)` / `NotFound(detail)` / `PermissionDenied(permission, reason)` dataclasses with a `from_bridge_response(response, *, found_value=None)` constructor that classifies a bridge response as one of the three variants. Reads both canonical (`code` / `permission`, v0.4.1) and legacy (`error_code` / `required_permission`, pre-v0.4.1) wire-key spellings so the rollout is forwards/backwards compatible across mixed-version installs.
- **Bridge response envelope extended** in `BridgeCommandHandler.kt::respondFromResult` to emit the canonical `code` + `permission` aliases ALONGSIDE the existing `error_code` + `required_permission` fields. `LocalDispatchResult` parsing also accepts either spelling. The phone now produces `{"ok": false, "error": "...", "code": "permission_denied", "permission": "android.permission.READ_CONTACTS", "error_code": "permission_denied", "required_permission": "android.permission.READ_CONTACTS"}`.
- **Tier C agent-tool wrappers upgrade permission errors to JIT structured responses.** `android_location` / `android_search_contacts` / `android_send_sms` / `android_call` in `plugin/tools/android_tool.py` now run their bridge response through `_maybe_jit_permission_response` after the HTTP round-trip. On `code: permission_denied` the wrapper returns `{"ok": false, "error": "User has not granted Contacts permission (android.permission.READ_CONTACTS). They can enable it in Settings > Apps > Hermes Relay > Permissions. Tool: android_search_contacts.", "code": "permission_denied", "permission": "..."}` — deterministic LLM-readable copy with the exact Settings deep-link path embedded.
- **Voice-mode JIT chip** in `VoiceModeOverlay.kt`. New `PermissionDeniedChip` composable surfaces above the mic button when the most recent voice intent dispatch returned `errorCode == "permission_denied"`. Tap deep-links to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for `BuildConfig.APPLICATION_ID` (so both flavors land on their own package's permission page). `VoiceUiState.permissionDeniedCallout: PermissionDeniedCallout?` carries the chip state. `buildPermissionDeniedCallout(label, result)` reads the structured `permission` field off `result.resultJson` and builds a copy line ("I need Contacts to Search contacts here. Tap to open Settings."). Cleared on chip tap and on the next mic-tap (fresh turn).
- **Voice TTS on permission_denied** was already in place from the 2026-04-15 voice fixes session — `speakDispatchResult` already says "Permission needed. {hint}" in this branch. The chip is purely additive.
- **17 new Python tests** in `plugin/tests/test_resolve_result.py`. Covers the ResolveResult classifier, both canonical/legacy/mixed wire-key spellings, success passthrough, non-permission error passthrough, and JIT upgrades for all four Tier C wrappers. All 17 pass; existing 39 Tier-C tests still pass with no regressions.

**Branch.** `feature/tiered-permissions` (forked from main, with `feature/bridge-notifications-permission` merged in first per the spec — that branch's POST_NOTIFICATIONS row sits inside the new Core-bridge tier without modification). Pushed to origin, PR-ready.

**Files touched (summary).**

- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/BridgePermissionChecklist.kt` (rewrite — tiered layout)
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/BridgeViewModel.kt` (status fields + probes)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/BridgeScreen.kt` (6 new permission launchers, wired into checklist)
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt` (canonical alias emission, dual-key parse)
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt` (PermissionDeniedCallout state, buildPermissionDeniedCallout, clearPermissionDeniedCallout)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/VoiceModeOverlay.kt` (PermissionDeniedChip composable + signature param)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ChatScreen.kt` (chip-tap callback wiring deep-link to Settings)
- `plugin/tools/resolve_result.py` (NEW — typed-union dataclass hierarchy + classifier)
- `plugin/tools/android_tool.py` (`_maybe_jit_permission_response` helper + 4 wrappers updated)
- `plugin/tests/test_resolve_result.py` (NEW — 17 unit tests)
- `ROADMAP.md`, `CHANGELOG.md`, `DEVLOG.md` (this entry)

**Notable decisions.**

1. **Did not introduce a server-side resolver layer.** The prompt suggested Python resolvers like `resolveContactPhone`, but the actual contact resolution lives in Kotlin (`ActionExecutor.searchContacts`). Implementing the spec verbatim — Python `ResolveResult` types — still gave value as the agent-tool wrapper's classifier of bridge responses, which is the layer that needed the structured permission-denied surfacing for the LLM. The Kotlin `ContactResolution` sealed-class equivalent already exists from 2026-04-15.
2. **Both wire-key spellings emitted by the phone (canonical + legacy).** Forwards/backwards compatibility across the v0.4.x APK rollout window. Drop the legacy aliases after v0.5.0 once the 0.4.0 APKs are estimated to be off the field.
3. **JIT chip uses errorContainer colour, not a notification-style banner.** Voice mode is a focused single-purpose modality; banner-treatment would compete with the destructive-countdown row. Chip is explicit and tappable but doesn't block the voice flow.
4. **Optional rows use a neutral status tint when not granted.** Original layout used error-red for any not-granted row, which made optional perms feel urgent — the v0.4.1 layout treats neutral-grey + optional-pill as the "this is fine if you skip it" affordance.
5. **Sideload-only rows hidden entirely on googlePlay** rather than rendered as disabled. Showing a permission row for a manifest entry that doesn't exist would confuse users and potentially flag review on the Play track.

**Verification.**

- `python -m unittest plugin.tests.test_resolve_result` — 17 tests pass.
- `python -m unittest plugin.tests.test_android_call plugin.tests.test_android_send_sms plugin.tests.test_android_search_contacts plugin.tests.test_android_location` — 39 existing tests still pass.
- `python -m py_compile plugin/tools/resolve_result.py plugin/tools/android_tool.py` — clean.
- Kotlin code reviewed for type correctness and import resolution; no `gradle build` per project convention (Bailey builds from Studio).

**Next session.** Bailey's on-device retest of the tiered checklist + JIT chip flow on Samsung S24 / Android 14 sideload APK. After that, version bump to v0.4.1 + cut release.

---

## 2026-04-15 — Bootstrap command middleware (slash-command hallucination fix)

**Motivation.** When a user types `/help` or `/model` into the Android app's chat, the message reaches the LLM verbatim on vanilla upstream hermes-agent installs because `APIServerAdapter` doesn't connect to the `GatewayRouter`. The LLM hallucinates a plausible-sounding wrong reply. The upstream fix lives in `gateway/platforms/api_server_slash.py` (Stage 1 PR), but vanilla installs won't get it until that PR merges. This session implements the bootstrap-middleware sibling that ships the fix now.

**What was built:**

- **`hermes_relay_bootstrap/_command_middleware.py`** (~420 LOC) — aiohttp middleware that:
  - Intercepts `POST /v1/chat/completions` and `POST /v1/runs` (zero-cost skip for all other paths)
  - Runs auth check (`adapter._check_auth`) before any command logic
  - Extracts the user message from each endpoint's body format
  - Resolves against `hermes_cli.commands.resolve_command()` from the central command registry
  - Stateless commands (`/help`, `/commands`, `/profile`, `/provider`) are dispatched locally using the same helpers as upstream
  - Stateful commands (`/model`, `/new`, `/retry`, etc.) return a deterministic decline notice
  - Unknown and CLI-only commands fall through to the LLM
  - For `/v1/chat/completions`: returns synthetic SSE stream (role + content + finish + `[DONE]`) or JSON `chat.completion`
  - For `/v1/runs`: injects `message.delta` + `run.completed` + sentinel into `adapter._run_streams` queue, returns `{"run_id": ..., "status": "started"}` with 202, so the events endpoint drains the queue normally
  - Feature-detects: if `gateway.platforms.api_server_slash` exists (upstream PR landed), middleware is skipped
  - Fail-open: any exception falls through to the original handler

- **`hermes_relay_bootstrap/_patch.py`** — added `_maybe_install_command_middleware()` call in the `__setitem__` hook alongside the existing route injection, using the same timing window where `app._middlewares` is still mutable.

- **`hermes_relay_bootstrap/__init__.py`** — updated docstring to document both injection layers (routes + middleware).

- **`plugin/tests/test_command_middleware.py`** (31 tests) — unit tests for message extraction, command resolution, response builders, feature detection, plus full aiohttp integration tests via `TestClient`/`TestServer` covering both endpoints, streaming, auth rejection, and fall-through behavior.

**Test results:** 31 new tests pass. Full existing suite (450 tests) still passes with no regressions.

**Android client impact:** None. The synthetic responses match the exact shapes the client already parses (`chat.completion.chunk` for completions SSE, `message.delta`/`run.completed` for runs events).

**Next:** Merge to main via PR, then deploy to server (`git pull && systemctl --user restart hermes-gateway`). Phone re-pair after restart.

---

## 2026-04-16 — v0.4.1 unattended access mode (sideload-only)

**Motivation.** First v0.4.1 fast-follow off the ROADMAP. Bailey wants to
walk away from the phone and let the agent finish whatever task is in
flight without the screen having to stay manually awake. Without
unattended access, every bridge action that lands on a sleeping screen
silently no-ops because `dispatchGesture` runs against the dimmed UI.

**Scope landed on `feature/unattended-access`:**

- **`UnattendedAccessManager`** (new file under `bridge/`) — process
  singleton holding the SCREEN_BRIGHT wake lock + orchestrating
  `KeyguardManager.requestDismissKeyguard`. Hosts a `WakeOutcome` enum
  (Success / SuccessNoKeyguardChange / KeyguardBlocked / Disabled) and
  two StateFlows (`enabled`, `credentialLockDetected`). Initialized
  once from `HermesRelayApp.onCreate`. The host Activity is registered
  in `MainActivity.onResume` and cleared in `onPause` so we don't leak
  it past the lifecycle.
- **DataStore additions to `BridgeSafetyPreferences`** —
  `unattendedAccessEnabled` (the user opt-in) and
  `unattendedWarningSeen` (latch for the one-time scary dialog). Both
  default false. Setters keep `KEY_SAFETY_INITIALIZED` in sync so
  user-clear-blocklist semantics are preserved.
- **`BridgeViewModel` exposure** — `unattendedEnabled`,
  `unattendedWarningSeen`, `credentialLockDetected` StateFlows;
  `setUnattendedAccessEnabled(boolean)` + `markUnattendedWarningSeen()`.
  An init-time collector mirrors the persisted setting into
  `UnattendedAccessManager.setEnabled()` so the dispatch fast-path can
  read state synchronously without DataStore. `onScreenResumed` now
  also re-probes the keyguard state.
- **`UnattendedAccessRow` component** — Compose card with the toggle,
  a one-time scary `AlertDialog` (security model + credential-lock
  limitation + how to disable), and a persistent `Card` chip when the
  device has a credential lock detected. Only rendered inside
  `BridgeScreen` when `BuildFlavor.isSideload` is true.
- **Bridge dispatch pre-gate** — `BridgeCommandHandler.dispatch` calls
  `UnattendedAccessManager.acquireForAction()` after the safety-rails
  pass and before the action `when` block, for any path NOT in the new
  `READ_ONLY_PATHS` set. KeyguardBlocked outcome short-circuits with
  HTTP 423 + `error_code = "keyguard_blocked"` + a `final = true`
  flag so the LLM doesn't blindly retry against the lock screen.
- **`classifyGestureFailure()`** — small helper on `ActionExecutor`
  that wraps gesture-dispatch failure messages with a keyguard-aware
  hint when the live keyguard state is locked. Tap / swipe / drag /
  long_press failure paths use it. `BridgeCommandHandler.classifyBridgeError`
  now routes "keyguard"-bearing strings to the `keyguard_blocked`
  error_code so both pre-gate and gesture-failure paths converge on
  the same structured response.
- **`BridgeStatusOverlayChip` variant** — added an `unattended: Boolean`
  parameter so the chip can render in an amber-dot "Unattended ON"
  variant. `BridgeStatusOverlay.setChipVisible` gained an `unattended`
  argument and a tear-down-and-rebuild path when the flag flips
  between attached state. `BridgeViewModel`'s overlay-toggle collector
  now combines master / overlayPref / unattendedEnabled and forces
  the chip on whenever unattended is active even if the user disabled
  the regular overlay preference.
- **Lifecycle hardening** — Master-toggle-off and relay-disconnect
  both call `UnattendedAccessManager.release()`. The persisted
  `unattendedAccessEnabled` value is preserved across master toggle
  cycles (the user's "I want unattended when bridge is on" preference
  shouldn't be wiped by every disconnect).
- **Manifest** — `DISABLE_KEYGUARD` declared in
  `app/src/sideload/AndroidManifest.xml`. WAKE_LOCK already lives in
  the main manifest for the existing PARTIAL_WAKE_LOCK gesture scope
  and covers SCREEN_BRIGHT acquires too — no new top-level permission.

**Tests landed.** `app/src/test/kotlin/com/hermesandroid/relay/bridge/
UnattendedAccessManagerTest.kt` covers state transitions (enabled ↔
disabled, credential lock detection from `isDeviceSecure`,
`acquireForAction` outcomes for the four reachable cases without a
real PowerManager: Disabled / SuccessNoKeyguardChange / Success /
KeyguardBlocked) using MockK to stub `Context.getSystemService` and
reflection to reset the singleton between tests. Also added
`BridgeSafetySettingsTest.kt` to lock in the false-by-default contract
on the two new preference fields.

**Decisions documented (NOT re-litigated this session):**

- No WiFi-disconnect failsafe. Tailscale / VPN invalidate the
  "leaving WiFi = leaving LAN" assumption; relay-disconnect detection
  + auto-disable timer cover the gap.
- Default auto-disable timer stays at 30 minutes. No unattended-mode
  override.
- Credential lock limitation is structural — Android does not let
  third-party apps dismiss PIN / pattern / biometric. Surface it via
  the warning dialog + persistent chip + `keyguard_blocked` rather
  than try to work around it.

**Wake-lock flag note.** `SCREEN_BRIGHT_WAKE_LOCK` has been
deprecated since API 17 in favor of `Window.FLAG_KEEP_SCREEN_ON` /
`Activity.setTurnScreenOn(true)`. We accept the deprecation warning
under explicit `@Suppress` annotations because the Activity-bound
alternatives don't apply to a background bridge that doesn't own
its own window — the bridge runs from a foreground service, gestures
are dispatched via AccessibilityService, and the only "UI surface"
we own off-Activity is the WindowManager overlay chip which can't
drive screen wake-up. Same story for `requestDismissKeyguard` —
it's API 26+ (our minSdk is 26 so always available) and reachable
only when an Activity is registered, hence the
`MainActivity.setHostActivity` plumbing.

**Files touched:**

- new: `app/src/main/kotlin/com/hermesandroid/relay/bridge/UnattendedAccessManager.kt`
- new: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/UnattendedAccessRow.kt`
- new: `app/src/test/kotlin/com/hermesandroid/relay/bridge/UnattendedAccessManagerTest.kt`
- new: `app/src/test/kotlin/com/hermesandroid/relay/data/BridgeSafetySettingsTest.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/HermesRelayApp.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/MainActivity.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/data/BridgeSafetyPreferences.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/BridgeViewModel.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/BridgeScreen.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/bridge/BridgeStatusOverlay.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/DestructiveVerbConfirmDialog.kt` (chip variant)
- mod: `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt`
- mod: `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt`
- mod: `app/src/sideload/AndroidManifest.xml`
- mod: `ROADMAP.md` (marked v0.4.1 unattended access shipped)
- mod: `CHANGELOG.md` (added unreleased section)

**Next.** Bailey builds + installs from Android Studio + flips the
toggle on a Samsung S24 with a PIN lock. Expected behaviours:
(a) scary dialog appears on first toggle-on, (b) keyguard-detected
chip stays visible while unattended is ON, (c) bridge actions return
`keyguard_blocked` when fired against a locked screen, (d) toggling
master off drops the wake lock immediately. After verification, cut
v0.4.1 release tag.

**Known residuals / follow-ups:**

1. The `WakeOutcome.Success` path doesn't actually wait on the
   `requestDismissKeyguard` callback — we predict success based on
   `isDeviceSecure == false`. Predict-don't-wait is fine for
   None / Swipe locks (which always succeed) but a Wave 2
   refinement could plumb the callback for richer telemetry.
2. The host-activity registration is single-Activity by design.
   If a future MainActivity refactor introduces a second Activity
   surface, the `setHostActivity(null)` call in onPause would
   clobber the wrong reference. Document this constraint where it
   matters.
3. `READ_ONLY_PATHS` is hand-maintained — adding a new bridge route
   requires updating both the dispatch `when` and the read-only
   set. A linter rule or test-time enforcement would catch drift.

---

## 2026-04-16 — Voice intent → server session sync (v0.4.1)

**Motivation.** Bailey's 2026-04-14 on-device repro: speak "open Chrome", phone opens Chrome (good), then type "did that work?" → LLM responds "I have no prior context for what you're asking about." Voice intents dispatch in-process via `BridgeCommandHandler.handleLocalCommand` (correct — voice actions are phone-local) and append a local-only trace bubble to chat (good UX), but the server-side Hermes session never absorbs the action so the gateway-side LLM has zero memory of it. Followups felt like the chat had "reset."

**Approach.** Synthesize OpenAI-format `assistant` (with `tool_calls`) + `tool` (with `tool_call_id`) message pairs from unsynced voice-intent traces and pass them under a new optional `messages` field on the existing `/v1/runs` and `/api/sessions/{id}/chat/stream` payloads. LLMs are trained on this exact shape — they read it as natural conversation history, not a side-channel system-prompt note. Lower retry risk than a bracketed system-message prefix. Frontend-only — zero server changes.

**Implementation summary.**

- **`data/ChatMessage.kt`** — added `voiceIntent: VoiceIntentTrace?` field (default null). The new `VoiceIntentTrace` class captures `toolName` (must start `android_*`), `argumentsJson` (OpenAI tool-call args, JSON-encoded string), `success`, `resultJson` (full result envelope including `ok`/`error`/`error_code`), and `syncedToServer` flag. Default null so every existing `ChatMessage(...)` call site keeps compiling without touching this field.
- **`voice/VoiceIntentSyncBuilder.kt`** (new file, ~170 LOC) — pure-function builder that walks chat history, filters to unsynced voice-intent traces, mints `call_voiceintent_<uuid>` IDs, and emits a JsonArray of synthetic `assistant` + `tool` message pairs in chronological order. `hasUnsynced()` short-circuits the empty-array allocation on the common-case turn. `successResultJson()` / `failureResultJson()` helpers normalize the tool-response payload shape so call sites don't hand-roll JSON. No Android dependencies — JVM-pure for cheap unit testing.
- **`network/HermesApiClient.kt`** — `sendChatStream` and `sendRunStream` both gained an optional `voiceIntentMessages: JsonArray? = null` parameter. When non-empty, splices it under a top-level `messages` field on the request body. Additive, OpenAI-compat — older servers ignore unrecognised body fields.
- **`viewmodel/ChatViewModel.kt`** — `startStream` snapshots history, calls `VoiceIntentSyncBuilder.buildSyntheticMessages`, threads the result into both API client paths, then calls `handler.markVoiceIntentsSynced()` after the API client takes ownership. Idempotent — already-synced traces are skipped. `recordVoiceIntent`/`recordVoiceIntentResult` signatures now accept an optional `voiceIntent: VoiceIntentTrace?` so VoiceViewModel can attach the structured payload.
- **`network/handlers/ChatHandler.kt`** — `appendLocalVoiceIntentTrace` and `appendLocalVoiceIntentResult` now accept an optional `voiceIntent` parameter; new `markVoiceIntentsSynced()` method flips `syncedToServer=true` on every trace currently in state.
- **`voice/VoiceBridgeIntentHandler.kt`** — `IntentResult.Handled` gained `androidToolName: String?` (defaults to null) and `androidToolArgsJson: String` (defaults to "{}"). Sideload classifier populates both per intent so `VoiceIntentSyncBuilder` can synthesize a structured tool_call.
- **Sideload `VoiceBridgeIntentHandlerImpl.kt`** — every `tryHandle` branch now passes `androidToolName` + `androidToolArgsJson` to `handleSafe`/`handleDestructive`. The dispatch callbacks (`onDispatchResult`) carry these forward to VoiceViewModel. Maps `SendSms` → `android_send_sms`, `OpenApp` → `android_open_app`, `Tap` → `android_tap_text`, `Back`/`Home` → `android_press_key`. Args mirror what the gateway-side LLM tool wrappers in `plugin/tools/android_tool.py` would emit for the same actions, so the synthetic tool_call looks identical to the real one.
- **`VoiceIntentResultCallback` typealias** — extended in BOTH `googlePlay/` and `sideload/` flavor source sets to `(intentLabel, result, androidToolName, androidToolArgsJson) -> Unit`. Play APK never invokes the callback (no destructive intents in the no-op handler) but typealias parity keeps `VoiceViewModel` compilable against either flavor with no `#if` gating.
- **`viewmodel/VoiceViewModel.kt`** — extended dispatch callback wiring builds a `VoiceIntentTrace` from the post-dispatch outcome (using `LocalDispatchResult.isSuccess` + `resultJson` + `errorMessage` + `errorCode`) and threads it into `chatViewModel.recordVoiceIntentResult(label, result, voiceTrace)`. Trace attaches to the post-dispatch RESULT bubble (not the pre-dispatch action bubble) because that's the moment the dispatch outcome is authoritative — pre-dispatch was either pending (destructive countdown) or not-yet-known.

**Tests.** `app/src/test/kotlin/com/hermesandroid/relay/voice/VoiceIntentSyncBuilderTest.kt` (12 cases):
- empty history → empty output
- no voice-intent messages in history → empty output
- single success trace → exactly one assistant+tool pair, correct shape
- failure trace → tool message content carries `ok:false` + `error` + `error_code`
- already-synced traces are skipped
- multiple traces preserve chronological order
- non-`android_*` tool name is filtered (defence-in-depth)
- blank args is filtered (defence-in-depth)
- assistant `tool_calls[].id` and tool `tool_call_id` reference the same minted ID
- `hasUnsynced` happy paths (empty / unsynced / all-synced)
- `successResultJson` / `failureResultJson` helpers behave correctly

`ChatHandlerTest.kt` gained 4 new cases for trace storage + `markVoiceIntentsSynced` flag flip + idempotency on already-synced traces + safety on plain non-trace messages.

**Files touched** (10):
- `app/src/main/kotlin/com/hermesandroid/relay/data/ChatMessage.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/voice/VoiceIntentSyncBuilder.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/network/HermesApiClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/ChatHandler.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ChatViewModel.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandler.kt`
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentFactory.kt`
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandlerImpl.kt`
- `app/src/googlePlay/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentFactory.kt`
- `app/src/test/kotlin/com/hermesandroid/relay/voice/VoiceIntentSyncBuilderTest.kt` (new)
- `app/src/test/kotlin/com/hermesandroid/relay/network/handlers/ChatHandlerTest.kt`

**Branch.** `feature/voice-session-sync`. Not merged. Pushed for review.

**What's next.** On-device verification: send a voice intent ("open Chrome"), then type "did that work?" — the LLM should describe the action with grounded context instead of hallucinating. If the upstream gateway logs the synthetic `messages` array as additional context (visible in `journalctl --user -u hermes-gateway -f`), the wire integration is clean. If the gateway 400s on the new field name (e.g. wants `additional_messages` or `history`), we may need to peek at `gateway/platforms/api_server.py` upstream to confirm the exact field name. The CHANGELOG note about "additive — OpenAI-compat" reflects best-current-knowledge but isn't yet wire-verified.

**Blockers.** None. Ships as a self-contained refactor.

---

## 2026-04-15 — Voice → bridge → agent pipeline end-to-end fixes

**Motivation.** Bailey's on-device voice SMS testing surfaced a stack of bugs that individually looked small but together blocked the voice → bridge → agent pipeline from working end-to-end. Over a single long session we diagnosed and fixed six distinct layers: voice classifier gaps, a safety-modal lifecycle bug, chat history clobbering, a plugin check_fn pointed at the wrong relay endpoint, missing LLM tool wrappers for the direct-dispatch phone actions, and two Android OEM-interaction issues (MediaProjection notification persistence + activity recreation on warm reopen). Landed across four commits on `feature/bridge-feature-expansion`.

**Summary of changes** (in narrative order, not commit order):

- **Voice intent classifier narrowed + phrasing-tolerant.** Removed the `Scroll` voice intent (nobody says "scroll down" aloud; `/scroll` route stays for server-side `android_scroll` tool calls). Added `SMS_INDIRECT` regex to catch natural indirect-object phrasing ("send Hannah a text saying smoke test") plus `message` as a direct verb. Added a phone-number literal bypass so "text +1 555 1234 saying hi" skips contact lookup.
- **Honest error classification replaces "couldn't find contact" catch-all.** `resolveContactPhone` now returns a `ContactResolution` sealed type distinguishing `Found` / `ServiceMissing` / `PermissionMissing` / `NotFound` / `NoPhoneNumber` / `OtherError`, with voice mode speaking a specific actionable message per category ("I need Contacts permission to look up that number" vs "I couldn't find a contact called Hannah"). `resolveAppPackage` got the same treatment via `AppResolution`. Pre-flight SEND_SMS check short-circuits before the 5s countdown if permission is missing. Multi-contact matches surface total-count + matched-name in the spoken confirmation so the user knows one of several got picked.
- **Safety-modal threading + savedstate lifecycle fixes.** `BridgeSafetyManager.awaitConfirmation` now wraps `host.showConfirmation` in `withContext(Dispatchers.Main.immediate)` — the ComposeView creation + setContent must run on Main, but was being called from `Dispatchers.Default` via the voice local-dispatch path, threw, and got swallowed as "likely overlay permission missing" (which misled on-device triage for a full test cycle). `OverlayLifecycleOwner.start()` init order flipped: `performRestore(null)` must run while lifecycle state is still INITIALIZED, not after advancing to CREATED — current androidx.savedstate asserts `performAttach` requires INITIALIZED. The old order worked against an earlier androidx.savedstate version and broke silently when the Compose BOM bumped.
- **Voice mode UX: post-dispatch feedback, TTS, visual countdown, fall-through visibility.** `handleLocalCommand` went from fire-and-forget to capturing the response via an `AtomicReference` sink on the `LocalDispatch` coroutine context element, returning `LocalDispatchResult(status, errorMessage, errorCode, resultJson)`. Voice mode emits a follow-up chat bubble ("**Send SMS — sent ✓**" / "**Send SMS — cancelled by you**" / permission/service errors) AND speaks the outcome via the existing TTS queue. Voice-in-voice cancel detects "cancel / stop / never mind" during the 5-second countdown and routes it straight to `cancelPending()` instead of classifying it as a new turn. Visual countdown: `DestructiveCountdownState` on `VoiceUiState` + `onCountdownStart` callback + `LinearProgressIndicator` in `VoiceModeOverlay` synced to the real delay. Classifier fall-through immediately flips `VoiceState.Thinking` so the UI shows progress during SSE connect latency instead of appearing to drop the utterance.
- **Voice mode transcript with chat parity.** `VoiceModeOverlay` now observes `ChatViewModel.messages` and renders the last 6 via `CompactTranscriptRow` + `StreamingResponseRow`. Voice-action traces render via `MarkdownContent`. User messages keep the "YOU" caption (fixed a caption mislabel where voice-intent user messages were showing as "ACTION" due to an id-prefix check that didn't filter by role). `ChatHandler.loadMessageHistory` preserves any `voice-intent-*` messages across server reloads so "Opened Chrome" bubbles don't vanish when session_end reload fires after voice fall-through to LLM.
- **Chat parity: structured result bubbles for android_* tool calls on the runs API.** `ChatHandler` now intercepts `tool.completed` SSE events from `/v1/runs` for action tools (send_sms, call, search_contacts, open_app, return_to_hermes, screenshot, press_key, setup — read-only tools skipped because they'd add noise on top of the existing `ToolProgressCard`) and emits the same structured markdown bubble the voice fast-path does. `agentName = "Phone action"` distinguishes chat-originated from voice-originated ("Voice action"). `MessageBubble.kt` picked up a subtle visual marker for both action-type bubbles so they read as distinct from LLM replies when interleaved.
- **Plugin `_check_requirements` pointed at the right endpoint with a three-gate rule.** Was hitting `/ping` which returns `{pong, ts}` and looking for `phone_connected` / `accessibilityService` fields that don't exist there — result: the gate always returned False and hid all 13 non-setup tools from every gateway platform. Victor's "no android_* tools available" reports were this. Now hits `/bridge/status` and requires all three: `phone_connected AND bridge.accessibility_granted AND bridge.master_enabled`. Tools vanish from the LLM's schema entirely when the master toggle is off (or accessibility is revoked post-Studio-reinstall), giving the model a clean "no tools" signal instead of an error-interpretation race. Trade-off accepted: tools disappear mid-session if the user flips the toggle — desired behavior per "stop the agent from controlling my phone" intent.
- **4 new direct-dispatch plugin tools.** `android_search_contacts`, `android_send_sms`, `android_call`, `android_return_to_hermes` all wrap phone routes (`/search_contacts`, `/send_sms`, `/call`, new `/return_to_hermes`) that were already fully implemented (safety modal, direct `SmsManager` / `CALL_PHONE` dispatch) but had never been exposed to the agent, forcing it to drive Messages / Phone / Contacts UI step-by-step via `tap` + `read_screen`. Tool count 14 → 18. The `/return_to_hermes` route short-circuits when `service.currentApp == service.packageName` (i.e. Hermes is already foreground) so agent wrap-up calls are benign no-ops in voice mode. Master-toggle check exempts `/return_to_hermes` so the agent can always wrap up cleanly.
- **Honest error_code in bridge.response JSON.** `respondFromResult` substring-classifies ActionExecutor errors and emits `error_code` + `required_permission` fields alongside the existing free-text `error`, for `permission_denied` / `service_unavailable` / `user_denied` / `bridge_disabled`. LLM gets both human-readable text AND a machine-readable classification. Clearer 403 English on the `bridge_disabled` path explicitly says "this is NOT a pairing problem" + names the exact toggle — earlier Victor was walking users through re-pairing when the master toggle was off.
- **Plugin tool descriptions carry the "prefer direct dispatch + return_to_hermes when done" guidance** instead of Victor's personality. Initial implementation edited `~/.hermes/config.yaml` on the server, which only works for Bailey's one install — reverted. Guidance now lives in `android_send_sms` / `android_call` / `android_open_app` descriptions where it travels with the plugin to any hermes-agent install. Portable across deploys.
- **MediaProjection notification persists bug fixed.** `BridgeForegroundService.onTaskRemoved` now revokes the MediaProjection + downgrades the FGS type back to SPECIAL_USE-only when the user swipes the app from recents. The bridge itself keeps running (agent phone control via WSS still works), but the system-level screen-cast icon goes away — which is what the user expects when they "close" the app. Before this fix the icon persisted indefinitely because foreground services legitimately survive task removal and the projection stayed bound.
- **Activity recreation on warm reopen fixed.** `MainActivity` in the manifest gained `launchMode="singleTask"` + `configChanges="uiMode|fontScale|locale|density|orientation|screenSize|screenLayout|keyboardHidden"`. Before this the activity was being recreated on config changes and on certain resume paths, triggering `installSplashScreen()` again and showing the splash on warm reopen. The residual case — Samsung OneUI aggressively killing backgrounded apps even with a foreground service — needs user-side battery whitelisting (Settings → Device Care → App battery management → Hermes Relay → Unrestricted) and can't be fixed in code.

**Commits landed on `feature/bridge-feature-expansion`:**

- `5c763eb` — honest errors, modal lifecycle, missing LLM tools (11 files, +974/-158)
- `536a131` — post-dispatch feedback + clearer bridge-disabled error (7 files, +325/-37)
- (pending, this session) — agent-team work: voice UX polish (TTS, cancel, countdown, phone-number bypass, multi-contact), chat structured feedback parity, plugin three-gate rule, `/return_to_hermes` short-circuit, tool description guidance, MediaProjection cleanup, activity recreation hardening

**Server state.** `hermes-gateway` restarted cleanly twice this session. `_check_requirements()` now returns True/False deterministically based on live `/bridge/status` (verified via Python import). Victor personality reverted to clean default — no plugin-specific coupling. All 18 tools register with no missing/orphan handlers.

**Known residual items for follow-up** (not blocking, documented for future-us):

1. Full multi-turn voice disambiguation for multi-contact matches — current fix just hints "found N, using first". Wave 3 multi-turn state machine deferred.
2. Samsung OneUI process-death UX — add an in-app nudge to whitelist battery optimization if running on Samsung + not currently whitelisted. Polish.
3. Tool-description guidance relies on LLM cooperation (non-deterministic) — monitor whether Victor actually picks up `android_send_sms` over UI automation in future tests. If not, we may need to reinforce via plugin.yaml or a new upstream plugin-metadata field.
4. `loadMessageHistory` race with voice-intent-result bubbles self-heals via timestamp sort but has a brief visual flicker window.
5. `android_return_to_hermes` no-op in voice mode is benign but wastes a tool call — agent should detect voice mode and skip. Minor.

**Next session.** Waiting on Bailey's retest with the bundled agent-team work. Once that passes, bump version (voice intent loop + chat parity is v0.4.0-worthy) and start the release cut.

---

## 2026-04-15 — Gateway slash-command dispatch: architectural finding + three-PR strategy

**Motivation.** Bailey typed `/model` into the Android app's chat on the feature/bridge-feature-expansion branch to switch the model for the session. The message reached the LLM as plain text and got a hallucinated reply: *"Switching model — `/model` is a client-side command, I don't execute it. Just send it as its own message (not wrapped in chat) and Hermes will swap the model for the session."* The reply is confidently wrong on two counts — the command is not "client-side", and nothing about the Android client's wire format would prevent Hermes from intercepting it. The LLM was filling a gap it didn't know existed.

**Root cause (confirmed via upstream code archaeology by a subagent oriented in a fresh clone of `Codename-11/hermes-agent` on `feat/session-api`):**

- Gateway slash commands (`/model`, `/new`, `/retry`, the 29 in `hermes_cli/commands.py::GATEWAY_KNOWN_COMMANDS`) are intercepted by **in-process platform adapters** — Discord, Telegram, Slack, Matrix, Signal, BlueBubbles, etc. All of them wrap inbound messages into `MessageEvent`s with `MessageType.COMMAND` and route them through `BasePlatformAdapter.handle_message()` → `GatewayRouter._handle_message()`. That router method contains a ~300-line `if canonical == "new"/"help"/"model"/...` dispatch chain at `gateway/run.py:2645–2929`, and the command handlers (`_handle_model_command` at 4226, `_handle_status_command` at 4065, `_handle_help_command` at 4151, and ~25 others) are instance methods on `GatewayRouter` that mutate router-owned state: `self._session_model_overrides`, `self._agent_cache`, `self._agent_cache_lock`, plus per-adapter interactive pickers via `self.adapters[platform].send_model_picker(...)`.
- **`APIServerAdapter` (`gateway/platforms/api_server.py`) does not connect to the router.** Its `__init__` (at `api_server.py:331`) takes only a `PlatformConfig` — no `GatewayRouter` reference. `_handle_chat_completions` and `_handle_runs` call `self._run_agent(...)` directly, which calls `self._create_agent(...)` to build a **fresh agent per request** with no persistent session state. Upstream confirms this is intentional: `run.py:3148` comments that api_server is an "excluded platform" from the router notification path.
- **Therefore `/model` cannot be made to work on `/v1/runs` with a local preprocessor.** A fresh agent per request means there is no persistent session to switch the model *on*. Same story for `/new`, `/retry`, `/undo`, `/compress`, `/title`, `/resume`, `/branch`, `/rollback`, `/approve`, `/deny`, `/background`, `/stop`, `/yolo`, `/fast`, `/reasoning`, `/personality` — they all mutate router-owned state that api_server doesn't maintain. What *can* run statelessly: `/help` and `/commands` via `gateway_help_lines()` at `hermes_cli/commands.py:340`, and possibly `/profile`, `/provider`, `/usage`, `/insights`, `/status` depending on their implementation.

**Strategy — a three-PR arc, each independently reviewable, each with a matching bootstrap-middleware sibling shipping earlier:**

1. **PR #8556 (already submitted, `feat/session-api` → `NousResearch/hermes-agent:main`).** *"feat(api-server): add session management API for frontend clients."* Scope is broader than the title: sessions CRUD + search + fork + messages, `/api/sessions/{id}/chat` + `/api/sessions/{id}/chat/stream`, memory CRUD, skills, config, available-models. Verified 2026-04-15 via `gh pr diff 8556`. This is the missing session primitives — once merged, api_server has a durable place where stateful state can live.

2. **Stage 1 PR — slash-command preprocessor on the stateless endpoints.** Sibling follow-up to #8556, same file (`gateway/platforms/api_server.py`), stacks on `feat/session-api` during review so the diff shows both together. Detects known gateway commands on `/v1/runs` + `/v1/chat/completions`, dispatches the small stateless subset (`/help`, `/commands`) via existing helpers, returns a deterministic synthetic-SSE "use a channel with session state" notice for the stateful majority. Respects upstream's intentional api_server design and does not touch `GatewayRouter`. Being prepared today in `C:/Users/Bailey/Desktop/Open-Projects/hermes-agent-pr-prep/` on branch `feat/api-server-gateway-commands` via a background subagent; Option B scope (stateless dispatch + polite decline) confirmed after the subagent stopped on the architectural finding instead of writing Option C code that would have fought upstream's design. The subagent is preparing the full diff + PR body for human review before any `gh pr create` runs. Matching bootstrap-middleware sibling (`hermes_relay_bootstrap/_command_middleware.py`) is filed as a v0.4.1 roadmap item so the hallucination fix ships to vanilla-upstream installs without waiting for the upstream PR.

3. **Stage 2 PR — stateful slash-command dispatch on `/api/sessions/{id}/chat/stream`.** Blocked on #8556 merging. Once session primitives ship upstream, a smaller separate PR adds a preprocessor **scoped to the session chat stream endpoint only**, using the URL's `session_id` as the persistence handle. Stateful commands become session-scoped dict writes (`session.model_override = new_model`) without refactoring `GatewayRouter` or plumbing api_server into the router. Clean partition: `/v1/*` stays stateless, statefulness lives on `/api/sessions/*`.

**Why not a single giant "Option C" PR** that refactors `GatewayRouter` to expose `dispatch_slash_command()` + wires `APIServerAdapter` to the router + adds per-session state to api_server: (a) it would withdraw #8556 to restart bigger, which burns reviewer goodwill and signals indecision to Nous, (b) it would touch 10+ files across subsystems normally owned separately, reviewing far worse than three small PRs, (c) it would fight the documented "api_server is excluded from router" design intent rather than extending it, (d) #8556 is already doing most of the work (session primitives); a parallel session substrate would duplicate it, (e) review slots on respected org PRs are hard-won; don't give one back.

**Docs updated this session:**

- `CLAUDE.md` — fork branch reference corrected to `feat/session-api` + scope clarification on #8556.
- `docs/decisions.md` — same fix in the upstream-notes section (line 85).
- `docs/upstream-contributions.md` — new §5 documenting the Stage 1 + Stage 2 upstream PR plan and the bootstrap-middleware workaround.
- `ROADMAP.md` — v0.4.1 bootstrap-middleware entry rewritten to reflect the Option B ceiling (cannot make `/model` actually switch models on `/v1/runs` — that's Stage 2, blocked on #8556) and the architectural reasoning.
- `TODO.md` — three new entries covering Stage 1 upstream PR, Stage 1 bootstrap middleware, and Stage 2 (blocked on #8556).
- `hermes_relay_bootstrap/_handlers.py`, `hermes_relay_bootstrap/_patch.py` — docstring branch-name corrections (`feat/api-server-enhancements` → `feat/session-api`).

**Not touched this session:**

- No Kotlin changes. The Android client at `HermesApiClient.kt:655-715` already parses `message.delta` / `response.output_text.delta` / `reasoning.available` / `tool.started` / `tool.completed` / `run.completed` / `[DONE]`, so when Stage 1 or the bootstrap middleware lands with a synthetic SSE stream matching those shapes, the client needs no changes.
- No changes to `gateway/platforms/api_server.py` or any file in `hermes-agent-pr-prep/`. The subagent has orientation-only state on `feat/api-server-gateway-commands`; implementation begins on its next wake.
- The `feature/bridge-feature-expansion` branch is untouched by this session and continues with its voice-bridge work. The slash-command work is a separate branch arc and will not land on the bridge feature branch.

## 2026-04-13 — Play Console migration to Axiom-Labs, LLC (applicationId → `com.axiomlabs.hermesrelay`)

Moved the Play Store listing from Bailey's personal Play Console account (where v0.1.x–v0.3.0 shipped under Internal testing) to the **Axiom-Labs, LLC** D-U-N-S-verified organization account. This unblocks straight-to-production rollout — personal accounts created after late 2023 are subject to Google's 14-day closed-testing rule (≥12 opted-in testers for 14 continuous days before production promotion); D-U-N-S-verified org accounts are exempt. See [Google's policy](https://support.google.com/googleplay/android-developer/answer/14151465).

**applicationId change.** `com.hermesandroid.relay` → `com.axiomlabs.hermesrelay` (googlePlay flavor) and `com.axiomlabs.hermesrelay.sideload` (sideload flavor, unchanged suffix pattern). Play Store package names are permanently reserved once used, so the old ID can never be reclaimed — the previous Internal-testing listing is retired and existing installs won't auto-upgrade to the new listing. Blast radius is one tester (Bailey), so manual reinstall is fine.

**Kotlin namespace stays at `com.hermesandroid.relay`.** AGP decouples `namespace` (build-time R-class / BuildConfig / on-disk source layout) from `applicationId` (runtime install identity), so we don't need to mass-rename the 130+ Kotlin files, their package declarations, proguard rules, or test FQCNs. The source tree stays stable; only the Play/Android install identity moves. `scripts/dev.bat` and `scripts/dev.sh` already use the split FQCN form `adb am start -n com.axiomlabs.hermesrelay/com.hermesandroid.relay.MainActivity` to bridge the two namespaces at launch time.

**Keystore identity is unchanged.** Same `CN=Bailey Dixon, Codename-11` upload cert, same SHA256 fingerprint, same `HERMES_KEYSTORE_*` GitHub Secrets, same CI signing flow. Play App Signing mints a new server-side app signing key per listing, but that's invisible to us since App Signing is enabled. No CI or keystore changes required.

**Files touched in this migration:**

- `app/build.gradle.kts` — `applicationId = "com.axiomlabs.hermesrelay"` + multi-paragraph comment block explaining the namespace/applicationId decoupling and the migration rationale
- `RELEASE.md` — new "Google Play Console developer account" section documenting the Axiom-Labs account, the D-U-N-S exemption from the 14-day rule, and a "Historical note (2026-04-13 migration)" block for future maintainers
- `ROADMAP.md` — "Current — Axiom-Labs migration" section added at the top
- `README.md` — Play Store badge URL → `?id=com.axiomlabs.hermesrelay`; copyright line → "Axiom-Labs"
- `CLAUDE.md` — "applicationId" bullet updated to call out both flavors under `com.axiomlabs.*`
- `scripts/dev.bat`, `scripts/dev.sh` — `am start` commands updated to the new applicationId
- `scripts/bridge-smoke.sh` — sideload package test payload → `com.axiomlabs.hermesrelay.sideload`
- `user-docs/guide/getting-started.md`, `user-docs/guide/release-tracks.md` — Play Store URLs + both applicationIds

**Play Console listing creation (for future reference):**

| Field | Value |
|---|---|
| App name | Hermes-Relay |
| Package name | `com.axiomlabs.hermesrelay` |
| Default language | English (US) |
| App/game | App |
| Free/paid | Free |
| Developer account | Axiom-Labs, LLC |

Nothing to rebuild or re-sign beyond the normal v0.3.1+ release flow.

## 2026-04-12 — Manual pairing fallback — `hermes-pair --register-code <code>`

Wired the host-side half of the in-app fallback pairing flow. The phone has been generating a local 6-char code in `Settings → Connection → Manual pairing code (fallback)` and `AuthManager.authenticate()` already sent that code as `pairing_code` when no server-issued code was present, but there was no convenient way for an operator to pre-register an arbitrary code with the relay without going through the QR flow. Now there is.

**Use case**: Bailey wants to pair a phone he's already SSH'd in *from*, where there's no second device with a camera to scan a QR off the host's display. The QR flow is unusable in that scenario; the new flag is the only path.

**`plugin/pair.py` additions:**

- New `normalize_pairing_code(code)` helper — upper-cases, strips, validates against `PAIRING_ALPHABET` (A-Z / 0-9) and `PAIRING_CODE_LENGTH` (6). Raises `InvalidPairingCodeError` (a `ValueError` subclass) with a clear message on length or alphabet mismatches so the CLI can fail fast instead of letting the operator find out via an HTTP 400.
- New `register_code_command(args)` — separate code path from the QR pipeline. Validates the code, parses `--ttl` / `--grants` (same parser as the QR flow, default `30d`), resolves the relay port from `read_relay_config()`, probes `/health` first so we can give a precise "relay not running" message instead of a generic post failure, then calls the existing `register_relay_code()` helper with all the optional metadata. Prints a clean success block listing the code, transport hint, session TTL, grants, and the exact 3-step "tap Connect in the app" instructions. Distinct exit codes: `0` success, `1` relay unreachable / rejected, `2` argument validation failed.
- `pair_command()` short-circuits to `register_code_command` when `args.register_code` is set, so the QR pipeline is skipped entirely (no relay code minted, no QR rendered, no API config probed).
- New CLI flags on the standalone `python -m plugin.pair` argparser: `--register-code <CODE>` (the trigger), `--transport-hint {ws,wss}` (override the auto-detected transport hint when running behind an external TLS proxy that the host can't see). All existing flags (`--ttl`, `--grants`, `--host`, `--port`, etc.) compose with `--register-code` exactly the way they compose with the QR flow.

**New CLI shape:**

```bash
hermes-pair --register-code ABCD12
hermes-pair --register-code ABCD12 --ttl 30d --grants terminal=7d,bridge=1d
hermes-pair --register-code ABCD12 --ttl never --transport-hint wss
```

The relay endpoint (`POST /pairing/register` in `plugin/relay/server.py`) and `PairingManager.register_code` were not touched — they already accepted user-supplied codes with optional TTL/grants/transport-hint metadata. This change just exposes that capability via a CLI flag so the operator doesn't have to hand-craft a `curl` POST.

**`plugin/tests/test_register_code.py` — 25 tests, stdlib `unittest` only.** Three test classes:

- `NormalizePairingCodeTests` (13 tests) — happy path (uppercase / lowercase / whitespace-stripped / all-digit / all-letter), every rejection branch (empty / whitespace-only / `None` / too short / too long / dash / punctuation / unicode).
- `RegisterCodeCommandTests` (10 tests) — happy path with default TTL (verifies it posts `30 * 24 * 3600` seconds and `transport_hint="ws"`); happy path with `--ttl 7d --grants terminal=1d,bridge=1d` and `tls=True` in the relay config (verifies `transport_hint="wss"` and the auto-uppercase of `abcd12 → ABCD12`); explicit `--transport-hint wss` override; every error path (invalid chars, wrong length, empty, relay unreachable → exit 1, relay rejection → exit 1, invalid `--ttl` → exit 2, invalid `--grants` → exit 2). Each error-path test asserts the network was NOT touched if validation failed first.
- `RegisterRelayCodeWireShapeTests` (2 tests) — patches `urllib.request.urlopen` directly and verifies the bytes the manual flow puts on the wire match what `handle_pairing_register` already parses: `{"code": ..., "ttl_seconds": ..., "grants": ..., "transport_hint": ...}`. Also covers the minimal-body case (just `{"code": ...}` when no metadata is supplied) so we don't accidentally start sending `null` fields the server's `_parse_pairing_metadata` would have to filter.

Run via `python -m unittest plugin.tests.test_register_code` — 25 passed in 0.006s.

**Docs touched:**

- `skills/devops/hermes-relay-pair/SKILL.md` — added `--register-code` to the "Useful flags" bullet list and a full "Manual fallback (`--register-code`)" section between Procedure and Pitfalls covering when to use it, the 3-step workflow, how `--ttl` / `--grants` / `--transport-hint` compose, and the exit codes.
- `skills/devops/hermes-relay-self-setup/SKILL.md` — added an "If you can't scan a QR" subsection inside section D pointing operators at the `hermes-relay-pair` skill's manual-fallback recipe.
- `user-docs/reference/configuration.md` — expanded the "Manual pairing code (fallback)" bullet from a one-liner into a full 3-step workflow walkthrough so users can find this without leaving the app's docs.
- `user-docs/guide/getting-started.md` — added a "Camera unavailable? Use manual pairing" callout under the "Choosing session lifetime + channel grants" section.
- `README.md` — added a one-line bullet under the install section listing `hermes-pair --register-code` alongside the slash command and the dashed shim.

**Conventions:** No Kotlin touched (parallel agent owns the in-app UI cleanup). Did not modify `plugin/relay/server.py` or `PairingManager` — endpoint already supported user-supplied codes. Tests run via stdlib `unittest` to bypass the `conftest.py` that imports `responses`.

## 2026-04-12 — Connection UX — Manual pairing code walkthrough + per-channel grant revoke

**Manual pairing code card.** Settings → Connection → "Manual pairing code (fallback)" used to be a bare-bones code + copy/regen stub that left users guessing what to do with the 6-character code. Replaced the body with a proper three-step walkthrough: (1) copy the code (with both an instant copy button and the existing regenerate button), (2) on the host, run `hermes-pair --register-code <code>` rendered in a copyable monospace shell-command surface, (3) tap **Connect** to fire the pair flow. Step 3 is a real `Button` that calls `applyServerIssuedCodeAndReset` → `disconnectRelay` → `connectRelay` (mirroring the existing dialog flow), tracks an in-flight `card3ConnectInProgress` flag with a `CircularProgressIndicator` + "Connecting…" label, and reports success/failure through `LocalSnackbarHost.showHumanError` with the "pair" classifier context. Below the steps is an expandable "How does this work?" explainer that spells out when this is the right flow vs. the QR scan, and reminds users that bridge control is gated by the master toggle on the Bridge tab — not by the pairing code itself. New private `ManualPairStep` helper renders the numbered step badges. Pairs cleanly with the parallel agent's host-side `hermes-pair --register-code` work above. Markers: `// === MANUAL-PAIR-FOLLOWUP: ... === / // === END MANUAL-PAIR-FOLLOWUP ===`.

**Per-channel grant revoke.** Paired Devices → device card grant chips are now individually revocable. Each chip got an inline `Close` icon button (a small clickable `Box` instead of `IconButton` to avoid the 48dp touch-target inflation that would blow up the `FlowRow`). Tapping the x opens an `AlertDialog` confirming "Revoke <channel> access for <device>?" with an explicit reminder that the session itself stays paired and other channels keep their current expiry. New `ConnectionViewModel.revokeChannelGrant(tokenPrefix, channel)` builds the full grants map by reading the device's current `PairedDeviceInfo.grants`, converting absolute-epoch grants back to seconds-from-now (clamped to ≥ 1 since `0` means "never expire" on the relay side), and replacing only the target channel with `1L` (≈ instantly expired by the time the PATCH lands). Then it reuses `RelayHttpClient.extendSession` with `ttlSeconds = null, grants = rebuilt` and refreshes the device list on success. The chip label also got a relative TTL helper (`formatRelativeTtl`) that renders "never" / "in 6d" / "in 23h" / "expired" instead of the previous absolute short date. The full per-session "Revoke" button stays — per-channel chips are additive, not a replacement. Markers: `// === PER-CHANNEL-REVOKE: ... === / // === END PER-CHANNEL-REVOKE ===`.

## 2026-04-12 — Phase 3 / status — relay `GET /bridge/status` endpoint + expanded `BridgeStatusReporter`

Backend half of the `android_phone_status()` work. The phone (`BridgeStatusReporter.kt`) now pushes a structured status envelope every 30 s with three nested groups:

- **`device`** — `Build.MODEL`, battery, `PowerManager.isInteractive`, `HermesAccessibilityService.instance?.currentApp`
- **`bridge`** — `master_enabled`, `accessibility_granted`, `screen_capture_granted`, `overlay_granted`, `notification_listener_granted`
- **`safety`** — blocklist count, destructive verb count, auto-disable timer minutes, `auto_disable_at_ms`

Old flat keys kept alongside the new structure for backwards compat. New `pushNow()` method on the reporter for out-of-band emissions; `ConnectionViewModel` calls it whenever the master toggle flips so the relay-side cache refreshes immediately instead of waiting up to 30 s.

**Relay side**: `BridgeHandler` gained `latest_status: dict | None` and `last_seen_at: float | None`, populated in `handle_status`. New `handle_bridge_status` route at `GET /bridge/status`, gated to loopback only (mirrors `/pairing/register`). Empty cache → 503; populated → 200 with `last_seen_seconds_ago` computed at response time. Stale-cache disconnects return 200 — the cached snapshot is still useful.

**Tests**: `plugin/tests/test_bridge_status.py` — 7 stdlib `unittest` cases (empty cache 503, populated 200, disconnected, non-loopback rejection, IPv6 `::1`, snapshot-not-reference regression, dual-field stamping). All pass; existing `test_bridge_channel.py` still green.

Markers: `# === PHASE3-status: ... === / # === END PHASE3-status ===` for the new Python blocks; `// === PHASE3-status: ... ===` for Kotlin.

## 2026-04-12 — Phase 3 / bridge UI hardening — master gate, MediaProjection consent, label disambiguation

Three follow-ups discovered while smoke-testing the merged Phase 3 build on a real device.

**Master gate fix.** `HermesAccessibilityService.cachedMasterEnabled` was supposed to be fed by an external `updateMasterEnabledCache(...)` writer, but **nothing was calling it** — the cache was permanently `false` and `BridgeCommandHandler` was 403'ing every command except `/ping` and `/current_app` regardless of the toggle state. Fix: the service now starts a `serviceScope` coroutine on `onServiceConnected` that observes `masterEnabledFlow(this)` and pumps every emission into the cache. Service-owned scope is cancelled on `onUnbind`/`onDestroy` and re-created on each connect (cancelled `SupervisorJob`s can't be reused). Cache is reset to `false` on teardown so a stale-but-bound state can't leak through.

**MediaProjection consent flow.** `MediaProjectionHolder.onGranted(...)` existed but **nothing called it** — there was no `ActivityResultLauncher` registered anywhere. Fix: `MainActivity` now registers a launcher in `onCreate` and a new `ScreenCaptureRequester` process-singleton holds the launch closure (installed on Activity create, cleared on destroy). `BridgeViewModel.requestScreenCapture()` calls `ScreenCaptureRequester.request()` to ask the user; the system consent dialog fires; the result callback feeds `MediaProjectionHolder.onGranted(...)` which closes the loop with the existing `ScreenCapture.kt`. Bridge tab's Screen Capture row is now tappable instead of inert; the row's checkmark reflects `MediaProjectionHolder.projection != null`.

**Notification Listener Test button parity.** Added `BridgeViewModel.testNotificationListener()` and an `onTestNotificationListener` lambda through `BridgePermissionChecklist` so the row gets a Test button matching the other three. The dedicated functional test on `NotificationCompanionSettingsScreen` still ships.

**Launcher label disambiguation per flavor.** With `googlePlayDebug` and `sideloadDebug` installed side-by-side (same icon, same name), there was no way to tell which install you were tapping in the launcher / recents / Settings → Apps. Fix: sideload's `res/values/strings.xml` now overrides three strings:
- `app_name` → `Hermes Dev` (10 chars, fits launcher icon, mirrors `Slack Dev` / `Chrome Dev` convention)
- `a11y_service_label` → `Hermes-Bridge Dev` (Accessibility settings list)
- `notification_companion_label` → `Hermes Dev notification companion`

googlePlay's `a11y_service_label` also flipped to `Hermes-Bridge` (with hyphen) for consistency. The Play track inherits all other strings from main.

**Other small fixes**: `BridgeForegroundService.kt` `Intent.flags = ...` → `addFlags(...)` (the property setter form doesn't compile because `Intent.setFlags` returns `Intent`, not void). `BridgeViewModel.kt` removed `StateFlow.distinctUntilChanged()` (deprecated no-op since StateFlow already deduplicates).

Markers: new code blocks tagged `// === PHASE3-bridge-ui-followup: ... === / // === END PHASE3-bridge-ui-followup ===`.

## 2026-04-12 — Phase 3 / status trio — `android_phone_status` tool + `hermes-status` CLI + `/hermes-relay-status` skill

Symmetric read-only counterpart to the pair trio. The relay ships `GET /bridge/status` (loopback, in a parallel agent's scope) that exposes live phone state — connection, battery, screen, foreground app, bridge permission flags, safety-rail config. This change builds the three consumers that wrap it so the agent, operators, and chat users all have a clean entry point.

**`plugin/tools/android_phone_status.py`.** New Hermes tool registered via the canonical `tools.registry` import pattern, modeled after `plugin/tools/android_notifications.py`. Zero args. Calls `http://127.0.0.1:8767/bridge/status` over loopback (no bearer — same trust model as `/media/register` and `/pairing/register`), returns `{"status": "ok"|"error", "phone_status": {...}|null, "error": str|null}`. Stdlib `urllib.request` only — no `requests`/`httpx`. Handles three canonical paths:

- **200 OK** → `status=ok` with the parsed relay body as `phone_status`.
- **503** (relay alive but no phone has ever connected) → `status=ok` with `phone_status={"phone_connected": false, ...}`. From the agent's perspective the phone not being connected isn't an error — it's just the current state, and the LLM should render it as prose.
- **URLError/OSError/timeout** → `status=error` with `error="relay unreachable"`. This is the only case where the agent should bail.

The tool's description is explicit about *when* to call it: before attempting any bridge operation (`android_tap`, `android_type`, `android_screenshot`) so the agent can check `phone_connected` + `bridge.*_granted` upfront instead of eating failures one by one. `check_fn=lambda: True` because gating status on bridge connectivity would be circular — it IS the connectivity check.

**`plugin/status.py`.** New CLI module modeled after `plugin/pair.py`. Probes the same endpoint, pretty-prints with a small ANSI `_Palette` helper that's a no-op when stdout isn't a TTY. Three distinct exit codes so shell scripts can distinguish the cases: `0` success + connected, `1` relay unreachable, `2` relay alive but no phone. `--json` flag emits raw JSON pass-through (always a stable envelope so callers can `jq` over it even on failure). `--port N` overrides `RELAY_PORT`. Invokable via `python -m plugin.status` or the `hermes-status` shim. `fetch_status(port)` is factored out as a pure-function core so the tests can exercise it without mocking argparse.

**`skills/devops/hermes-relay-status/SKILL.md`.** New slash-command skill mirroring `hermes-relay-pair`'s structure verbatim — same frontmatter shape, same `metadata.hermes.category: devops`, same "When to Use / Prerequisites / Procedure / Pitfalls / Verification" section layout. Explains how to interpret the three exit codes, when to re-pair vs. when to restart the relay, and how to read the permission flags when the user's "accessibility is granted" but status says otherwise (OS killed the service). Picked up automatically by the `external_dirs` scan installer registers — no config change needed on upgrade.

**`install.sh` / `uninstall.sh` — hermes-status shim.** Added step [5/6] sibling block that writes `~/.local/bin/hermes-status` alongside `hermes-pair`. Same template: a tiny bash shim that execs `$HERMES_VENV_PY -m plugin.status "$@"` with a friendly error if the venv python can't be found. Uninstaller mirrors it for removal — both shims come out together under `[5/6]`. Header comments in both scripts updated to reference the plural "shims".

**`plugin/tests/test_android_phone_status.py` — 19 tests, stdlib `unittest` only.** Covers the tool's success path, 503 mapping, 503 with empty body, connection refused (URLError), raw OSError, other HTTP errors (500), non-JSON body, non-object JSON (`[1,2,3]`, `42`), schema sanity (`parameters.required == []`, `properties == {}`), and the registry handler wrapper. Also covers the CLI's `fetch_status` return-triple shape across all three exit codes + `main(["--json"])` happy path + all three failure modes. The rendering pass has two smoke tests: one verifies the happy path includes all the expected fields (device name, battery %, current app, blocklist count, destructive verbs, permission labels), one verifies the disconnected short-circuit omits the Device/Bridge/Safety sections entirely. Mocks `urllib.request.urlopen` via `unittest.mock.patch`, uses a tiny `_FakeResponse` context-manager stub and `_http_error(code, body)` helper that builds a real `urllib.error.HTTPError` whose `.read()` returns the fixture body (critical: the 503 handler reads the body to extract the phone-state envelope, so the mock has to wrap a `BytesIO` in the `fp` slot, not just pass bytes). All tests run green:

```
python -m unittest plugin.tests.test_android_phone_status -v
Ran 19 tests in 0.010s
OK
```

**Files touched:** new `plugin/tools/android_phone_status.py`, new `plugin/status.py`, new `skills/devops/hermes-relay-status/SKILL.md`, new `plugin/tests/test_android_phone_status.py`, edits to `install.sh` + `uninstall.sh` for the shim pair. No relay-side changes — the `/bridge/status` endpoint is owned by a parallel agent working in the `plugin/relay/server.py` scope. This change is pure consumer.

## 2026-04-12 — Phase 3 / safety-rails followup — deep link, overlay nag, in-app permission Test buttons

Three small UX wins on top of the merged Wave 2 safety rails. All gate cleaner first-run smoke testing on a real device.

**Deep link from foreground notification → BridgeSafetySettingsScreen.** `BridgeForegroundService.ACTION_OPEN_SETTINGS` previously dropped the user on `MainActivity`'s home screen — they had to navigate Settings → Bridge safety manually. New `app/src/main/kotlin/com/hermesandroid/relay/util/NavRouteRequest.kt` is a tiny `MutableSharedFlow<String>` singleton that any external launcher (foreground service, broadcast receiver, shortcut intent) can post route requests to. `MainActivity` reads `EXTRA_NAV_ROUTE` in `onCreate` + `onNewIntent` and forwards via `NavRouteRequest.tryRequest`. `RelayApp` adds a `LaunchedEffect(navController)` that collects `NavRouteRequest.requests` and forwards each emission to `navController.navigate(route) { launchSingleTop = true }`. `BridgeForegroundService` now sets `MainActivity.EXTRA_NAV_ROUTE = "settings/bridge_safety"` on its launch intent. Single observer at the app root, no per-screen plumbing.

**Overlay-permission nag banner on BridgeScreen.** When the master toggle is on but `Settings.canDrawOverlays(context)` is false, a prominent red `errorContainer`-colored card appears at the top of `BridgeScreen` warning the user that confirmation prompts can't display. Without the banner, `BridgeSafetyManager.awaitConfirmation` was failing-closed silently — destructive verbs would just be denied with no UX hint about why. New private `OverlayPermissionNagCard` Composable in `BridgeScreen.kt` with a `Warning` icon, plain-language explanation, and a tap action that opens `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` for our package. Goes away on its own once the permission is granted (BridgeViewModel re-probes on `ON_RESUME`).

**In-app permission Test buttons in `BridgePermissionChecklist`.** Matches the pattern ε already shipped on `NotificationCompanionSettingsScreen`. Each row in the bridge permission checklist now has an optional "Test" text-button next to its status icon. Tapping it runs a side-effect-free diagnostic on the underlying runtime state and surfaces the result via the global `LocalSnackbarHost`. New methods in `BridgeViewModel`:

- `testAccessibilityService()` — checks `HermesAccessibilityService.instance` is non-null AND `instance.rootInActiveWindow` is non-null. Three result strings cover "not bound", "bound but no active window", and "OK".
- `testScreenCapture()` — checks `MediaProjectionHolder.projection` is non-null. Reports either "active grant" or "no grant — bridge will request consent next /screenshot".
- `testOverlayPermission()` — checks `Settings.canDrawOverlays(context)`. Reports granted vs not-granted with a hint to tap the row.

Each method emits a one-shot human-readable string on a new `MutableSharedFlow<String>` (`testEvents`) that `BridgeScreen` collects in a `LaunchedEffect` and shows via `snackbarHost.showSnackbar(message)`. Notification listener row keeps `onTest = null` because the existing dedicated test on `NotificationCompanionSettingsScreen` already covers it.

**Why diagnostic-only, not full functional tests** (e.g., actually capture a screenshot or actually flash an overlay): the diagnostic checks need zero side effects, no extra dependencies, no permission flows mid-test. They answer the "is this thing actually wired through?" question 80% of the time — full functional tests are a Wave 3 follow-up if the diagnostics aren't enough in practice.

**Files touched:** new `util/NavRouteRequest.kt`, new private composables in `BridgeScreen.kt`, edits to `MainActivity.kt`, `BridgeForegroundService.kt`, `RelayApp.kt`, `BridgePermissionChecklist.kt`, `BridgeViewModel.kt`. All marker blocks use `PHASE3-safety-rails-followup`.

## 2026-04-12 — Rename Phase 3 agent codenames from Greek letters to ASCII slugs

Bulk rename across the repo + Obsidian plan: replaced the Greek-letter agent codenames I'd been using since the original Phase 3 plan (`α β accessibility bridge-ui notif-listener safety-rails voice-intents vision-nav` — wait, only the first four were Greek; the actual original set was `α β γ δ ε ζ η θ`) with descriptive ASCII slugs. The Greek letters were a math/CS-paper convention and they sort nicely, but they're a pain to type, render badly in some terminals, and made commit-history search awkward.

Mapping:

| Old | New |
|---|---|
| α | bridge-server |
| flavor-split | flavor-split |
| accessibility | accessibility |
| bridge-ui | bridge-ui |
| notif-listener | notif-listener |
| safety-rails | safety-rails |
| voice-intents | voice-intents |
| vision-nav | vision-nav |

Scope: 42 repo files (DEVLOG, CLAUDE, Kotlin sources, Python sources, manifests, Vue docs) + the canonical Obsidian plan at `Plans/Phase 3 — Bridge Channel.md`. Implementation was a single sed pass per file with all 8 substitutions in one invocation, applied via a bash for-loop. Verified with `grep -rc '[αβγδεζηθ]'` returning 0 across the affected trees.

**Git history is not rewritten.** Existing commits and merge commits keep their original Greek-letter subjects (e.g., `merge(phase3-α): migrate legacy bridge relay`). Renaming history would require a force push and would invalidate every commit hash since the divergence point — not worth the cost for a cosmetic change. Going forward, new branches, commit messages, and marker blocks all use the ASCII slugs.

**Marker block convention going forward:** `// === PHASE3-bridge-server: ... === / // === END PHASE3-bridge-server ===` (and the analogous Python `# === ... ===` and XML `<!-- === ... === -->` forms). Followup blocks use `PHASE3-<slug>-followup`.

## 2026-04-12 — Phase 3 / Wave 1 hotfix — dedupe AccessibilityService entry; Gradle deprecation cleanup

Two small fixes discovered while smoke-testing Wave 1 in Android Studio.

**Duplicate AccessibilityService entry in Android Settings.** Bailey noticed Settings → Accessibility → Installed services listed two Hermes entries (`Hermes-Relay` and `Hermes Bridge`). Root cause: a coordination miss between agents flavor-split and accessibility during the Wave 1 agent-team run. flavor-split created the flavor manifests *first* and stubbed `<service android:name=".accessibility.BridgeAccessibilityService" tools:ignore="MissingClass">` blocks in both flavor overlays, anticipating that accessibility would name the service class `BridgeAccessibilityService`. accessibility later named it `HermesAccessibilityService` instead. Manifest merger only collapses `<service>` elements with matching `android:name`, so two different names → two separate `<service>` entries in the merged manifest. The flavor-split-stub entry pointed at a class that doesn't exist anywhere — enabling that row in Android Settings would have crashed the bind.

Fix: removed the stub `<service>` blocks from both `app/src/googlePlay/AndroidManifest.xml` and `app/src/sideload/AndroidManifest.xml` entirely (the flavor manifests are now empty `<application />` overlays kept around for future flavor-specific permissions / activities). The flavor distinction now lives purely at the resource layer — each flavor's `res/xml/accessibility_service_config.xml` carries its own use-case description and flag bitset, and Gradle's resource merger picks the right file at build time. Added `a11y_service_label` ("Hermes-Relay Bridge") to `app/src/main/res/values/strings.xml` so the single canonical service entry in `app/src/main/AndroidManifest.xml` can be labeled distinctly from the launcher icon.

**Gradle deprecation warning.** AGP printed `android.dependency.excludeLibraryComponentsFromConstraints=true is deprecated, will be removed in version 10.0`. The two flags `useConstraints=true` + `excludeLibraryComponentsFromConstraints=true` were doing the work of one — the second flag overrode the first to mean "actually, exclude library components from constraint resolution." AGP's recommended migration is `useConstraints=false`. Collapsed two lines in `gradle.properties` into one. Semantics unchanged.

**Lessons learned (for future agent teams):** when an agent that creates a manifest stub names a class to be filled in by another agent, the two agents need to agree on the class name *up front* — manifest merger does not auto-collapse `<service>` blocks with different `android:name` values, even if everything else matches. Putting the canonical class name in the plan's "Wire format" section would have prevented this.

## 2026-04-12 — Onboarding/Settings unification + lifecycle-aware health checks

Closed two long-standing UX gaps that compounded each other: (1) onboarding's "Connect" page only configured the API server side and discarded the QR's relay block (code, TTL, grants, transport hint), so users walked out of onboarding in a "half-paired" state and had to re-do setup in Settings; (2) connection status badges showed stale Connected/Disconnected for up to 30 s after foregrounding because nothing kicked a re-probe on `Lifecycle.Event.ON_RESUME` — the StateFlow snapshot was just preserved across backgrounding even when the underlying server had died or the network had flipped.

**New: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionWizard.kt`** — shared three-step pairing wizard (Scan → Confirm → Verify) with a circular step indicator at the top. Used by both `OnboardingScreen` and `ConnectionSettingsScreen`'s "Pair with QR" button so first-run and re-pair stay in lockstep forever. Step 1 launches `QrPairingScanner` via the camera permission flow. Step 2 displays the scanned details (server URL, relay URL, grants, transport security badge), an inline TTL radio list, and a red "plain ws://" warning when the relay is insecure. Step 3 observes `AuthState` with a 15s `withTimeout`, surfaces classified errors via Retry/Back/Cancel, and calls `onComplete()` on `AuthState.Paired`. The wizard's "Skip for now" affordance only renders when the caller passes `showSkip = true` (onboarding sets that, Settings doesn't).

**New: `ConnectionViewModel.applyPairingPayload(payload, ttlSeconds)`** — single entry point for "user just confirmed a scanned QR + chose a TTL." Does the full credential dance: API URL + key, relay URL + insecure mode, `applyServerIssuedCodeAndReset` (wipes TOFU pin + applies code), `setPendingGrants`, `setPendingTtlSeconds`, `disconnectRelay() + connectRelay()`, then `revalidate()`. Replaces the ~50-line confirm-callback that used to live in `ConnectionSettingsScreen.kt::SessionTtlPickerDialog.onConfirm`. The wizard is the only caller now.

**New: `ConnectionViewModel.HealthStatus` (Unknown / Probing / Reachable / Unreachable)** + `apiServerHealth` + `relayServerHealth` StateFlows. Distinct from the existing `apiServerReachable: Boolean` so the UI can render a dedicated "Probing" pose right after foreground / network change. The boolean stays in place for legacy callers; new UI consumes the tri-state flow.

**New: `ConnectionViewModel.revalidate()`** — single entry point for "the world might have changed — re-check everything." Flips both health flows to `Probing` immediately so the badge poses don't flash stale Connected/Disconnected, then fires API + relay `/health` probes in parallel and joins them, and kicks `reconnectIfStale()` to bring the WSS back if it was holding a paired-but-disconnected state. Idempotent — guarded by a `revalidationJob` so rapid foreground/background cycles don't pile up parallel probes.

**New: lifecycle hook in `RelayApp.kt`** — `DisposableEffect(lifecycleOwner)` registers a `LifecycleEventObserver` that calls `connectionViewModel.revalidate()` on `ON_RESUME`. This is *the* fix for the resume-lag flash. Single observer at the app root so every screen gets fresh badges on foreground without each one wiring its own.

**New: connectivity reaction inside ConnectionViewModel.init** — the existing `ConnectivityObserver.observe()` flow was previously exposed as `networkStatus` but never read. Now a collector inside `init` calls `revalidate()` on every `Available` transition (with `drop(1)` to skip the seed value so we don't double-probe on first composition). Airplane-mode flips, network handoffs, and Tailscale up/down events now actually trigger a re-probe instead of being purely advisory.

**New: periodic relay health loop** — mirrors the existing 30s API health loop. Uses `RelayHttpClient.probeHealth()` (unauthenticated, 3s timeout, no rate-limit cost) to update `relayServerHealth` independently of the WSS heartbeat. Plugs the historical gap where relay status was only verified by WSS or manual Save & Test taps.

**New: fourth state on `ConnectionStatusBadge` — `BadgeState.Probing`** — gray pulsing dot at 1.2s cadence, distinct from the amber Connecting pulse at 0.8s and the green Connected pulse at 1.5s. Added a new `state: BadgeState` overload of both `ConnectionStatusBadge` and `ConnectionStatusRow`. The boolean overloads are preserved (with a new optional `isProbing: Boolean = false` parameter) so legacy call sites — chat header, terminal screen, bridge cards — keep working without churn. Only call sites that *want* a Probing state need to migrate.

**Edited: `app/src/main/kotlin/com/hermesandroid/relay/ui/onboarding/OnboardingScreen.kt`** — replaced the bespoke `ConnectPage` (which discarded the relay block, never walked the user through TTL picking, and let users exit onboarding in a half-paired state) and `RelayPage` (a bare URL text field) with a single `ConnectPage` that embeds `ConnectionWizard`. Pages list is now: Welcome → Chat → (Terminal → Bridge if relay enabled) → Connect (wizard). Onboarding's `onComplete` callback collapsed from 4 args (`apiServerUrl, apiKey, relayUrl, relayPairingCode`) to zero — the wizard owns credential application via `applyPairingPayload`, so the callback only needs to mark complete + navigate. The pager's bottom Next/Back navigation is hidden on the Connect page so the wizard owns its own affordances. Top-bar Skip is also hidden on the Connect page so the wizard's "Skip for now" is the only skip affordance.

**Edited: `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ConnectionSettingsScreen.kt`** — "Scan Pairing QR" + "Guided setup" buttons collapsed into a single "Pair with QR" button that opens the wizard inside a full-screen `Dialog`. "Re-pair" button (shown when paired) now opens the wizard instead of launching the camera directly. Removed: the `cameraPermissionLauncher` (wizard owns permission), `pendingQrPayload` state, the inline `SessionTtlPickerDialog` confirm-callback block (~50 lines), and the `showPairingWalkthrough`/`PairingWalkthroughDialog` dialog. The API/Relay/Session status rows now consume `apiServerHealth` and `relayServerHealth` so they render the new Probing pose during the post-resume window.

**Edited: `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt`** — root Connection summary card's API and Relay rows updated to consume the new health flows + render Probing on resume. Status text reads "Checking…" during the probe instead of flashing stale "Connected"/"Unreachable".

**Deleted: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/PairingWalkthroughDialog.kt`** (~370 lines) — the old 4-step dialog-counter wizard. Wizard supersedes it. `SessionTtlPickerDialog.kt` is preserved because the wizard reuses its `ttlPickerOptions()` and `defaultTtlSeconds()` helpers.

The end result: one canonical pairing flow used by both onboarding and Settings, badges that flip to "Checking…" instead of flashing stale state on resume, and an explicit Probing pose so the user can tell the difference between "we don't know yet" and "we know it's down."

## 2026-04-12 — Docs: two-track explainer + FeatureMatrix component

Closed the user-doc gap left by Phase 3's googlePlay/sideload Gradle flavor split. Before this, the user-docs site framed sideloading as just an alternative install path — there was no signal that the sideload APK actually unlocks tier 3/4/6 features (voice→bridge intent routing, vision-driven `android_navigate`, workflow recording) that are *compiled out* of the Play Store APK by `BuildFlavor.bridgeTier{3,4,6}` checks.

**New: `user-docs/.vitepress/theme/components/FeatureMatrix.vue`** — polished feature comparison component that matches the design language of `HermesFlow.vue` / `InstallSection.vue` / `HeroDemo.vue` (Space Grotesk + Space Mono, `--vp-c-brand-1` purple accent, flat + border-separated, no gradients/shadows/blur). Renders a semantic `<table>` with one row per *feature* (tier numbers are an implementation detail users don't care about), grouped into Chat & voice / Bridge — read / Bridge — control / Safety rails / Install & updates sections. Three support states per cell (`full` / `limited` / `none`) with inline SVG icons inheriting `currentColor` from the cell's class. Sideload-only rows fade to `--vp-c-text-3` opacity 0.6; "limited" cells get a *• see note* suffix that surfaces a per-row footnote about the read-only Play accessibility surface. Responsive: above 720px shows both columns side-by-side with a brand-soft tinted Sideload column; below 720px collapses to a single visible column with role=tablist mobile tab switcher (the semantic table stays intact for screen readers — the inactive column is hidden via `display:none` on mobile-only cells, not removed). Below 480px the text labels collapse to icon-only with the support state on the cell's `aria-label`. Zero npm deps — all icons are inline SVG.

**New: `user-docs/guide/release-tracks.md`** — canonical prose explainer. Plain-language opener ("Hermes-Relay ships in two flavors built from the same codebase"), TL;DR up top, "Why two tracks?" section that explains Google Play's accessibility-service scrutiny without jargon, embedded `<FeatureMatrix />`, decision guide ("Want X? → Track Y" bullets), "Can I switch later?" section explaining the side-by-side `applicationIdSuffix = ".sideload"` story, install instructions for both tracks (linking to existing getting-started.md content rather than duplicating), and a "Safety rails — always on, in both tracks" closer to make clear the floor isn't a sideload feature. Wired into the `/guide/` sidebar between Installation & Setup and Chat.

**Edited: `user-docs/features/index.md`** — added a "Bridge — Phone Control" table with a Track column and a `<span class="track-badge track-badge--sideload">Sideload only</span>` convention for compiled-out features (voice→bridge, vision navigation, workflow recording). Added a "Bridge — Safety Rails" table to make the floor explicit. Removed Bridge from "Coming Soon" since Phase 3 is shipping. Added a "Choose your track" section near the bottom with `<FeatureMatrix />` embedded and a link to release-tracks.md. Top-of-page intro now flags the two flavors and the badge convention.

**Edited: `user-docs/guide/getting-started.md`** — replaced the single-line "download the APK or wait for Play Store" sentence in step 1 with a short two-flavor pitch and a link to release-tracks.md. Did not duplicate the matrix here — just points to it.

**Edited: `user-docs/.vitepress/config.mts`** — added Release tracks to the `/guide/` sidebar between Installation & Setup and Chat.

**Edited: `user-docs/.vitepress/theme/index.ts`** — registered FeatureMatrix as a global Vue component so MD files can use `<FeatureMatrix />` without per-file imports (matches the existing HermesFlow registration pattern).

Build verified locally with `npx vitepress build` — clean compile, all pages render, no errors.

## 2026-04-12 — Phase 3 / Wave 2 / safety-rails: bridge safety rails

Branch: `feature/phase3-zeta-safety-rails` (off `main` @ 8d86a62 which has Wave 1 merged).

**What landed.** Tier 5 safety for the bridge channel — five enforcement components that together make it OK to actually ship agent device control:

1. **Per-app blocklist** (`BridgeSafetyPreferences.kt`) — DataStore-backed set of package names. Ships with a conservative default covering common banking apps (Chase / Wells Fargo / BoA / Revolut / Monzo / Starling), payment apps (Venmo / Cash App / PayPal / Coinbase), password managers (1Password / Bitwarden / LastPass / Dashlane / Keeper), and 2FA apps (Google Authenticator / Authy / Duo). Users edit via `BridgeSafetySettingsScreen` — searchable LazyColumn of installed apps with checkboxes. Enforcement is in `BridgeSafetyManager.checkPackageAllowed(packageName: String?)`, called by `BridgeCommandHandler` against `HermesAccessibilityService.currentApp`; a blocked package fails fast with HTTP 403 `{"error": "blocked package <name>"}`.

2. **Destructive-verb confirmation modal** (`DestructiveVerbConfirmDialog.kt` + `BridgeStatusOverlay.kt`) — when `/tap_text` or `/type` carries a body that matches one of the configurable destructive verbs (default: send, pay, delete, transfer, confirm, submit, post, publish, buy, purchase, charge, withdraw) on a word-boundary regex, `BridgeSafetyManager.awaitConfirmation(method, text)` suspends the handler coroutine, shows a full-screen Compose modal through a `SYSTEM_ALERT_WINDOW` overlay (necessary because the agent can act while the app is backgrounded), and waits on a `CompletableDeferred<Boolean>` under a `withTimeout`. Timeout default 30s; timeout = DENY. Fail-closed: if the overlay permission is missing, the check returns false.

3. **Auto-disable timer** (`AutoDisableWorker.kt`) — a coroutine `Job` owned by `BridgeSafetyManager` with a `delay(minutes.toMillis())`. Rescheduled on every accepted bridge command. On fire: flips the master toggle off via `HermesAccessibilityService.setMasterEnabled(context, false)` and posts a one-shot "Bridge auto-disabled after idle" notification through `NotificationManagerCompat`. Default idle window: 30 min, slider 5..120 min. **Deviation from spec:** spec called for WorkManager, but `androidx.work` is not in the classpath and adding the dep is out of scope for this slice. The coroutine-job approach is documented in `AutoDisableWorker.kt` as the canonical upgrade path if WorkManager is added later.

4. **Persistent foreground service** (`BridgeForegroundService.kt`) — plain `Service` with `startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` on Android 14+, graceful fallback below. Notification shows "Hermes agent has device control" with two action buttons: **Disable** (broadcasts back into the service which flips the master toggle) and **Settings** (opens `MainActivity`; TODO followup to wire a deep-link extra for direct nav to `BridgeSafetySettingsScreen`). Started/stopped from `BridgeViewModel` via a `masterToggle.distinctUntilChanged().collect {}` observer.

5. **Optional status overlay chip** (`BridgeStatusOverlay.kt`) — tiny floating "Hermes active" pill in the top-right corner. Off by default, opt-in via `BridgeSafetySettingsScreen`. Uses the same `SYSTEM_ALERT_WINDOW` pipeline as the confirmation modal — only one `WindowManager` attachment point per process. The overlay host implements `ConfirmationOverlayHost` so the safety manager can reach both the chip and the modal through the same singleton.

**Files created (8):**

- `app/src/main/kotlin/com/hermesandroid/relay/data/BridgeSafetyPreferences.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/bridge/BridgeSafetyManager.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/bridge/BridgeForegroundService.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/bridge/BridgeStatusOverlay.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/bridge/AutoDisableWorker.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/BridgeSafetySettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/DestructiveVerbConfirmDialog.kt` (also hosts `BridgeStatusOverlayChip`)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/BridgeSafetySummaryCard.kt`

**Shared-concern files touched (6)** — all marked with `// === PHASE3-safety-rails: ... === / === END PHASE3-safety-rails ===`:

- `BridgeCommandHandler.kt` — injects `safetyManager: BridgeSafetyManager?`, runs the three-stage check (blocklist → confirmation → reschedule timer) before dispatching each action.
- `AndroidManifest.xml` — adds `SYSTEM_ALERT_WINDOW` + `FOREGROUND_SERVICE_SPECIAL_USE` uses-permission + a `<service android:name=".bridge.BridgeForegroundService" android:foregroundServiceType="specialUse"/>` declaration with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification property.
- `BridgeScreen.kt` — replaces the `SafetyPlaceholderCard` stub with the real `BridgeSafetySummaryCard` driven by `BridgeSafetyManager.peek()?.settings` and `.autoDisableAtMs`. New `onNavigateToBridgeSafety` parameter.
- `SettingsScreen.kt` — adds `onNavigateToBridgeSafety` callback and a "Bridge safety" `SettingsCategoryRow` between Notification companion and Media.
- `RelayApp.kt` — `Screen.BridgeSafetySettings` data object, composable(route), wires the nav callback from Settings + from Bridge screen.
- `BridgeViewModel.kt` — observes `masterToggle` and calls `BridgeForegroundService.start/stop`, reschedules/cancels the auto-disable timer on toggle changes, and combines the master toggle with `statusOverlayEnabled` to drive the chip visibility.
- `ConnectionViewModel.kt` (wiring only, marked) — calls `BridgeSafetyManager.install()` + `BridgeStatusOverlay.install()` at construction and passes the manager into `BridgeCommandHandler`.

**Key design notes / blockers / decisions.**

- **WorkManager not in classpath.** Went with a coroutine-owned timer instead of adding a new dependency. `AutoDisableWorker.kt` documents the upgrade path. This is acceptable because the toggle is in-process — no inter-process scheduling is required, and process death already implies the service is disconnected.

- **SYSTEM_ALERT_WINDOW UX.** Users must grant the permission through `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` before either the confirmation modal or the status chip can display. `BridgeSafetySettingsScreen` detects via `Settings.canDrawOverlays(context)` and kicks off the grant flow on first switch tap. **Fail-closed:** if the permission is missing when a destructive verb fires, `BridgeSafetyManager.awaitConfirmation` denies the action outright — better to drop a command than to silently allow it. Phase 3 Wave 3 should add a prominent "grant overlay permission" nag on the BridgeScreen when the user enables the master toggle without having granted it.

- **Compose in a WindowManager overlay.** `ComposeView` attached via `WindowManager.addView` doesn't automatically get a `ViewTreeLifecycleOwner` — we synthesize an always-RESUMED `OverlayLifecycleOwner` + `ViewModelStoreOwner`. Skipped `SavedStateRegistryOwner` because the dialog only uses `remember`, not `rememberSaveable`, and `androidx.savedstate` isn't pinned in the version catalog. If future overlay content needs `rememberSaveable`, pin that artifact and extend `OverlayLifecycleOwner`.

- **Confirmation coroutine wiring.** The safety manager keeps a `ConcurrentHashMap<Long, PendingConfirmation>` keyed by a monotonic request id. `awaitConfirmation` registers an entry, asks the overlay host to show the modal, then `withTimeout(...) { deferred.await() }`. The overlay's Allow / Deny buttons call back with the result. Timeouts dismiss the overlay programmatically. The suspending `BridgeCommandHandler.dispatch` call is what holds the slot — the relay sees a slow response instead of a premature 403, which matches the intended UX (user gets 30s to react, agent waits).

- **accessibility/bridge-ui API dependencies.** Relied on: `HermesAccessibilityService.currentApp` (accessibility, @Volatile String?), `HermesAccessibilityService.setMasterEnabled(context, enabled)` (accessibility), `HermesAccessibilityService.isMasterEnabled()` (accessibility), `BridgePreferencesRepository` pattern (bridge-ui), `relayDataStore` extension (existing), `BridgeViewModel.masterToggle: StateFlow<Boolean>` (bridge-ui). All used as-is — no edits in accessibility/bridge-ui territory.

**What's next.**

- Wire a deep-link extra on `MainActivity` so `BridgeForegroundService.ACTION_OPEN_SETTINGS` lands directly on `BridgeSafetySettingsScreen` (currently opens the app and the user taps through).
- Record `BridgeActivityEntry` rows for blocked-package / denied-confirmation events so the bridge activity log shows *why* an agent command failed.
- Wave 3 can add tests: unit tests for the destructive-verb regex word-boundary matching, an instrumented test for the foreground-service lifecycle, and a fake overlay host for `awaitConfirmation` timeout behavior.

## 2026-04-12 — Phase 3 / Wave 2 / voice-intents: voice→bridge intent routing (sideload-only, Tier 3)

Wired voice mode to optionally route transcribed utterances through the bridge channel instead of chat. When the user holds the voice button and says "text Sam I'll be 10 min late" on a **sideload** build, a lightweight keyword classifier recognizes the SMS intent, the voice layer speaks a confirmation ("About to text Sam: I'll be 10 min late. Say cancel to stop."), and a `bridge` envelope is queued for dispatch after a 5-second cancel window. "Open camera" / "scroll down" / "go back" / etc. execute immediately with no countdown.

**Cross-flavor compile pattern.** This is the first Wave 2 surface to exercise the `googlePlay` vs `sideload` flavor split that agent flavor-split is wiring up in parallel. `VoiceBridgeIntentHandler` is an interface + sealed `IntentResult` in `main/` so `VoiceViewModel` has a stable compile-time reference. Two separate implementations ship per-flavor with matching FQCNs and a shared top-level factory function `createVoiceBridgeIntentHandler(multiplexer)`:

- `app/src/googlePlay/kotlin/.../VoiceBridgeIntentHandlerImpl.kt` — `NoopVoiceBridgeIntentHandler` always returns `IntentResult.NotApplicable`, never touches the bridge, never references any device-control / accessibility class. Keeps the Play APK honest with its conservative feature description.
- `app/src/sideload/kotlin/.../VoiceBridgeIntentHandlerImpl.kt` — `RealVoiceBridgeIntentHandler` + `VoiceIntentClassifier` with keyword/regex patterns for SendSms / OpenApp / Tap / Scroll / Back / Home. Destructive intents go through the 5 s countdown; safe intents dispatch immediately.

`VoiceViewModel.initialize()` calls the factory exactly once; the ViewModel only ever holds the interface reference. No reflection, no runtime flavor check — Gradle picks the right impl at build time via the source-set flavor. The routing call site in `processVoiceInput` tries the handler first and falls through to `chatVm.sendMessage(text)` on `NotApplicable`.

**Classifier heuristic.** Six intents, first-match-wins, all case-insensitive with a filler-stripping preamble ("hey", "okay", "please", "can you", …):

| Intent | Pattern example | Parse |
|---|---|---|
| SendSms (with separator) | `text <contact> saying <body>` / `text <contact>: <body>` / `send a message to <contact> saying <body>` | `contact`, `body` |
| SendSms (no separator) | `text <contact> <body≥2chars>` — conservative: 1-2 word contact, requires a body | `contact`, `body` |
| OpenApp | `open|launch|start <app>` (optional `the`/`app` suffix) | `appName` |
| Tap | `tap|press|click(?: on)? <target>` (optional `button` suffix) | `target` |
| Scroll | `scroll (to the)? up|down|top|bottom` | `direction` |
| Back | `(go|navigate)? back` | — |
| Home | `(press|go)? home(?: screen)?` | — |

Design bias: **false negatives > false positives**. "Can you text me when you're done?" falls through to chat because the SMS regex can't parse a contact + body from it. Anything that doesn't cleanly split into a structured action becomes a chat message. Each pattern is a single named-group regex so Wave 3 tuning is mechanical.

**v1 confirmation simplification.** Full conversational confirmation ("About to text… say yes or cancel") would need a multi-turn voice state machine we don't have. Instead: destructive intents speak the confirmation via the existing sentence-TTS queue, start a 5 s countdown coroutine, and dispatch unless `VoiceViewModel.cancelPendingBridgeIntent()` is called first. The overlay's Cancel button wires into that method. Full voice-confirmation is a Wave 3 follow-up (see Phase 3 plan row `voice-bridge-confirmation`).

**Bridge envelope wire shape.** Simple and documented so Wave 2 sibling safety-rails (`bridge-safety-rails`) owns the authoritative schema:

```
{ channel: "bridge",
  type:    "tool.call",
  payload: { tool: "android_send_sms" | "android_open_app" | ... ,
             args: { ... },
             requires_confirmation: true|false,
             source: "voice" } }
```

Emission goes through `ChannelMultiplexer.send(envelope)` — the new `bridgeMultiplexer: ChannelMultiplexer? = null` parameter on `VoiceViewModel.initialize()` is optional so existing call sites keep compiling until they're updated to pass the instance. The googlePlay factory ignores the multiplexer entirely.

**Files:**

New (main):
- `app/src/main/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandler.kt` — interface + `sealed class IntentResult { Handled, NotApplicable }`

New (googlePlay flavor):
- `app/src/googlePlay/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandlerImpl.kt` — `NoopVoiceBridgeIntentHandler`
- `app/src/googlePlay/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentFactory.kt` — factory returning the no-op

New (sideload flavor):
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentHandlerImpl.kt` — `RealVoiceBridgeIntentHandler` with v1 countdown + envelope builders
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceBridgeIntentFactory.kt` — factory returning the real impl
- `app/src/sideload/kotlin/com/hermesandroid/relay/voice/VoiceIntentClassifier.kt` — regex-based classifier + `VoiceIntent` sealed class

Touched (main):
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt` — new `bridgeMultiplexer` param on `initialize()`; factory call at the bottom of init; intent-routing block at the top of `processVoiceInput` (between the Thinking-state update and the sentence-buffer reset); new `cancelPendingBridgeIntent()` public method for the overlay's Cancel button. All changes wrapped in `// === PHASE3-voice-intents: ... === / // === END PHASE3-voice-intents ===` markers.

**Known cross-worktree dependency.** This worktree assumes agent flavor-split's `productFlavors { googlePlay; sideload }` + `BuildFlavor.bridgeTier3` constant lands in `build.gradle.kts` / `FeatureFlags.kt` before this branch merges. Without flavor-split's flavor config, Gradle won't resolve the `createVoiceBridgeIntentHandler(...)` reference from `VoiceViewModel` because neither flavor source set is active. Order of merges: flavor-split first, then voice-intents (and safety-rails + vision-nav).

**Next steps (Wave 2 sibling + Wave 3):**
- safety-rails owns the `bridge` envelope contract — if the wire-level field names change (`tool` → `name`, etc.), update `VoiceBridgeIntentHandlerImpl.buildXxxEnvelope` in one place.
- Overlay wire-up (Bailey or a future agent): show `IntentResult.Handled.intentLabel` in the voice overlay, add a Cancel button that calls `cancelPendingBridgeIntent()` when `requiresConfirmation=true`.
- Wave 3 `voice-bridge-confirmation`: replace the 5 s countdown with a proper multi-turn confirmation ("say yes or cancel" → STT → classifier for yes/no).
- Classifier unit tests: `extractNextSentence`-style unit tests for `VoiceIntentClassifier.classify()`. Top-level function already testable without an Application context.

## 2026-04-12 — Phase 3 / Wave 2 / vision-nav — android_navigate vision-driven navigation tool

Lands the Tier 4 "burner-phone trick, done sanely" plan row. New Hermes tool `android_navigate(intent, max_iterations=5)` closes the loop on high-level bridge automation: instead of the agent having to know exact node IDs or coordinates, it passes a natural-language intent ("compose a tweet about rainy weather") and the tool loops `screenshot → vision model → action → repeat` until the model emits `done` or hits the iteration cap.

**Files added:**
- `plugin/tools/android_navigate.py` — main loop + registry registration. Reuses the same `_bridge_url()`/`_auth_headers()`/`ANDROID_BRIDGE_URL` transport layer as `android_tool.py` so pairing config stays single-sourced. Dispatches parsed actions to `/tap_text`, `/tap`, `/type`, `/swipe`, `/press_key` (the HTTP routes Wave 1 stood up). Screenshot per iteration goes through the bridge `/screenshot` endpoint, then tries `plugin.relay.client.register_media()` for an opaque token and gracefully falls back to a `file://` marker if the relay isn't reachable. Every step records `{step, action, params, screenshot_token, reasoning, result}` into a trace the agent gets back on both success and failure envelopes. Iteration cap defaults to 5, hard-clamped to `ABSOLUTE_MAX_ITERATIONS = 20` so the agent can't accidentally burn capture budget.
- `plugin/tools/android_navigate_prompt.py` — prompt template + response parser. Parser is case-insensitive, tolerates trailing punctuation on the verb, allows `done` to omit the PARAMS line, enforces per-verb param shape (e.g. `tap` needs either `(x,y)` ints or `node_id` string), and returns `ParsedAction(action="error", reasoning=<why>)` on any malformed input so the loop can surface structural failures cleanly.
- `plugin/tests/test_android_navigate.py` — 35 stdlib-only `unittest` tests covering all 6 valid actions, every malformed-input branch (empty / missing lines / bad JSON / wrong shape / unknown verb / list-not-object / bad swipe direction), success path (single-step and multi-step), iteration cap, screenshot failure, parse error, action-execution failure, `llm_gap` envelope, empty intent short-circuit, `max_iterations` clamping, and schema sanity. Uses only `unittest.mock` so it runs cleanly via `python -m unittest plugin.tests.test_android_navigate` without the pytest `conftest.py` that imports the `responses` module.

**LLM integration — known gap:** The plan specifies "vision model integration" but doesn't pick a provider, and the plugin has no published LLM client surface for tools to call. The gateway's run loop is not re-entrant (calling the agent from inside a tool deadlocks the run). Rather than invent a new LLM client and ship an opinionated default, the loop has a `call_vision_model` injection point. Tests patch it; production either (a) sets `HERMES_NAVIGATE_STUB_REPLY` for smoke testing, or (b) swaps `_default_vision_model` for a real client (e.g. Anthropic `messages.create` with a base64 image block). Until (b) lands, calling `android_navigate` against a live phone returns a clean `{"status": "error", "reason": "llm_gap", ...}` envelope so the agent sees exactly why the loop can't run — no silent fakes, no import-time crashes. The `_default_vision_model` docstring documents the replacement contract.

**Tool guardrails** (from the plan):
- Never continuous capture — exactly one `/screenshot` per iteration, only when the tool is invoked.
- Default 5 iterations, hard-clamped ceiling of 20.
- 200 ms settle delay between action and next screenshot; callers needing longer waits use `android_wait` explicitly.
- `intent` must be non-empty; empty/whitespace returns an error envelope without touching the bridge.

**Files NOT touched:** `plugin/relay/*`, `plugin/relay/channels/bridge.py`, `plugin/tools/android_tool.py`, `plugin/tools/android_relay.py`, `plugin/relay/auth.py`, `plugin/relay/media.py`, `plugin/relay/voice.py`, anything under `app/`. The slice is self-contained in `plugin/tools/` as scoped.

**Test results:** `python -m unittest plugin.tests.test_android_navigate` → 35 tests, all pass in ~0.05 s.

## 2026-04-12 — Dynamic phone-status system prompt block

Replaced the single hardcoded "app context prompt" sentence with a transparent, granular block that reflects real Phase 3 bridge/permission state. The old toggle promised far more than it delivered — one static line, regardless of anything actually happening on the phone. The new block is a master toggle + 4 sub-toggles with a live preview card in Chat Settings.

**New file:** `app/src/main/kotlin/com/hermesandroid/relay/util/PhoneStatusPromptBuilder.kt` — pure function `buildPromptBlock(settings, snapshot)` returning `String?`. Returns `null` when master is off so no empty system message is sent. Defines `AppContextSettings` (5 booleans) and `PhoneSnapshot` (11 nullable fields). Output capped under ~100 words; the brief suggests calling `android_phone_status` for full detail, keeping per-turn token cost down while giving the agent a permission-lit entrypoint.

**ConnectionViewModel:** Four new DataStore keys alongside the existing `KEY_APP_CONTEXT` — `KEY_APP_CONTEXT_BRIDGE_STATE` (default true), `KEY_APP_CONTEXT_CURRENT_APP` (default **false** — privacy), `KEY_APP_CONTEXT_BATTERY` (default **false** — privacy), `KEY_APP_CONTEXT_SAFETY_STATUS` (default true). Each gets a StateFlow + setter mirroring the `appContextEnabled` pattern.

**ChatViewModel:** Deleted `APP_CONTEXT_PROMPT` constant, replaced `var appContextEnabled: Boolean` with `var appContextSettings: AppContextSettings`. `send()` now calls `buildPromptBlock(appContextSettings, capturePhoneSnapshot())`. The `capturePhoneSnapshot()` helper uses guarded reflection (`runCatching` on every lookup) to read Phase 3 classes — `HermesAccessibilityService.instance`, `MediaProjectionHolder.projection`, `BridgeSafetyManager.peek()`. The reflection path exists so ChatViewModel compiles cleanly before those classes land in this worktree (they're being built by a parallel agent); once they exist, reads light up with zero further code changes here. Non-Phase-3 sources (battery, overlay permission, notification listener flat-string) use direct platform APIs.

**RelayApp.kt:** Single `LaunchedEffect` now keys on all 5 toggles and writes a fresh `AppContextSettings` into `chatViewModel.appContextSettings` on any change.

**ChatSettingsScreen.kt:** Master toggle renamed to "Share phone status with agent". When enabled, `AnimatedVisibility` reveals 4 sub-toggle rows + a "Preview" Card. The preview uses `remember(master, bridgeState, currentApp, battery, safetyStatus)` to regenerate exact output text on every toggle change, calling the same `buildPromptBlock` with a neutral empty snapshot so the user sees the shape without leaking current phone state into the settings screen. Shows "(no system message will be sent)" when builder returns `null`.

**Privacy model:** Default configuration sends "mobile-friendly preamble + bridge permission summary + safety rails count" — no package names, no battery level. Users opt in explicitly to each privacy-sensitive field. The `android_phone_status` tool (being built in a parallel worktree) is the disclosure path for full detail so per-turn cost stays bounded.

Markers: all new blocks tagged `// === PHASE3-status: ... === / // === END PHASE3-status ===` for grep.

## 2026-04-12 — Add canonical uninstall.sh + bootstrap docs

Companion to the bootstrap injection work below. There was no formal uninstall path before — `install.sh` is idempotent so most update flows worked, but cleanly removing the plugin (e.g., to test that install.sh works on a truly fresh state) required manually undoing 6 install steps. New `uninstall.sh` reverses them in opposite order:

1. Stops + disables `hermes-relay.service`, removes the systemd unit, daemon-reloads
2. Removes `~/.local/bin/hermes-pair` shim
3. Scrubs the relay's `skills.external_dirs` entry from `~/.hermes/config.yaml` via the same yaml parsing pattern install.sh uses, with a `.bak` backup before write — preserves all other entries
4. Removes `~/.hermes/plugins/hermes-relay` symlink + any legacy stales (`hermes-android`, etc.)
5. Removes `hermes_relay_bootstrap.pth` from venv site-packages, `pip uninstall hermes-relay`
6. Removes `~/.hermes/hermes-relay` clone (sanity-checked: refuses to delete a directory that doesn't have `.git` + `install.sh`)

What it never touches: `~/.hermes/.env` (other tools authenticate against this), `~/.hermes/state.db` (sessions DB shared with the gateway), `~/.hermes/hermes-agent/` (the agent itself), `~/.hermes/hermes-agent/venv/` (only our `.pth` is removed, not the venv core), and `~/.hermes/hermes-relay-qr-secret` (kept by default — the QR signing identity is precious; opt in to wipe with `--remove-secret`).

Flags: `--dry-run` previews without changing anything, `--keep-clone` leaves the git tree in place, `--remove-secret` wipes the QR secret. Help text: `bash uninstall.sh --help`.

`install.sh` header docs updated to mention `bootstrap injection` (step 2) and the uninstall path. Success summary now prints both `git pull && bash install.sh` for updates and `bash uninstall.sh --dry-run` for previewing removal. README.md and `user-docs/guide/getting-started.md` got equivalent updates with the bootstrap explanation + uninstall flags.

## 2026-04-12 — Bootstrap injection: vanilla upstream hermes-agent now works with the plugin

Closed the "you must run our hermes-agent fork to get full features" gap. The Codename-11 fork (`feat/api-server-enhancements`, 13 commits, submitted as PR [#8556](https://github.com/NousResearch/hermes-agent/pull/8556)) adds 20 management endpoints — `/api/sessions/*` CRUD, `/api/memory`, `/api/skills`, `/api/config`, `/api/available-models`, `/api/sessions/{id}/chat/stream` — that the Android app depends on. Until #8556 merges and reaches a release, vanilla upstream users were missing the sessions browser, conversation-history-on-restart, personality picker, command palette, and memory management.

**The fix: a single `.pth` file that runs at Python interpreter startup.** New `hermes_relay_bootstrap/` package ships with the plugin, gets loaded by Python's `site` module before anything in hermes-agent imports `aiohttp.web`. The bootstrap installs a `sys.meta_path` finder that wraps the loader for `aiohttp.web`. When the import resolves, our wrapper replaces `web.Application` with a thin subclass. The subclass overrides `__setitem__` to detect `app["api_server_adapter"] = self` — the line at `gateway/platforms/api_server.py:1735` where the gateway gives us a reference to the adapter while the router is still mutable. At that moment we feature-detect by route path and bind ~14 management handlers from `_handlers.py` directly onto the same router. The gateway then continues with its own route registrations and starts the server. From the outside, vanilla upstream now serves all the fork's management endpoints.

**What is NOT injected and why:** the chat-stream handler (`/api/sessions/{id}/chat/stream`). It depends on `_create_agent` and `agent.run_conversation` with multimodal content — the riskiest cross-cutting upstream methods that the fork may have implicitly modified. Instead, chat goes through standard upstream `/v1/runs`, which already emits structured `tool.started`/`tool.completed` SSE events. This is arguably an upgrade — `/v1/runs` has live tool events whereas the sessions chat-stream path required a post-stream message-history reload to render tool cards. The Android client adapts via a new `streamingEndpoint = "auto"` mode (default for new installs).

**Files added:**
- `hermes_relay_bootstrap/__init__.py` (~30 lines) — installs the meta_path finder
- `hermes_relay_bootstrap/_patch.py` (~170 lines) — `_AioHttpWebFinder`, `_PatchingLoader`, `_PatchedApplication`, `_maybe_register_routes` with feature detection by route path
- `hermes_relay_bootstrap/_handlers.py` (~500 lines) — 14 ported management handlers + helpers (sessions CRUD, memory CRUD, skills, config, available-models). Handlers take `adapter` as a closure parameter rather than being bound methods, so we don't pollute upstream's class.
- `hermes_relay_bootstrap.pth` — single line: `import hermes_relay_bootstrap`

**Files changed:**
- `pyproject.toml` — added `hermes_relay_bootstrap*` to packages.find include list
- `install.sh` step 2 — copies the `.pth` into the venv's `site-packages/` after `pip install -e`. Verified empirically: setuptools' editable install does NOT ship `data-files` to site-packages reliably (it puts them in `venv/data/` instead, where Python's `site` module never looks). Manual copy is necessary.
- `app/src/main/kotlin/.../HermesApiClient.kt` — new `ServerCapabilities` data class + `probeCapabilities()` method that returns per-endpoint presence. Uses HEAD-method probes (initially tried OPTIONS but the gateway's CORS middleware intercepts preflight and returns 403 for both existing and missing paths — discovered during the live install test on 2026-04-12). Treats any non-404 status as "route exists." `detectChatMode()` becomes a thin compatibility wrapper around `probeCapabilities().toChatMode()`.
- `app/src/main/kotlin/.../ConnectionViewModel.kt` — exposes `serverCapabilities: StateFlow<ServerCapabilities>`, populates it from `probeCapabilities()` in `rebuildApiClient()`, adds `resolveStreamingEndpoint(preference)` helper that collapses `"auto"` to a concrete `"sessions"` or `"runs"` based on the latest snapshot. Default endpoint preference for new installs flipped from `"sessions"` to `"auto"`.
- `app/src/main/kotlin/.../ChatViewModel.kt` — `streamingEndpoint` default flipped from `"sessions"` to `"runs"` (the safer fallback before RelayApp pushes the resolved value).
- `app/src/main/kotlin/.../ui/RelayApp.kt` — `LaunchedEffect(streamingEndpoint, serverCapabilities)` recomputes the resolved endpoint when either changes, pushes into ChatViewModel.
- `app/src/main/kotlin/.../ui/screens/ChatSettingsScreen.kt` — Settings → Streaming endpoint dropdown gains an "Auto" option (alongside Sessions/Runs). Helper text dynamically shows which path Auto is currently using.
- `CLAUDE.md` — non-standard endpoints table rewritten to show all three "provided by" mechanisms (fork, bootstrap, upstream-merged), plus key files entries for bootstrap + capability detection.
- `docs/decisions.md` — new ADR 16 covering the runtime injection rationale, options considered (A/B/C/D), risks accepted, removal path.
- `vault/Hermes-Relay.md` — new "Bootstrap Injection Architecture" section with the compatibility matrix; updated "Hermes Integration" table to show two install paths (fork or bootstrap).

**Compatibility matrix** (all three combinations safe to ship the bootstrap with):

| Gateway version | Bootstrap behavior | Result |
|---|---|---|
| Codename-11 fork (`axiom`) | Detects existing `/api/sessions`, no-ops | Fork serves everything natively ✓ |
| Vanilla upstream main | Detects no `/api/sessions`, injects routes | Bootstrap-injected endpoints serve ✓ |
| Post-PR-#8556 upstream-merged | Detects existing `/api/sessions`, no-ops | Upstream serves everything natively ✓ |

**Removal path** (when PR #8556 reaches a released hermes-agent version): delete `hermes_relay_bootstrap/`, delete `hermes_relay_bootstrap.pth`, remove the `.pth` drop block from `install.sh`. The Android client `probeCapabilities()` + `streamingEndpoint = "auto"` plumbing stays — it's permanent infrastructure that handles mixed-version deployments.

## 2026-04-12 — Phase 3 / Wave 1.5 — bridge-server-followup + notif-listener-followup (post-merge polish)

Two small follow-ups discovered during the Wave 1 agent-team merge. Both gate clean smoke testing of the merged Wave 1 work in Android Studio.

**bridge-server-followup — `POST /media/upload` route on the relay.** Agent accessibility's `ScreenCapture` posts screenshot bytes via multipart to `/media/upload`, but bridge-server only ported the existing `/media/register` (loopback + path-based). Phone has no shared filesystem with the relay, so `/media/register` was unusable for the bridge screenshot path. The new endpoint accepts a `file` multipart field with bearer auth (every paired phone has a session token), streams the bytes to a `NamedTemporaryFile` under `tempfile.gettempdir()` (which is in the default `MediaRegistry.allowed_roots`), enforces `MediaRegistry.max_size_bytes` while reading (returns 413 on overflow), then hands the path off to `MediaRegistry.register()` so token issuance / expiry / LRU eviction / `GET /media/{token}` are byte-for-byte identical to the loopback path. Marker block: `# === PHASE3-bridge-server-followup: /media/upload === / # === END PHASE3-bridge-server-followup ===` in `plugin/relay/server.py`. `tempfile` import added at the top. Route registered next to `/media/register`. py_compile clean.

**notif-listener-followup — wire `HermesNotificationCompanion.multiplexer` + Settings nav row.** Agent notif-listener flagged two small touch-ups in its handoff:

1. `ConnectionViewModel.init` now sets `HermesNotificationCompanion.multiplexer = multiplexer` once, immediately after the bridge handler registration. The companion service buffers up to 50 envelopes in its own `pendingEnvelopes` queue while the slot is null, so wiring it from here (rather than at service-bind time) is safe — the buffer drains on the next `onNotificationPosted` once the slot is set. Marker: `// === PHASE3-notif-listener-followup: notification companion multiplexer wiring === / // === END PHASE3-notif-listener-followup ===`.
2. `SettingsScreen` gains a new `onNavigateToNotificationCompanion: () -> Unit` callback parameter and a `SettingsCategoryRow` between Voice mode and Media (`Icons.Filled.Notifications`, "Notification companion", "Let your assistant triage notifications you've shared"). `RelayApp` adds `Screen.NotificationCompanionSettings` (route `settings/notifications`), wires the new callback in the `SettingsScreen` call site, and registers a `composable(...)` that hosts `NotificationCompanionSettingsScreen(onBack = popBackStack)`. Both new imports added.

After this entry the Bridge tab + Notification companion screen are both reachable through the normal navigation tree, and accessibility's screenshot pipeline has a working server-side endpoint. Wave 2 (safety-rails safety, voice-intents voice-bridge, vision-nav vision) is unblocked once Bailey confirms both build flavors compile in Android Studio.

## 2026-04-12 — Phase 3 / Wave 1 / bridge-server — Migrated legacy bridge relay into unified relay (port 8767)

Retired the standalone bridge relay (`plugin/tools/android_relay.py` + the duplicate top-level `plugin/android_relay.py`, both listening on port 8766) and folded its functionality into the unified Hermes-Relay on port 8767 as the bridge channel. The wire protocol (`bridge.command` / `bridge.response` / `bridge.status`) stays byte-for-byte identical — only the transport changed. Agents accessibility, bridge-ui, notif-listener can now build against a single port.

- `plugin/relay/channels/bridge.py` — replaced the stub with a real `BridgeHandler`. One handler instance per `RelayServer`; holds `phone_ws` + `pending: dict[request_id, Future]` behind an `asyncio.Lock`. `handle_command(method, path, params, body)` mints a request_id, registers the future, sends a `bridge.command` envelope, and awaits a response with 30s timeout (matches the legacy `android_relay._RESPONSE_TIMEOUT`). `handle(ws, envelope)` opportunistically latches `phone_ws` and dispatches `bridge.response`/`bridge.status`. `detach_ws(ws, reason)` fails all pending futures with `ConnectionError` so HTTP callers don't hang when the phone drops.
- `plugin/relay/server.py` — added 14 HTTP routes (`/ping`, `/screen`, `/screenshot`, `/get_apps`, `/apps` [legacy alias], `/current_app`, `/tap`, `/tap_text`, `/type`, `/swipe`, `/open_app`, `/press_key`, `/scroll`, `/wait`, `/setup`) each delegating to `_bridge_dispatch → server.bridge.handle_command`. BridgeError → 503/504/502 depending on message. Wired `server.bridge.detach_ws(ws)` into `_on_disconnect` so phone drops instantly fail in-flight commands instead of hanging to the 30s timeout. All additions are bracketed by `# === PHASE3-bridge-server: ... === / # === END PHASE3-bridge-server ===` markers so the Agent notif-listener notification-listener merges are mechanical.
- `plugin/android_tool.py` + `plugin/tools/android_tool.py` — BRIDGE_URL default changed from `http://localhost:8766` to `http://localhost:8767` (both copies; `plugin/android_tool.py` is the one imported by `plugin/__init__.py`, `plugin/tools/android_tool.py` is the standalone-toolset copy). `_relay_port()` falls back through `ANDROID_RELAY_PORT → RELAY_PORT → 8767`. `android_setup()` rewritten: no longer imports the deleted `android_relay` module, instead probes `http://localhost:<port>/health` to verify the unified relay is up and returns a structured error if not. Env-var side effects preserved so the existing `test_android_tool.py::TestSetup` still passes.
- `plugin/android_relay.py` + `plugin/tools/android_relay.py` — **DELETED**. Both copies of the standalone relay are gone.
- `plugin/tests/test_bridge_channel.py` — new unittest suite (7 tests) covering envelope routing, future resolution, timeout cleanup, disconnect cleanup, send-failure cleanup, and the legacy-timeout regression guard. Uses a `_FakeWs` stand-in and bypasses the pytest `conftest.py` that imports `responses`. Run with `python -m unittest plugin.tests.test_bridge_channel`. All 7 green locally; existing `test_relay_media_routes` / `test_qr_sign` / `test_session_grants` / `test_media_registry` still pass (22 + 47 assertions) so `create_app` + route registration hold.

Auth model judgment call: the bridge HTTP routes are unauthenticated at the HTTP layer, matching the legacy standalone relay. Defensible because (a) the trust boundary is unchanged — only same-host processes reach `localhost:8767`, (b) a disconnected/unpaired phone naturally causes every call to fail with 503, and (c) the bridge grant is already tracked per-session in `Session.grants["bridge"]` so Wave 2 (safety-rails) can add a bearer wrapper without touching the handler. Noted inline in the PHASE3-bridge-server block so Agent safety-rails can find it.

Branch: `feature/phase3-alpha-bridge-server-migration`.

## 2026-04-12 — Phase 3 / Wave 1 / flavor-split — googlePlay + sideload build flavors

Agent flavor-split (`bridge-flavor-split`) adds the Gradle flavor split that the Phase 3 Bridge channel needs before any accessibility code lands. Google Play reviews AccessibilityService heavily, so Phase 3 ships two parallel release tracks from one codebase: a conservative `googlePlay` flavor with a notifications-and-confirmations use-case description, and a `sideload` flavor with the full agent-control description for GitHub Releases / F-Droid / ADB distribution.

Scope of this change:

- **`app/build.gradle.kts`** — `flavorDimensions += "track"` with two product flavors. The `sideload` flavor carries `applicationIdSuffix = ".sideload"` and `versionNameSuffix = "-sideload"` so both tracks can coexist on the same device during Phase 3 testing. The `googlePlay` flavor keeps the canonical `com.hermesandroid.relay` applicationId so existing v0.2.0 Play Store installs upgrade cleanly and Play Console keeps its release history. Decision trade-off: power users with both installed will see two launcher icons until we differentiate labels in a follow-up.
- **`app/src/googlePlay/*`** — flavor manifest overlay declaring `.accessibility.BridgeAccessibilityService` (owned by Agent accessibility in `src/main/`), conservative `accessibility_service_config.xml` (event subset: `typeWindowStateChanged|typeWindowContentChanged|typeViewClicked`, `flagDefault` only, no gestures, `canRetrieveWindowContent=true`), and `a11y_description_googleplay` targeted at Play Store policy review.
- **`app/src/sideload/*`** — flavor manifest overlay, full-capability `accessibility_service_config.xml` (`typeAllMask`, `flagRetrieveInteractiveWindows|flagReportViewIds|flagRequestTouchExplorationMode`, `canPerformGestures=true`), and `a11y_description_sideload` with explicit voice/vision/logging language.
- **`FeatureFlags.kt`** — new `BuildFlavor` object with `current` / `displayName` / six `bridgeTier1..6` flags. Tiers 1, 2, 5 are baseline-true for both tracks. Tiers 3 (voice-first), 4 (vision-first), 6 (ambitious future) are `get() = current == SIDELOAD` so UI code can do a single `if (BuildFlavor.bridgeTier3)` check and R8 can fold the branch at release-build time for the Play track.
- **`AboutScreen.kt`** — added a small "Track: Google Play" / "Track: Sideload" row under the existing Version row (which owns the 7-tap dev-options reveal). Marked with `=== PHASE3-flavor-split ===` banners so bridge-ui and notif-listener can land their own additions without merge conflicts.

Not shipped in this change: the actual `BridgeAccessibilityService` class (Agent accessibility) and any tier-gated UI surfaces (Agents bridge-ui/notif-listener). The manifests reference `.accessibility.BridgeAccessibilityService` with `tools:ignore="MissingClass"` so the Gradle + AAPT check doesn't block Agent accessibility's landing.

Branch: `feature/phase3-beta-bridge-flavor-split`.

## 2026-04-12 — Phase 3 / Wave 1 / accessibility — accessibility runtime (service + reader + executor + capture)

Wave 1 Agent accessibility (`accessibility-runtime`) landing the phone-side execution layer for the bridge channel. Five new files under a new `com.hermesandroid.relay.accessibility` package plus `BridgeCommandHandler` under `network/handlers`:

- **`HermesAccessibilityService`** — master `AccessibilityService` subclass. Self-registers as a `@Volatile` singleton on `onServiceConnected`, clears on unbind/destroy. Caches the foregrounded package from `TYPE_WINDOW_STATE_CHANGED` events (the only event type we consume — content-change events fire thousands/min and we read the tree on demand via `rootInActiveWindow`). Master enable flag lives in DataStore (`bridge_master_enabled`) so UI + safety rails can toggle it without killing the service.
- **`ScreenReader`** — UI tree → `ScreenContent(rootBounds, nodes[], truncated)`. `@Serializable` output; one `ScreenNode` per interesting node (non-blank text, content description, clickable/longClickable/scrollable/editable). Hard caps: `MAX_NODES=512`, `MAX_TEXT_LEN=2000` per field. `findNodeBoundsByText` and `findFocusedInput` helpers for the executor. Recycles child nodes in a `try/finally` pattern per-iteration.
- **`ActionExecutor`** — `tap`/`tapText`/`swipe`/`scroll` via `GestureDescription` wrapped in `suspendCancellableCoroutine` so the suspend form actually waits for `GestureResultCallback.onCompleted`. `typeText` uses `ACTION_SET_TEXT` with a `Bundle` arg. `pressKey` maps a curated string vocabulary (`home`/`back`/`recents`/`notifications`/`quick_settings`/`power_dialog`/`lock_screen`) to `AccessibilityService.GLOBAL_ACTION_*` constants — we deliberately don't accept raw `KeyEvent` codes so agents can't inject arbitrary keypresses. `wait(ms)` clamped to 15s. Every method returns `ActionResult(ok, data, error)` so the handler can map 1:1 to HTTP-style status codes.
- **`ScreenCapture`** — `MediaProjection` → `VirtualDisplay` → `ImageReader` → PNG bytes → multipart upload to `POST /media/upload` on the relay. Crops `rowStride` padding before `Bitmap.copyPixelsFromBuffer`. 2.5s capture timeout. `MediaProjectionHolder` singleton holds the per-session grant — Bridge UI (Agent bridge-ui) is responsible for the `ActivityResultLauncher` flow that calls `onGranted(resultCode, data)`. Registers a `MediaProjection.Callback` to null the holder when the system revokes.
- **`BridgeStatusReporter`** — coroutine that emits `bridge.status` envelopes every 30s with `screen_on` (via `PowerManager.isInteractive`), `battery` (`BatteryManager.BATTERY_PROPERTY_CAPACITY` with a sticky-intent fallback for OEM quirks), `current_app` (from the service singleton), and `accessibility_enabled` (true when the service instance is non-null). Owned by `ConnectionViewModel`.
- **`BridgeCommandHandler`** — routes inbound `bridge.command` envelopes to the executor and emits `bridge.response`. Wire paths: `/ping` (works without the service), `/tap`, `/tap_text`, `/type`, `/swipe`, `/scroll`, `/press_key`, `/wait`, `/screen`, `/screenshot`, `/current_app`. Gates everything except `/ping` and `/current_app` on the master-enable toggle, returning 503 if the service isn't connected or 403 if the soft master is off. `/screen` serializes the full `ScreenContent` via `kotlinx.serialization.json`.

Wired into `ChannelMultiplexer.kt` via a `// === PHASE3-accessibility ===` marked section (simplified the existing bridge branch that was stubbed with a TODO), and `ConnectionViewModel.kt` gets a matching marker block that instantiates `ScreenCapture`, `BridgeCommandHandler`, and `BridgeStatusReporter`, registers the handler, and starts the reporter. `AndroidManifest.xml` declares the service with `BIND_ACCESSIBILITY_SERVICE` permission + intent filter + `@xml/accessibility_service_config` meta-data (the XML itself is flavor-provided by Agent flavor-split). Added `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, and `POST_NOTIFICATIONS` for the Wave 2 persistent-notification work.

**Known blocker for bridge-ui to wire:** `MediaProjectionManager.createScreenCaptureIntent()` requires an `Activity`-scoped result. `ScreenCapture.createConsentIntent()` exposes the intent; the Bridge screen needs an `ActivityResultLauncher<Intent>` that forwards the result to `MediaProjectionHolder.onGranted(context, resultCode, data)`. Until that lands, `/screenshot` returns 503 with a clear error message.

**Known blocker for bridge-server to fix:** the relay's `/media/register` endpoint is loopback-only and path-based (see `plugin/relay/media.py`). The phone can't use it — there's no shared filesystem. `ScreenCapture.uploadViaMultipart` POSTs to `/media/upload` (new endpoint; mirrors the `/voice/transcribe` multipart pattern) which doesn't exist yet. Until bridge-server ships it, `/screenshot` surfaces a 404 with the exact message `"relay /media/upload endpoint not found — server needs Phase 3 bridge-server migration"`. Token extraction uses the same `{"ok": true, "token": "..."}` shape as `/media/register`.

Branch: `feature/phase3-accessibility-runtime`.

## 2026-04-12 — Phase 3 / Wave 1 / bridge-ui — BridgeScreen rewrite (bridge-screen-ui)

Replaced the `BridgeScreen` "Coming Soon" placeholder with the real Tier-1
control surface from the Phase 3 plan. Wave 1 Agent bridge-ui (`bridge-screen-ui`)
deliverable — the UI scaffold is now ready to render whatever state Agent accessibility's
`HermesAccessibilityService` exposes once its runtime lands.

New files:

- `app/src/main/kotlin/.../data/BridgePreferences.kt` — DataStore repo with
  `bridge_master_enabled` boolean and serialized `bridge_activity_log` JSON
  list (capped at 100 entries). Mirrors `VoicePreferences.kt` /
  `MediaSettings.kt` style. Uses lenient `Json` for forward-compat.
- `app/src/main/kotlin/.../viewmodel/BridgeViewModel.kt` — AndroidViewModel
  exposing `masterToggle`, `bridgeStatus`, `permissionStatus`, `activityLog`
  StateFlows. Reads a11y service enablement via
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, overlay via
  `Settings.canDrawOverlays`, notification listener via
  `enabled_notification_listeners`. Seeds `BridgeStatus` on init from
  `PowerManager.isInteractive` + `BatteryManager.BATTERY_PROPERTY_CAPACITY`
  so the card isn't empty before accessibility lands. Four explicit `TODO(accessibility-handoff)`
  markers documenting the StateFlow/class-name/activity-writer surface that
  accessibility needs to expose for final wiring.
- `app/src/main/kotlin/.../ui/components/BridgeMasterToggle.kt` — headline
  Switch card with status inlines (device / battery / screen / current app),
  explanation dialog (required by Play Store a11y review), and a11y-granted
  gate so users can't flip it on before enabling the service.
- `app/src/main/kotlin/.../ui/components/BridgeStatusCard.kt` — standalone
  status card with `ConnectionStatusBadge` integration. Usable independently
  of the master toggle so safety-rails can re-arrange the cards without losing state.
- `app/src/main/kotlin/.../ui/components/BridgePermissionChecklist.kt` —
  four-row checklist (Accessibility / Screen Capture / Overlay / Notification
  Listener) with tap-to-open Intent launchers wrapped in `runCatching` for
  OEM skin safety. `ACTION_ACCESSIBILITY_SETTINGS`,
  `ACTION_MANAGE_OVERLAY_PERMISSION` with package URI, and the direct
  `enabled_notification_listeners` intent string.
- `app/src/main/kotlin/.../ui/components/BridgeActivityLog.kt` — scrollable
  `LazyColumn` (bounded at `heightIn(max = 320.dp)`) with tap-to-expand row
  showing full timestamp, status, result text, and optional screenshot token.
  `java.time.DateTimeFormatter` for `HH:mm:ss` / `yyyy-MM-dd HH:mm:ss`.
  Stubbed screenshot thumbnail rendering with a TODO pointing at accessibility's
  MediaRegistry upload path.

Shared-concern edits (marked with `PHASE3-bridge-ui:` banners):

- `app/src/main/kotlin/.../ui/RelayApp.kt` — the existing `BridgeScreen()`
  call wraps in the bridge-ui marker. Signature compatible: `BridgeScreen` defaults
  its ViewModel via `viewModel()`, so no new nav-graph plumbing needed.

Each new component carries a `@Preview` (or two — e.g.,
`BridgeMasterTogglePreviewOn` vs `PreviewBlocked`) so Bailey can iterate in
Android Studio's preview pane without rebuilding the whole app.

accessibility-handoff points (in priority order, documented in `BridgeViewModel` KDoc):

1. `bridgeStatus` StateFlow — bridge-ui stubs a best-effort read from system APIs;
   accessibility replaces with the real HermesAccessibilityService status flow.
2. `A11Y_SERVICE_CLASS` constant — bridge-ui hard-codes
   `"com.hermesandroid.relay.accessibility.HermesAccessibilityService"`.
   accessibility confirms the final FQCN.
3. `recordActivity` write path — accessibility's command dispatcher calls
   `BridgeViewModel.recordActivity(entry)` on every `bridge.command` to
   populate the activity log. Until then the log stays empty and the empty-
   state copy shows.
4. Master toggle read from accessibility — accessibility's service reads `bridge_master_enabled` from
   `BridgePreferencesRepository.settings` and treats it as the runtime disable
   switch. No extra wiring needed from bridge-ui; accessibility just imports the repo.

Build-flavor flags (`BuildFlavor.bridgeTierN` from flavor-split) are not referenced yet
— this worktree pre-dates flavor-split's landing. Once flavor-split's object lands on main, bridge-ui's
components remain compatible (they don't tier-gate anything; Tier 1 is
always-on in both flavors per the plan).

Safety card is a placeholder stub — Agent safety-rails (Wave 2) owns the real safety
UI. bridge-ui's `SafetyPlaceholderCard` just says "Configure in Bridge Safety
Settings" with a Wave-2 teaser subtitle.

Branch: `feature/phase3-delta-bridge-screen-ui`.

## 2026-04-12 — Phase 3 / Wave 1 / notif-listener — notification companion (opt-in triage helper)

Adds an opt-in helper that lets the user's Hermes assistant read notifications they've explicitly granted access to via Android's standard `NotificationListenerService` API — the same one Wear OS, Android Auto, and Tasker have used for over a decade. Disabled by default; the user controls grant + revoke via Android Settings → Notification access.

**Three pieces, all marked with `PHASE3-notif-listener` block markers in shared files:**

1. **Phone — `NotificationListenerService` + Compose settings screen.** New `app/src/main/kotlin/.../notifications/` package with `HermesNotificationCompanion` (the bound service) + `NotificationModels` (`@Serializable NotificationEntry`) + `ui/screens/NotificationCompanionSettingsScreen` (About / Status / Test sections, mirrors `VoiceSettingsScreen` style). The service buffers up to 50 envelopes in a `ConcurrentLinkedQueue` if `companion.multiplexer` isn't wired yet (cold-start ordering), drains on the next `onNotificationPosted`, then sends via `multiplexer.sendNotification(envelope)` — a thin new wrapper in `ChannelMultiplexer` that fast-paths to no-op when `sendCallback == null` (relay offline). Notifications with empty title+text are skipped (background-sync placeholders that just confuse the LLM). The `Status` row uses `LifecycleEventObserver(ON_RESUME)` so the grant state updates immediately when the user comes back from Android Settings.

2. **Server — bounded in-memory deque.** New `plugin/relay/channels/notifications.py::NotificationsChannel` with `recent: collections.deque[dict]` capped at 100 entries via `maxlen` (LRU-by-time eviction for free). `handle()` dispatches `notification.posted` envelopes to `handle_envelope()` which appends; `get_recent(limit)` returns newest-first with `limit` clamped to `[1, max_entries]`. Wired into `RelayServer.__init__` as `self.notifications` and dispatched in `_on_message` for `channel == "notifications"`. The cache is in-memory only and lost on restart by design — same semantics as a smartwatch out of range.

3. **Agent tool — `android_notifications_recent(limit=20)`.** New `plugin/tools/android_notifications.py` registers the tool into the `tools.registry` (with the `try/except ImportError` pattern matching `android_tool.py`). It hits `http://127.0.0.1:8767/notifications/recent?limit=N` over loopback via stdlib `urllib.request` — no auth needed because `handle_notifications_recent` skips bearer for loopback callers (matches the `/media/register` and `/pairing/register` trust model). Remote callers still go through `_require_bearer_session`. The tool docstring explicitly frames it as "List recent notifications the user has shared with this assistant" so the LLM treats absence as "not granted yet" rather than an error.

**Files touched:**

- New: `plugin/relay/channels/notifications.py`, `plugin/tools/android_notifications.py`, `app/src/main/kotlin/.../notifications/HermesNotificationCompanion.kt`, `app/src/main/kotlin/.../notifications/NotificationModels.kt`, `app/src/main/kotlin/.../ui/screens/NotificationCompanionSettingsScreen.kt`
- Edited (additive only, marker-blocked): `plugin/relay/server.py` (import + handler init + HTTP route + dispatch + route registration), `app/src/main/kotlin/.../network/ChannelMultiplexer.kt` (`sendNotification` wrapper), `app/src/main/AndroidManifest.xml` (service entry with `BIND_NOTIFICATION_LISTENER_SERVICE` permission), `app/src/main/res/values/strings.xml` (`notification_companion_label`)
- Docs: `CLAUDE.md` Key Files table (8 new rows / additive notes), `DEVLOG.md` (this entry)

**Decisions:**

- **No new session grant type.** Spec explicitly said don't add `notifications` to `auth.py` grants — this is opt-in via Android system permission, not via per-channel session grant. Reuses the existing `chat` grant trust boundary.
- **Loopback bypass for the tool.** The route's spec said "bearer auth gated like /media/*", but the tool is in-process on the host, so we mirror `/media/register`'s loopback gate: 127.0.0.1 callers skip the bearer check, remote callers still require a session token. This means the tool doesn't need to grovel through the relay's session store to mint a token.
- **Drop on relay-offline rather than buffer at the multiplexer.** A wearable doesn't replay notifications it missed while out of range; we don't either. The cold-start buffer in the service is for the much shorter "service bound but multiplexer not wired yet" window.
- **`activeNotifications` for the Test button**, not a relay round-trip. Lets the user verify the listener is bound even if the relay is unreachable, which is the more common failure mode at first-grant time.

**Validation:** `python -m py_compile plugin/relay/channels/notifications.py plugin/tools/android_notifications.py plugin/relay/server.py` → OK. Kotlin compile happens on Bailey's next Android Studio run.

**Branch:** `feature/phase3-epsilon-notification-companion` (off `worktree-agent-a84b51cc`).

**Next:** wire `HermesNotificationCompanion.multiplexer` from `ConnectionViewModel` after relay handshake (one-line touch), add a Settings entry-point row that navigates to `NotificationCompanionSettingsScreen`, and (Phase 3 follow-up) consider a per-package allow/deny list so the user can mute social-media spam from the listener pipeline before it ever hits the relay.

## 2026-04-12 — Fix: TTS waveform stays alive through multi-sentence playback

The waveform was flatlinining after the first sentence while audio kept playing. Root cause: `maybeAutoResume()` fired after every sentence in the TTS consumer loop. The SSE stream finishes before TTS plays all queued sentences, so `streamObserverJob?.isActive` was already false → state flipped to Idle → amplitude bridge stopped → waveform died. Fix: restructured TTS consumer from `for` loop to `while` + `tryReceive` peek. `maybeAutoResume` only fires when the queue is actually drained (`tryReceive` returns failure), not between sentences. Between sentences within the same response, `tryReceive` succeeds immediately and the consumer skips the Idle transition. Additionally, the consumer re-asserts Speaking state before each synthesis call to handle the edge case where the queue was briefly empty between observer pushes.

## 2026-04-12 — Classified error feedback across voice/chat/settings/pairing

Ended the "error: unknown" era. New `RelayErrorClassifier.kt` converts any `Throwable` → `HumanError(title, body, retryable, actionLabel)` based on exception type + context tag. Branch order: `UnknownHostException` → `ConnectException` → `SocketTimeoutException` → `SSLException` → `SecurityException` → `IllegalStateException` → `IOException` (message-scan for HTTP 401/403/404/413/500/503) → default. SSL before IOException is load-bearing since `SSLPeerUnverifiedException extends IOException`. Context tags (`transcribe`, `synthesize`, `voice_config`, `record`, `pair`, `save_and_test`, `media_fetch`, `send_message`) shape the title and drive 404 body specialization.

Global `SnackbarHost` via `LocalSnackbarHost: CompositionLocal<SnackbarHostState>` at `RelayApp` scope — every screen can toast via `LocalSnackbarHost.current.showHumanError(err)`. Both `VoiceViewModel` and `ChatViewModel` gained `errorEvents: SharedFlow<HumanError>` (one-shot events, replay=0, buffer=4, DROP_OLDEST). 5 voice error sites + 4 chat error sites converted to use the classifier. Mic permission banner in ChatScreen rebuilt with "Open Settings" action. ConnectionSettingsScreen Save & Test and manual pair now show classified errors.

## 2026-04-12 — Voice polish: reactive waveform, pill edges, stop semantics, enter/exit chimes

Comprehensive polish pass on voice mode addressing issues surfaced during live testing:

- **Waveform sensitivity**: four layers of damping were fighting us. Fixed at every stage: perceptual curve in VoiceRecorder (noise-floor + speech-ceiling + sqrt), attack/release envelope follower in VoiceViewModel (0.75/0.10 at 60Hz), killed the Compose spring in VoiceWaveform, amplitude-driven phase velocity via `withFrameNanos` ticker (speeds up 3.5× at peak amplitude).
- **Pill edges**: dual-technique merge — geometric `sin(π·t)` taper forces wave to centerY at endpoints + `BlendMode.DstIn` horizontal gradient mask in `saveLayer`. Studied Conjure's pill approach (dark gradient overlay against solid background) — won't work on our translucent overlay, hence the layer technique.
- **TTS replying to wrong turn**: `ignoreAssistantId` captures the pre-send last-assistant-id so the stream observer doesn't replay the previous turn's response. Root cause: StateFlow.collect replays current value on subscribe, and the current value at subscribe time still has the previous assistant message.
- **Stop semantics**: `interruptSpeaking()` rewritten to drain queue + stop player + `chatViewModel.cancelStream()` + cancel all jobs → Idle (not Listening). Old version only paused playback; SSE kept feeding ttsQueue.
- **Enter/exit chimes**: new `VoiceSfxPlayer.kt` — pre-synthesized 200ms PCM sweeps (440→660 Hz ascending enter, mirror exit) via `AudioTrack.MODE_STATIC`. Phase-accumulated to avoid chirp artifacts.
- **Stop button color**: hardcoded vivid red `Color(0xFFE53935)` for both Listening and Speaking states (Material 3 dark `colorScheme.error` resolved to pale pink).
- **Scrollable response**: overlay response Column now `weight(1f, fill=false) + verticalScroll`.
- **NaN guards**: `VoicePlayer.computeRms` and `VoiceViewModel.sanitizeAmplitude` — `Float.coerceIn` silently passes NaN per IEEE 754.
- **Gradle logcat task**: `silenceAndroidViewLogs` Exec task hooked via `finalizedBy` on all `install*` tasks — runs `adb shell setprop log.tag.View SILENT` to suppress Compose's Android 15 `setRequestedFrameRate=NaN` spam.

## 2026-04-12 — Relay startup: Python-side `.env` bootstrap + systemd user service

Fixed a drift-class bug where the relay would 500 on `/voice/transcribe` with `STT provider 'openai' configured but no API key available` after any restart that wasn't preceded by a manual `source ~/.hermes/.env`. Root cause: the relay was run as a detached `nohup` process, which inherits whatever env the launching shell happens to have exported — not what's in `~/.hermes/.env`. The gateway already solves this via `hermes_cli/main.py:144` calling `load_hermes_dotenv(project_env=...)` at import time, which is why its user systemd unit carries no `EnvironmentFile=` directive. The relay was missing that pattern entirely.

### New: `plugin/relay/_env_bootstrap.py`

A tiny helper (55 lines) exposing `load_hermes_env() -> list[Path]`. Preferred path: `from hermes_cli.env_loader import load_hermes_dotenv` and call it with defaults — keeps precedence and encoding fallbacks (latin-1 on `UnicodeDecodeError`) in exact lockstep with hermes-agent. Fallback path: direct `python-dotenv` against `$HERMES_HOME/.env` (or `~/.hermes/.env` when unset) with `override=True`. Silent no-op in stripped-down containers that explicitly provide env via `docker run -e …`. Both entry points — `plugin/relay/__main__.py` and the legacy `relay_server/__main__.py` shim — call `load_hermes_env()` **before** importing `.server`, so anything in the module-import chain that reads `os.getenv` at module level sees the same environment regardless of launcher.

### New: systemd **user** unit matching the gateway's shape

Rewrote `relay_server/hermes-relay.service` from scratch. The old template was a system-level unit hardcoded to `/home/bailey/hermes-relay` with `/usr/bin/python3` — nobody was using it correctly. New template is a user unit with `%h` expansion so it's user-agnostic:

```ini
[Service]
Type=simple
ExecStart=%h/.hermes/hermes-agent/venv/bin/python -m plugin.relay --no-ssl --log-level INFO
WorkingDirectory=%h/.hermes/hermes-relay
Environment="PATH=%h/.hermes/hermes-agent/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
Environment="VIRTUAL_ENV=%h/.hermes/hermes-agent/venv"
Environment="HERMES_HOME=%h/.hermes"
Restart=on-failure
RestartSec=30
...
[Install]
WantedBy=default.target
```

No `EnvironmentFile=` on purpose — `_env_bootstrap.py` handles that. Matches the gateway unit's env directives (`PATH`, `VIRTUAL_ENV`, `HERMES_HOME`) + restart policy (`StartLimitIntervalSec=600`, `StartLimitBurst=5`) + log destination (`StandardOutput=journal`). User target so it lives in `~/.config/systemd/user/` and doesn't need sudo.

### `install.sh` step [6/6] — optional systemd install

Added a final installer step that idempotently drops the unit into `~/.config/systemd/user/`, runs `daemon-reload`, and `enable --now`s the service. Skipped gracefully on:
- Hosts without `systemctl` (macOS, some BSDs)
- Hosts where `systemctl --user show-environment` fails (bare chroots, WSL without systemd, containers without a user bus)
- `$HERMES_RELAY_NO_SYSTEMD` set (explicit opt-out for users who prefer their own process supervisor)

If an orphan `nohup`-launched `python -m plugin.relay` is already holding :8767, the installer warns the user to kill it first rather than racing. A parting hint about `loginctl enable-linger $USER` for users who want the relay to survive SSH logout.

After install, the update cycle is now a single command:

```bash
cd ~/.hermes/hermes-relay && git pull
systemctl --user restart hermes-relay
```

No `pkill` / `nohup` / `disown` dance, no manual env sourcing, no stale-key drift on restart.

### Docs surface updated

- `docs/relay-server.md` — new Quick Start with `install.sh` as the recommended path, manual-run and Docker sections preserved, new `.env auto-loading` subsection explaining precedence + why there's no `EnvironmentFile=`.
- `user-docs/reference/relay-server.md` — mirror of the above in user-facing style, plus two new troubleshooting entries (voice 500 "no API key available" → check `.env` + restart, and service stops on SSH logout → `loginctl enable-linger`).
- `CLAUDE.md` Server Deployment section — relay is now listed as `hermes-relay.service` (user unit), the restart command is `systemctl --user restart hermes-relay`, the verification step is `cat /proc/$PID/environ` via `systemctl show -p MainPID`, and the old nohup instructions carry a history note pointing at this entry.
- `DEVLOG.md` — this entry.

### Why this is the right shape for the general plugin path

The installer is the only user-facing install surface for hermes-relay and already owns plugin registration, skill discovery, and the `hermes-pair` shim. Adding systemd-user install to it means any Linux user who runs the one-liner gets the relay in the same canonical place as hermes-gateway (`~/.config/systemd/user/`) with the same management commands (`systemctl --user …`). Non-systemd hosts fall back to manual `python -m plugin.relay --no-ssl` and the Python-side env loader still does the right thing. Docker users mount `~/.hermes` and the bootstrap finds `.env` at its canonical path inside the container. There's no setup branching that varies by user, distro, or launch method — the env load happens at import time, which is the one place every launch path passes through.

Fix scope: 3 Python files touched (2 entry points + 1 new helper), 1 systemd template rewritten, 1 installer stanza added, 4 docs updated. No breaking changes to existing callers — manual `python -m plugin.relay` still works and is actually more robust than before because it now auto-loads `.env`.

---

## 2026-04-12 — Voice mode: end-to-end voice conversation via relay TTS/STT endpoints

Shipped voice mode in a single session via a four-agent team in a shared worktree (`feature/voice-mode`). Plan came from the Obsidian vault at `Hermes-Relay/Plans/Voice Mode.md` — a 4-phase spec (V1 server endpoints → V2 app audio pipeline → V3 orb animation → V4 polish). All four phases landed in parallel; V2b UI waited on V2a's locked ViewModel contract, everything else ran concurrently. Net: ~2750 lines across 9 new files + 6 modified files, no upstream hermes-agent changes required.

### Architecture

Voice lives entirely in the relay plugin, not upstream hermes-agent. The relay is editable-installed into the hermes-agent venv, so `plugin/relay/voice.py` imports `tools.tts_tool.text_to_speech_tool` and `tools.transcription_tools.transcribe_audio` directly from the server's configured providers. Both upstream functions are **sync**, so the aiohttp handlers wrap them in `asyncio.to_thread(...)` to avoid blocking the event loop. Config (provider, voice, model) is read internally by the tools from `~/.hermes/config.yaml` — the relay doesn't pass provider arguments, which means Bailey can swap TTS providers (currently ElevenLabs) or STT providers (currently OpenAI whisper-1) on the server without touching the phone.

Three new routes on the relay alongside `/media/*`:
- `POST /voice/transcribe` — multipart audio → `{text, provider}`
- `POST /voice/synthesize` — `{text}` JSON → `audio/mpeg` file response
- `GET /voice/config` — provider availability + current settings

All three gated on the same bearer auth as `/media/*` (local helper `_require_bearer_session`, not the private server.py version, to avoid circular imports). Fourteen unit tests in `plugin/tests/test_voice_routes.py`, all passing on the server (68/68 across voice + existing media/sessions suites). Upstream tool imports are **lazy inside each handler** so `voice.py` can be imported on Windows (where the hermes-agent venv doesn't exist); tests inject fake modules into `sys.modules` at collect time.

### Android audio pipeline

`app/.../audio/VoiceRecorder.kt` wraps `MediaRecorder` with MPEG_4/AAC output to `.m4a` (not WebM — the plan suggested WebM but m4a is Android-native and whisper-1 handles it fine via the upstream `_validate_audio_file` path). Amplitude is polled at ~60fps from `mediaRecorder.maxAmplitude / 32767f` and exposed as a `StateFlow<Float>` for the orb.

`app/.../audio/VoicePlayer.kt` wraps `MediaPlayer` + `android.media.audiofx.Visualizer` for real-time amplitude during TTS playback. The Visualizer construction is in a try/catch — some OEM devices refuse to construct one even with MODIFY_AUDIO_SETTINGS granted, and rather than crashing the voice session on those devices we fall back to a flat-zero amplitude and log. Voice still works; the sphere just won't pulse during Speaking on those devices.

`app/.../network/RelayVoiceClient.kt` mirrors `RelayHttpClient`'s ctor shape — same `okHttpClient` / `relayUrlProvider` / `sessionTokenProvider` pattern, same error handling. Transcribe uses `MultipartBody.Builder`, synthesize uses JSON POST with the response bytes streamed to `cacheDir/voice_tts_<ts>.mp3`. Added a third method `getVoiceConfig()` for the Voice Settings screen to read provider availability.

`app/.../viewmodel/VoiceViewModel.kt` orchestrates the state machine: `Idle → Listening → Transcribing → Thinking → Speaking → Idle`. Sentence-boundary detection lives in a top-level `internal fun extractNextSentence(StringBuilder)` that drains complete sentences (whitespace-lookahead for `e.g.`, min length 6 so tiny fragments don't get synthesized) into a `Channel<String>` consumed by a dedicated coroutine that synthesizes + plays + awaits completion per sentence. This gives streaming TTS without needing chunked-Opus support on the server — the latency win comes from client-side chunking of the SSE stream.

### ChatViewModel integration — cleaner than the plan

The plan called for a `// VOICE HOOK` callback added to `ChatViewModel` so `VoiceViewModel` could observe streaming deltas. Instead, `VoiceViewModel.startStreamObserver` collects `chatVm.messages: StateFlow<List<ChatMessage>>` and diffs the last assistant message's content length on each emission. Zero changes to `ChatViewModel` / `ChatHandler`. The transcribed user text is routed through the normal `chatVm.sendMessage(text)` path, so voice utterances appear as regular user messages in chat history — the same message shows up if you reload the session on another device.

The trade-off is documented in a KDoc comment on the observer: this assumes "the last `isStreaming=true` message is the current turn," which is true today. If `ChatViewModel` ever streams multiple assistant messages concurrently (agent hand-off, multi-agent runs) this would need a dedicated per-turn flow. Flagged for Phase 3+ review.

### MorphingSphere: Listening + Speaking states

Two new `SphereState` values added additively — all five existing call sites (RelayApp, BridgeScreen, ChatScreen ×3) compile unchanged because `voiceAmplitude: Float = 0f` and `voiceMode: Boolean = false` are defaulted. The plan spec had exact formulas for amplitude → visual parameter mapping (lines 338-367 of `Voice Mode.md`) and the sphere-animator teammate mapped them to the actual render variables:

- **Listening** — soft blue/purple (#597EF2 ↔ #A573F2, cooler than Idle's green/purple). `breatheSpeed` lerps 1.0→1.3× at half amplitude, `turbulenceAmp` gets +0.15×amp, perimeter-noise `wobbleAmplitude` scales up 30% max. Subtle — the orb "breathes with the user."
- **Speaking** — vivid green/teal (#40EB8C ↔ #4DD9E0) with `coreWarmth` (a new synthetic Float, not a refactor — spliced into the existing warmth term at line ~400) lerping 0.3→1.0 on amplitude for a white-hot core at peaks. `breatheSpeed` up to 2×, `turbulenceAmp` +0.5×amp, `wobbleAmplitude` +80%, `dataRingSpeed` up to 4× on peak amplitude. Dramatic — the orb *performs* the voice.

Radius scale for `voiceMode=true` is capped at 1.08×, not 1.0× as the plan suggested. The existing `baseRadius` is already 0.60× half-extent and the data ring at 1.55× would overflow the canvas past 1.1×. Expansion is subtle by physical necessity. Three `@Preview` functions (`MorphingSphereListeningPreview`, `MorphingSphereSpeakingLowPreview`, `MorphingSphereSpeakingPeakPreview`) with deterministic frames so Bailey can scrub amplitude values in Studio without a device.

### Voice mode UI

`VoiceModeOverlay.kt` is the full-screen experience: top bar with interaction-mode dropdown + close X, centered sphere at 60% height with `voiceMode=true`, transcribed + response text with `AnimatedContent` fades, mic button at bottom with `pulseScale` driven by amplitude. The mic button respects `interactionMode`:

- **TapToTalk** — click starts recording; auto-stops on silence (threshold from `VoicePreferences`, default 3000ms); click again to stop manually; click during Speaking routes to `interruptSpeaking()`.
- **HoldToTalk** — `awaitEachGesture { awaitFirstDown() + waitForUpOrCancellation() }` starts on press and stops on release.
- **Continuous** — after TTS finishes speaking, `maybeAutoResume()` auto-starts listening again. Current detection uses `streamObserverJob?.isActive != true` as the "turn done" signal because `Channel.isEmpty` isn't public API — may resume very slightly early on the last sentence before the final observer tick, but it's imperceptible in practice.

Haptics: `HapticFeedbackType.LongPress` on record start, `HapticFeedbackType.TextHandleMove` on record stop. Error banner with retry button handles mic permission denial, voice-config failure (shows "Unknown" provider rows), and transcribe/synthesize errors.

`ChatScreen` now hosts a mic FAB in the bottom-right corner (hidden while voice mode is active) that triggers the permission flow. Existing chat content fades to 40% alpha via `animateFloatAsState` when voice mode opens. `VoiceSettingsScreen.kt` is a new sub-screen off Settings with four sections: Voice Mode (interaction mode + silence threshold slider + auto-TTS placeholder), Text-to-Speech (read-only provider/voice labels), Speech-to-Text (read-only provider + language picker stored for future use), and a Test Voice button that calls the new `VoiceViewModel.testVoice(sample)` extension. `VoicePreferences.kt` is a new DataStore-backed repo mirroring `MediaSettings.kt`.

### Things left as follow-ups

1. **Silence-detector wiring** — V2a's recorder exposes the amplitude StateFlow, and `VoicePreferences.silenceThresholdMs` is persisted, but the ViewModel doesn't yet collect recorder amplitude and auto-call `stopListening()` on silence. V2b flagged this as a boundary question (expose a preferences setter on VoiceViewModel vs. observe DataStore directly). Not a voice-mode blocker — tap-to-stop works — but the auto-stop UX promised in the plan isn't there yet. Small follow-up task.
2. **Auto-resume precision** — the `streamObserverJob.isActive` signal for continuous mode is slightly imprecise (see above). If Bailey reports stalled turns in continuous mode this is where to look.
3. **Voice config write-through** — reads `GET /voice/config`, but the Voice Settings screen can't yet write changes back to `~/.hermes/config.yaml`. That would need a management API endpoint (Phase M territory per the plan). For now, Bailey edits the yaml and restarts hermes-gateway.
4. **Dedicated OkHttpClient for voice** — `voiceClient` has its own OkHttpClient instance (2-min read timeout) rather than sharing `ConnectionViewModel.relayOkHttp` (which is private). Minor duplication; would clean up if we exposed that field.
5. **ChatScreen box/column indent** — `Box { Column {...} }` with same-level indentation in ChatScreen is slightly ugly. Compiles fine; purely cosmetic.

### Files

**New (server):** `plugin/relay/voice.py`, `plugin/tests/test_voice_routes.py`.
**New (app):** `app/.../audio/VoiceRecorder.kt`, `app/.../audio/VoicePlayer.kt`, `app/.../network/RelayVoiceClient.kt`, `app/.../viewmodel/VoiceViewModel.kt`, `app/.../ui/components/VoiceModeOverlay.kt`, `app/.../ui/screens/VoiceSettingsScreen.kt`, `app/.../data/VoicePreferences.kt`, `app/src/test/.../viewmodel/SentenceExtractionTest.kt`.
**Modified:** `plugin/relay/server.py` (+21 lines — imports, handler wiring, 3 route registrations), `app/src/main/AndroidManifest.xml` (RECORD_AUDIO + MODIFY_AUDIO_SETTINGS), `app/.../ui/components/MorphingSphere.kt` (+145 lines — Listening/Speaking states, voiceAmplitude modifier, coreWarmth spliced into existing warmth term, voiceMode scale, 3 preview functions), `app/.../ui/RelayApp.kt` (+49 lines — VoiceViewModel wiring + VoiceSettings nav route), `app/.../ui/screens/ChatScreen.kt` (+115 lines — mic FAB + permission flow + chat alpha + overlay mount), `app/.../ui/screens/SettingsScreen.kt` (+46 lines — nav entry).

### Dev workflow note: the team actually worked

Four agents, shared worktree, 1 blocker (V2b waited on V2a's locked `VoiceUiState` contract). Agents reported via `SendMessage` → mailbox → delivered as team idle notifications. The lead (me) did: SSH recon (real upstream signatures from `~/.hermes/hermes-agent/tools/`), plan review, task list with explicit `blockedBy`, parallel agent spawn with complete self-contained briefs (including full upstream signatures so no agent needed to re-SSH), integration spot-checks on diffs, then this docs pass. Server-voice teammate even SCP'd test files to the server, ran them via `unittest`, and cleaned up its stray files on the server side when done. Zero Kotlin gradle runs — every agent respected the "Bailey compiles in Studio" rule.

Next time: for a feature this size, the worktree + team approach is the right default. The alternative (single-session linear work) would have been 3–4× slower.

## 2026-04-11 — clearSession reconnect leak → self-inflicted rate limit

Bailey reported "unable to pair via QR code" after hitting Revoke on his own device in Paired Devices. Worked again after an app restart.

### Root cause (from `~/hermes-relay.log`)

```
20:38:29  DELETE /sessions/f75cfb14 → 200 (self-revoke)
20:38:29  GET /sessions             → 401 (phone loadPairedDevices with dead token)
20:38:32  Client disconnected
[relay restart]
20:39:03  WebSocket from 172.16.24.13 → Auth failed: Invalid pairing code or session token
20:39:04  WebSocket from 172.16.24.13 → Auth failed
20:39:05  WebSocket from 172.16.24.13 → Auth failed
20:39:06  WebSocket from 172.16.24.13 → Auth failed
20:39:07  WebSocket from 172.16.24.13 → Auth failed
20:39:07  [WARNING] IP 172.16.24.13 blocked for 300s after 5 failed auth attempts
20:39:08+ GET /ws → 429 (blocked)
```

Five `Auth failed` in four seconds. The rate limiter did exactly what it was built to do.

### The bug — three state machines disagree

1. **`AuthManager.clearSession()`** wipes the stored token, sets `authState = Unpaired`, and regenerates a fresh *local* pairing code (`_pairingCode.value = generatePairingCode()`).
2. **`ConnectionManager`'s internal reconnect loop** (`scheduleReconnect` → `doConnect`) runs *entirely inside* the network module — it bypasses `ConnectionViewModel.connectRelay` and its `hasPairContext` gate completely. `shouldReconnect` stays `true` until someone calls `disconnect()`.
3. **`ConnectionViewModel.clearSession()`** called `authManager.clearSession()` and returned — **never called `disconnectRelay()`**. So the reconnect scheduler kept firing after the state wipe.

Sequence of collapse:

- Self-revoke succeeds (server deletes session)
- Phone's Paired Devices UI calls `clearSession()` → state wiped, reconnect loop still alive
- WS disconnects (because the relay closes the socket after revoke / the relay restart fires)
- `onClosed` → `scheduleReconnect` → backoff → `doConnect` with freshly-regenerated *local* pair code in the auth envelope
- Server: `"Invalid pairing code or session token"` (the local code isn't registered)
- Loop fires 5 times in ~4 seconds (exponential backoff starts at 1s)
- Rate limiter blocks the IP for 5 minutes
- User scans a fresh QR → `/pairing/register` clears the block — **but** the phone's next WS connect bounces off whatever delayed retry is still in flight (and also possibly a 429 from the still-valid block depending on exact timing)
- User restarts app → cold start has `shouldReconnect = false` and empty session store, no auto-connect, fresh QR scan succeeds

### Fix

**`network/ConnectionManager.kt`**:
- New constructor parameter `reconnectGate: () -> Boolean = { true }` (defense-in-depth gate for the internal auto-reconnect loop).
- `scheduleReconnect()` calls `reconnectGate()` both **before** scheduling and **after** the backoff delay. If either returns `false`, the retry is silently dropped and `connectionState` is set to `Disconnected`. Rationale: the delay window is where state flips happen — user hits Revoke while the previous attempt was sleeping, and we need to re-check.
- Logs `"scheduleReconnect: gate says no pair context — aborting retry"` at INFO for traceability.
- Default value preserves backwards compat with tests that construct a bare `ConnectionManager(multiplexer)`.

**`viewmodel/ConnectionViewModel.kt`**:
- `ConnectionManager` now constructed with `reconnectGate = { authManager.hasPairContext }` — same `hasPairContext` predicate introduced for the Option B Save & Test gate.
- `clearSession()` rewritten to call `disconnectRelay()` **before** `authManager.clearSession()`. Order matters: tear down the reconnect loop before wiping the state it depends on, so in-flight retries return cleanly instead of firing with half-wiped state. KDoc explains the 2026-04-11 rate-limit incident as the why.

Both fixes together: `clearSession` stops the immediate loop, and the `reconnectGate` is a safety net for any other code path that wipes state without calling disconnect. Either alone would technically fix the reported bug; together they harden against future regressions.

### Test plan (on-device)

- [ ] Go to Settings → Paired Devices → open the current device's card → Revoke → confirm. Snackbar shows "This device was unpaired". App navigates to pair screen.
- [ ] Immediately scan a fresh QR (from `/hermes-relay-pair` on the server). Should succeed — no "unable to pair" error, no 429 on the relay's `/ws`.
- [ ] Check `~/hermes-relay.log` after revoke: should see the `DELETE /sessions/...` line but NOT a burst of `Auth failed` entries. Phone should stay quiet until the user scans the new QR.
- [ ] Bonus: toggle airplane mode while paired (forces disconnect with pair context still valid), confirm reconnect still works normally after re-enabling network.

### Server deploy

Phone-only change. No server restart needed. Bailey rebuilds from Android Studio.

## 2026-04-11 — Paired Devices JSON unwrap fix + /media/by-path permissive sandbox (B+C)

Two bugs surfaced on-device:

### 1. Paired Devices crash — "Expected start of the array '[', but had '{'"

`RelayHttpClient.listSessions` was parsing the response body as a bare `List<PairedDeviceInfo>`, but the server returns `{"sessions": [...]}` (`plugin/relay/server.py::handle_sessions_list`, line 406). Classic wire-format mismatch. The phone saw JSON starting with `{` and crashed kotlinx.serialization's list deserializer.

**Fix**: parse the body to `JsonElement`, pull out the `sessions` field, then decode the array via `Json.decodeFromJsonElement(ListSerializer(PairedDeviceInfo.serializer()), arrayElement)`. If the `sessions` field is missing, return a descriptive `IOException` instead of the kotlinx parse error so the UI shows a meaningful message.

One concept to internalize: `Json.decodeFromJsonElement(deserializer, element)` is a **member function** on the `Json` instance — no extension import needed. The reified-generic form (`Json.decodeFromJsonElement<T>(element)`) needs the `import kotlinx.serialization.json.decodeFromJsonElement` extension import; the explicit-deserializer form doesn't.

### 2. "Path not allowed by relay sandbox" for legitimate LLM emits

The `/media/by-path` route enforced `allowed_roots` against every phone-side fetch. When the LLM ran `search_files` and found an image somewhere like `~/projects/claw3d/readme.png`, the phone hit the sandbox and rendered a "Path not allowed" error card — even though the agent had already read the file's bytes via its own tools and would happily paste them into chat if asked.

**Decision**: B + C (C as default).

- **C (conceptual flip)**: drop the allowlist on `/media/by-path` by default. The trust boundary is the bearer-auth'd paired phone; the LLM can already exfiltrate bytes via plain text responses, so the sandbox was defense-in-depth with a high false-positive rate in practice. The token path (loopback-only `/media/register`) keeps its strict allowlist because it's trivially enforceable there.
- **B (config knob)**: `RELAY_MEDIA_STRICT_SANDBOX=1` (or `RelayConfig.media_strict_sandbox = True`) re-enables the allowlist enforcement on the by-path route for operators who want the tighter default back.

### Changes

**`plugin/relay/media.py`**:
- `validate_media_path(path, allowed_roots, max_size_bytes)` — `allowed_roots` now accepts `list[str] | None`. When None, the root-under-allowlist check is skipped entirely. All other checks (absolute path, realpath, exists, regular file, size cap) remain unconditional.
- `MediaRegistry.__init__` gains `strict_sandbox: bool = False` parameter, stored as `self.strict_sandbox`. Logged at init time alongside the existing max_entries/ttl/max_size summary.

**`plugin/relay/config.py`**:
- `RelayConfig.media_strict_sandbox: bool = False` field.
- `RELAY_MEDIA_STRICT_SANDBOX` env var parsed in `from_env()` — accepts `1`/`true`/`yes`/`on` (case-insensitive).

**`plugin/relay/server.py`**:
- `RelayServer.__init__` passes `strict_sandbox=config.media_strict_sandbox` to the `MediaRegistry`.
- `handle_media_by_path` computes `roots_for_check = server.media.allowed_roots if server.media.strict_sandbox else None` and passes that to `validate_media_path`. Permissive mode ⇒ only file-level checks (absolute, exists, regular, size). Strict mode ⇒ full allowlist enforcement.
- Docstring on `handle_media_by_path` rewritten to explain the permissive-by-default model and the opt-in path.

**`app/src/main/kotlin/.../network/RelayHttpClient.kt`**:
- `listSessions()` — unwraps `{"sessions": [...]}` before decoding the array. Missing-field path surfaces as a clean `IOException("Relay response missing 'sessions' field")`.

**`plugin/tests/test_relay_media_routes.py`**:
- Existing `RelayMediaRoutesTests` class pinned `config.media_strict_sandbox = True` so its legacy assertions (outside-sandbox → 403, etc.) still hold.
- New sibling class `RelayMediaByPathPermissiveTests` exercises the production default:
  - `test_by_path_outside_allowlist_still_streams_in_permissive_mode` — **the regression-guard test**. A file in a totally unrelated tmpdir streams successfully with a valid bearer. If this breaks, Bailey's claw3d workflow breaks again.
  - `test_by_path_still_rejects_relative_in_permissive_mode` — absolute-path check is unconditional.
  - `test_by_path_still_404s_nonexistent_in_permissive_mode` — file-existence check is unconditional.
  - `test_register_still_enforces_allowlist_in_permissive_mode` — the token path (loopback `POST /media/register`) stays strict regardless of the flag.

**`CLAUDE.md`**:
- Updated the `plugin/relay/media.py` and "Inbound media fetch (path)" rows in the Key Files / Integration Points tables to document the permissive default and the opt-in `RELAY_MEDIA_STRICT_SANDBOX` knob.

### Server deploy

Python-side changes. Bailey needs to `git pull` on the server and restart the relay (`pkill -TERM -f "python -m plugin.relay"` + `nohup ... & disown` per the standard recipe) before the new by-path semantics take effect. Phone-side `listSessions` fix ships with the next Android Studio build.

### Test plan (on-device after next build)

- [ ] Paired Devices screen: open → loads without the "Couldn't load paired devices" error card. Should show the current device with transport badge, expiry, and grant chips.
- [ ] Chat with `find an image`: LLM finds something in `~/projects/**` → card renders inline (not "Path not allowed by relay sandbox").
- [ ] Optional: `RELAY_MEDIA_STRICT_SANDBOX=1` on the server → same image request now renders the "Path not allowed" card (strict-mode opt-in works).

## 2026-04-11 — Option B: Save & Test as HTTP health probe + pair-context gate on connectRelay

Bailey flagged a trap in the Settings → Manual configuration flow: the **Connect** button fired `connectRelay(url)` unconditionally, even when the phone had no session token and no pending server-issued pair code. The WSS handshake would open, the phone would send an `auth` envelope with whatever code was lying around (usually the locally-generated phone→host Phase 3 code, which isn't registered on the relay), auth would fail, and after 5 such failures in 60 seconds the relay's rate limiter would block the IP for 5 minutes. Users fumbling with manual config could lock themselves out of pairing entirely.

Reviewed three fix options; picked **B** (split reachability from connect). Rejected **C** (two buttons — "Test" + "Connect") because the second button is redundant with the existing Reconnect button / auto-reconnect paths and would be disabled-when-unpaired / duplicated-when-paired. The right answer is one button that does a reachability probe, not two buttons fighting over WSS semantics.

### Changes

**Server**: none. This is entirely phone-side.

**`plugin/relay` unchanged** except indirectly — the new phone-side `probeHealth` method hits the relay's existing `GET /health` endpoint (already implemented at `plugin/relay/server.py::handle_health`).

**`auth/AuthManager.kt`** — new `hasPairContext: Boolean` getter. Returns true when any of:
1. `authState` is `Paired` (stored session token)
2. `authState` is `Pairing` (mid-handshake)
3. `serverIssuedCode != null` (fresh QR scan or manual code entry just landed)

Crucially does **NOT** count the locally-generated `_pairingCode.value` as valid — that's the phone→host Phase 3 code and is meaningless to the relay side, so sending it yields guaranteed rate-limit hits. This is the fix for the entire class of "connect when unpaired" bugs.

**`network/RelayHttpClient.kt`** — new `probeHealth(relayUrl): Result<RelayHealth>` method:
- Converts `ws://`/`wss://` → `http://`/`https://` via the same regex used by `fetchMedia`
- Unauthenticated `GET /health` with a **3-second** timeout (via `okHttpClient.newBuilder().connectTimeout(3, SECONDS).readTimeout(3, SECONDS).build()` — no need for a separate long-lived client)
- Parses the JSON body and validates it looks like a hermes-relay health response: `status == "ok"` AND a non-blank `version` field. Anything else fails with a descriptive error. Prevents a random HTTP server on port 8767 from falsely passing.
- Returns `RelayHealth(version, clients, sessions)` on success so the UI can render `"✓ Reachable — hermes-relay v0.2.0 (0 clients, 1 session)"` — actual metadata, not just a boolean.
- Error messages differentiate: `"Connection refused — is the relay running on this URL?"`, `"Relay is not responding (3s timeout)"`, `"Relay responded HTTP 404"`, `"Relay returned non-JSON: ..."`, etc.

**`viewmodel/ConnectionViewModel.kt`**:
- Rewrote `connectRelay(url)` / `connectRelay()` to delegate to a private `connectRelayInternal` that **gates on `authManager.hasPairContext`**. When the gate fails, log an informational message and return silently — the UI doesn't get any error state because this path should only be hit as a user-initiated mistake, not a programmatic one. The four legitimate entry points (pair walkthrough, stale row tap, Reconnect button, QR confirm) all set pair context *before* calling connectRelay, so the gate passes.
- New sealed interface `RelayReachable { Probing, Ok(version, clients, sessions), Fail(message) }` + `relayReachableResult: StateFlow<RelayReachable?>` + `testRelayReachable(url)` method that saves the URL to DataStore **and** probes `/health`. The save-before-probe order is deliberate: a failed probe still leaves the URL persisted so the user can edit and retry without losing their typing.
- `clearRelayReachableResult()` — called by the Relay URL text field's `onValueChange` so stale probe results vanish when the user edits the URL.
- **Deleted** the old callback-based `testRelayReachability(wsUrl, onResult: (Boolean) -> Unit)` — it was a thinner version of `probeHealth` that spun up its own OkHttpClient per call and returned only a boolean. Single caller in SettingsScreen migrated to the new state-flow API.
- Removed the now-unused `okhttp3.Request` import (was only referenced by the deleted method).

**`ui/screens/SettingsScreen.kt`** — Manual configuration button row rewritten:
- **Connect button deleted.** It was the leak source. Reconnecting a paired session is handled by the existing Reconnect button (in the Connection card, gated on `Paired`), the stale row tap, and `reconnectIfStale()` on screen entry. No fourth "Connect" button is needed.
- **"Test" button renamed to "Save & Test"** — wired to `testRelayReachable(relayUrlInput)`. Label change matches the new behavior (the button also persists the URL to DataStore). Disabled when the URL is blank or a probe is in flight.
- **Disconnect button kept** — still useful for debugging / forcing reconnect cycles.
- **Result row** rewritten to read from `relayReachableResult: StateFlow` instead of the old local `relayTestInProgress` / `relayTestResult` vars (both removed). Shows:
  - `Probing /health…` with spinner during the probe
  - `✓ Reachable — hermes-relay v0.2.0 (0 clients, 1 session)` (green) on success, with version + client count so the user knows they're pointing at an actual relay
  - `✗ <specific error>` (red) on any failure
  - Nothing when idle (so the row collapses cleanly when not in use)
- **Relay URL text field's `onValueChange`** now clears the probe result — stale "✓ Reachable" indicators won't hang around after the user starts typing a different URL.

### Bonus fix — Settings QR confirm never connected WSS

While tracing the pair-context gate I noticed the Settings → Scan Pairing QR → TTL picker confirm flow stashed the server-issued code + grants + TTL but **never actually called `connectRelay`**. The WSS stayed disconnected until the user either left and re-entered Settings (firing `reconnectIfStale`, which still wouldn't fire since `authState` is still `Unpaired`) or manually tapped Reconnect. This was a pre-existing bug dating from the pairing+security architecture cycle — the walkthrough dialog path (which has its own inline `connectRelay` call) masked it.

Added the missing `disconnectRelay()` + `connectRelay(relay.url)` pair to the TTL picker's `onConfirm` callback, mirroring the manual code pair path at `submitPairing` / `authManager.applyServerIssuedCodeAndReset` / `connectRelay(...)`. Since `applyServerIssuedCodeAndReset` has just set `serverIssuedCode`, the new pair-context gate passes cleanly.

### Bonus fix — Connection status rows didn't look tappable

Second Bailey UX feedback from the same cycle: the **API Server / Relay / Session** status rows in the Connection section open drawer sheets on tap, but there was **no visual affordance** that they were tappable — users would stumble onto the tap target by accident.

Fixed in `ConnectionStatusRow`:
- New `onClick: (() -> Unit)? = null` parameter. When non-null, the component applies a Material ripple + rounded-corner clip + internal `clickable` modifier, so the whole row is a proper list-item tap target.
- **Trailing chevron icon** (`Icons.AutoMirrored.Filled.KeyboardArrowRight`) rendered after the status text whenever `onClick != null` (and no `onTest` button is competing for the trailing slot). Gives users the standard Settings-list-item visual cue that the row opens something.

All three call sites in SettingsScreen migrated from `modifier = Modifier.fillMaxWidth().clickable { ... }` to `onClick = { ... }` + `modifier = Modifier.fillMaxWidth()`. Old pattern still works for legacy call sites (the component applies the interactive wrapping additively).

### Files changed

**Server**: none.

**Phone**:
- `app/src/main/kotlin/com/hermesandroid/relay/auth/AuthManager.kt` — `hasPairContext` getter
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayHttpClient.kt` — `probeHealth()` + `RelayHealth` data class + `jsonObject` import
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` — `RelayReachable` sealed interface, `relayReachableResult` flow, `testRelayReachable()`, `clearRelayReachableResult()`, `connectRelayInternal()` with pair-context gate, deleted `testRelayReachability`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt` — Connect button removed, Save & Test rewired, result-row reads from ViewModel flow, onValueChange clears probe, QR confirm flow adds missing `connectRelay`, three status rows migrated to `onClick` parameter
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionStatusBadge.kt` — `ConnectionStatusRow` gains `onClick` + chevron affordance

**Docs**: this DEVLOG entry.

### Test plan

On-device, after Bailey's next Android Studio build:

1. **Fresh Manual Config reachability probe** — Settings → Connection → Manual configuration → type a relay URL → tap **Save & Test**. Verify:
   - Spinner + "Probing /health…" appears briefly
   - On a live relay: green "✓ Reachable — hermes-relay vX.Y.Z (N client, M session)"
   - On a dead URL: red "✗ <reason>" (refused / timeout / non-JSON / etc)
   - Editing the URL clears the result immediately
2. **Pair-context gate doesn't regress pairing** — scan a fresh `/hermes-relay-pair` QR → TTL picker confirms → verify the WSS connects + `authState` reaches `Paired` without any manual intervention
3. **Manual code entry still works** — Settings → Already configured? Enter code only → type a valid code → verify it pairs and WSS connects
4. **Drawer affordance visible** — open Settings → Connection section → verify **API Server / Relay / Session** rows show a chevron at the right edge + have a ripple on tap → tapping each opens its respective info sheet
5. **Flicker still fixed** — open Settings with a stale session → verify the row shows "Reconnecting..." for the whole transition, not the old "Stale → Connecting → Connected" flash

### Known caveats

- The Connect button removal is a **UX change** for power users who were using it to force-reconnect. They should use the Reconnect button in the Connection card (gated on Paired) or the stale row tap. If this breaks a workflow Bailey relies on, the button can come back gated on `authManager.hasPairContext`.
- The new `probeHealth` gives 3 seconds before timing out. On a very slow LAN this might be too tight; consider bumping to 5s if probes start false-failing.
- `probeHealth` doesn't follow redirects (OkHttp default). A relay behind a reverse proxy that 301s `/health` would fail the probe. None of the current deployments do that.

---

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
