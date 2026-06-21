# Plan — Decompose ConnectionViewModel into transport controllers

> Working plan for the ConnectionViewModel decomposition (the ADR 34 deferred
> follow-up). Source of truth for the `/goal` run. **Run in an isolated git
> worktree off `dev`.** This doc itself need not survive — fold into the PR or
> delete after merge.

## Why

`viewmodel/ConnectionViewModel.kt` is **~5,531 lines · 95 functions · 111 exposed
StateFlow/SharedFlow** spanning ~21 responsibility clusters. It is the single
place in the app that reaches across **both** sides of the ADR 34 package fence —
it owns the upstream `HermesApiClient` / `GatewayChatClient` / `DashboardApiClient`
zoo **and** the relay `ConnectionManager` **and** pairing **and** profiles — so it
is structurally exempt from the isolation the fence enforces. Decompose it into
focused collaborators behind its existing public surface, so the standard-vs-relay
wiring lives in named, testable seams instead of emergent shared state.

Precedent exists: `viewmodel/ConnectionSwitchCoordinator.kt` was already extracted
as a collaborator. This continues that pattern.

## Non-negotiables (read before touching code)

- **Worktree.** Do all work in a dedicated worktree off `dev`, e.g.
  `git worktree add ../wt-cvm -b feature/connectionviewmodel-decomposition dev`.
  Remove it when done (`git worktree remove`).
- **Behavior-preserving ONLY.** Pure mechanical extraction: move fields/methods
  into a collaborator that `ConnectionViewModel` owns and delegates to. Do **not**
  change logic, fix bugs, rename public members, alter call ordering, or add
  features. Tempting cleanups are out of scope — note them in the PR, don't do them.
- **Public surface frozen.** Every public `fun` and `val StateFlow/SharedFlow` on
  `ConnectionViewModel` must stay byte-identical so `ChatViewModel`, UI screens, and
  tests compile **unchanged**. The whole-module compile IS the API-preservation
  guard — if a caller needs editing, you changed the public surface; revert and
  re-do the extraction behind a delegating getter.
- **Keep the Konsist fence green.** New collaborators live in a new package
  `viewmodel/connection/`. They may import `network.upstream` + `network.relay`
  freely (they are not under `network.*`, so `ArchitectureBoundaryTest` does not
  govern them) — but never relax the fence to make an extraction easier.
- **Verification per step** (NO full assemble / `adb install` — on-device builds are
  Bailey's via Studio):
  `./gradlew :app:compileSideloadDebugKotlin :app:compileSideloadDebugUnitTestKotlin`
  then the focused slice
  `--tests "*ArchitectureBoundaryTest" --tests "*RelayUrlDeriverTest" --tests "*ConnectionSwitchTest"`.
  Run `./gradlew lint` once before the PR.
  (Use `--no-daemon`; never pipe gradle through `tail` — it masks the exit code.)
- **Conventional Commits, one commit per extracted controller.** `--no-ff` PR into
  `dev` at the end. Update `DEVLOG.md`.
- **If a cluster is too entangled** to extract without logic changes, STOP, leave it
  in place, and report — open the PR with the completed steps rather than forcing it.

> Line numbers below are guideposts from `dev` at plan time; re-grep before moving
> anything (they drift as earlier steps land).

## Step 0 — Worktree + baseline

Create the worktree + branch off `dev`. Confirm a **clean baseline compile**
(`compileSideloadDebugKotlin` + `compileSideloadDebugUnitTestKotlin`) before
touching anything, so any later break is attributable to the extraction.

## Step 1 — Extract `UpstreamTransportController` (commit: refactor)

The densest, most self-contained knot — do it first. Into
`viewmodel/connection/UpstreamTransportController.kt`, move:

- the gateway client cache: `gatewayClientCache`, `activeGatewayChatClient()`, and
  its shutdown sites (~`:605`–`:656`, `:3295`)
- the **4 scattered `DashboardApiClient` build sites** (`:632`, `:644`, `:1010`,
  `:1038`, `:1058`) consolidated into **one** factory method
- `_apiClient` / `_chatApiClient` (`HermesApiClient`) ownership (`:540`–`:543`)
- streaming-endpoint resolution: `resolveStreamingEndpoint()` (`:1480`) wrapping
  `resolveStreamingEndpointPreference`

`ConnectionViewModel` keeps its public getters and delegates. Add a focused unit
test for the consolidated `DashboardApiClient` factory + `resolveStreamingEndpoint`
if they extract as pure functions. Verify (whole-module compile proves callers
unchanged).

## Step 2 — Extract `RelayTransportController` (commit: refactor)

Into `viewmodel/connection/RelayTransportController.kt`, move `ConnectionManager`
ownership, relay-URL derivation (`:742`), relay connection state (`:368`), and the
relay methods (`:4507`). Delegate. Verify.

## Step 3 — Extract `PairingController` (commit: refactor)

Into `viewmodel/connection/PairingController.kt`, move unified pairing apply
(`:3618`), paired-device list (`:1232`) + management (`:5330`), multi-endpoint
exposure (`:4583`), and insecure-ack helpers (`:5485`). Delegate. Verify.

## Step 4 — Extract `ProfileController` (commit: refactor)

Into `viewmodel/connection/ProfileController.kt`, move the agent-profiles cluster
(`:973`) with its profile stores and per-profile `DashboardApiClient` builders
(reuse the Step-1 factory). Delegate. Verify.

## Step 5 — `ChatTransportProvider` seam (capstone; optional)

Introduce a small provider the ViewModel/`ChatViewModel` asks for a chat transport,
instead of resolving the preference + building a client inline. Behavior-preserving:
it wraps the Step-1 controller's existing resolve-then-build. This is the seam that
turns standard-vs-relay into one guardable decision. **If it risks any behavior
change, defer it and report** — Steps 1–4 stand on their own.

## Acceptance / DONE

- `ConnectionViewModel` materially smaller; transport / pairing / profile concerns
  live in `viewmodel/connection/` collaborators; **public API unchanged** (whole
  module compiles, every caller untouched).
- `UpstreamTransportController` + `RelayTransportController` + `PairingController` +
  `ProfileController` extracted (Step 5 optional).
- `compileSideloadDebugKotlin` + `compileSideloadDebugUnitTestKotlin` green; focused
  slice passes; `./gradlew lint` clean; `ArchitectureBoundaryTest` green.
- `DEVLOG.md` updated. One PR (`feature/connectionviewmodel-decomposition` → `dev`,
  `--no-ff`). Worktree removed.
- **Report:** `ConnectionViewModel` line-count before/after, what moved where, any
  cluster left in place + why, and any deferred cleanups noted.

## Explicitly out of scope

- Any behavior/logic change, bug fix, public-API rename, or new feature.
- On-device verification (Bailey, via Studio).
- Decomposing `ChatViewModel` or other view models.
