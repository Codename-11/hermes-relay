# Diagrams

The published architecture diagram for Hermes-Relay.

## Files

| File | Role |
|------|------|
| `architecture-homepage.svg` | **Published asset** — scalable, dependency-free. Embedded at the top of the docs Architecture page; a copy lives at `user-docs/public/architecture-homepage.svg`. |
| `architecture-homepage.png` | 2× raster fallback — used in the root `README.md` (GitHub renders PNG reliably; relative SVG can be sanitized). |
| `architecture-homepage.excalidraw` | Editable source in Excalidraw format. Open at [excalidraw.com](https://excalidraw.com) or the VS Code Excalidraw extension to tweak and export. |

## One model, three representations — keep them in sync

The same connection/capability model is drawn for three audiences. When the architecture changes, update **all three**, then re-copy the SVG and refresh the PNG:

1. **`docs/path-architecture.html`** — full engineering reference (transport resolution, the build-flavor axis). Source of truth for *detail*.
2. **`user-docs/.vitepress/theme/components/CombineModel.vue`** — the interactive "how the pieces combine" tiers in the docs site.
3. **`docs/diagrams/architecture-homepage.{svg,excalidraw}`** — the at-a-glance overview (this folder), mirrored to `user-docs/public/` and the README PNG.

Canonical gating facts they all derive from: `app/src/main/kotlin/com/hermesandroid/relay/data/FeatureFlags.kt` (`BuildFlavor`) and `docs/upstream-surface-matrix.md`.

## Regenerating

- **Edit the SVG** directly (plain SVG, system fonts) — or edit the `.excalidraw` in Excalidraw and export to SVG.
- **Refresh the PNG** the easy way: open the SVG in a browser and "save/export as image." Or reproducibly, via the excalidraw-diagram skill's Chromium (no network needed for a local SVG):
  ```bash
  REPO=/path/to/hermes-relay
  cd ~/.agents/skills/excalidraw-diagram/references
  uv run python - "$REPO/docs/diagrams/architecture-homepage.svg" <<'PY'
  import sys
  from pathlib import Path
  from playwright.sync_api import sync_playwright
  svg = Path(sys.argv[1]).resolve(); out = svg.with_suffix(".png")
  with sync_playwright() as p:
      b = p.chromium.launch(headless=True)
      pg = b.new_page(viewport={"width": 1240, "height": 596}, device_scale_factor=2)
      pg.goto(svg.as_uri()); pg.wait_for_timeout(500); pg.screenshot(path=str(out)); b.close()
  print(out)
  PY
  ```
- **Render the `.excalidraw` → PNG** via the skill (needs the headless browser to reach `esm.sh`, i.e. a network-capable terminal):
  ```bash
  cd ~/.agents/skills/excalidraw-diagram/references
  uv run python render_excalidraw.py "$REPO/docs/diagrams/architecture-homepage.excalidraw"
  ```
- After changing the SVG, re-copy it to `user-docs/public/architecture-homepage.svg`.
