# Hermes-Relay-Android v1.4.2

**Release Date:** July 11, 2026

**Since v1.4.1:** Hermes-Relay now supports Simplified Chinese throughout the Android app. Android's per-app language settings can switch between English and Simplified Chinese, and the localization structure, validation, and contributor documentation make future languages easier to add safely.

v1.4.2 is recommended for Simplified Chinese users and anyone contributing a translation. This is an Android and documentation release; it does not require a relay plugin update. Standard chat and Vanilla Hermes voice remain compatible with unmodified upstream Hermes.

---

## Download

**Installing on your phone?** Download hermes-relay-1.4.2-sideload-release.apk and tap it — that's the direct-install build with the full feature set (installs as com.axiomlabs.hermesrelay.sideload). Prefer the conservative build (no Device Control surface)? Get it from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The other file, hermes-relay-1.4.2-googlePlay-release.aab, is an Android App Bundle for uploading to Play Console — it **cannot** be installed by tapping it on a phone.

Verify integrity with SHA256SUMS.txt from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Simplified Chinese throughout the app

- **Use the complete Android experience in Simplified Chinese.** Onboarding, connection setup, Chat, Manage, Voice, settings, diagnostics, notifications, accessibility labels, and both product flavors are localized.
- **Switch languages with Android.** Supported Android versions expose English and Simplified Chinese through the system's per-app language settings; other versions follow the device language.
- **Get locale-correct counts.** Connection scan results and queued-message counts use Android plurals instead of English-only suffix formatting.

### Translation support that can grow

- **Prevent incomplete catalogs.** CI checks resource names, plural structure, and format-argument parity against canonical English resources.
- **Start from documented conventions.** A localization guide, translated README, and Simplified Chinese user-doc entry points define how to add and maintain another language.
- **Preserve contributor credit.** The contribution workflow documents how maintainers salvage valuable stale translation PRs onto current `dev` while retaining original authorship.

---

## Upgrade notes

- App version: **1.4.2** (versionCode **24**).
- No relay plugin update is required for localization support.
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
