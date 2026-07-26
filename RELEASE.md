# Releasing Hermes-Relay

Canonical release policy and operator recipe for Android, Server, and Desktop.
Read [AGENTS.md](AGENTS.md) first. Android account setup, manual recovery, Play
tracks, and signing troubleshooting live in
[docs/release/android-operations.md](docs/release/android-operations.md).

## Release invariants

| Contract | Rule |
|---|---|
| Integration branch | `dev` |
| Release branch | `main` |
| Normal PR target | `dev`, including documentation-only changes |
| Release PR | `dev` → `main`, merged with a merge commit / `--no-ff` |
| Tag source | The new `main` tip after the release PR |
| Staging source | An exact tested `dev` SHA or release-candidate tag |
| Production source | Immutable `android-v*`, `server-v*`, or `desktop-v*` tag |
| Hotfix base | The affected immutable production tag |
| Back-merge | `main` → `dev` immediately after a hotfix |

Feature completion means merged and verified on `dev`; it does not mean
released. Staging is an environment, never a branch.

Never rewrite or move a production tag. If workflow code must be repaired after
a tag exists, dispatch the current workflow from `main` while every job checks
out and verifies the existing immutable tag.

## Version tracks

The surfaces version independently:

| Surface | Authoritative version source | Stable tag |
|---|---|---|
| Android | `gradle/libs.versions.toml` (`appVersionName`, `appVersionCode`) | `android-vX.Y.Z` |
| Server / plugin | `pyproject.toml` plus the server sync files checked by `scripts/check-server-version-sync.py` | `server-vX.Y.Z` |
| Desktop / tray | `desktop/package.json` plus the files checked by `npm run check:version-sync` | `desktop-vX.Y.Z` |

Use Semantic Versioning. Android `appVersionCode` must increase for every Play
upload, including prereleases.

Version bumps happen only during release preparation on `dev`. Feature, fix,
documentation, and chore branches accumulate user-facing notes under
`CHANGELOG.md` `[Unreleased]`; they do not independently bump application
versions.

## Normal flow

```text
branch from dev
→ PR into dev
→ CI
→ merge commit / no-ff
→ accumulate under CHANGELOG [Unreleased]
→ release preparation on dev
→ exact staging and release gates
→ release PR dev → main
→ tag the new main tip
→ publish the affected surface
→ verify artifacts and rollout
```

Every release train gets a dedicated Forge release issue/session. It owns:

- affected-surface version and notes reconciliation;
- exact tested staging SHA;
- `dev` → `main` release PR;
- immutable tag and artifacts;
- deployment or rollout;
- live verification.

Feature sessions stop at verified `dev`; they do not inherit release authority.

## Android stable release

### 1. Prepare the version on `dev`

Start from a clean, current `dev` branch:

```bash
git switch dev
git pull --ff-only origin dev
bash scripts/bump-android-version.sh X.Y.Z
```

Reconcile only Android-owned release content:

- `gradle/libs.versions.toml`
- `CHANGELOG.md`
- `RELEASE_NOTES.md`
- `app/src/main/assets/whats_new.txt`
- `app/src/main/assets/changelog.json`
- `app/src/googlePlay/play/release-notes/*/default.txt`
- `docs/play-store-listing.md`
- `docs/localization-status.json` when English source catalogs changed
- `DEVLOG.md`

Promote only Android bullets from `[Unreleased]`; leave Server and Desktop
entries there for their own releases. Scrub public notes for personal names,
private infrastructure, internal orchestration narration, and implementation
noise.

Run the fast local gates:

```bash
python scripts/check-version-tracks.py
python scripts/check-android-locales.py
python scripts/check-android-collection-apis.py
python scripts/check-privacy-policy.py --live
python -m json.tool app/src/main/assets/changelog.json
python -m unittest scripts/test_android_release_artifacts.py
```

Run focused Android tests appropriate to the changed surface. Full lint,
strict focused tests, signed release construction, final DEX scanning, and Play
draft upload are enforced by preflight.

Commit the release preparation on a branch from `dev`, merge it into `dev`, and
push the resulting `dev` tip. Use a conventional commit such as:

```text
release(android): android-vX.Y.Z
```

### 2. Publish with the orchestrator

The prepared `dev` tip can be released end to end with:

```powershell
pwsh scripts/release-android.ps1 -Version X.Y.Z
```

To install a configuration-preserving development-signed build on the connected
phone after publication:

```powershell
pwsh scripts/release-android.ps1 -Version X.Y.Z -DeployPhone
```

Preview the command without GitHub or Play mutations:

```powershell
pwsh scripts/release-android.ps1 -Version X.Y.Z -DryRun
```

The command requires:

- clean local `dev` exactly matching `origin/dev`;
- prepared version metadata and changelog heading;
- an unused `android-vX.Y.Z` tag;
- authenticated `gh`;
- configured GitHub release and Play secrets.

It then:

1. dispatches and awaits **Play Preflight — Android** on the exact `dev` tree;
2. opens or reuses the `dev` → `main` release PR;
3. awaits the required checks and merges with a merge commit;
4. requires the new `main` tree to equal the preflighted `dev` tree;
5. dispatches and awaits **Approve Android Release** from `main`;
6. verifies the immutable tag and public GitHub Release;
7. optionally installs and verifies the compatible phone build.

Running this explicitly is production approval. Do not invoke it for a build
that is not ready for Production.

### 3. What preflight proves

`.github/workflows/play-preflight-android.yml` runs two parallel lanes:

- strict Android lint and focused tests;
- release metadata checks, release-signed APK/AAB build, final DEX scan, and
  private Production-draft upload.

After Play accepts the draft, preflight stores a private 30-day Actions artifact
named:

```text
play-preflight-X.Y.Z-<git-tree>
```

It contains:

- the signed sideload APK;
- the exact Google Play AAB already uploaded as a draft;
- `SHA256SUMS.txt`;
- `play-preflight.json` with version, versionCode, commit, complete Git tree,
  Play track/status, filenames, sizes, and hashes.

The artifact is useful only when its originating workflow run concluded
successfully. The release PR, approval, and publication workflows all enforce
that condition.

### 4. Release PR fast path

For the canonical `dev` → `main` PR, `.github/workflows/ci-required.yml` checks
that:

- the simulated merge tree is byte-for-byte equal to the `dev` tree;
- a non-expired preflight artifact exists for that version and tree;
- its originating preflight workflow succeeded.

That proof replaces the repeated Android lint/test/release-build matrix on the
release PR. Other affected surfaces continue to run their selected checks. Any
other PR targeting `main` receives normal full surface CI.

When this fast path is first introduced, the release PR may still use the older
workflow definition from `main`; subsequent releases use the proof path.

### 5. Approval and publication

`.github/workflows/approve-release-android.yml`:

1. requires `main` and matching version metadata;
2. verifies the successful exact-tree preflight artifact;
3. creates `android-vX.Y.Z` at the current `main` tip;
4. dispatches the immutable-tag release workflow;
5. waits for that workflow and fails if publication fails.

`.github/workflows/release-android.yml` then:

1. verifies tag, version, changelog, privacy URL, and `main` ancestry;
2. downloads the private preflight bundle;
3. verifies every filename, size, SHA-256 digest, versionCode, and Git tree;
4. promotes the existing Play Production draft to `completed`;
5. publishes those exact APK/AAB bytes plus `SHA256SUMS.txt` to GitHub.

Stable publication does not rebuild the application. Prerelease tags, which do
not use stable Play preflight, retain their own focused CI and build path.

Play review and storefront propagation remain asynchronous. With Managed
Publishing disabled, an approved production change becomes available after
Google review. If Managed Publishing is enabled, Play holds the approved change
for a Console operator; keep it disabled for fully unattended releases.

### 6. Phone verification and signing

The public sideload APK is release-signed. A development-signed installation of
the same package cannot be updated by that APK without uninstalling and erasing
its app data.

`-DeployPhone` deliberately builds the exact source tree as
`sideloadDebug` and installs it with the compatible development signature. It
verifies `versionName`, `versionCode`, and activity launch without deleting the
phone configuration. To test the public APK itself, use a device installation
already signed with the release key or perform an explicitly approved clean
install.

## Android prerelease

Use `X.Y.Z-rc.N`, `-beta.N`, or another SemVer prerelease identifier. Prepare
and merge it like a stable Android release, then create the immutable
`android-vX.Y.Z-rc.N` tag from `main`.

Prereleases do not use the stable Production-draft preflight. The release
workflow runs focused CI, builds the artifacts, and marks the GitHub Release as
a prerelease. Do not silently promote a prerelease to Production.

## Server / plugin release

Prepare on `dev`:

```bash
git switch dev
git pull --ff-only origin dev
bash scripts/bump-server-version.sh X.Y.Z
python scripts/check-server-version-sync.py --expect X.Y.Z
```

Update `PLUGIN_RELEASE_NOTES.md` and promote only Server entries from
`CHANGELOG.md` `[Unreleased]`. Merge the preparation into `dev`, open and merge
the `dev` → `main` release PR, then tag the new `main` tip:

```bash
git tag server-vX.Y.Z
git push origin server-vX.Y.Z
```

`.github/workflows/release-plugin.yml` verifies `main` ancestry and version
agreement, runs plugin tests, builds the wheel/sdist, creates checksums, and
publishes `Hermes-Relay-Server vX.Y.Z`.

Do not change Android or Desktop versions unless those surfaces are also being
released.

## Desktop / tray release

Prepare on `dev`:

```powershell
git switch dev
git pull --ff-only origin dev
Set-Location desktop
npm version --no-git-tag-version X.Y.Z
npm run verify
npm run check:version-sync -- --expect X.Y.Z
Set-Location ..
```

Update `CLI_RELEASE_NOTES.md` and promote only Desktop entries from
`CHANGELOG.md` `[Unreleased]`. Merge the preparation into `dev`, open and merge
the `dev` → `main` release PR, then tag the new `main` tip:

```bash
git tag desktop-vX.Y.Z
git push origin desktop-vX.Y.Z
```

`.github/workflows/release-cli.yml` verifies `main` ancestry and version
agreement, builds the standalone binaries and Windows tray, creates checksums,
and publishes the Desktop release.

Do not change Android or Server versions unless those surfaces are also being
released.

## After release

Verify the affected surface:

- immutable tag resolves to the intended `main` commit;
- GitHub Release exists with expected names and checksums;
- workflow summary reports the intended surface and version;
- Play Production shows the Android versionCode with the expected status;
- deployed or installed runtime reports the released version;
- `dev` remains the integration source of truth.

Release documentation, including `DEVLOG.md`, should already be part of release
preparation. Do not create an untracked post-tag documentation delta merely to
record that the release happened.

## Required Android secrets

| Secret | Purpose |
|---|---|
| `HERMES_KEYSTORE_BASE64` | Release-signing keystore |
| `HERMES_KEYSTORE_PASSWORD` | Keystore password |
| `HERMES_KEY_ALIAS` | Signing alias |
| `HERMES_KEY_PASSWORD` | Key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Stable Play draft upload and promotion |

All five are required for stable automated Android publication. See
[Android release operations](docs/release/android-operations.md) for setup and
recovery.

## Hotfix recipe

1. Identify the affected immutable production tag.
2. Branch from that tag: `git switch -c fix/short-name <surface-tag>`.
3. Make the smallest safe fix.
4. Bump only the affected surface patch version during hotfix release prep.
5. Update only that surface's changelog and release notes.
6. Open a focused PR to `main` and merge with a merge commit.
7. Tag the new `main` tip with the affected surface prefix.
8. Verify artifacts, deployment or rollout, and live behavior.
9. Immediately merge `main` back into `dev` with `--no-ff`.

For Android, merge the focused hotfix PR to `main`, run signed preflight from
that untagged `main` tip, then use **Approve Android Release** to create the patch
tag and publish it. Approval still requires the exact-tree proof. Do not rewrite
the affected historical tag.
