# Attachment Experience — Audit & Design Spec

**Status:** Design spec — ready for review, pre-implementation
**Date:** 2026-06-18
**Owner surface:** Android app (chat UI), relay plugin (media layer), user docs
**Goal path:** `docs/plans/2026-06-18-attachment-experience.md`
**Goal:** A smooth, in-app attachment experience — Telegram-grade previews, save-without-opening, and configurable sensitive-media blur — built standard-path-first with the relay filling the inbound-richness gaps.

---

## Bottom Line

- **Standard path stays vanilla-upstream.** Everything that can ship to all Play users without a paired relay does: in-app preview modals, save-without-opening, client-side blur, the modern photo picker, and the SSE-attachment fix.
- **The relay fills the inbound gap.** Upstream has no structured agent→phone media-return protocol. Rich inbound media (server-local files, thumbnails, authoritative metadata) is the relay's value-add and always has been (`spec.md §6.2a`).
- **Sensitive-media flagging is model-emitted metadata, server-side only.** No relay-side NSFW classifier and no on-device model. The agent annotates media it surfaces with a sensitivity flag; the relay transports it; the client renders blur per the user's setting. Config gates whether the agent is asked to annotate.
- This is a UX + protocol-extension effort, not a relay-protocol change. The `MEDIA:` and `CARD:` marker recipes already exist; we extend the marker grammar and the renderers, not the transport.

---

## Verified Context

### Current state (what ships in v1.0.0)

**Inbound (agent → phone)** — `InboundAttachmentCard.kt`, `ChatImageViewer.kt`, `ChatImageContent.kt`, `MediaSaver.kt`, `MediaCacheWriter.kt`, `RelayHttpClient.kt`:

- Six render modes derived from MIME (`ChatMessage.kt:197-226`): IMAGE / VIDEO / AUDIO / PDF / TEXT / GENERIC.
- Markers parsed off the chat stream (`ChatHandler.kt:83-84, 1326-1491`): `MEDIA:hermes-relay://<token>` and bare `MEDIA:/abs/path`.
- Bytes fetched over `GET /media/{token}` or `GET /media/by-path` with bearer auth (`RelayHttpClient.kt:102-275`), cached to a FileProvider LRU dir (`MediaCacheWriter.kt:98-163`).
- **Only IMAGE gets an in-app modal** (`ChatImageViewer.kt:85-223`): pinch/pan, double-tap, share, save, close.
- VIDEO / AUDIO / PDF / TEXT / GENERIC render as a file card → tap fires `ACTION_VIEW` and **leaves the app** (`InboundAttachmentCard.kt:189-194`).
- File cards have a long-press menu: Open / Share / Save to device (`InboundAttachmentCard.kt:328-374`). Inline images do **not** — they only open the modal (`InboundAttachmentCard.kt:242-250`).
- Cards show a static emoji, not a thumbnail (`InboundAttachmentCard.kt:401-411`).
- Inbound media settings exist and are well-factored: `MediaSettings.kt` (max size, auto-fetch threshold, cellular toggle, cache cap) + `MediaSettingsScreen.kt`.
- No NSFW / blur / spoiler handling anywhere.

**Outbound (user → agent)** — `ChatInputBar.kt`, `ChatScreen.kt`, `GatewayChatClient.kt`, `ChatViewModel.kt`:

- Attach button + multi-select `OpenMultipleDocuments` picker (`ChatScreen.kt:800-827`), pending-attachment strip with remove (`ChatScreen.kt:2225-2283`).
- Gateway upload is solid: `image.attach_bytes` (+ legacy `image.attach.bytes` fallback), `pdf.attach`, `file.attach`, 60s attach timeout, size caps (`GatewayChatClient.kt:1363-1437`).
- **Defect:** on the SSE/completions fallback, pending attachments are silently dropped (`ChatViewModel.kt:3578-3595`).
- No modern Photo Picker, no camera capture, no clipboard-image paste, no upload progress.

### Upstream ceiling (what the standard path can/can't do)

Verified against the local upstream clone (`C:\Users\Bailey\Desktop\_hermes-upstream`):

- **Gateway `/api/ws` (tui_gateway)** is the standard byte-upload surface (`tui_gateway/server.py:6546-7046`): `image.attach_bytes` (base64/data-URL, 25 MB, magic-byte ext check), `pdf.attach` (base64, 50 MB / 25 pages, renders to PNG via poppler), `file.attach` (`data:<mime>;base64` → `@file:` ref).
- **api_server SSE/completions** (`api_server.py:207-322`): inbound images via OpenAI `image_url` (http(s) **or** `data:` URL) only. No multipart, no file upload, `audio_api:false`. Rejects `file`/`input_file` parts. **This means outbound images *can* ride the SSE path as inline `image_url` data-URLs — we just aren't doing it.**
- **Agent → client** has **no** structured attachment object. Push adapters (Telegram/Slack) regex markdown `![](url)` out of text (`base.py:2708-2773`); the streaming HTTP surface passes `MEDIA:` through as literal text — which is exactly why the relay `/media` layer exists (`spec.md §6.2a`).

### Gateway UX patterns worth emulating (not their protocol)

- **Telegram**: media-group debounce → multiple photos become one logical message → grid/gallery; image-documents route to vision by MIME; **`has_spoiler`** is the canonical sensitive-media flag model (the adapter doesn't surface it, but the shape is right).
- **Slack**: rich per-file metadata (MIME / size / owner) travels with each download.
- **Both**: captions on media; in-app preview, never "open in another app."

### Relay media layer — extension points (`plugin/relay/media.py`, `plugin/relay/server.py`)

- Token mint (`secrets.token_urlsafe(16)`), in-memory `OrderedDict` LRU (default 500 / 24h TTL), loopback-only register, bearer-auth fetch, optional path sandbox.
- `register_media()` (`plugin/relay/client.py`) is the in-process tool entry point; `android_screenshot` is the primary producer today.
- A magic-byte image validator already exists for clipboard inbox (`_IMAGE_MAGIC` in `server.py`) — reusable for inbound content re-sniffing.
- Clean places to add metadata: `_MediaEntry` dataclass fields, the `register` signature, and `/media/{token}` response headers.

---

## Design

Every item is tagged **[Standard]** (vanilla upstream), **[Client]** (pure app-side, source-agnostic), or **[Relay]** (needs our plugin).

### A. In-app preview modals (Telegram parity)

Goal: tapping any attachment previews it **inside** the app. The current "leave to `ACTION_VIEW`" path stays as an explicit "Open externally" affordance, never the default.

| Item | Tag | Design |
|---|---|---|
| A1 — Card thumbnails | **[Client]** generate locally; **[Relay]** server-side thumb to avoid full download | Image: decode a downscaled bitmap. Video: first-frame via `MediaMetadataRetriever`. PDF: first page via `PdfRenderer`. Falls back to the existing emoji when generation fails. |
| A2 — Video modal | **[Client]** | Media3/ExoPlayer is already a dependency (`VoicePlayer.kt`). Inline poster + play overlay → fullscreen player (scrub, mute, fullscreen toggle). |
| A3 — Audio mini-player | **[Client]** | Reuse `VoicePlayer` + `Visualizer`: inline waveform + play/scrub, no app switch. |
| A4 — PDF viewer | **[Client]** | `android.graphics.pdf.PdfRenderer` (built-in, API 21+): page thumbnails + paginated modal. No third-party dep. |
| A5 — Text/code viewer | **[Client]** | In-app monospace viewer reusing the message markdown renderer for the TEXT mode. |
| A6 — Multi-image gallery | **[Client]** | N images in one message → grid; viewer supports horizontal swipe across the set (Telegram media-group parity). |

New component sketch: a single `AttachmentViewer` modal that dispatches on `renderMode` (image/video/audio/pdf/text), with `ChatImageViewer` becoming its IMAGE case. Keeps one entry point and one share/save toolbar.

### B. Save without opening (close the gaps; plumbing already exists)

`MediaSaver.kt` already does MediaStore scoped-storage save + share-sheet fallback. Remaining gaps:

| Item | Tag | Design |
|---|---|---|
| B1 — Long-press menu on inline images | **[Client]** | Add the same Open / Share / Save menu images currently lack; today they jump straight to the modal. |
| B2 — One-tap save affordance | **[Client]** | Small download-icon overlay on cards and in the viewer toolbar (the viewer already has Save; make it consistent on cards). |
| B3 — Download progress + cancel | **[Client]** | In-flight fetch is currently un-cancelable (`InboundAttachmentCard` LoadingCard). Add determinate progress where Content-Length is known + a cancel target. |

### C. Sensitive-media blur (configurable) — model-emitted metadata

Per the design directive: **automatic sensitivity is decided server-side, ideally by the model itself, and shipped as metadata.** No relay-side classifier, no on-device model. The same flag carries both an explicit spoiler and the model's own judgment — there is only one mechanism.

**Three concerns, separated:**

1. **Deciding** (server-side): the agent flags media as sensitive when it surfaces it. Config-gated — see below.
2. **Transporting** the flag: the relay path uses an authoritative response **header** (`X-Media-Sensitive`); the standard path uses a **markdown convention**. (An earlier marker-suffix idea was dropped for back-compat — see C3.)
3. **Rendering** the blur (client-side): pure app-side, source-agnostic.

| Item | Tag | Design |
|---|---|---|
| C1 — Client blur renderer | **[Client]** | Blurred placeholder (Compose `Modifier.blur` / `RenderEffect`) + an eye-off / sensitive icon + "Tap to reveal". Reveal state is per-attachment. Applies in cards, inline images, and the modal. |
| C2 — Setting | **[Client]** | New `MediaSettings` field `blurMode: Off / Flagged (default) / AllImages`, added alongside the existing four knobs in `MediaSettings.kt` + `MediaSettingsScreen.kt`. `Flagged` honors the model/relay flag; `AllImages` blurs every inbound image regardless of source (works on pure standard path with zero server support). |
| C3 — Flag transport (standard path) — **shipped** | **[Standard]** convention | **Marker-suffix idea dropped:** a `?sensitive=1` segment would be swallowed by the greedy bare-path regex `MEDIA:(/\S+)` and break fetch on older clients. For markdown-only public images (no `MEDIA:` marker, vanilla upstream), use a markdown convention — Telegram-style spoiler wrap `||![alt](url)||` and/or an alt-text sentinel (`![sensitive]`, `![nsfw]`). Implemented in `ChatImageContent.kt`. |
| C4 — Flag transport (relay header, authoritative) — **shipped, primary carrier** | **[Relay]** | `register_media(..., sensitive: bool)` stores it on `_MediaEntry`; `/media/{token}` and `/media/by-path` return `X-Media-Sensitive: 1`; `RelayHttpClient.FetchedMedia.sensitive` reads the header → `Attachment.sensitive`. Back-compat-safe and authoritative (a marker can be stripped/edited; registered metadata can't). |
| C5 — Config gate | **[Relay]** / agent | A relay/agent-side switch (env or per-profile, e.g. `RELAY_MEDIA_SENSITIVITY_HINTS`) controls whether the agent is instructed to annotate sensitive media via the prompt-builder. Off → no flags emitted → client behaves as today (or per `AllImages` if the user opted in). |

**Why no classifier:** the model already has full context on what it's surfacing (it generated or fetched it). Asking it to emit a one-bit flag as metadata is cheaper, more accurate, and avoids shipping an ML model into the relay or the app. The relay's only job is faithful transport of that bit.

### D. Related / full-suite

| Item | Tag | Design |
|---|---|---|
| D1 — Fix SSE attachment drop | **[Standard]** | On the completions/SSE path, attach images as inline `image_url` data-URLs (upstream supports this). PDFs/files can't ride SSE → surface an explicit "attachments need the gateway transport" notice instead of silently dropping. **Verify** whether `/api/sessions/{id}/chat/stream` reuses the same multimodal normalizer before relying on it there. |
| D2 — Android Photo Picker | **[Standard]** | Use `PickVisualMedia` / `PickMultipleVisualMedia` for images — permissionless, modern. Keep `OpenMultipleDocuments` for arbitrary files. |
| D3 — Camera capture | **[Standard]** | `CAMERA` is already declared; add a capture affordance (`TakePicture` → FileProvider temp → attach). |
| D4 — Clipboard-image paste | **[Standard]** | Desktop `/paste` parity (`desktop/src/chatAttach.ts`): read a bitmap off the clipboard into a pending attachment. |
| D5 — Upload progress | **[Standard]** | Per-attachment progress in the pending strip during the 60s PDF-render window; currently no feedback. |
| D6 — Relay thumbnails + content re-sniff | **[Relay]** | Generate a small thumbnail on register so cards/galleries don't pull full-size bytes; surface via `X-Thumbnail-Token` (or a `/media/{token}/thumb` route). Reuse the existing `_IMAGE_MAGIC` table to re-sniff inbound content vs. the claimed `Content-Type`. |
| D7 — Captions | **[Client]** | Render text adjacent to a `MEDIA:` marker as the attachment caption (Telegram/Discord parity). |

---

## Phasing (standard-path-first; relay extras folded into each phase where cheap)

- **Phase 1 — In-app previews (A1–A6, D7).** Biggest visible payoff, almost entirely client-side, helps standard *and* relay. Stops attachments kicking users out of the app. Relay thumbnails (D6) can land here or Phase 4.
- **Phase 2 — Polish + correctness (B1–B3, D1, D2, D5).** Save-menu gaps, SSE drop fix, modern photo picker, upload progress.
- **Phase 3 — Sensitive-media blur (C1–C5).** Client renderer + setting first (C1–C2 ship value alone), then marker/relay transport + config gate.
- **Phase 4 — Remaining relay enhancements (C4 reinforcement, D6 content re-sniff, D3, D4).** Authoritative metadata, server thumbnails, camera, clipboard.

---

## Spec/doc updates at implementation time

- `docs/spec.md §6.2a` — extend the `MEDIA:` grammar (sensitivity segment), document the in-app preview dispatch and the new `AttachmentViewer`, retire the "VIDEO/AUDIO/PDF render as tap-to-open file cards" description as each lands.
- `docs/spec.md §3.4 / docs/security.md` — note the sensitive-flag trust model: model-emitted, relay-reinforced, client-honored; no classifier.
- `docs/decisions.md` — a short ADR for the sensitivity-metadata design (one-bit, model-emitted, transport-agnostic) is warranted given it touches the marker grammar.
- `user-docs/` — a guide page for the attachment experience (previews, save, blur setting).
- `CHANGELOG.md [Unreleased]` per phase.

---

## Open questions / verify-first

1. **Sessions SSE multimodal:** does `/api/sessions/{id}/chat/stream` run the same `image_url` normalization as `/v1/chat/completions` (`api_server.py:207-322`)? Decides whether D1 covers the sessions transport or only completions.
2. **Marker grammar back-compat:** confirm the `?sensitive=1` suffix doesn't collide with any existing token charset (`[A-Za-z0-9_-]`) or bare-path parsing; the parser must strip it cleanly on old clients (forward-compat: unknown suffix should be ignored, media still renders).
3. **Blur default:** ship `Flagged` as default (honor server flags, no blur when none) vs. a more conservative default. Recommendation: `Flagged`.
4. **PdfRenderer min-SDK:** API 21+ covers min-SDK 26; confirm no flavor-specific gaps.
5. **Config-gate home:** relay env (`RELAY_MEDIA_SENSITIVITY_HINTS`) vs. per-profile setting vs. both. Recommendation: env default + optional per-profile override.
