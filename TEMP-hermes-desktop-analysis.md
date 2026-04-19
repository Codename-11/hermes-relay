# hermes-desktop — Patterns Worth Learning (TEMP — delete after session)

> Analysis of https://github.com/fathah/hermes-desktop for profile, config, and plugin management patterns applicable to hermes-relay.
> Session-scratch file — do not commit.

## Profile model (aligns with upstream, matches our new §21)

Source: `src/main/profiles.ts` (253 lines).

**Layout assumption:**
- Default profile = `~/.hermes/` itself (root). Named profiles = `~/.hermes/profiles/<name>/`. Same as upstream, same as R1's discovery rewrite.
- Active profile tracked in a plain text file: `~/.hermes/active_profile` (one line = the name). Simple, file-based, no DB.

**`ProfileInfo` shape returned to the UI:**
```ts
{ name, path, isDefault, isActive,
  model, provider,                    // parsed from config.yaml
  hasEnv, hasSoul,                    // file existence flags for .env / SOUL.md
  skillCount,                         // walks skills/ tree
  gatewayRunning }                    // reads gateway.pid + process.kill(pid, 0) liveness check
```

The `gatewayRunning` field is the interesting one — **they surface whether each profile's gateway daemon is actually up** by PID-file + signal-0 liveness probe. We could do the same on the relay side to tell the phone "this profile's API is reachable at `host:port`" vs "not running."

**All write operations shell out to the hermes CLI** rather than manipulating the directory directly. Creates use `hermes profile create <name> [--clone]`; deletes use `hermes profile delete <name> --yes` (with a "Cannot delete default" guard); switch uses `hermes profile use <name>`. That's the right choice — the CLI owns the lifecycle and side effects (command alias installation, skill seeding, etc.) that would be painful to reimplement in our relay.

**For our relay: we can match this model exactly.** Add a `gateway_running` boolean per profile to R1's `_load_profiles` output, populated by reading each profile's PID file (or doing an HTTP probe to the profile's configured gateway port). That tells the phone which profiles can actually be used as a separate Connection vs which are only available as overlays (current implementation).

## Config management (YAML-as-text, not parsed)

Source: `src/main/config.ts` (352 lines).

**Read/write strategy:** regex-based line edits on the YAML text, NOT round-tripping through a parser. E.g. `setConfigValue("model.default", "gpt-4o", profile)` runs a regex replace on the `default:` line and saves. Preserves comments, preserves ordering, preserves the exact shape the user wrote.

**5-second TTL cache** (`const CACHE_TTL = 5000`) around `readEnv` and `getModelConfig` with key invalidation on writes. Stops repeated file reads during a single UI interaction.

**Per-profile config routing** via `profilePaths(profile?)` helper that resolves `{ home, envFile, configFile }` for the target profile. Every public function takes an optional `profile` param; omitting it means "default." Very clean — one routing helper instead of if/else everywhere.

**Credential pool is SEPARATE from config.yaml.** Stored at `~/.hermes/auth.json` under a `credential_pool` key. Structure: `{ provider: [{ key, label }, ...] }`. Lets users store multiple OpenAI keys with labels ("Work", "Personal") and rotate, without cluttering the main config.

**Takeaway for us:** our relay currently parses YAML via `yaml.safe_load`. For READ-ONLY loading, that's fine. But if we ever want to let the phone EDIT server-side config (e.g., switch default model via an app-side picker), regex-based patch-in-place is the right approach — it won't destroy the operator's comments / formatting. Not urgent for the phone since the phone doesn't currently write server config.

## Skills enumeration

Source: `src/main/skills.ts` (293 lines).

**Walks `skills/<category>/<name>/SKILL.md`** — same directory shape our relay already uses. Parses YAML frontmatter between `---` markers for `name` + `description`; falls back to first H1 + first paragraph if no frontmatter. Useful fallback — makes skills with minimalistic SKILL.md files (no frontmatter) still list cleanly.

**Install/uninstall also shells out to the CLI** (`hermes skills install <id> [--yes]`). Again — the CLI owns the real lifecycle (download, deps, permission prompts). The desktop app is a thin GUI over the CLI.

**Skill search uses `hermes skills browse --query <q> --json`** (they assume upstream has a `--json` flag; gracefully falls back to bundled-skill filtering if not).

## IPC surface (preload)

Source: `src/preload/index.ts` (629 lines total, profile-aware methods shown below).

Every config-touching method accepts an optional `profile?: string`:
```ts
getEnv(profile?), setEnv(key, value, profile?),
getConfig(key, profile?), setConfig(key, value, profile?),
getHermesHome(profile?),
getModelConfig(profile?), setModelConfig(provider, model, baseUrl, profile?),
getPlatformEnabled(profile?), setPlatformEnabled(platform, enabled, profile?),
readMemory(profile?), addMemoryEntry/updateMemoryEntry/removeMemoryEntry(..., profile?),
writeUserProfile(content, profile?),
readSoul(profile?), writeSoul(content, profile?),
```

Plus non-profile-scoped:
```ts
listProfiles(), createProfile(name, clone), deleteProfile(name), setActiveProfile(name),
```

**The pattern is:** a single `window.hermesAPI` façade with ~50 thin methods, each one `ipcRenderer.invoke("channel-name", ...args)`. No middleware, no store, no adapters — just Promise-returning functions. The renderer does all state shape conversion locally via `useState`/`useEffect`.

**For our Android app:** we've already got a similar pattern via `HermesApiClient` + ConnectionViewModel. The parallel isn't "copy Electron IPC" — it's that **keeping the API surface flat and profile-aware from the start** is worth it. Our recent §21 additions (agentProfiles, selectedProfile, selectProfile) fit that pattern.

## UI patterns

Source: `src/renderer/src/screens/Agents/Agents.tsx` (264 lines — the profile-management screen).

**Card per profile**, each showing:
- Avatar (icon for default, first letter for others)
- Name + status (Active indicator)
- Model · provider
- Skill count
- Gateway running badge (green dot / "Running" text when PID-file says so)
- Actions: chat-with, delete (disabled for default)

**Create flow is a modal with a single text field + "Clone config" checkbox**, maps directly to `hermes profile create <name> [--clone]`. No wizard — one screen, one submit.

Our `ConnectionsSettingsScreen` is structurally the same. If we want an `AgentsScreen` (future Phase 2 work), this is the right shape to crib.

## What to apply to hermes-relay

Ranked by payoff:

1. **`gateway_running` / port-liveness per profile.** R1's discovery list today says "here are these profiles" but doesn't say "this one's gateway is also listening on port X." If we add a PID-file read + port probe to the relay scan, the phone could show which profiles can be used as separate Connections today (the user doesn't need to guess). Small addition (~20 lines in `_load_profiles`).
2. **Profile info in `auth.ok` grows a `has_soul: bool` + `skill_count: int`.** Cheap to compute, tells the phone "this profile has an identity + N skills" so the picker UI can be more informative.
3. **Credential pool as first-class concept.** Not directly relevant to the phone today — the phone doesn't manage API keys. But if we ever add a "rotate my key" feature, the `~/.hermes/auth.json` shape is already there.
4. **Regex-based YAML patch-in-place** for any future "edit server config from phone" feature. Preserves operator's comments/ordering.

None of these block the current feature. All are small additive wins.

---

## Session continuation block — PRESERVE THIS FOR COMPACTION

**Current branch:** `feature/multi-profile-connections` at tip `cf3b09d` (pushed to origin).

**What we just built (last few commits, newest first):**
- `cf3b09d` Settings "Active agent" summary card → navigates to Chat with `openAgentSheet=true` nav arg
- `e277a0f` Snackbar confirmation on profile/personality switch
- `4e50ea8` Consolidated Profile + Personality into new `AgentInfoSheet` in Chat top bar; deleted `ProfilePicker.kt` + `PersonalityPicker.kt`
- `7031115` Polish pass: pair-stamp, URL scheme validation, status UX
- `7af0571` R2: thread profile `system_message` into chat send (profile > personality precedence)
- `ec7559c` docs §21 rewrite + R1 Python changes (mixed commit: my docs + R1's directory-scan work)
- `b9d2914` `JsonObject` wrap fix on v3→v4 backup migration

**Three-layer state model (established):**
- **Connection** = Hermes server (server:port pairing). Stored in `ConnectionStore` (was `ProfileStore` before Pass 1). Switch = heavy context swap.
- **Profile** = upstream-Hermes agent directory at `~/.hermes/profiles/<name>/` — discovered server-side, overlays `model` + `SOUL.md` (as `system_message`) on chat requests. Chat-only; ephemeral selection; clears on Connection switch.
- **Personality** = system-prompt preset inside a profile's `config.yaml` under `agent.personalities`. Profile overlay wins over personality when both selected.

**Relay-side toggle:** `RELAY_PROFILE_DISCOVERY_ENABLED=1` (default) controls whether `_load_profiles` walks the directory.

**Deploy state:** server `172.16.24.250` is on our feature branch; relay running with "Profile discovery: enabled — Profiles loaded: default, mizu".

**Pending work items:**
1. **NEW — address immediately:** chat stats missing from the consolidated `AgentInfoSheet` (they used to be in the old dialog); sheet can't scroll; general UI/UX cleanup. This is the drawer Bailey taps from the agent name in Chat top bar.
2. Bailey's parallel session has uncommitted edits in `ProfileData.kt`, `ConnectionSettingsScreen.kt`, `ConnectionsSettingsScreen.kt`, `SettingsScreen.kt`, `ConnectionViewModel.kt`, plus an untracked `RelayUiState.kt`. Don't touch — it's his other work.
3. TODO in `ChatScreen.kt`: visual "overridden by profile" hint on personality chip when profile carries SOUL (marked `TODO(profile-overrides-personality)`).
4. Persist profile selection per Connection (currently ephemeral). Filed as v2.
5. Gateway-running probe per profile (see insight #1 above).

**Server info Bailey is running:** Docker-Server at `172.16.24.250`, user `bailey`, Hermes at `~/.hermes/hermes-agent/` on branch `axiom`. Has `default` (Victor) + `mizu` (Axiom-Labs Public Ops) profiles configured.

**DELETE THIS FILE at session end or after context is compacted into memory.**
