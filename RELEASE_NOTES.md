# Hermes-Relay-Android v1.4.4

**Release Date:** July 12, 2026

This patch adds Spanish across Android, clearer Relay diagnostics, safer translation maintenance, and a direct path from What’s New to the complete release history.

## Highlights

- Choose System default, English, Español, or 简体中文 in Settings → Appearance.
- Refresh Diagnostics to see Relay plugin and protocol versions, capabilities, profile enablement, and when the check ran.
- Diagnostic issue exports include sanitized app, Android, and device context.
- What’s New opens the complete bundled release history and has large-text visual regression coverage.
- CI detects translated catalogs whose canonical English source has changed.
- Profile-scoped session operations and recovery no longer fall through to the default profile.

## Download

Install `hermes-relay-1.4.4-sideload-release.apk` directly, or use the conservative Google Play build. The `.aab` artifact is for Play Console and cannot be installed directly.

## Upgrade notes

- App version: **1.4.4** (versionCode **26**).
- The new diagnostics contract requires a current Relay plugin for full detail; older plugins remain supported and report version unknown.
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
