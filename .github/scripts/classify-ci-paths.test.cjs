'use strict';

const assert = require('node:assert/strict');
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

console.log('CI path classification tests passed.');
