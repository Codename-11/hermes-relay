# GitHub Copilot instructions — Hermes-Relay

This file exists so GitHub Copilot (which reads `.github/copilot-instructions.md`,
not `AGENTS.md`) picks up the project's agent guidance.

**Read [AGENTS.md](../AGENTS.md) first — it is the single source of truth**
for agent guidance: the entry point, the non-negotiables, and the public-repo
writing hygiene. `CLAUDE.md` imports that same canonical file. Follow
`AGENTS.md` and its linked project records; don't restate them here.

Quick non-negotiables (the full list and rationale are in `AGENTS.md`):

- **Standard path = vanilla upstream only.** The default no-plugin connection
  must work against unmodified upstream hermes-agent; server-side needs go
  through upstream PRs or the optional relay plugin, never fork patches.
- **Conventional Commits**, `main`/`dev` branching — feature branches off
  `dev`, `--no-ff` merges, tags cut from `main`.
- **Android:** Jetpack Compose (no XML), kotlinx.serialization (no Gson),
  OkHttp (no Ktor), `wss://` only. Narrow local checks use the Android lane;
  pushed exact SHAs prefer `Android On-Demand` for heavy verification; full
  local pre-push remains an explicit fallback.
- **Public repo:** no personal names, no private infrastructure, no
  AI/assistant self-narration in committed prose.
