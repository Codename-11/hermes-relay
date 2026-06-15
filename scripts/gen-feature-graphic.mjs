// scripts/gen-feature-graphic.mjs
//
// Regenerates the Google Play Store feature graphic
// (assets/play-store-feature-1024x500.png) from an inline SVG, so the asset is
// reproducible instead of a mystery binary. Edit the SVG below (tagline, trio,
// palette) and re-run to update the banner.
//
// Palette: RelayRefresh — base #08090D, electric-indigo accent
// (#6E7CFF / #4F5BD5 / #3A44B8), warm-white ink #F4F1E9. Mark = the "Chevron
// Compass" from assets/logo.svg, recolored from the legacy purple to indigo.
//
// Rasterizer: @resvg/resvg-js (Rust resvg — exact pixel dims, no headless
// browser). It is not a declared dependency; it is present transitively via
// vitepress (the docs toolchain). Run from the repo root so Node resolves it
// from the root node_modules:
//
//     node scripts/gen-feature-graphic.mjs
//
// Play feature-graphic spec: exactly 1024x500 PNG/JPEG, < 15 MB.

import { Resvg } from '@resvg/resvg-js'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const OUT = fileURLToPath(new URL('../assets/play-store-feature-1024x500.png', import.meta.url))

const svg = `<svg width="1024" height="500" viewBox="0 0 1024 500" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#0B0C16"/>
      <stop offset="1" stop-color="#08090D"/>
    </linearGradient>
    <radialGradient id="glow" cx="50%" cy="22%" r="42%">
      <stop offset="0" stop-color="#6E7CFF" stop-opacity="0.16"/>
      <stop offset="1" stop-color="#6E7CFF" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="mark" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#8E9BFF"/>
      <stop offset="1" stop-color="#4F5BD5"/>
    </linearGradient>
    <linearGradient id="ghost" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#6E7CFF" stop-opacity="0.30"/>
      <stop offset="1" stop-color="#6E7CFF" stop-opacity="0.04"/>
    </linearGradient>
  </defs>

  <rect width="1024" height="500" fill="url(#bg)"/>
  <rect width="1024" height="500" fill="url(#glow)"/>

  <g stroke="#6E7CFF" stroke-opacity="0.05" stroke-width="22" fill="none" stroke-linecap="round" stroke-linejoin="round">
    <path d="M -40 150 L 110 0 L 260 150"/>
    <path d="M 764 500 L 914 350 L 1064 500"/>
  </g>

  <g transform="translate(412,40) scale(0.39)" fill="none" stroke-linecap="round" stroke-linejoin="round">
    <g transform="translate(256,256) scale(1.30) translate(-256,-256)" stroke="url(#ghost)" stroke-width="22">
      <path d="M 112 188 L 192 268 L 320 268 L 400 188"/>
      <path d="M 112 116 L 192 196"/>
      <path d="M 400 116 L 320 196"/>
    </g>
    <g stroke="url(#mark)" stroke-width="36">
      <path d="M 256 192 L 320 256 L 256 320 L 192 256 Z"/>
      <path d="M 128 192 L 256 64 L 384 192"/>
      <path d="M 128 320 L 256 448 L 384 320"/>
    </g>
  </g>

  <text x="512" y="288" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif"
        font-size="68" font-weight="700" letter-spacing="-1" fill="#F4F1E9">Hermes-Relay</text>

  <text x="512" y="330" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif"
        font-size="20" font-weight="600" letter-spacing="5" fill="#6E7CFF">YOUR HERMES AGENT, IN YOUR POCKET</text>

  <line x1="462" y1="356" x2="562" y2="356" stroke="#3A44B8" stroke-width="2" stroke-linecap="round"/>

  <g font-family="Segoe UI, Arial, sans-serif" text-anchor="middle">
    <text x="256" y="415" font-size="27" font-weight="700" letter-spacing="1" fill="#F4F1E9">Chat</text>
    <text x="512" y="415" font-size="27" font-weight="700" letter-spacing="1" fill="#F4F1E9">Voice</text>
    <text x="768" y="415" font-size="27" font-weight="700" letter-spacing="1" fill="#F4F1E9">Manage</text>

    <text x="256" y="443" font-size="14" font-weight="600" letter-spacing="2" fill="#68647D">LIVE STREAMING</text>
    <text x="512" y="443" font-size="14" font-weight="600" letter-spacing="2" fill="#68647D">HANDS-FREE</text>
    <text x="768" y="443" font-size="14" font-weight="600" letter-spacing="2" fill="#68647D">FULL DASHBOARD</text>
  </g>

  <circle cx="384" cy="406" r="3" fill="#4F5BD5"/>
  <circle cx="640" cy="406" r="3" fill="#4F5BD5"/>
</svg>`

const resvg = new Resvg(svg, {
  fitTo: { mode: 'width', value: 1024 },
  background: '#08090D',
  font: { loadSystemFonts: true, defaultFontFamily: 'Segoe UI' },
})
const png = resvg.render().asPng()
writeFileSync(OUT, png)
console.log('wrote', OUT, '—', png.length, 'bytes')
