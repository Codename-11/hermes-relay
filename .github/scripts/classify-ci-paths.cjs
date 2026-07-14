'use strict';

function classifyCiPaths(paths) {
  const forceAll = paths.some((path) => [
    '.github/workflows/ci-required.yml',
    '.github/scripts/classify-ci-paths.cjs',
    '.github/scripts/classify-ci-paths.test.cjs',
  ].includes(path));
  const exact = (values) => paths.some((path) => values.includes(path));
  const under = (prefixes) => paths.some((path) => prefixes.some((prefix) => path.startsWith(prefix)));

  return {
    android: forceAll || under(['app/', 'relay-core/', 'relay-ui/', 'ui-preview/', 'quest/', 'gradle/']) || exact([
      'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat',
      'scripts/check-android-locales.py', 'scripts/android-locale-harness.py',
      'scripts/check-android-collection-apis.py', '.github/workflows/ci-android.yml',
      '.github/workflows/play-preflight-android.yml',
      '.github/workflows/approve-release-android.yml',
      '.github/workflows/release-android.yml',
    ]),
    desktop: forceAll || under(['desktop/']) || exact([
      '.github/workflows/ci-desktop.yml',
    ]),
    plugin: forceAll || paths.some((path) => /^plugin\/[^/]+\.py$/.test(path)) ||
      under(['plugin/relay/', 'plugin/tools/', 'plugin/tests/', 'relay_server/', 'hermes_relay_bootstrap/']) || exact([
        'plugin/plugin.yaml', 'pyproject.toml', 'scripts/check-plugin-version-sync.py',
        'scripts/check-server-version-sync.py', 'scripts/bump-plugin-version.sh',
        'scripts/bump-server-version.sh', '.github/workflows/ci-plugin.yml',
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
