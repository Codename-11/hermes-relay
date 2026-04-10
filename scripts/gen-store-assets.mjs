/**
 * Generate Google Play Store listing assets from assets/logo.svg.
 *
 * Outputs:
 *   assets/play-store-icon-512.png      — 512x512 hi-res icon
 *   assets/play-store-feature-1024x500.png — 1024x500 feature graphic
 *
 * Usage: node scripts/gen-store-assets.mjs
 */

import { Resvg } from '@resvg/resvg-js';
import { readFileSync, writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const assetsDir = resolve(root, 'assets');

// ── 1. 512x512 Play Store Icon ──────────────────────────────────────────

const logoSvg = readFileSync(resolve(assetsDir, 'logo.svg'), 'utf8');

const iconResvg = new Resvg(logoSvg, {
  fitTo: { mode: 'width', value: 512 },
  background: 'rgba(0,0,0,0)',
});
const iconPng = iconResvg.render().asPng();
const iconPath = resolve(assetsDir, 'play-store-icon-512.png');
writeFileSync(iconPath, iconPng);
console.log(`✓ ${iconPath} (${iconPng.length} bytes)`);

// ── 2. 1024x500 Feature Graphic ─────────────────────────────────────────
// Dark background with centered logo (scaled down) + app name + tagline

const featureSvg = `
<svg viewBox="0 0 1024 500" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#0F0F1E"/>
      <stop offset="100%" stop-color="#1A1A2E"/>
    </linearGradient>
    <linearGradient id="fg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#9B6BF0"/>
      <stop offset="100%" stop-color="#6B35E8"/>
    </linearGradient>
    <linearGradient id="ghost" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#6B35E8" stop-opacity="0.35"/>
      <stop offset="100%" stop-color="#9B6BF0" stop-opacity="0.05"/>
    </linearGradient>
    <!-- Subtle grid pattern for background texture -->
    <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
      <path d="M 40 0 L 0 0 0 40" fill="none" stroke="#ffffff" stroke-opacity="0.03" stroke-width="0.5"/>
    </pattern>
  </defs>

  <!-- Background -->
  <rect width="1024" height="500" fill="url(#bg)"/>
  <rect width="1024" height="500" fill="url(#grid)"/>

  <!-- Decorative: large faded chevrons in background corners -->
  <g opacity="0.05">
    <path d="M -30 80 L 70 -20 L 170 80" stroke="#9B6BF0" stroke-width="40" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
    <path d="M 854 80 L 954 -20 L 1054 80" stroke="#9B6BF0" stroke-width="40" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
    <path d="M -30 420 L 70 520 L 170 420" stroke="#9B6BF0" stroke-width="40" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
    <path d="M 854 420 L 954 520 L 1054 420" stroke="#9B6BF0" stroke-width="40" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
  </g>

  <!-- Logo icon — centered at (512, 130), scale 0.4 of 512 viewBox -->
  <!-- translate places the 256,256 center of the logo artwork at (512, 130) -->
  <g transform="translate(512, 130) scale(0.4) translate(-256, -256)">
    <!-- Ghost layer -->
    <g transform="translate(256, 256) scale(1.35) translate(-256, -256)" stroke="url(#ghost)" stroke-width="24" stroke-linecap="round" stroke-linejoin="round" fill="none">
      <path d="M 112 188 L 192 268 L 320 268 L 400 188"/>
      <path d="M 112 116 L 192 196"/>
      <path d="M 400 116 L 320 196"/>
    </g>
    <!-- Foreground -->
    <g stroke="url(#fg)" stroke-width="36" stroke-linecap="round" stroke-linejoin="round" fill="none">
      <path d="M 256 192 L 320 256 L 256 320 L 192 256 Z"/>
      <path d="M 128 192 L 256 64 L 384 192"/>
      <path d="M 128 320 L 256 448 L 384 320"/>
    </g>
  </g>

  <!-- App name -->
  <text x="512" y="295" text-anchor="middle"
        font-family="'Segoe UI', 'Roboto', 'Helvetica Neue', Arial, sans-serif"
        font-size="48" font-weight="700" fill="#FFFFFF" letter-spacing="2">
    Hermes-Relay
  </text>

  <!-- Tagline -->
  <text x="512" y="330" text-anchor="middle"
        font-family="'Segoe UI', 'Roboto', 'Helvetica Neue', Arial, sans-serif"
        font-size="16" fill="#9B6BF0" letter-spacing="4" font-weight="300">
    YOUR AI AGENT, IN YOUR POCKET
  </text>

  <!-- Divider line -->
  <rect x="432" y="350" width="160" height="1.5" rx="1" fill="url(#fg)" opacity="0.3"/>

  <!-- Three channel features -->
  <g font-family="'Segoe UI', 'Roboto', 'Helvetica Neue', Arial, sans-serif" text-anchor="middle">
    <!-- Chat -->
    <text x="250" y="395" font-size="22" font-weight="600" fill="#FFFFFF">Chat</text>
    <text x="250" y="418" font-size="12" fill="#8E8EA0" letter-spacing="1">SSE STREAMING</text>

    <!-- Divider dot -->
    <circle cx="430" cy="405" r="2.5" fill="#6B35E8" opacity="0.6"/>

    <!-- Terminal -->
    <text x="512" y="395" font-size="22" font-weight="600" fill="#FFFFFF">Terminal</text>
    <text x="512" y="418" font-size="12" fill="#8E8EA0" letter-spacing="1">SECURE SHELL</text>

    <!-- Divider dot -->
    <circle cx="594" cy="405" r="2.5" fill="#6B35E8" opacity="0.6"/>

    <!-- Bridge -->
    <text x="774" y="395" font-size="22" font-weight="600" fill="#FFFFFF">Bridge</text>
    <text x="774" y="418" font-size="12" fill="#8E8EA0" letter-spacing="1">DEVICE CONTROL</text>
  </g>

  <!-- Subtle bottom edge glow -->
  <rect x="0" y="495" width="1024" height="5" fill="url(#fg)" opacity="0.15"/>
</svg>
`;

const featureResvg = new Resvg(featureSvg, {
  fitTo: { mode: 'width', value: 1024 },
  font: {
    loadSystemFonts: true,
  },
});
const featurePng = featureResvg.render().asPng();
const featurePath = resolve(assetsDir, 'play-store-feature-1024x500.png');
writeFileSync(featurePath, featurePng);
console.log(`✓ ${featurePath} (${featurePng.length} bytes)`);

console.log('\nDone. Upload these to Google Play Console.');
