# AGENTS.md

Universal agent instructions for **Hermes-Relay**. This is the entry point for any
coding agent (Claude Code, Codex, Cursor, etc.).

## Read this first

The detailed, authoritative context lives in **[CLAUDE.md](CLAUDE.md)** —
architecture, the upstream Hermes API reference, repository layout, per-language
code style, the dev loop, and the Key Files map. Read it before touching code,
then `docs/spec.md` and `docs/decisions.md`.

- Release process → **[RELEASE.md](RELEASE.md)**
- Contributor setup → **[CONTRIBUTING.md](CONTRIBUTING.md)**
- `android_*` toolset + MCP → **[docs/mcp-tooling.md](docs/mcp-tooling.md)**
- Follow-ups / deferred work / known gaps → **[TODO.md](TODO.md)** (the single home for "what's next" — never DEVLOG, never scattered code comments)

## Non-negotiables (the short list)

- **Vanilla Hermes path = upstream-only.** The default (no-plugin) connection —
  chat via the API server, Vanilla Hermes voice via the Hermes dashboard — must work
  against unmodified upstream hermes-agent. Server-side needs go through upstream
  PRs or the optional relay plugin, never fork patches.
- **Verify endpoints against upstream** (`gateway/platforms/api_server.py` /
  `tui_gateway/server.py` in hermes-agent) before assuming a route exists.
- **Conventional Commits + `main`/`dev` branching.** Feature branches off `dev`,
  `--no-ff` merges, version bumps at release-prep on `dev`, tags cut from `main`.
- **Android:** Jetpack Compose only (no XML), kotlinx.serialization (no Gson),
  OkHttp (no Ktor), `wss://` only. Run `./gradlew lint` before pushing Kotlin.
- **Plugin (Python 3.11+):** aiohttp + asyncio (no threading), type hints
  everywhere, structured `logging` (no `print`). **Desktop CLI (Node ≥21):**
  zero runtime deps, strict TS + ES modules, ship compiled `dist/`. Full
  per-language style and the dev loop live in CLAUDE.md → "Code Style".

## Review guidelines

- Report only actionable correctness, security, compatibility, or release-risk
  findings; avoid stylistic preferences unless they violate a documented rule.
- Treat the vanilla Hermes upstream boundary as release-critical. Flag any
  default-path dependency on relay-only or fork-only server behavior.
- Check that changes preserve public-repo writing hygiene and do not expose
  secrets, private infrastructure, or personal information.
- Use the affected surface's CI result as evidence, but do not imply Android UI
  or device behavior was proven without an explicit on-device verification.
- Prioritize findings that warrant holding the merge. State the impacted path
  and the concrete failure mode.

## Public-repo writing hygiene

Everything committed is public. In CHANGELOG, DEVLOG, README, docs, and release
notes:

- **No personal names** — attribute impersonally; identity lives in git + the
  signing cert.
- **No private infrastructure** — real hostnames/IPs, internal deployment names,
  `~/SYSTEM.md`. (Generic example IPs in setup docs are fine.)
- **No AI/assistant process self-narration** ("I should have…", course
  corrections) — state the technical conclusion only.
- **No internal jargon or fork/branch plumbing** in user-facing notes.
- **CHANGELOG** uses Keep-a-Changelog grouping; condense the version block to
  crisp public bullets at release-prep (see RELEASE.md §2 "Scrub for public
  distribution"). **DEVLOG** is a depersonalized, factual engineering log.
