'use strict';

const assert = require('node:assert/strict');
const {
  artifactPrNumber,
  buildReviewComment,
  reportReviewBundle,
} = require('./review-bundle-report.cjs');

const run = {
  id: 32729383426,
  name: 'Build Review Bundle',
  event: 'pull_request',
  conclusion: 'success',
  head_sha: '90ab705a883ca963035f4f8ccda815619dbd4f3b',
  head_branch: 'fix/gateway-history-attachments',
  head_repository: { owner: { login: 'JackHunzicker' } },
  html_url: 'https://github.com/Codename-11/hermes-relay/actions/runs/32729383426',
  pull_requests: [],
};
const artifact = {
  id: 9521126010,
  name: 'hermes-relay-review-pr-398-90ab705a883c',
  expired: false,
  expires_at: '2026-08-31T12:52:24Z',
};

assert.equal(artifactPrNumber([artifact], run.head_sha), 398);

const successBody = buildReviewComment({
  conclusion: 'success',
  prNumber: 398,
  headSha: run.head_sha,
  runUrl: run.html_url,
  artifact,
});
assert.match(successBody, /## Review candidate ready/);
assert.match(successBody, /hermes-relay-review-pr-398-90ab705a883c/);
assert.match(successBody, /expires \*\*August 31, 2026\*\*/);
assert.match(successBody, /HR Candidate/);
assert.ok(!successBody.includes(['Hermes', 'Candidate'].join(' ')));
assert.match(successBody, /REVIEW_MANIFEST\.json/);

const blockedBody = buildReviewComment({
  conclusion: 'action_required',
  prNumber: 398,
  headSha: run.head_sha,
  runUrl: run.html_url,
});
assert.match(blockedBody, /## Review candidate awaiting approval/);
assert.doesNotMatch(blockedBody, /Download/);

async function testExistingCommentIsUpdated() {
  const calls = { create: [], update: [] };
  const github = {
    rest: {
      actions: { listWorkflowRunArtifacts() {} },
      issues: {
        listComments() {},
        createComment: async (args) => calls.create.push(args),
        updateComment: async (args) => calls.update.push(args),
      },
      pulls: { list: async () => ({ data: [] }) },
    },
    paginate: async (method) => {
      if (method === github.rest.actions.listWorkflowRunArtifacts) return [artifact];
      if (method === github.rest.issues.listComments) {
        return [{
          id: 77,
          user: { login: 'github-actions[bot]' },
          body: '<!-- hermes-relay-review-candidate -->\nold',
        }];
      }
      throw new Error('Unexpected pagination method');
    },
  };
  const messages = [];
  await reportReviewBundle({
    github,
    context: {
      repo: { owner: 'Codename-11', repo: 'hermes-relay' },
      payload: { workflow_run: run },
    },
    core: {
      info: (message) => messages.push(message),
      warning: (message) => messages.push(message),
    },
  });
  assert.equal(calls.create.length, 0);
  assert.equal(calls.update.length, 1);
  assert.equal(calls.update[0].comment_id, 77);
  assert.match(calls.update[0].body, /## Review candidate ready/);
  assert.deepEqual(messages, ['Updated review-candidate comment on PR #398.']);
}

async function testManualRunSelectionCreatesComment() {
  const calls = { create: [], update: [] };
  const github = {
    rest: {
      actions: {
        getWorkflowRun: async ({ run_id: runId }) => {
          assert.equal(runId, run.id);
          return { data: run };
        },
        listWorkflowRunArtifacts() {},
      },
      issues: {
        listComments() {},
        createComment: async (args) => calls.create.push(args),
        updateComment: async (args) => calls.update.push(args),
      },
      pulls: { list: async () => ({ data: [] }) },
    },
    paginate: async (method) => {
      if (method === github.rest.actions.listWorkflowRunArtifacts) return [artifact];
      if (method === github.rest.issues.listComments) return [];
      throw new Error('Unexpected pagination method');
    },
  };
  await reportReviewBundle({
    github,
    context: {
      repo: { owner: 'Codename-11', repo: 'hermes-relay' },
      payload: { inputs: { run_id: String(run.id) } },
    },
    core: {
      info() {},
      warning() {},
      setFailed: (message) => assert.fail(message),
    },
  });
  assert.equal(calls.update.length, 0);
  assert.equal(calls.create.length, 1);
  assert.equal(calls.create[0].issue_number, 398);
  assert.match(calls.create[0].body, /## Review candidate ready/);
}

async function testSkippedRunIsIgnored() {
  let apiCalled = false;
  const messages = [];
  const github = {
    rest: {
      actions: {
        listWorkflowRunArtifacts() {},
      },
    },
    paginate: async () => {
      apiCalled = true;
      return [];
    },
  };
  await reportReviewBundle({
    github,
    context: {
      repo: { owner: 'Codename-11', repo: 'hermes-relay' },
      payload: {
        workflow_run: {
          ...run,
          id: 32736508535,
          conclusion: 'skipped',
          head_sha: 'a38849ff1680a1993230773a5d602b781367c789',
        },
      },
    },
    core: {
      info: (message) => messages.push(message),
      warning: (message) => messages.push(message),
      setFailed: (message) => assert.fail(message),
    },
  });
  assert.equal(apiCalled, false);
  assert.deepEqual(messages, ['Ignoring skipped review-bundle run 32736508535.']);
}

Promise.all([
  testExistingCommentIsUpdated(),
  testManualRunSelectionCreatesComment(),
  testSkippedRunIsIgnored(),
])
  .then(() => console.log('Review-bundle report tests passed.'))
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
