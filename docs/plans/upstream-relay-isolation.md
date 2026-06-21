# Plan — Isolate vanilla-upstream surfaces from Relay additions

> Working plan for the `feature/upstream-relay-isolation` effort. Source of truth for the
> `/goal` run. This doc itself need not ship — fold it into the PR or delete after merge.

## Why

The upstream/relay split is currently enforced by **convention + CLAUDE.md**, not by structure,
and the "standard path = vanilla upstream" invariant is **never validated against true vanilla
hermes-agent** (the live server runs the axiom fork). Two net-additive, low-risk efforts close
that gap: a structural package fence with an enforced import rule (Plan A) and a vanilla-upstream
contract test (Plan C).

## Working rules

- One branch off `dev`: `feature/upstream-relay-isolation`. Conventional Commits, one logical
  commit per step. `--no-ff` PR into `dev` at the end. Update `DEVLOG.md`.
- Do **NOT** run a full gradle assemble or `adb install` (on-device builds are Bailey's via
  Android Studio). DO verify with `./gradlew lint` and focused unit tests.
- If the real code contradicts this plan, **stop and report** rather than guessing.
- Read `CLAUDE.md`, `docs/decisions.md`, `docs/spec.md` first.

## Step 0 — ADR (commit: docs)

Add an ADR to `docs/decisions.md`:
- **Problem:** separation enforced by convention/docs not structure; standard path never
  validated against true vanilla upstream.
- **Decision:** package fence + Konsist import rule + vanilla contract test.
- **Rejected:** full `ConnectionViewModel` split now (too risky — deferred); custom
  ktlint/detekt lint rule (deferred in favor of a Konsist JUnit test that reuses existing
  test infra).
- Keep it public-repo clean (no personal names, no private infra).

## Step 1 — Plan A: package fence (commit: refactor)

Split `app/src/main/kotlin/com/hermesandroid/relay/network/` into three sub-packages; move files
and update `package` declarations **and every import site repo-wide** (including `app/src/test`).

| `network/upstream/` | `network/relay/` | `network/shared/` |
|---|---|---|
| HermesApiClient, GatewayChatClient, GatewayEventMapper, GatewayModels, GatewayKeepAliveService, DashboardApiClient, HermesChatPayloads, models/SessionModels, StandardHermesVoiceClient (extract from VoiceAudioClient.kt if co-located) | RelayHttpClient, RelayVoiceClient, ConnectionManager, ChannelMultiplexer, RelayProfileInspectorClient, RelayUrlDeriver, models/Envelope, handlers/BridgeCommandHandler | ConnectivityObserver, EndpointResolver, HermesLanDiscovery, ProfileApiUrlResolver, VoiceAudioClient INTERFACE (routing seam) |

**Decide by reading, don't guess:** `handlers/ChatHandler.kt` (parses both structured SSE and
inline annotations — likely `shared/`) and `VoiceAudioClient.kt` (interface → `shared/`, standard
impl → `upstream/`). Document the call in the commit/PR.

Verify the move compiles via `./gradlew lint` (NOT a full build).

## Step 2 — Plan A: enforce the boundary (commit: test)

- Add Konsist (`com.lemonappdev:konsist`) to `gradle/libs.versions.toml` as a test dependency.
- Add `app/src/test/.../network/ArchitectureBoundaryTest.kt` asserting:
  - `network.upstream` imports nothing from `network.relay`
  - `network.relay` imports nothing from `network.upstream`
  - `network.shared` imports neither upstream nor relay concrete clients
- **CRITICAL CI GOTCHA:** the test job in `.github/workflows/ci-android.yml` runs ONLY an
  explicit `--tests` list (the broad aggregate hangs, issue #32). Add `ArchitectureBoundaryTest`
  to that explicit list or it silently never runs in CI.
- Verify locally:
  `./gradlew :app:testSideloadDebugUnitTest --tests "*ArchitectureBoundaryTest" --console=plain`

## Step 3 — Plan C: vanilla-upstream contract test (commit: test/ci)

Add a job (new `.github/workflows/ci-contract.yml`, or a job in `ci-plugin.yml`) proving the
no-plugin standard path works on **UNMODIFIED** `NousResearch/hermes-agent`:

1. Clone hermes-agent at a pinned ref (`UPSTREAM_REF` env, default a known-good SHA); pip install.
   Triggers: `pull_request` + push to `dev`/`main` + weekly `schedule` + `workflow_dispatch`.
   Scheduled run targets `UPSTREAM_REF=main` (drift siren); PR runs stay pinned (not flaky).
2. Ensure the bootstrap `.pth` is **ABSENT** (`hermes relay compat remove`, or never install it)
   so the test exercises REAL native upstream routes, not bootstrap-injected ones. Assert this.
3. Probe script asserting the **route surface contract** the app depends on — derive the list
   from `HermesApiClient.probeCapabilities()` / the CLAUDE.md Integration Points table:
   `/v1/capabilities`, `/v1/chat/completions`, `/v1/runs` (+`/{id}/events`), `/api/sessions`
   (+`/{id}/messages`, `/{id}/chat/stream`), `/api/auth/ws-ticket`, `/api/audio/transcribe`,
   `/api/audio/speak`, `/api/ws`.

**KEY DESIGN:** assert route **EXISTENCE, not agent behavior**. Boot with a dummy/stub provider;
treat `401/400/405` as PASS (route exists, auth/payload rejected) and `404` as FAIL (route gone).
No real model keys, no LLM round-trip.

**FALLBACK** if booting the dashboard headless is too heavy: import the upstream app factory and
assert the required paths exist in `app.router` (static route-table contract) — faster, catches
renamed/removed routes, misses only runtime-auth regressions. Note the tradeoff in a comment.

## Acceptance

- ADR in `docs/decisions.md`; `DEVLOG.md` updated.
- `network/` split into `upstream|relay|shared`; all imports updated; `./gradlew lint` clean.
- `ArchitectureBoundaryTest` exists, is in the CI explicit `--tests` list, and passes.
- Contract job exists, runs without the bootstrap, asserts route existence on pinned vanilla
  upstream, and has a scheduled drift run.
- One PR (`feature/upstream-relay-isolation` → `dev`, `--no-ff`). Summarize what changed.
- Report any divergence from this plan (esp. ChatHandler/VoiceAudioClient placement, or upstream
  route names that differ from the list above).
