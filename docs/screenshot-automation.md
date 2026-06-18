# Screenshot Automation

Hermes-Relay keeps screenshot sources in `assets/screenshots/` and exports
Google Play-ready graphics into the Gradle Play Publisher metadata tree:

```text
app/src/googlePlay/play/listings/en-US/graphics/
  icon/1.png
  feature-graphic/1.png
  phone-screenshots/1.png ... 8.png
```

The scene list and export rules live in `docs/media/screenshots.json`.

## Commands

List the canonical scenes:

```bash
python scripts/screenshots.py list
```

Capture a single scene from the connected ADB device:

```bash
python scripts/screenshots.py capture 02_chat --demo --force
```

Export the committed sources to Play metadata:

```bash
python scripts/screenshots.py export --target play
```

Validate source images and exported Play graphics:

```bash
python scripts/screenshots.py validate
```

Running `python scripts/screenshots.py` with no arguments opens the original
interactive capture helper.

## Publishing

Android tag releases do not republish screenshots, title, description, icon,
or feature graphic. The release workflow keeps using the bundle-only
`publishGooglePlayReleaseBundle` task for the app upload and release notes.

When store assets or listing copy change, use the **Play Store Listing**
workflow. It validates only when the screenshot plan, source/exported Play
graphics, listing files, or screenshot script change; publishing metadata to
Play Console is manual through the workflow dispatch input.

For local publishing with a configured `play-service-account.json`:

```bash
python scripts/screenshots.py validate
./gradlew publishGooglePlayReleaseListing
```

## Capture Rules

- Use neutral, public-safe data only: no private hostnames, IPs, profile names,
  keys, tokens, internal server names, or personal notification content.
- Keep Android demo mode on for clean status-bar captures when the device
  supports it.
- The README sources are currently `1080x2244`. The Play export crops them to
  `1080x2160` because Google Play requires screenshot dimensions between
  320 and 3840 pixels and the long side cannot exceed twice the short side.
- Google Play listing upload stays on Gradle Play Publisher, matching the
  existing Android publishing toolchain. Fastlane is not required.
