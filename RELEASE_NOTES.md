# Hermes-Relay-Android v1.4.3

**Release Date:** July 11, 2026

**Since v1.4.2:** The app language can now be changed directly from Settings → Appearance. Choose System default, English, or Simplified Chinese without leaving Hermes-Relay.

v1.4.3 is recommended for multilingual users. The picker stays synchronized with Android's per-app language setting and persists the choice on Android 12 and lower. This Android-only patch does not require a relay plugin update.

Release packaging now also rejects Java 21 list endpoint calls that are unavailable before Android API 35, covering both first-party Kotlin and bundled dependency bytecode.

---

## Download

**Installing on your phone?** Download hermes-relay-1.4.3-sideload-release.apk and tap it — that's the direct-install build with the full feature set (installs as com.axiomlabs.hermesrelay.sideload). Prefer the conservative build (no Device Control surface)? Get it from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The other file, hermes-relay-1.4.3-googlePlay-release.aab, is an Android App Bundle for uploading to Play Console — it **cannot** be installed by tapping it on a phone.

Verify integrity with SHA256SUMS.txt from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Change language without leaving the app

- **Pick the app language in Appearance.** System default, English, and 简体中文 are available in a wrapping, accessible selector.
- **Stay synchronized with Android.** A selection made in Hermes-Relay appears in Android's per-app language setting, and a system-side selection is reflected in the app.
- **Keep older devices supported.** AppCompat stores and restores the language choice on Android 12 and lower.

### Built for the existing localization system

- The picker reads the same English and Simplified Chinese catalogs introduced in 1.4.2.
- Future locales use the same explicit registry: add the catalog, locale-configuration entry, picker label, and tag-resolution test.

### Safer Android compatibility checks

- Kotlin source checks reject `removeFirst()` and `removeLast()` list calls in favor of explicit indexed removal.
- Release CI scans the final minified APK DEX, catching incompatible calls introduced by transitive dependencies or build-tool changes.

---

## Upgrade notes

- App version: **1.4.3** (versionCode **25**).
- No relay plugin update is required for localization support.
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
