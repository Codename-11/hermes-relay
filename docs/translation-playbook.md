# AI-assisted translation playbook

English is the canonical source for product meaning. AI translation provides
initial coverage; fluent community corrections improve it canonically in the
locale catalog. A language does not need a fluent reviewer before it can ship,
but its verification state must remain accurate in
`docs/localization-status.json`.

## Required translation pass

Translate complete resources in semantic groups rather than isolated strings.
Provide the model with the screen or feature context and this glossary:

- Keep product and protocol names unchanged unless an established local form is
  clearer: Hermes, Relay, Bridge, Threads, Gateway, API, SOUL, and provider or
  model names.
- Preserve resource identifiers, XML markup, escapes, newlines, URLs, and every
  printf argument index and conversion type.
- Use the language's native name in the language picker.
- Prefer concise, natural interface language over literal word order.
- Do not soften permissions, security warnings, destructive confirmations,
  privacy statements, or error recovery instructions.
- Flag ambiguous English source text. Fix ambiguity in English before
  translating it instead of inventing locale-specific meaning.

## Independent critique pass

Use a separate model context or a second model to compare the translation with
English. Ask it to report only:

1. changed or missing meaning;
2. unnatural or culturally misleading wording;
3. inconsistent glossary terms;
4. broken placeholders, markup, or plurals;
5. risky wording in permissions, privacy, security, destructive actions, and
   Play listing copy.

Apply justified corrections, rerun the critique, and record the locale as
`ai-translated`. Do not describe it as human-reviewed.

## Community correction path

Corrections belong in the locale catalog, not in Kotlin conditionals or a fork
of the English behavior. A correction PR should cover one locale and explain
the intended meaning for sensitive strings. Maintainers treat fluent-speaker
corrections as authoritative unless they alter technical meaning.

After review:

- `community-reviewed` requires at least one fluent review or substantive
  correction PR in `review_refs`;
- `verified` requires a comprehensive in-app review of the shipped surface;
- add recurring terminology corrections to this glossary;
- preserve translator credit and stale-PR lineage under `CONTRIBUTING.md`.

## Technical release gate

Run:

```bash
python scripts/check-android-locales.py
./gradlew :app:compileGooglePlayDebugKotlin :app:compileSideloadDebugKotlin
./gradlew lint
```

Review normal and 200% font sizes, TalkBack labels, notifications, dialogs,
onboarding, connection setup, Chat, Manage, Voice, and both product flavors.
Technical checks prove structural safety; the status registry communicates the
separate level of linguistic review.
