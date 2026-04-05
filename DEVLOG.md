# Hermes Companion — Dev Log

## 2026-04-05 — Project Restructuring

**Done:**
- Wrote SPEC.md (full spec — protocol, UI, 6 phases, tech stack)
- Wrote DECISIONS.md (framework choice, architecture rationale, deferrals)
- Restructured repo: moved docs to `docs/`, rewrote README to match ARC conventions
- Created CLAUDE.md handoff for agent team development
- Created DEVLOG.md

**Current state:**
- Upstream bridge app exists in `hermes-android-bridge/` (Kotlin + Ktor, XML layouts)
- Python plugin exists in `hermes-android-plugin/` (14 `android_*` tools, working)
- No Compose code yet — MVP will rewrite the Android app
- No companion relay yet — `companion-relay/` dir TBD

**Next:**
- MVP Phase 0: Compose scaffold, WSS connection manager, channel multiplexer, auth flow
- MVP Phase 1: Companion relay + chat channel (streaming UI)
- Need Android Studio locally to build/test the Compose app
