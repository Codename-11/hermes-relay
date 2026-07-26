# Android release operations

Setup, fallback, and troubleshooting reference for the canonical Android
release process in [RELEASE.md](../../RELEASE.md).

The normal stable path is:

```powershell
pwsh scripts/release-android.ps1 -Version X.Y.Z
```

The procedures below are initial setup or emergency recovery, not the normal
release checklist.

## Release signing

Generate and protect a dedicated Android release keystore. Never commit the
keystore, its Base64 representation, passwords, service-account JSON, or local
secret files.

Store these GitHub Actions secrets:

| Secret | Value |
|---|---|
| `HERMES_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `HERMES_KEYSTORE_PASSWORD` | Keystore password |
| `HERMES_KEY_ALIAS` | Release-key alias |
| `HERMES_KEY_PASSWORD` | Release-key password |

PowerShell encoding example:

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes('release.keystore')
) | Set-Content -Encoding ascii release.keystore.b64
```

Delete the temporary Base64 file after storing the secret.

Google Play App Signing manages the store-distributed signing certificate.
The repository keystore is the upload/release certificate and must remain
stable across releases.

## Play Developer API

`PLAY_SERVICE_ACCOUNT_JSON` is required for stable automated releases.

One-time setup:

1. Enable the Google Play Android Developer API in a Google Cloud project.
2. Create a dedicated service account and JSON key.
3. Grant that account the minimum Play Console application permissions needed
   to view the app, create and edit Production releases, and use Play App
   Signing.
4. Store the complete JSON document as the GitHub Actions secret
   `PLAY_SERVICE_ACCOUNT_JSON`.
5. Confirm **Play Preflight — Android** can create a Production draft.

The service account is not optional for the stable workflow. Manual Console
upload is an emergency fallback only.

## Managed Publishing

For unattended releases, keep Play Console Managed Publishing disabled.

- Disabled: `completed` submits the production change; availability follows
  Google review and propagation.
- Enabled: an approved change remains under **Changes ready to publish** until
  a Console operator publishes it.

The Play Developer API reports acceptance of the edit; review reports and
storefront propagation remain asynchronous.

## Track intent

- **Production**: stable `android-vX.Y.Z` releases.
- **Open testing / beta**: intentional public beta.
- **Closed testing / alpha**: intentional private beta.
- **Internal**: throwaway test distribution.

Tracks are options, not a mandatory ladder. Stable releases go directly to
Production unless the release plan explicitly says otherwise.

## Emergency manual Play recovery

Use this only when the automated workflow is unavailable and the release owner
has explicitly approved a manual recovery.

To upload the prepared Google Play bundle as a Production draft:

```powershell
.\gradlew.bat publishGooglePlayReleaseBundle `
  --track=production `
  --release-status=draft `
  --resolution-strategy=ignore `
  --release-name='Hermes-Relay X.Y.Z'
```

To promote an existing Production draft:

```powershell
.\gradlew.bat promoteGooglePlayReleaseArtifact `
  --update=production `
  --version-code=N `
  --release-status=completed `
  --release-name='Hermes-Relay X.Y.Z'
```

Both commands require `play-service-account.json` in the configured local
location. Remove the file after use.

If API automation is unavailable, the final fallback is Play Console:

1. Open **Release → Production**.
2. Create or edit the release.
3. Upload the `-googlePlay-release.aab`.
4. Use the locale-specific files under
   `app/src/googlePlay/play/release-notes/` for What's New.
5. Review and start the rollout.

Record the exact versionCode and resulting Play status in the release issue.

## Private preflight artifact

Stable preflight stores a private Actions artifact named:

```text
play-preflight-X.Y.Z-<git-tree>
```

Retention is 30 days. The release workflow rejects an expired artifact, an
artifact from a failed run, a tree/version mismatch, unexpected files, or any
size/SHA-256 mismatch.

If the artifact expires before approval, rerun preflight from the unchanged
source tree. Never recreate the artifact locally and upload it under the proof
name.

## Phone signing behavior

Android will not update an installed package when the signing certificate
changes.

- Public sideload releases use the release key.
- Rapid development builds use the development/debug key.
- Both currently use `com.axiomlabs.hermesrelay.sideload`.

Therefore, a public release APK cannot update a development-signed installation
without uninstalling it and erasing app data. The orchestrator's `-DeployPhone`
option preserves configuration by installing a development-signed build from
the exact release source tree. Use a release-signed installation when the goal
is to test the downloadable public APK itself.

Never automatically uninstall the app as a signing workaround.

## Troubleshooting

### No successful preflight artifact

Confirm:

- preflight ran from the final `dev` or untagged `main` tree;
- requested version matches `appVersionName`;
- Play draft upload succeeded;
- both preflight jobs concluded successfully;
- the artifact has not expired;
- the release PR merge tree is unchanged.

### Version code already used

Increase `appVersionCode` with `scripts/bump-android-version.sh`. Play version
codes are permanent and cannot be reused even when a draft is discarded.

### Play rejects signing

Verify all keystore secrets exist and inspect the artifact certificate. Stable
preflight intentionally fails when `HERMES_KEYSTORE_BASE64` is absent; it never
falls back to a debug-signed Play upload.

### Approval succeeded but publication failed

Approval now waits for the release workflow and inherits its failure. Inspect
the linked **Release Android** run. If workflow code—not application content—is
the problem, repair the workflow on `main`, dispatch it for the existing
version, and keep every job checked out at the immutable tag. Never move the
tag.

### Release PR still runs the old full Android matrix

`pull_request` workflows use the definition on the PR base branch. The first
release carrying the fast path may therefore run the older `main` workflow.
After that release lands, canonical `dev` → `main` release PRs use the
preflight-proof job.

### Phone install reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

The installed app and APK have different signing certificates. Use the
orchestrator's compatible `-DeployPhone` path, use a release-signed test
installation, or explicitly export configuration before an approved clean
install. Do not silently uninstall.
