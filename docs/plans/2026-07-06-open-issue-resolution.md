# Open-Issue Resolution Batch — 2026-07-06

Triage of all 13 open GitHub issues (multi-agent code-verified pass + adversarial
cross-check), grouped into workstreams that land on `dev` via worktree feature
branches before the next releases. Owner-only actions (GitHub comments/closures,
labels, Play Console, on-device checks) are tracked in `TODO.md` — automation
never posts to GitHub on the owner's behalf.

## Verdict summary

| Issue | Verdict | Action |
|---|---|---|
| #166 hang on "still working…" (local models + delegating skills) | Real bug, **app-side** (mislabeled `area:plugin`) | Fix: SSE stream-death recovery poller (WS-A) |
| #165 `hermes relay start` broken under native plugin install / Docker | Real bug, plugin packaging | Fix: relative imports + installer venv autodetect (WS-B), ships as **plugin-v1.3.1** |
| #155 / #154 / #146 auto-filed diagnostics reports | Support/user-config, not defects | De-noise the in-app Report flow (WS-C); owner closes all three |
| #145 onboarding text overflow, no scroll | Real bug, Android UI | Fix: scrollable slides + compact-height hero (WS-D) |
| #144 confusing 4-asset releases | Docs/release UX | Fix: attach 2 assets + rewrite Download block (WS-E) |
| #131 crash: label string as URL host | **Fixed** — `3573ba8` (PR #136), shipped android-v1.2.5 | Owner closes |
| #129 crash: SocketTimeout on session probe | **Fixed** — `99b9cf1` (PR #128), shipped android-v1.2.4 | Owner closes |
| #124 crash: NetworkOnMainThread on TLS teardown | **Fixed** — `802385c` (PR #125), shipped android-v1.2.3 | Owner closes |
| #70 v1.0.0 force-close on connect | **Fixed** — keyset crash `48ddba5` (android-v1.1.0); in-thread TLS crash = #124, fixed android-v1.2.3 | Owner closes |
| #94 Z Fold7 Play crash tracker | Hardening shipped — `a455e46` (android-v1.2.0); vitals confirmation pending | Owner: Play Console check, then close |
| #121 desktop typed stream.event chat renderer | Feature, `cli-v*` track | **Implemented on `dev` 2026-07-12**; remains in Desktop Unreleased for the next CLI tag |

All tag-containment claims above were verified with `git merge-base --is-ancestor`,
not taken from issue comments.

## Workstreams (worktree branches → `dev`, `--no-ff` merges)

### WS-A `fix/chat-stream-recovery` — #166 (Android, M)

Root cause (verified against upstream source): long turns (slow local model +
delegating skills like claude-code) outlive the phone's SSE socket — screen-off /
Doze / Wi-Fi power-save drops it (not an OkHttp timeout: client readTimeout is
5 min, upstream sends `: keepalive` every 30 s). Upstream
`_handle_session_chat_stream` cancels the SSE writer on the write failure, **but
the agent run continues in an uncancellable executor thread and the final answer
IS persisted** to the session store. The app's `onErrorCb` does one immediate
history reload that races the still-running turn, finds nothing, and strands the
"Still working…" placeholder forever.

Fix (client-only; standard path stays unmodified-upstream-compatible): on
transport failure for the sessions SSE path (non-user-cancel, IO-class errors),
enter a *recovering* state instead of finalizing the turn as dead — poll
`GET /api/sessions/{id}/messages` with backoff (5 s → 30 s, capped ~30 min),
reconcile persisted messages as they appear, finalize when the last assistant
message is stable across consecutive polls; fire the turn-complete notification
if backgrounded; cancel the poller on new send / session switch / Stop. Log a
Warning diagnostics entry so future reports of this shape are self-explanatory.
Upstream PR candidates (optional, not required): intentional detached-run
semantics on client disconnect; pollable/resumable session-turn status.

Docs: troubleshooting entry — long turns with local models. Tests: MockWebServer
stream-kill → recovery poller unit tests.

### WS-B `fix/plugin-native-imports` — #165 (Plugin, M) — **parked until plugin-v1.3.0 is tagged; ships as plugin-v1.3.1**

Root cause (verified): ~15 runtime files use absolute `from plugin.…` imports.
The upstream native installer loads the plugin as `hermes_plugins.hermes_relay`,
so no top-level `plugin` package exists → `hermes relay start`, `hermes pair`,
and android media registration all break. Separately, `install.sh` hardcodes the
classic `hermes-agent/venv` layout and dies on the official Docker image
(`/opt/hermes/.venv`). (The reporter's uv-pip gap was already fixed in
plugin-v1.1.0+ — that is not what they hit.)

Fix: convert runtime imports to package-relative form (works identically under
editable, native, and `python -m` entry); package bootstrap for the
standalone-exec'd dashboard `plugin_api.py`; `install.sh` venv autodetection
(classic / uv / Docker) with a graceful steer-to-native-install path on
immutable images; `hermes relay doctor` gains an import-chain check so this
class of break can never pass doctor again. New `test_native_layout_imports.py`:
AST guard (no absolute `plugin.` imports in runtime code) + subprocess smoke
test importing the tree under a renamed package dir.

Sequencing: the import conversion touches the voice/realtime stack that the
pending plugin-v1.3.0 e2e validation exercises — so this branch does **not**
merge to `dev` until v1.3.0 is cut, then ships as plugin-v1.3.1 with the
paired docs (native-install/Docker notes cite the fixed version).

### WS-C `fix/diagnostics-report-noise` — #155/#154/#146 (Android, M)

The diagnostics activity log offers an unconditional "Report" button that turns
*Info-severity routine probe lines* into `[Bug]:`-titled GitHub issues with a
templated body (hardcoded "What happened", unedited connection-mode line).

Fix: severity-gate the Report flow — Info entries get a pre-flight requiring a
"what were you expecting?" answer that replaces the boilerplate; non-Error
prefills retitle to `[Diagnostic]:` and carry the existing `question` label
instead of `bug`; the body reports the *actual* route role instead of the
template line. Add `ServerAddress.loopbackHostWarning()` (+ tests): advisory
that `localhost`/`127.0.0.1` on a phone points at the phone itself.
**Scope guard:** the wizard/detail-screen wiring of that advisory is deferred —
the connections UI is owned by a parallel workstream; only the pure util + the
Report-flow changes land here. Docs: troubleshooting gains "No reachable
endpoint", localhost-on-phone, and a Tailscale checklist.

### WS-D `fix/onboarding-scroll` — #145 (Android, S)

Slides are a fixed-height stack (232 dp hero + cards) in a non-scrolling Column;
short viewports / large font scale push content below the fold with no scroll.
Fix: `verticalScroll` on the slide column (indicator + Back/Next stay pinned
outside the pager), hero shrinks under compact height. Add a compact-height
(`w320dp-h480dp`, fontScale 1.3) Roborazzi render test so the regression class
is caught host-side.

### WS-E `chore/release-assets` — #144 (Release infra + docs, S)

Releases attach 4 artifacts; GitHub sorts alphabetically so the **non-installable
`.aab` lists first**, and "Parity/testing artifact" means nothing to users. Note:
the issue's suggested primary file is wrong — the correct GitHub download is the
**sideload APK** (the googlePlay flavor's install path is the Play Store).

Fix: attach only `…-sideload-release.apk` + `…-googlePlay-release.aab` +
`SHA256SUMS.txt` (parity artifacts remain reproducible from the tag via CI);
release-notes template leads with a "Installing on your phone? Download this
file" block + an explicit ".aab is not tap-installable" note; RELEASE.md codifies
the format. The sideload APK filename never changes (the in-app update checker
matches it).

### WS-F `docs/freshness-pass` — docs truth sweep (S/M)

Independent of code fixes (docs that ride a code fix land on that branch):
- Setup guides say tap "Vanilla Hermes" — the button has been labeled
  "Hermes" since app v1.2.2; quote UI labels verbatim.
- Four broken deep-link anchors into getting-started
  (`#sideload-apk`, `#relay-server-optional`, `#install-the-server-plugin`).
- Smaller accuracy items: README tool count, security.md prototype framing,
  CLI cert-pin example, index.md SSE wording.
- Whether to retire "Vanilla Hermes" as a docs-wide term is an owner decision;
  this pass only fixes verbatim UI-label quotes.

## Release sequencing

1. **plugin-v1.3.0 first** — finish the pending on-device e2e of the ADR-33
   voice batch, then release-merge and tag. Nothing from this batch rides it.
2. **Android batch** — WS-A, WS-C, WS-D converge on `dev` → next `android-v*`
   tag (with WS-E's release-format change already on `dev` so the new tag
   demonstrates it). #131/#129/#124/#70/#94 need closure only.
3. **plugin-v1.3.1** — WS-B merges after the v1.3.0 tag exists.
4. **CLI** — #121 is implemented on `dev` (typed `stream.event` renderer,
   Relay WSS chat mode, and golden-stream test; relay side already ships the
   typed passthrough) and remains queued for the next `cli-v*` milestone.

## Verification gates

- Android: `./gradlew lint` + unit tests green per branch; owner does on-device
  passes (Doze/screen-off recovery for WS-A, max font/display scale for WS-D).
- Plugin: `python -m unittest` suite + new native-layout tests; owner validates
  on the official Docker image (no local reproduction of the immutable layout).
- Docs: VitePress build + anchor check; deploys on the next `dev` → `main`
  release merge.
