# Hermes-Relay Release Video Pipeline

Human-in-the-loop pipeline for Hermes-Relay release videos and X post packages.

The pipeline automates draft generation, render, QA, approval-state recording,
and post-package prep. Public posting remains explicitly human-gated unless an
approved package is configured for automation.

## Generate latest release draft

```bash
cd video-pipelines/hermes-relay-release
./scripts/generate_release_video.py --clean
```

## Generate a specific release draft

```bash
./scripts/generate_release_video.py --tag android-v1.3.0 --clean
```

## Generate from local release metadata

Use `--release-json` before a GitHub Release exists, or when rendering an
already-captured release payload.

```bash
./scripts/generate_release_video.py \
  --tag android-v1.3.0 \
  --release-json /path/to/release.android-v1.3.0.json \
  --audio assets/audio/hermes-relay-v13-dubstep-technical-bass-37s.mp3 \
  --clean
```

Audio selection is intentionally fresh-per-release:

1. explicit `--audio`;
2. staged `assets/audio/hermes-relay-v<major><minor>-*.mp3`;
3. generated Lyria bed using `GEMINI_API_KEY`;
4. legacy `manifest.defaults.json` audio only when `--allow-default-audio` is
   passed explicitly.

The default output root is `out/packages/<tag>/` under this pipeline directory.
Generated packages are ignored by git.

## Draft package layout

```text
out/packages/<tag>/
├── manifest.json
├── POST_DRAFT.md
├── QA.md
├── index.html
├── assets/
└── out/
    ├── hermes-relay-<tag>.mp4
    └── frames/
        └── contact-sheet.png
```

## Human gate before X post

Review:

1. `out/hermes-relay-<tag>.mp4`
2. `out/frames/contact-sheet.png`
3. `QA.md`
4. `POST_DRAFT.md`

Approve/promote a draft package:

```bash
./scripts/approve_release_package.py \
  --workdir out/packages/android-v1.3.0 \
  --release-tag android-v1.3.0 \
  --approved APPROVED_FOR_RELEASE \
  --clean
```

Dry-run a post package:

```bash
./scripts/post_approved.py \
  --workdir out/approved/YYYY-MM-DD-release-video-android-v1.3.0 \
  --approved APPROVED_FOR_RELEASE \
  --dry-run
```

Non-dry-run posting requires `X_POST_COMMAND` to be configured. The command may
use `{video}` and `{caption}` placeholders; if omitted, the video path and
caption are appended as trailing arguments.

## QA requirements

- `npm run check` has zero errors.
- Rendered video has video and audio streams.
- Duration matches selected audio or explicit manifest duration.
- Audio loudness is sane.
- Contact sheet has no blank/broken frames.
- On-screen copy has no literal template stubs and no unreplaced `{{TOKENS}}`.
- Media-stack layout keeps the phone frame and file chips inside the media
  column.

## Notes

- The pipeline reads published release metadata with `gh release view`.
- Preview packages can use local metadata via `--release-json`.
- Source screenshots resolve relative to `source_repo_path` in
  `manifest.defaults.json` or an override config.
- Keep final video copy release/product-focused; design-style labels are
  production notes only, not on-screen public wording.
