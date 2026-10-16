'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { hasRequiredCiGate } = require('./check-dependabot-merge.cjs');

const rule = () => ({
  type: 'required_status_checks',
  parameters: {
    strict_required_status_checks_policy: true,
    required_status_checks: [{ context: 'Required checks', integration_id: 15368 }],
  },
});

test('permits the enforced current-base GitHub Actions gate', () => {
  assert.equal(hasRequiredCiGate([rule()]), true);
});

test('unprotected branches and unrelated rules fail closed', () => {
  for (const rules of [null, {}, [], [{ type: 'pull_request' }]]) {
    assert.equal(hasRequiredCiGate(rules), false);
  }
});

test('a stale-base check is not enough', () => {
  const changed = rule();
  changed.parameters.strict_required_status_checks_policy = false;
  assert.equal(hasRequiredCiGate([changed]), false);
});

test('the aggregate gate must be required, not just one surface job', () => {
  const changed = rule();
  changed.parameters.required_status_checks[0].context = 'android / Test (Android)';
  assert.equal(hasRequiredCiGate([changed]), false);
});

test('the check must be bound to GitHub Actions', () => {
  for (const integration_id of [undefined, null, 42]) {
    const changed = rule();
    changed.parameters.required_status_checks[0].integration_id = integration_id;
    assert.equal(hasRequiredCiGate([changed]), false);
  }
});
