# GitHub Copilot instructions — Hermes-Relay

This file exists so GitHub Copilot (which reads `.github/copilot-instructions.md`,
not `AGENTS.md`) picks up the project's agent guidance.

**Read [AGENTS.md](../AGENTS.md) first — it is the single source of truth**
for agent guidance: the entry point, the non-negotiables, and the public-repo
writing hygiene. It links on to `CLAUDE.md` for the deep reference
(architecture, upstream Hermes API, repository layout, per-language code style,
the dev loop, and the Key Files map). Follow those; don't restate them here.

Quick non-negotiables (the full list and rationale are in `AGENTS.md`):

- **Standard path = vanilla upstream only.** The default no-plugin connection
  must work against unmodified upstream hermes-agent; server-side needs go
  through upstream PRs or the optional relay plugin, never fork patches.
- **Conventional Commits**, `main`/`dev` branching — feature branches off
  `dev`, `--no-ff` merges, tags cut from `main`.
- **Android:** Jetpack Compose (no XML), kotlinx.serialization (no Gson),
  OkHttp (no Ktor), `wss://` only; run `./gradlew lint` before pushing Kotlin.
- **Public repo:** no personal names, no private infrastructure, no
  AI/assistant self-narration in committed prose.
