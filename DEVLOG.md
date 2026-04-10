# Hermes-Relay — Dev Log

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
