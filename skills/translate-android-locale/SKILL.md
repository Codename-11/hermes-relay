---
name: translate-android-locale
description: Generate, critique, install, and validate AI-assisted Android string-resource translations for Hermes-Relay. Use when adding a locale, refreshing a locale after English changes, or applying canonical translation corrections.
---

# Translate Android locale

1. Read `docs/translation-playbook.md` and `docs/localization.md` completely.
2. Prepare a non-shipping draft:

   ```bash
   python scripts/android-locale-harness.py prepare <bcp47-tag> "<native name>"
   ```

3. Translate both draft catalogs under `build/i18n/<tag>/` in semantic batches.
   Protect resource identifiers, XML, markup, escapes, and placeholders.
4. Use a fresh model context to critique meaning, naturalness, glossary
   consistency, and sensitive strings. Apply justified corrections.
5. Install only after the draft is complete:

   ```bash
   python scripts/android-locale-harness.py install <bcp47-tag>
   ```

6. Add the locale to `AppLanguage`, the Appearance label map, picker-label
   resources, `locales_config.xml`, and `AppLanguageTest`.
7. Keep the registry at `ai-translated` until a fluent review PR is recorded.
8. Run the locale validator, both flavor compilations, and Android lint as
   documented in `docs/localization.md`.

Keep one locale per commit. Do not mix signing, version, or release changes into
translation commits. Apply later corrections to the canonical locale catalog
and preserve contributor credit.
