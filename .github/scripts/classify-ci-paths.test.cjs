'use strict';

const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { classifyCiPaths } = require('./classify-ci-paths.cjs');

const none = {
  android: false,
  desktop: false,
  plugin: false,
  dashboard: false,
  contract: false,
  docs: false,
};

assert.deepEqual(classifyCiPaths(['README.md']), none);
assert.deepEqual(classifyCiPaths(['desktop/src/cli.ts']), { ...none, desktop: true });
assert.deepEqual(classifyCiPaths(['relay-core/src/main/kotlin/Wire.kt']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/check-android-release-notes.py']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/check-android-native-compat.py']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/android_release_artifacts.py']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/tests/android_release_artifacts_test.py']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/tests/check_android_native_compat_test.py']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/android-lane.ps1']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/android-prepush.py']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/dev.bat']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/dev.sh']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['scripts/tests/android_prepush_test.py']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['.github/workflows/android-on-demand.yml']), { ...none, android: true });
assert.deepEqual(classifyCiPaths(['.github/workflows/approve-release-extensions.yml']), {
  ...none,
  desktop: true,
  plugin: true,
});
assert.deepEqual(classifyCiPaths(['.github/workflows/release-cli.yml']), { ...none, desktop: true });
assert.deepEqual(classifyCiPaths(['.github/workflows/release-plugin.yml']), { ...none, plugin: true });
assert.deepEqual(classifyCiPaths(['plugin/relay/server.py']), { ...none, plugin: true });
assert.deepEqual(classifyCiPaths(['plugin/dashboard/src/App.tsx']), { ...none, dashboard: true });
assert.deepEqual(classifyCiPaths(['user-docs/index.md']), { ...none, docs: true });
assert.deepEqual(
  classifyCiPaths(['app/src/main/kotlin/com/hermesandroid/relay/network/upstream/DashboardApiClient.kt']),
  { ...none, android: true, contract: true },
);
assert.deepEqual(classifyCiPaths(['.github/workflows/ci-required.yml']), {
  android: true,
  desktop: true,
  plugin: true,
  dashboard: true,
  contract: true,
  docs: true,
});
assert.deepEqual(classifyCiPaths(['.github/workflows/release-backmerge.yml']), {
  android: true,
  desktop: true,
  plugin: true,
  dashboard: true,
  contract: true,
  docs: true,
});
assert.deepEqual(classifyCiPaths(['.github/workflows/approve-release-train.yml']), {
  android: true,
  desktop: true,
  plugin: true,
  dashboard: true,
  contract: true,
  docs: true,
});

const repoRoot = join(__dirname, '..', '..');
const approvalWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'approve-release-extensions.yml'),
  'utf8',
);
const cliReleaseWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'release-cli.yml'),
  'utf8',
);
const pluginReleaseWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'release-plugin.yml'),
  'utf8',
);
const desktopCiWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'ci-desktop.yml'),
  'utf8',
);
const androidPreflightWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'play-preflight-android.yml'),
  'utf8',
);
const androidApprovalWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'approve-release-android.yml'),
  'utf8',
);
const androidReleaseWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'release-android.yml'),
  'utf8',
);
const requiredChecksWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'ci-required.yml'),
  'utf8',
);
const releaseTrainWorkflow = readFileSync(
  join(repoRoot, '.github', 'workflows', 'approve-release-train.yml'),
  'utf8',
);

assert.match(approvalWorkflow, /permissions:\r?\n  contents: read/);
assert.match(
  approvalWorkflow,
  /approve:[\s\S]*?permissions:\r?\n      actions: write\r?\n      contents: write/,
);
assert.match(
  approvalWorkflow,
  /ref: \$\{\{ contains\(inputs\.version, '-'\) && 'dev' \|\| 'main' \}\}/,
);
assert.match(cliReleaseWorkflow, /workflow_dispatch:[\s\S]*?Approved CLI\+UI version/);
assert.match(cliReleaseWorkflow, /name: Restore exact-source tray build cache[\s\S]*?actions\/cache@v5/);
assert.match(desktopCiWorkflow, /name: Restore exact-source tray build cache[\s\S]*?actions\/cache@v5/);
assert.match(pluginReleaseWorkflow, /workflow_dispatch:[\s\S]*?Approved Plugin version/);
assert.match(androidPreflightWorkflow, /Package immutable preflight artifacts/);
assert.match(androidApprovalWorkflow, /Android public approval accepts stable SemVer only/);
assert.match(androidReleaseWorkflow, /Download exact stable preflight artifacts/);
assert.match(androidReleaseWorkflow, /artifact-ids: \$\{\{ needs\.validate\.outputs\.preflight_artifact_id \}\}/);
assert.match(requiredChecksWorkflow, /name: Reuse exact-tree required checks/);
assert.match(requiredChecksWorkflow, /name: required-checks-\$\{\{ needs\.changes\.outputs\.tree \}\}/);
assert.match(releaseTrainWorkflow, /name: Hermes-Relay Coordinated Release Approval/);
assert.match(releaseTrainWorkflow, /Coordinated Android approval is stable-only/);

console.log('CI path classification tests passed.');
