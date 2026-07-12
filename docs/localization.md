# Localization

English is the canonical product language. Android currently ships Simplified
Chinese as `values-b+zh+Hans`; additional languages can be added without changing
the runtime architecture.

Translation coverage and linguistic verification are separate. Shipped locale
status is recorded in `docs/localization-status.json` as `ai-translated`,
`community-reviewed`, or `verified`. AI-assisted catalogs may ship after the
technical gates pass; the status must not imply human review that did not occur.
See `docs/translation-playbook.md` for the required translation and critique
workflow.

Users can switch between System default, English, and Simplified Chinese from
Settings → Appearance → Language. The picker stays synchronized with Android's
per-app language setting; Android 12 and lower use AppCompat's automatic locale
storage.

## Android resource contract

- Canonical resources live in `app/src/main/res/values/strings.xml`.
- Locale catalogs live in Android-qualified directories such as
  `values-b+zh+Hans`, `values-b+zh+Hant`, `values-es`, or `values-b+pt+BR`.
- Use script-qualified Chinese directories. Do not put Simplified Chinese in
  generic `values-zh`, because that can incorrectly serve Simplified strings to
  Traditional Chinese locales.
- Product-flavor strings have matching catalogs under their own source set, for
  example `app/src/sideload/res/values-b+zh+Hans/strings.xml`.
- Brand and protocol terms may remain unchanged when translating them would make
  the UI less precise: Hermes, Relay, Bridge, Threads, Gateway, API, SOUL, and
  provider/model names.

Every shipped locale must contain the same translatable resource names and
resource types as English. Format arguments must use the same argument indexes
and conversion types. Plural categories may differ by language, but every
locale must provide `other`.

Run the fast catalog gate before Gradle:

```bash
python scripts/check-android-locales.py
```

Then run the release-relevant Android checks:

```bash
./gradlew :app:compileGooglePlayDebugKotlin :app:compileSideloadDebugKotlin
./gradlew lint
```

Review at normal font size and at least 200% font size. Verify TalkBack labels,
notifications, dialogs, onboarding, connection setup, Chat, Manage, Voice, and
both product flavors. Android automatically falls back to English if a runtime
resource is unavailable, but CI intentionally requires complete catalogs so
new UI cannot silently remain English.

## Adding a language

1. Choose an Android qualifier that represents the language rather than a flag
   or country when possible.
2. Copy the English resource structure into the new locale directory.
3. Add the BCP-47 tag to `app/src/main/res/xml/locales_config.xml` so Android
   13+ exposes the language in per-app system settings.
4. Add the language tag to `AppLanguage`, add its picker label to every catalog,
   and cover tag resolution in `AppLanguageTest`. Use the language's own name
   for its label so it remains recognizable after an accidental switch.
5. Add the new option to the label map in `AppearanceSettingsScreen`.
6. Translate user-facing text while preserving resource names, markup, escapes,
   and format arguments.
7. Add any matching flavor catalogs.
8. Run `python scripts/check-android-locales.py`, the focused Android unit tests,
   and Android lint.
9. Test the locale on an emulator or device, including in-app and Android-system
   language switching, process restart, text expansion, and
   accessibility.
10. Add the language to the README language links and status registry. New
   AI-assisted locales start as `ai-translated` with empty `review_refs`.

## README and user documentation

`README.md` remains canonical. Translations use separate files such as
`README.zh-CN.md` and link back to English. Product documentation can be rolled
out by locale under `user-docs/<locale>/`; untranslated technical references
should link to the canonical English page rather than copying stale content.

| Surface | English | Simplified Chinese |
|---|---:|---:|
| Android app | Canonical | Complete catalog |
| Root README | Canonical | Maintained summary |
| User docs | Canonical | Quick start and core navigation |
| Maintainer `docs/` and ADRs | Canonical | English fallback |

## Translation corrections and pull requests

Keep translation PRs scoped to one locale or one clearly described catalog
refresh. Do not include signing changes, custom APK release workflows, version
bumps, or fork-specific branding. AI-assisted translation is accepted when its
method and verification state are recorded honestly. Fluent review is
encouraged, not fabricated or treated as a prerequisite for initial coverage.
Community correction PRs are the canonical way to fix misses; once reviewed,
add their PR URLs to `review_refs` and advance the locale only to the
verification level actually completed.

If a valuable translation PR becomes too stale to merge safely, maintainers may
salvage it onto current `dev` under the contributor-credit policy in
`CONTRIBUTING.md`. The original contributor remains credited in Git history and
in the replacement PR lineage.
