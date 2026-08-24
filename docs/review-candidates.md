# Review builds and release candidates

Hermes-Relay supports two candidate lanes with different intent:

| Lane | Source | Publication | Version/tag |
|---|---|---|---|
| PR review bundle | Exact PR head commit | Private GitHub Actions artifact | No version bump or tag |
| Release candidate | Release-prepared exact `dev` SHA | Public GitHub prerelease | Surface tag ending in `-rc.N` |

Neither lane changes a stable Android installation. Candidate APKs use the
dedicated package ID `com.axiomlabs.hermesrelay.sideload.candidate`, the launcher
label **Hermes Candidate**, an amber launcher background, separate Android app
data, and a persistent in-app banner containing the candidate kind, source, and
short commit SHA. Installing a newer candidate replaces only the previous
candidate slot. It must be paired separately because it does not share the
stable app's encrypted connection state.

## PR review bundles

For an open PR targeting `dev`, apply the `review-candidate` label. The label
event runs in the PR's unprivileged workflow context and builds that exact head
commit, including fork PRs. The label is an ongoing opt-in: reopening the PR or
pushing a new head commit rebuilds the bundle from the new exact head. Removing
the label stops those automatic rebuilds. GitHub may hold a first-time fork
contributor's initial run for explicit maintainer approval before any untrusted
code executes.

After each non-skipped candidate completion, a separate trusted `workflow_run`
reporter creates or updates one marked comment on the PR. Skipped workflow shells
for unlabeled PR events are ignored before artifact or comment APIs are called.
The reporter reads only workflow and artifact
metadata from the completed run and checks out only the repository's default
branch; it never checks out the PR head, downloads the candidate, or executes
fork code with write permission. The comment links the exact artifact and run,
records the source SHA and expiry, and keeps the install and Relay rollback
instructions brief. Rebuilt heads update the same bot comment instead of adding
new comments. Maintainers may also dispatch the reporter with an existing
completed **Build Review Bundle** run ID; the reporter validates that workflow
identity before using its metadata.

For an integrated `dev` commit, use the release-candidate lane below. Review
bundles intentionally have no privileged manual-dispatch path that can execute
an arbitrary source SHA.

The workflow resolves and checks out one immutable commit, then uploads one
short-lived artifact containing:

- a minified, debug-signed `sideloadCandidate` APK;
- Relay wheel and source distributions;
- a Relay source archive from the same commit;
- `REVIEW_MANIFEST.json` with provenance and install-isolation facts; and
- `SHA256SUMS.txt` covering every installable/package artifact.

The workflow intentionally uses no signing or deployment secrets, including for
fork PRs. It does not create a tag, GitHub Release, Play upload, deployment, or
updater notification.

### Android install and rollback

1. Verify the downloaded files with `SHA256SUMS.txt`.
2. Install the APK normally or with `adb install -r <candidate.apk>`.
3. Confirm the launcher says **Hermes Candidate** and the in-app banner shows
   the expected PR/SHA before pairing it.
4. Remove only the candidate with:
   `adb uninstall com.axiomlabs.hermesrelay.sideload.candidate`.

The Google Play package (`com.axiomlabs.hermesrelay`) and stable sideload package
(`com.axiomlabs.hermesrelay.sideload`) remain untouched.

### Relay install and rollback

Relay plugins cannot be enabled side-by-side in one Hermes process: the plugin
identity, tool names, hooks, Dashboard route prefix, and default relay port are
single-owner surfaces. A review bundle therefore does **not** silently install or
replace Relay on a user's host.

Use one of these explicit review environments:

1. a disposable/staging Hermes instance (preferred); or
2. an operator-owned immutable plugin snapshot with the prior plugin path and
   service configuration recorded before activation.

The Relay package/source SHA must match `REVIEW_MANIFEST.json`. After review,
restore the recorded plugin path and service configuration and verify Dashboard,
Gateway, Relay health, connected clients, and session continuity. Never point a
managed plugin symlink through a dirty development checkout.

## Release candidates

Use an RC only after release scope is frozen and the affected surface's version
metadata and notes have been prepared on `dev`.

1. Select the exact tested release-prep commit on `dev`.
2. Create the affected surface tag, such as `android-v1.12.0-rc.1`,
   `server-v1.9.0-rc.1`, or `desktop-v0.5.0-rc.1`, at that commit.
3. The release workflow verifies the prerelease tag is contained in `dev`.
4. Android RCs publish the side-by-side Candidate APK and never upload to Play.
5. Plugin RCs publish prerelease packages for explicit staging/opt-in install;
   they do not replace a running production plugin automatically.
6. CLI+UI RCs publish opt-in prerelease binaries/installers; stable updater
   channels continue to ignore them.
7. Record the exact tag/SHA and test results in the release issue.

After approval, prepare the stable version on `dev`, merge `dev → main` with the
repository's no-ff policy, and create the stable production tag from the new
`main` tip. Stable tag workflows continue to reject commits not contained in
`main`.
