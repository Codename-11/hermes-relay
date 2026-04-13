# Releasing Hermes-Relay

> The full recipe for cutting a new release. Read this end-to-end before
> tagging your first release.

## Versioning

Hermes-Relay follows [SemVer](https://semver.org/): `MAJOR.MINOR.PATCH`,
with optional prerelease identifiers.

- `MAJOR` — breaking changes (protocol, settings schema, minimum OS)
- `MINOR` — new features, backwards compatible
- `PATCH` — bug fixes, backwards compatible
- Prerelease suffixes: `-alpha`, `-beta`, `-rc.N` (e.g. `0.2.0-beta.1`)

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

### The three version sources (MUST stay in lockstep)

There are **three** places the version lives, and they MUST all match on
every release commit. Drift is silent and painful — we chased a "why does
/health say 0.2.0" bug for hours on 2026-04-12 because `pyproject.toml`
had drifted to `0.5.0` speculatively and `plugin/relay/__init__.py` was
still at a stale `0.2.0`.

| File | Line | Written by |
|---|---|---|
| `gradle/libs.versions.toml` | `appVersionName = "…"` | You (canonical) |
| `pyproject.toml` | `version = "…"` | You (Python package) |
| `plugin/relay/__init__.py` | `__version__ = "…"` | You (runtime, reported by `/health`) |

**Always bump them atomically via `scripts/bump-version.sh`**:

```bash
bash scripts/bump-version.sh 0.3.0
```

The script validates SemVer, bumps `appVersionCode` monotonically, rewrites
all three files, runs a post-bump sanity grep, prints the diff, and tells
you the next steps. It deliberately does NOT commit, tag, or touch
`CHANGELOG.md` / `RELEASE_NOTES.md` — those need human prose.

## Branching policy

Hermes-Relay uses **feature branches + no-ff merges**, with version bumps
gated to release-prep commits on `main`. `main` is always "last release +
unreleased features," never mid-refactor.

### Branch names

| Prefix | When | Example |
|---|---|---|
| `feature/<name>` | New feature (>1-2 commits) | `feature/bridge-scroll-tool` |
| `fix/<name>` | Focused bug fix | `fix/media-projection-fgs` |
| `docs/<name>` | Docs-only changes larger than a typo | `docs/sideload-guide` |
| `chore/<name>` | Cleanup / refactor / tooling | `chore/sync-version-sources` |

Straight-to-main is still OK for **single-file typo fixes** and **tiny
one-liner tweaks**. Judgment call — if in doubt, branch.

### Merge style: `--no-ff`

Always merge with `git merge --no-ff <branch>` (or the "Create a merge
commit" option in the GitHub PR UI). This preserves the branch context
as a visible merge commit in `git log --graph`, which is valuable when:

- An agent team pushed several commits to a branch — the per-commit trail
  is useful for "which agent did what"
- `git bisect` needs to treat the whole branch as one unit
- Someone reviews history in 6 months and wants to know "what was the
  bundle of changes that introduced feature X"

Squash merges lose that detail and are **not** the house style.

### Version bumps happen at release-prep, NOT on feature branches

Feature branches **never** touch `gradle/libs.versions.toml`,
`pyproject.toml`, or `plugin/relay/__init__.py`. If two feature branches
both bumped the version, they'd collide on `appVersionCode` (which must
be monotonic) and you'd hit a merge conflict for no good reason.

The version-bump commit lives on `main`, created via
`scripts/bump-version.sh`, immediately before `git tag`. It's a dedicated
commit with the message `release: vX.Y.Z` that also lands the CHANGELOG
and RELEASE_NOTES updates.

### Branch protection on `main`

Light branch protection is enabled on `main` to enforce the above:

- Direct pushes blocked (must go through PR)
- PR must pass CI (build + unit tests) before merge
- Force push and branch deletion blocked
- Signed commits + review approval NOT required (solo-dev overhead)

Release-prep commits (`release: vX.Y.Z`) are an exception — they're the
one time direct push is allowed via a short-lived bypass, because they
include the version bump + tag push in one transaction. Everything else
goes through a PR.

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

> If the keystore at `hermes.keystore.path` is missing, `app/build.gradle.kts`
> silently falls back to debug signing. The build succeeds but Play Console
> rejects the AAB — always verify with `keytool -list -printcert` (step 3
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

1. Register at <https://play.google.com/console/signup> ($25 one-time fee).
2. Complete identity verification (personal accounts need a government ID;
   organization accounts need a D-U-N-S number).
3. Create the app listing: name, language, free/paid, declarations.

**New personal accounts only**: Google requires an app to run in
**closed testing** with **at least 12 opted-in testers** for **14
continuous days** before it can be promoted to production. Internal testing
does NOT satisfy this requirement — only the closed testing track starts
the 14-day clock. Organization (D-U-N-S) accounts are exempt. See
[Google's policy](https://support.google.com/googleplay/android-developer/answer/14151465).

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
secret.** Add all four (see the table in "Required GitHub Secrets" below).

If `HERMES_KEYSTORE_BASE64` is missing, CI release builds fall back to
debug signing and print a warning in the workflow summary — those
artifacts will not be accepted by Play Console.

## Release Process

### 1. Bump the version (atomic across all three sources)

Use `scripts/bump-version.sh` — it rewrites `libs.versions.toml`,
`pyproject.toml`, AND `plugin/relay/__init__.py::__version__` in lockstep,
increments `appVersionCode` monotonically, and runs a sanity check. Don't
edit the files by hand; drift is silent and painful.

```bash
bash scripts/bump-version.sh 0.3.0
```

Confirm the bump:

```bat
scripts\dev.bat version
```

The script's diff output should show exactly three files changed and all
three carrying the new version string.

### 2. Update release notes and changelog

- `RELEASE_NOTES.md` — body of the GitHub Release for this version
  (rewritten each release; the workflow uses this as-is). Keep the
  **Download** section near the top — it tells users to grab
  `app-release.apk` (not the `.aab`) and links to the sideload guide.
  The v0.1.0 body is a good template.
- `CHANGELOG.md` — cumulative history; append a new section.

### 3. Build and verify locally

```bat
scripts\dev.bat bundle
keytool -list -printcert -jarfile app\build\outputs\bundle\release\app-release.aab
```

The `keytool` output must show your release certificate (the CN/OU/O
values you entered during `keytool -genkey`). If it shows
`CN=Android Debug, O=Android, C=US`, the keystore wasn't picked up —
recheck `local.properties` before continuing.

Optional device smoke test: `scripts\dev.bat release` then
`adb install -r app\build\outputs\apk\release\app-release.apk`.

### 4. Commit and tag

The release-prep commit is one of the few allowed direct-to-main pushes
(see Branching Policy above — branch protection exempts the
`release: vX.Y.Z` pattern because tagging + bumping must be atomic):

```bash
git add gradle/libs.versions.toml pyproject.toml plugin/relay/__init__.py \
        RELEASE_NOTES.md CHANGELOG.md
git commit -m "release: v0.3.0"
git push origin main

git tag v0.3.0
git push origin v0.3.0
```

Pushing a tag matching `v*` triggers `.github/workflows/release.yml`,
which builds, signs, checksums, and creates a GitHub Release. Watch the
run under the **Actions** tab.

> **Why all three files in the commit?** See "The three version sources"
> above — `bump-version.sh` rewrites them atomically, so they must be
> staged + committed atomically too. Missing one creates the same drift
> the script was built to prevent.

### 5. Upload to Play Console

**Manual upload (default):**

1. Download `app-release.aab` from the GitHub Release assets, or use your
   local build at `app\build\outputs\bundle\release\app-release.aab`.
2. In Play Console: **Release > Testing > Internal testing** (or **Closed
   testing** for the 14-day clock).
3. **Create new release** > upload the AAB.
4. Paste `RELEASE_NOTES.md` into the release notes field.
5. **Review release** > **Start rollout.**

**Automated upload (if `play-service-account.json` is configured):**

```bat
scripts\dev.bat bundle
gradlew publishReleaseBundle
```

Defaults to the `internal` track with `DRAFT` status (configured in the
`play { }` block in `app/build.gradle.kts`). Override per-invocation with
`--track=alpha` (= Closed testing), `--track=beta` (= Open testing), or
`--track=production`.

To promote an existing release between tracks without rebuilding:

```bat
gradlew promoteReleaseArtifact --from-track=internal --promote-track=alpha
```

### 6. Promote through tracks

Typical path:

1. **Internal testing** — personal smoke test (no tester or time minimum)
2. **Closed testing (alpha)** — starts the 14-day clock for new personal
   accounts; needs at least 12 opted-in testers
3. **Open testing (beta)** — optional public beta
4. **Production** — live on the Play Store

Promote via the Play Console UI or `gradlew promoteReleaseArtifact`.

### 7. After release

- Verify the GitHub Release has APK, AAB, and `SHA256SUMS.txt` attached.
- Confirm the release body includes the **Download** section that tells
  users which asset to grab. If you kept the structure from
  `RELEASE_NOTES.md` this will already be baked in. If for some reason
  it's missing, edit the body with:
  ```bash
  gh release view vX.Y.Z --repo Codename-11/hermes-relay --json body --jq .body > /tmp/body.md
  # edit /tmp/body.md to add/fix the Download section
  gh release edit vX.Y.Z --repo Codename-11/hermes-relay --notes-file /tmp/body.md
  ```
  (This step was only needed as a retrofit for v0.1.0 — v0.1.1+ inherit
  the Download section automatically from `RELEASE_NOTES.md`.)
- Confirm Play Console shows the new versionCode on the target track.
- Update `DEVLOG.md` with a short entry for the release.

## CI Behavior

On every push of a tag matching `v*`, `.github/workflows/release.yml`:

1. Validates the tag matches `appVersionName` in
   `gradle/libs.versions.toml` (mismatches fail the workflow).
2. Runs `./gradlew assembleDebug` and `./gradlew test`.
3. Decodes `HERMES_KEYSTORE_BASE64` into `$RUNNER_TEMP/release.keystore`
   and exports `HERMES_KEYSTORE_PATH` (skipped if the secret is unset).
4. Builds both artifacts: `./gradlew bundleRelease assembleRelease`.
5. Generates `SHA256SUMS.txt` covering both.
6. Creates a GitHub Release named `v<version>` with `RELEASE_NOTES.md` as
   the body. Attaches the APK, AAB, and `SHA256SUMS.txt`. Tags any version
   containing a dash (e.g. `v0.2.0-beta.1`) as a prerelease automatically.
7. Prints a `$GITHUB_STEP_SUMMARY` showing whether release signing
   succeeded. If `HERMES_KEYSTORE_BASE64` is missing, the summary warns
   that the artifacts are debug-signed and unsuitable for Play Store.

## Required GitHub Secrets

| Secret                      | Purpose                             | How to populate                                  |
|-----------------------------|-------------------------------------|--------------------------------------------------|
| `HERMES_KEYSTORE_BASE64`    | Release keystore, base64-encoded    | `base64 -w 0 release.keystore`                   |
| `HERMES_KEYSTORE_PASSWORD`  | Store password                      | Password set during `keytool -genkey`            |
| `HERMES_KEY_ALIAS`          | Key alias                           | Alias set during `keytool -genkey`               |
| `HERMES_KEY_PASSWORD`       | Key password                        | Usually the same as the store password          |

## Hotfix Recipe

When production has a bug and you need to ship a fix without picking up
unrelated `main` changes:

1. `git checkout -b fix/short-name v0.1.0` — branch from the released tag.
2. Apply the fix, add a test, commit.
3. Bump `appVersionName` and `appVersionCode` in
   `gradle/libs.versions.toml`.
4. Update `RELEASE_NOTES.md` and `CHANGELOG.md`.
5. `git tag v0.1.1 && git push origin v0.1.1` — CI builds and publishes.
6. Upload to Play Console as normal.
7. Merge the hotfix branch back into `main` so the fix isn't lost.

## Troubleshooting

**`Tag version (X) does not match appVersionName (Y)` in CI validate step**
You pushed a tag before bumping `gradle/libs.versions.toml`, or vice versa.
Fix: update the file, commit, delete the remote tag
(`git push --delete origin vX`), re-tag, and push again.

**Play Console rejects the AAB as debug-signed**
Run `keytool -list -printcert -jarfile <aab>` locally — if it shows
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
