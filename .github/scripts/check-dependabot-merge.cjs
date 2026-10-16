'use strict';

const GITHUB_ACTIONS_APP_ID = 15368;

function hasRequiredCiGate(rules) {
  return Array.isArray(rules) && rules.some((rule) =>
    rule.type === 'required_status_checks' &&
    rule.parameters?.strict_required_status_checks_policy === true &&
    rule.parameters?.required_status_checks?.some((check) =>
      check.context === 'Required checks' &&
      check.integration_id === GITHUB_ACTIONS_APP_ID));
}

if (require.main === module) {
  const rules = JSON.parse(require('node:fs').readFileSync(process.argv[2], 'utf8'));
  if (!hasRequiredCiGate(rules)) {
    console.error('Refusing auto-merge: the target branch must require current-base Required checks from GitHub Actions.');
    process.exitCode = 1;
  }
}

module.exports = { hasRequiredCiGate };
