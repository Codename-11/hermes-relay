'use strict';

function classifyCiPaths(paths) {
  const forceAll = paths.some((path) => [
    '.github/workflows/ci-required.yml',
    '.github/workflows/release-backmerge.yml',
    '.github/workflows/approve-release-train.yml',
    '.github/scripts/classify-ci-paths.cjs',
    '.github/scripts/classify-ci-paths.test.cjs',
    'scripts/plan_release_backmerge.py',
    'scripts/tests/plan_release_backmerge_test.py',
  ].includes(path));
  const exact = (values) => paths.some((path) => values.includes(path));
  const under = (prefixes) => paths.some((path) => prefixes.some((prefix) => path.startsWith(prefix)));

  return {
    android: forceAll || under(['app/', 'relay-core/', 'relay-ui/', 'ui-preview/', 'quest/', 'gradle/']) || exact([
      'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat',
      'scripts/check-android-locales.py', 'scripts/android-locale-harness.py',
      'scripts/check-android-collection-apis.py', 'scripts/check-android-native-compat.py',
      'scripts/check-android-release-notes.py',
      'scripts/android_release_artifacts.py',
      'scripts/android-lane.ps1', 'scripts/android-prepush.py', 'scripts/dev.bat', 'scripts/dev.sh',
      'scripts/tests/android_prepush_test.py',
      'scripts/tests/check_android_native_compat_test.py',
      'scripts/tests/check_android_release_notes_test.py',
      'scripts/tests/android_release_artifacts_test.py',
      '.github/workflows/android-on-demand.yml', '.github/workflows/ci-android.yml',
      '.github/workflows/play-preflight-android.yml',
      '.github/workflows/approve-release-android.yml',
      '.github/workflows/release-android.yml',
    ]),
    desktop: forceAll || under(['desktop/']) || exact([
      '.github/workflows/ci-desktop.yml',
      '.github/workflows/approve-release-extensions.yml',
      '.github/workflows/release-cli.yml',
    ]),
    plugin: forceAll || paths.some((path) => /^plugin\/[^/]+\.py$/.test(path)) ||
      under(['plugin/relay/', 'plugin/tools/', 'plugin/tests/', 'relay_server/', 'hermes_relay_bootstrap/']) || exact([
        'plugin/plugin.yaml', 'pyproject.toml', 'scripts/check-plugin-version-sync.py',
        'scripts/check-server-version-sync.py', 'scripts/bump-plugin-version.sh',
        'scripts/bump-server-version.sh', '.github/workflows/ci-plugin.yml',
        '.github/workflows/approve-release-extensions.yml',
        '.github/workflows/release-plugin.yml',
      ]),
    dashboard: forceAll || under(['plugin/dashboard/']) || exact([
      '.github/workflows/ci-dashboard.yml',
    ]),
    contract: forceAll ||
      under(['app/src/main/kotlin/com/hermesandroid/relay/network/upstream/']) || exact([
        'scripts/check-upstream-route-contract.py', '.github/workflows/ci-contract.yml',
      ]),
    docs: forceAll || under(['user-docs/']) || exact([
      '.github/workflows/docs.yml',
    ]),
  };
}

module.exports = { classifyCiPaths };
