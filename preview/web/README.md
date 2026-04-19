# MorphingSphere — browser preview

A zero-dependency browser harness for iterating on the ASCII morphing sphere
without Android Studio or an emulator.

## Run

```bash
# From repo root:
python3 -m http.server 8000 --directory preview/web
# then open http://localhost:8000/
```

Or open `preview/web/index.html` directly in a browser — the ES module import
requires `file://` access (works in Firefox, blocked in Chrome); the local
server path above works everywhere.

## Controls

- **State** — Idle · Thinking · Streaming · Listening · Speaking · Error
- **Voice mode** — expands the sphere radius ~8%
- **Voice amp** — 0..1, modulates Listening/Speaking behavior
- **Intensity** — general activity boost
- **Tool burst** — transient spike (ripples, turbulence)
- **Pause** — freezes animation; <kbd>Space</kbd> toggles
- **Reset t** — restart the time origin
- Keyboard: <kbd>1</kbd>..<kbd>6</kbd> picks a state

## How this relates to the Kotlin source

`sphere.js` is a **line-for-line mirror** of
`app/src/main/kotlin/com/hermesandroid/relay/ui/components/MorphingSphereCore.kt`.

When you change the algorithm:

1. Edit `MorphingSphereCore.kt` (the source of truth)
2. Mirror the same change in `sphere.js`
3. Reload the browser to verify

This keeps Android + browser visually consistent. The only deliberate
differences are:

- **Font** — Android uses `FontFamily.Monospace`; browser uses the OS default
  mono. Glyph widths and antialiasing differ slightly. Bundle JetBrains Mono
  if you need pixel parity across surfaces.
- **32-bit integer math** — the hash function uses `Math.imul` + `|0` to
  match Kotlin's `Int` overflow semantics exactly.
- **`.mod(n)`** — Kotlin's floored-positive modulo vs JS `%` sign-preserving;
  the port handles this in the data ring code.
