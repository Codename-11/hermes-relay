# Releasing Hermes-Relay

> The full recipe for cutting a new release. Read this end-to-end before
> tagging your first release.

## Release Tracks And Versioning

Hermes-Relay follows [SemVer](https://semver.org/): `MAJOR.MINOR.PATCH`,
with optional prerelease identifiers.

- `MAJOR` — breaking changes (protocol, settings schema, minimum OS)
- `MINOR` — new features, backwards compatible
- `PATCH` — bug fixes, backwards compatible
- Prerelease suffixes: `-alpha`, `-beta`, `-rc.N` (e.g. `0.2.0-beta.1`)

Hermes-Relay now ships three independently versioned surfaces. Public GitHub
Release titles use product names (`Hermes-Relay-Android`,
`Hermes-Relay-Plugin`, `Hermes-Relay-CLI`); tag prefixes stay short and stable
for automation.

| Surface | Tag prefix | Version source | Bump script | Release workflow |
|---|---|---|---|---|
| Hermes-Relay-Android | `android-v*` | `gradle/libs.versions.toml` | `scripts/bump-android-version.sh` | `.github/workflows/release-android.yml` |
| Hermes-Relay-Plugin | `plugin-v*` | `pyproject.toml` plus checked plugin/dashboard metadata | `scripts/bump-plugin-version.sh` | `.github/workflows/release-plugin.yml` |
| Hermes-Relay-CLI | `cli-v*` | `desktop/package.json` | `npm version` or manual package bump | `.github/workflows/release-cli.yml` |

This split is intentional. The plugin carries relay features for both Android
and CLI clients, so plugin fixes can ship without forcing an Android app
`versionCode` bump, and CLI alphas can continue on their own cadence. Historical
Android releases before this naming split used bare `v*` tags. Historical
plugin/server releases used `relay-v*` tags, and historical CLI prereleases used
`desktop-v*` tags. New releases use the explicit tag prefixes above.

### Android app versioning

**Source of truth:** `gradle/libs.versions.toml`

```toml
[versions]
appVersionName = "0.1.0"
appVersionCode = "1"
```

- `appVersionName` is the user-visible SemVer string (what Play Store shows)
- `appVersionCode` is an integer build number that **must increase
  monotonically** with every upload to Play Console, even across prereleases

Both are read by `app/build.gradle.kts` via `libs.versions.appVersionName.get()`
and `libs.versions.appVersionCode.get().toInt()`.

### versionCode progression

| appVersionName | appVersionCode | Notes                       |
|----------------|----------------|-----------------------------|
| `0.1.0`        | `1`            | Initial Play Store release  |
| `0.1.1`        | `2`            | Bug fix                     |
| `0.2.0-beta.1` | `3`            | Prereleases bump code too   |
| `0.2.0-beta.2` | `4`            |                             |
| `0.2.0`        | `5`            | Stable release              |
| `1.0.0-rc.1`   | `6`            |                             |
| `1.0.0`        | `7`            |                             |

Never decrement `appVersionCode` — Play Console rejects any upload whose
code is lower than or equal to a previous upload on the same track. Confirm
current values with `scripts\dev.bat version`.

Always bump Android releases via:

```bash
bash scripts/bump-android-version.sh 0.6.2
```

`scripts/bump-version.sh` remains as a backward-compatible alias for the
Android script.

### Plugin / Python package versioning

Plugin version metadata lives in these plugin-owned files and must stay in
lockstep:

| File | Line | Purpose |
|---|---|---|
| `pyproject.toml` | `version = "..."` | Python package metadata |
| `plugin/relay/__init__.py` | `__version__ = "..."` | runtime version reported by `/health` and `/relay/info` |
| `plugin/plugin.yaml` | `version: ...` | Hermes plugin metadata |
| `plugin/dashboard/manifest.json` | `"version": "..."` | Hermes dashboard plugin metadata |
| `plugin/dashboard/package.json` | `"version": "..."` | dashboard build/package metadata |
| `plugin/dashboard/package-lock.json` | `"version": "..."` | locked dashboard package metadata |

Always bump Plugin releases via:

```bash
bash scripts/bump-plugin-version.sh 0.6.2
```

Check the current metadata with:

```bash
python scripts/check-plugin-version-sync.py
```

Check all release tracks at once with:

```bash
python scripts/check-version-tracks.py
```

This aggregate check reports Android, plugin, and CLI versions
side by side and validates that each track's own source files are internally
consistent. It deliberately does not require all three tracks to share the same
SemVer.

The `plugin-v*` release workflow validates the tag against the same metadata,
runs plugin tests, builds a wheel and sdist, generates checksums, and
publishes a `Hermes-Relay-Plugin vX.Y.Z` GitHub Release with the package
artifacts.

## Branching policy

> **Updated 2026-04-19:** moved from `main`-only to `main + dev`. See
> `docs/decisions.md` §23 for the rationale.

Hermes-Relay uses **`main` + `dev` with feature branches and no-ff
merges**. `main` is **released state only** — every commit on `main`
corresponds to a shipped version or a release-merge of `dev`. Day-to-day
integration happens on `dev`.

**Merging is decoupled from releasing.** Feature branches land on `dev`
continuously as they go green in CI — there is no "one feature per
release" rule. The `[Unreleased]` section of `CHANGELOG.md` on `dev` is
the accumulator: every merged PR appends bullets there. A release is a
separate act, taken when the accumulated state on `dev` is worth shipping
(see "When to cut a release" below). Cutting a release means opening a
surface-specific release PR from `dev` into `main`, merging it `--no-ff`,
then tagging `main`.

**Server tracks `dev` for staging.** The hermes-host deployment pulls
`dev` so merged features get exercised against real data before they
reach a tag. Users (Play Store, sideload, `hermes-relay-update`) only
see state that lives on `main` and on release tags.

### Branch names

| Prefix | When | Example |
|---|---|---|
| `feature/<name>` | New feature (>1-2 commits) | `feature/bridge-scroll-tool` |
| `fix/<name>` | Focused bug fix | `fix/media-projection-fgs` |
| `docs/<name>` | Docs-only changes larger than a typo | `docs/sideload-guide` |
| `chore/<name>` | Cleanup / refactor / tooling | `chore/sync-version-sources` |

All of the above branch off `dev` and merge back to `dev`. There is no
straight-to-main exemption — even single-file typos go through a feature
branch and PR into `dev`.

### Merge style: `--no-ff`

Always merge with `git merge --no-ff <branch>` (or the "Create a merge
commit" option in the GitHub PR UI). This applies at every level —
feature → `dev`, and `dev` → `main` for release merges. `--no-ff`
preserves the branch context as a visible merge commit in
`git log --graph`, which is valuable when:

- An agent team pushed several commits to a branch — the per-commit trail
  is useful for "which agent did what"
- `git bisect` needs to treat the whole branch as one unit
- Someone reviews history in 6 months and wants to know "what was the
  bundle of changes that introduced feature X"

Squash merges lose that detail and are **not** the house style.

### Version bumps happen at release-prep on `dev`, NOT on feature branches

Feature branches **never** touch `gradle/libs.versions.toml`,
plugin-owned version metadata, or `desktop/package.json`.
If two feature branches both bumped a release version, they'd collide on
version files and, for Android, on `appVersionCode` (which must be
monotonic).

Version-bump commits live on `dev` as the last commit of release-prep
work. Android commits use `release(android): android-vX.Y.Z`; plugin commits
use `release(plugin): plugin-vX.Y.Z`; CLI commits use
`release(cli): cli-vX.Y.Z`. A release PR then merges `dev` →
`main` with `--no-ff`, and the matching tag is cut from the resulting
`main` tip.

### Branch protection

Light branch protection is enabled:

- **`main`** — direct pushes blocked; only release PRs from `dev` merge
  here. PR must pass CI (Android + Plugin) before merge. Force push and
  branch deletion blocked.
- **`dev`** — direct pushes blocked for non-trivial work; feature
  branches PR in. PR must pass CI. Force push and branch deletion
  blocked.
- Signed commits + review approval NOT required (solo-dev overhead).

## One-time Setup

### 1. Release signing keystore

Generate a keystore with `keytool` (bundled with the JDK):

```bash
keytool -genkey -v -keystore release.keystore \
  -alias hermes-relay -keyalg RSA -keysize 2048 -validity 10000
```

Answer the prompts (CN, OU, O, etc.) — these end up in the certificate
Play Console pins to your app. **Back up the keystore file and its
passwords.** Losing them means you can never ship another update to the
same Play Store listing.

#### Local builds

Point `local.properties` at the keystore so `scripts\dev.bat release` and
`scripts\dev.bat bundle` produce signed artifacts:

```properties
hermes.keystore.path=C:/path/to/release.keystore
hermes.keystore.password=YOUR_STORE_PASSWORD
hermes.key.alias=hermes-relay
hermes.key.password=YOUR_KEY_PASSWORD
```

`local.properties`, `*.keystore`, and `*.jks` are already gitignored.
Relative `hermes.keystore.path` values resolve from the repo root, so
`release.keystore` works when the keystore lives beside this file.

> If the keystore at `hermes.keystore.path` is missing, `app/build.gradle.kts`
> silently falls back to debug signing. The build succeeds but Play Console
> rejects the AAB — always verify with `keytool -printcert` (step 3
> below).

#### CI builds

Encode the keystore as base64 and store it as a GitHub Secret:

```bash
base64 -w 0 release.keystore > release.keystore.b64   # Git Bash / WSL
```

On Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) `
  | Out-File -Encoding ascii release.keystore.b64
```

Paste the contents of `release.keystore.b64` into the
`HERMES_KEYSTORE_BASE64` secret (see step 4 below). Delete the local `.b64`
file afterward.

### 2. Google Play Console developer account

Hermes-Relay ships under the **Axiom-Labs, LLC** Play Console account
(D-U-N-S verified organization). The applicationId is
`com.axiomlabs.hermesrelay` (googlePlay flavor) and
`com.axiomlabs.hermesrelay.sideload` (sideload flavor — not shipped through
Play at all). The Kotlin namespace / source tree stays at
`com.hermesandroid.relay` for historical reasons; see `app/build.gradle.kts`
for the decoupling rationale.

If you're setting up a fresh account (for a fork or a new downstream):

1. Register at <https://play.google.com/console/signup> ($25 one-time fee).
2. Complete identity verification (personal accounts need a government ID;
   organization accounts need a D-U-N-S number).
3. Create the app listing: name, language, free/paid, declarations.

**The 14-day closed-testing rule does NOT apply to Hermes-Relay.** Google
requires *new personal* developer accounts to run an app in closed testing
with ≥12 opted-in testers for 14 continuous days before promotion to
production. Organization accounts with a verified D-U-N-S number are exempt
from this policy, and Axiom-Labs is a D-U-N-S-verified org account. See
[Google's policy](https://support.google.com/googleplay/android-developer/answer/14151465)
for the full text.

> **Historical note (2026-04-13 migration):** v0.1.x through v0.3.0 shipped
> on Internal testing under Bailey's personal Play Console account with
> applicationId `com.hermesandroid.relay`. That listing was retired as part
> of the org-account migration. Play Store package names are permanently
> reserved once used — `com.hermesandroid.relay` can never be reclaimed —
> so all releases from v0.3.1 onwards ship fresh under the new
> `com.axiomlabs.hermesrelay` listing. The upload keystore identity is
> unchanged (same `CN=Bailey Dixon, Codename-11` cert, same SHA256
> fingerprint), so existing GitHub Secrets and the CI signing flow need no
> changes. Google Play App Signing mints a new server-side app signing key
> per listing — that's invisible to us since App Signing is enabled.

### 3. Play Developer API service account (optional)

Required only if you want `gradlew publishReleaseBundle` to upload directly
to Play Console. Manual UI uploads work without this.

1. Open <https://console.cloud.google.com/> and select the project linked
   to your Play Console account (Play Console > Setup > API access shows
   which one).
2. **IAM & Admin > Service Accounts > Create Service Account** (e.g.
   `hermes-relay-publisher`). No project roles needed.
3. On the new service account, **Keys > Add key > Create new key > JSON**
   and download the file.
4. In Play Console > **Setup > API access**, find the service account,
   click **Grant access**, and assign the **Release manager** role.
5. Save the JSON as `play-service-account.json` in the repo root (already
   in `.gitignore`).
6. Verify with `gradlew bootstrapReleasePlayResources` — should succeed
   without auth errors.

### 4. GitHub Actions secrets

In the repo: **Settings > Secrets and variables > Actions > New repository
secret.** Add all four (see the table in "Required Android Release Secrets"
below).

If `HERMES_KEYSTORE_BASE64` is missing, CI release builds fall back to
debug signing and print a warning in the workflow summary — those
artifacts will not be accepted by Play Console.

## When to cut a release

Cut a release when **any of the following** is true:

- The `[Unreleased]` section of `CHANGELOG.md` has enough user-facing
  change that a version number is worth attaching.
- A user-facing bug is fixed and you want affected users to pick it up
  via `hermes-relay-update` or a Play Store auto-update.
- A regulatory / policy deadline applies (new Play Console target SDK,
  etc).
- You've been sitting on unreleased work for more than a couple of
  weeks and the delta-from-last-release is growing faster than it
  should.

**Don't** cut a release just because a feature landed. If one feature
isn't enough to justify a version bump, wait — merge the next one, let
it sit alongside in `[Unreleased]`, and ship them together. A release
is a statement to users that "this is a thing worth updating to," so
the threshold is intent-driven, not event-driven.

If you want to dogfood accumulated `main` state without declaring GA,
tag a **pre-release** (`android-vX.Y.Z-rc.N`). Users can opt in via
`hermes-relay-update --branch rc/vX.Y.Z-rc.N` without being auto-pushed
the unstable build.

## Release Process

### 1. Bump the Android app version

Use `scripts/bump-android-version.sh`. It rewrites
`gradle/libs.versions.toml`, increments `appVersionCode` monotonically,
and runs a sanity check. Don't edit the Android version files by hand.

```bash
bash scripts/bump-android-version.sh 0.6.2
```

Confirm the bump:

```bat
scripts\dev.bat version
```

The script's diff output should show `gradle/libs.versions.toml` carrying
the new app version and a higher `appVersionCode`.

### 2. Update release notes and changelog

- `CHANGELOG.md` — promote the accumulated `[Unreleased]` block to a
  versioned header. The block already exists: every feature PR has
  been appending to it. All you do here is:
  1. Change the `## [Unreleased]` header to `## [X.Y.Z] - YYYY-MM-DD`.
  2. Insert a fresh empty `## [Unreleased]` header above it so the
     next PR has a landing spot.
  3. Skim the new versioned block and tighten / reorder if needed —
     Keep-a-Changelog grouping (`Added` / `Changed` / `Fixed`) should
     already be in place from the accumulator phase.
- `RELEASE_NOTES.md` — body of the GitHub Release for this version
  (rewritten each release; the workflow uses this as-is). This is the
  operator-facing summary, not the CHANGELOG mirror. Keep the
  **Download** section near the top — it should spell out which file
  to grab by its `-sideload-release.apk` / `-googlePlay-release.aab`
  suffix (every artifact is version-tagged as
  `hermes-relay-<version>-<flavor>-<buildType>` via `archivesName`
  in `app/build.gradle.kts`) and link to the sideload guide.
  The v0.3.0 body is a good template.
- `app/src/main/assets/whats_new.txt` — in-app "What's New" content
  shown in the settings/about screen. Update with the version number
  and a brief feature summary. Gets stale silently if forgotten
  (v0.4.0 shipped with 0.1.0 content until caught post-release).
- `docs/play-store-listing.md` — Play Store listing copy. Update
  the version reference and the "Release Notes" section that gets
  pasted into the Play Console "What's new" field. Keep the Play
  "What's new" within **500 characters** and framed around the
  release's themes, not a feature dump.

#### Scrub for public distribution

This is a **public repo** and these four files are user-facing. Before
promoting the `[Unreleased]` block and writing the notes, scrub the
versioned CHANGELOG block and all three release-notes artifacts for
wording that shouldn't ship publicly. The CHANGELOG accumulates in a
dev-log voice during the iteration phase — release-prep is where it
becomes public copy. Check for and remove/rewrite:

- **Personal names / quoted asides** — `git grep -niE "bailey|: \"" CHANGELOG.md`
  on the new block. Attribute fixes impersonally ("a user reported"),
  not by name. (Author identity already lives in git + the signing cert.)
- **Private infrastructure** — server hostnames/IPs, `~/SYSTEM.md`,
  internal deployment names, anything that should stay in the operator's
  environment and not the repo. `grep -niE "192\.168|10\.0\.|hermes-host|SYSTEM\.md"`.
  (Example IPs like `192.168.1.100` in install docs are fine.)
- **Fork / branch plumbing + internal nicknames** — references to private
  fork branches, rollout channels, or in-team incident nicknames read as
  internal. Keep the *what changed*, drop the *where we staged it*.
- **Personal example data** — genericize sample profile/agent names to
  neutral placeholders so the copy doesn't expose a specific setup.

The goal is that someone who has never seen the repo can read the block
and the release notes and learn only what the software does.

### 3. Build and verify locally

```bat
scripts\dev.bat bundle
keytool -printcert -jarfile app\build\outputs\bundle\googlePlayRelease\hermes-relay-*-googlePlay-release.aab
```

The `keytool` output must show your release certificate (the CN/OU/O
values you entered during `keytool -genkey`). If it shows
`CN=Android Debug, O=Android, C=US`, the keystore wasn't picked up —
recheck `local.properties` before continuing.

Product flavors (`googlePlay`, `sideload`) nest outputs under a flavor
directory: APKs live in `app/build/outputs/apk/<flavor>/release/` and
AABs live in `app/build/outputs/bundle/<flavor>Release/`. Every file is
prefixed `hermes-relay-<version>-` via `archivesName` in
`app/build.gradle.kts`.

Optional device smoke test: `scripts\dev.bat release` then
`adb install -r app\build\outputs\apk\sideload\release\hermes-relay-*-sideload-release.apk`.

### 4. Commit on `dev`, merge to `main`, tag from `main`

The release-prep commit lands on `dev` first. Then a release PR merges
`dev` → `main` with `--no-ff`, and the `android-v<version>` tag is cut from the
resulting merge commit on `main`:

```bash
# From a clean dev checkout:
git checkout dev
git pull --ff-only origin dev

git add gradle/libs.versions.toml RELEASE_NOTES.md CHANGELOG.md \
        app/src/main/assets/whats_new.txt docs/play-store-listing.md
git commit -m "release(android): android-v0.6.2"
git push origin dev

# Open the release PR (dev -> main) and merge with --no-ff.
# After merge, tag from the new main tip:
git checkout main
git pull --ff-only origin main
git tag android-v0.6.2
git push origin android-v0.6.2
```

Pushing a tag matching `android-v*` triggers `.github/workflows/release-android.yml`,
which builds, signs, checksums, and creates a GitHub Release. Watch the
run under the **Actions** tab.

Plugin/Python version files are intentionally not part of an Android app
release unless the plugin package itself is also being released.

### Plugin / Python package release

Use this when plugin or relay behavior changes independently of Android app
delivery, for example CLI channel support, bridge routes, pairing server fixes,
voice auth, dashboard plugin UI, or packaging changes.

```bash
git checkout dev
git pull --ff-only origin dev

bash scripts/bump-plugin-version.sh 0.6.2
git add pyproject.toml plugin/relay/__init__.py plugin/plugin.yaml plugin/dashboard/manifest.json plugin/dashboard/package.json plugin/dashboard/package-lock.json CHANGELOG.md
git commit -m "release(plugin): plugin-v0.6.2"
git push origin dev

# Open the release PR (dev -> main) and merge with --no-ff.
# After merge, tag from the new main tip:
git checkout main
git pull --ff-only origin main
git tag plugin-v0.6.2
git push origin plugin-v0.6.2
```

Pushing `plugin-v*` triggers `.github/workflows/release-plugin.yml`, which
validates all plugin-owned version metadata with
`scripts/check-plugin-version-sync.py`. Run
`python scripts/check-version-tracks.py` locally before tagging when a change
touches more than one release surface. The workflow also runs plugin tests,
builds a wheel and sdist, generates `SHA256SUMS.txt`, and creates a GitHub
Release named `Hermes-Relay-Plugin v<version>` for the plugin package.

### 5. Upload to Play Console

> **If `PLAY_SERVICE_ACCOUNT_JSON` is configured as a repo secret, this step is
> automated for stable tags.** The release workflow runs
> `publishGooglePlayReleaseBundle --track=production` and the build appears as a
> Production **draft** — skip to the Play Console, confirm the draft, and click
> **Start rollout**. The manual path below is the fallback when the secret is
> unset (or for staging on a non-production track).

**Pick the track first.** The AAB is track-agnostic — the same
`-googlePlay-release.aab` goes to whichever track you publish on. Choose by intent,
not habit:

- **Production** — the default for a stable GA release (`android-vX.Y.Z`). The
  listing is live, so this is where real releases land. The org account is
  D-U-N-S-verified, so the 14-day / 12-tester closed-testing gate does **not**
  apply — you can publish straight to Production.
- **Open / Closed testing** — only when you actually want a public/private beta
  channel for this build.
- **Internal testing** — only for a throwaway pre-release smoke check (e.g. a
  prerelease tag), not for a GA. Don't default here.

**Manual upload:**

1. Download the file ending in `-googlePlay-release.aab` from the GitHub
   Release assets (for example, `hermes-relay-1.0.0-googlePlay-release.aab`),
   or use your local build at
   `app\build\outputs\bundle\googlePlayRelease\hermes-relay-<version>-googlePlay-release.aab`.
2. In Play Console, open the track you chose above — for a GA that's
   **Release > Production**.
3. **Create new release** > upload the AAB.
4. Paste the Play "What's new" from `docs/play-store-listing.md` (≤500 chars) into
   the release notes field. (`RELEASE_NOTES.md` is the GitHub-Release body, not the
   Play field — don't paste that; it's over the limit.)
5. **Review release** > **Start rollout** (set the staged-rollout percentage if you
   want a gradual production ramp).

**Automated upload (if `play-service-account.json` is configured):**

```bat
scripts\dev.bat bundle
gradlew publishReleaseBundle --track=production
```

The `play { }` block in `app/build.gradle.kts` defaults to the `internal` track
with `DRAFT` status as a safety net for unattended runs, so pass `--track` explicitly
for a real release: `--track=production` (GA), or `--track=alpha` (Closed) /
`--track=beta` (Open) for a beta channel.

To promote an existing release between tracks without rebuilding:

```bat
gradlew promoteReleaseArtifact --from-track=internal --promote-track=alpha
```

### 6. Tracks (a menu, not a mandatory ladder)

The org account is exempt from the 14-day / 12-tester closed-testing rule, so a
stable GA publishes **straight to Production** — there is no required promotion
chain. The other tracks are opt-in tools, not steps you must climb:

- **Production** — live on the Play Store. Where GA releases go.
- **Open testing (beta)** — opt-in public beta channel.
- **Closed testing (alpha)** — opt-in private beta (named tester lists).
- **Internal testing** — throwaway smoke check (e.g. a prerelease tag), no tester
  or time minimum.

If you *do* stage through tracks, promote an existing release without rebuilding via
the Play Console UI or:

```bat
gradlew promoteReleaseArtifact --from-track=internal --promote-track=production
```

### 7. After release

- Verify the GitHub Release has APK, AAB, and `SHA256SUMS.txt` attached.
- Confirm the release body includes the **Download** section that tells
  users which asset to grab. If you kept the structure from
  `RELEASE_NOTES.md` this will already be baked in. If for some reason
  it's missing, edit the body with:
  ```bash
  gh release view android-vX.Y.Z --repo Codename-11/hermes-relay --json body --jq .body > /tmp/body.md
  # edit /tmp/body.md to add/fix the Download section
  gh release edit android-vX.Y.Z --repo Codename-11/hermes-relay --notes-file /tmp/body.md
  ```
  (This step was only needed as a retrofit for v0.1.0 — v0.1.1+ inherit
  the Download section automatically from `RELEASE_NOTES.md`.)
- Confirm Play Console shows the new versionCode on the target track.
- Update `DEVLOG.md` with a short entry for the release.

## CI Behavior

Android, Plugin, dashboard, and desktop now have separate CI/release lanes.
This keeps a dashboard CSS fix from running the full server suite, and keeps
plugin changes from forcing an Android app `versionCode` bump.

On every push of a tag matching `android-v*`, `.github/workflows/release-android.yml`:

1. Validates the tag matches `appVersionName` in
   `gradle/libs.versions.toml` (mismatches fail the workflow).
2. Runs the Android debug build and the stable sideload pairing/connection
   regression slice with explicit timeouts.
3. Decodes `HERMES_KEYSTORE_BASE64` into `$RUNNER_TEMP/release.keystore`
   and exports `HERMES_KEYSTORE_PATH` (skipped if the secret is unset).
4. Builds both Android release artifacts:
   `./gradlew bundleRelease assembleRelease`.
5. Generates `SHA256SUMS.txt` covering both.
6. Creates a GitHub Release named `Hermes-Relay-Android v<version>` with `RELEASE_NOTES.md` as
   the body. Attaches the APK, AAB, and `SHA256SUMS.txt`. Tags any version
   containing a dash (e.g. `android-v0.2.0-beta.1`) as a prerelease automatically.
7. Prints a `$GITHUB_STEP_SUMMARY` showing whether release signing
   succeeded. If `HERMES_KEYSTORE_BASE64` is missing, the summary warns
   that the artifacts are debug-signed and unsuitable for Play Store.

On every push of a tag matching `plugin-v*`,
`.github/workflows/release-plugin.yml`:

1. Validates the tag matches all plugin-owned version metadata checked by
   `scripts/check-plugin-version-sync.py`.
2. Runs plugin syntax checks and the focused route/auth/session test slice.
3. Builds the Python wheel and sdist with `python -m build`.
4. Generates `dist/SHA256SUMS.txt`.
5. Creates a GitHub Release named `Hermes-Relay-Plugin v<version>` with the wheel,
   sdist, and checksum file attached.

On every push of a tag matching `cli-v*`,
`.github/workflows/release-cli.yml` builds and publishes the CLI binaries and
Windows tray installer. Dashboard-only changes are covered by
`.github/workflows/ci-dashboard.yml`, which builds the dashboard plugin,
runs the dashboard API tests, and verifies the modal CSS markers are present
in the built bundle.

## Required Android Release Secrets

| Secret                      | Purpose                             | How to populate                                  |
|-----------------------------|-------------------------------------|--------------------------------------------------|
| `HERMES_KEYSTORE_BASE64`    | Release keystore, base64-encoded    | `base64 -w 0 release.keystore`                   |
| `HERMES_KEYSTORE_PASSWORD`  | Store password                      | Password set during `keytool -genkey`            |
| `HERMES_KEY_ALIAS`          | Key alias                           | Alias set during `keytool -genkey`               |
| `HERMES_KEY_PASSWORD`       | Key password                        | Usually the same as the store password          |
| `PLAY_SERVICE_ACCOUNT_JSON` | **Optional** — Play auto-upload     | Paste the full Play Developer API service-account JSON (step 3) |

If `PLAY_SERVICE_ACCOUNT_JSON` is set, the `android-v*` release workflow uploads
the `googlePlay` AAB to the **Production track as a DRAFT** automatically (stable
tags only — prereleases are skipped). CI does the upload; you still click **Start
rollout** in Play Console. If the secret is unset, the workflow skips the upload
and you upload manually (§5) — nothing else changes.

## Hotfix Recipe

When production has a bug and you need to ship a fix without picking up
unreleased work from `dev`, branch from the affected release tag and only
bump the version source for the surface you are shipping.

For an Android app hotfix:

1. `git checkout -b fix/short-name android-v0.6.1` — branch from the released
   Android tag (not from `main` or `dev`).
2. Apply the fix, add a test, commit.
3. Run `bash scripts/bump-android-version.sh 0.6.2` to update
   `gradle/libs.versions.toml`.
4. Update `RELEASE_NOTES.md`, `CHANGELOG.md`, in-app What's New, and Play
   listing notes as needed.
5. Open a PR from `fix/short-name` into `main`, merge with `--no-ff`.
6. `git tag android-v0.6.2` from the new `main` tip and `git push origin android-v0.6.2`
   so Android release CI builds and publishes.
7. Upload to Play Console as normal.
8. Merge `main` back into `dev` (`git checkout dev && git merge --no-ff main`)
   so `dev` picks up the hotfix and the versionCode bump. Without this,
   `dev`'s `appVersionCode` lags behind `main` and the next app release
   bump collides.

For a Plugin hotfix, branch from the affected `plugin-v*` tag, apply
the fix, run `bash scripts/bump-plugin-version.sh <next-version>`, merge to
`main`, and tag `plugin-v<next-version>`. Do not touch
`gradle/libs.versions.toml` unless an Android app release is also shipping.

## Troubleshooting

**`Tag version (X) does not match appVersionName (Y)` in CI validate step**
You pushed a tag before bumping `gradle/libs.versions.toml`, or vice versa.
Fix: update the file, commit, delete the remote tag
(`git push --delete origin android-vX`), re-tag, and push again.

**Play Console rejects the AAB as debug-signed**
Run `keytool -printcert -jarfile <aab>` locally — if it shows
`CN=Android Debug`, fix `local.properties` for local builds or
`HERMES_KEYSTORE_BASE64` for CI. For CI, check the workflow summary; if it
says "Debug-signed", one of the four `HERMES_*` secrets is missing or the
base64 blob is malformed. Re-encode with `base64 -w 0` (the `-w 0` flag is
required — without it, line breaks corrupt the secret).

**Play Console: "Version code X has already been used"**
Every upload must increment `appVersionCode`. Bump it and rebuild — you
cannot reuse a code even after deleting a draft.

**`gradle-play-publisher: No matching track found`**
Use Play Console-compatible track names: `internal`, `alpha`, `beta`,
`production`.

**`gradlew publishReleaseBundle` fails with `FileNotFoundException:
play-service-account.json`**
The service account JSON is missing. Either complete the service account
setup above or use the manual Play Console upload path.
