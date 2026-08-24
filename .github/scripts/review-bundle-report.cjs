'use strict';

const COMMENT_MARKER = '<!-- hermes-relay-review-candidate -->';
const ARTIFACT_NAME_RE = /^hermes-relay-review-pr-(\d+)-([0-9a-f]{12})$/;

function formatExpiry(value) {
  if (!value) return 'the artifact retention window';
  return new Intl.DateTimeFormat('en-US', {
    month: 'long',
    day: 'numeric',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(value));
}

function buildReviewComment({ conclusion, prNumber, headSha, runUrl, artifact }) {
  const shortSha = headSha.slice(0, 12);

  if (conclusion === 'success' && artifact) {
    const artifactUrl = `${runUrl}/artifacts/${artifact.id}`;
    return `${COMMENT_MARKER}
## Review candidate ready

Built from PR #${prNumber} head \`${shortSha}\`.

[Download \`${artifact.name}\`](${artifactUrl}) — expires **${formatExpiry(artifact.expires_at)}**.

1. Unzip the bundle and verify its files against \`SHA256SUMS.txt\`.
2. Install the APK under \`android/\`. It appears as **Hermes Candidate**, leaves stable installs untouched, and must be paired separately.
3. Test the Relay package only in a disposable/staging Hermes instance or with an explicit snapshot and rollback plan. Confirm the source SHA in \`REVIEW_MANIFEST.json\`.

[View workflow run](${runUrl})`;
  }

  if (conclusion === 'action_required') {
    return `${COMMENT_MARKER}
## Review candidate awaiting approval

GitHub held the build for PR #${prNumber} head \`${shortSha}\` at the first-time fork approval gate. A maintainer must approve the run before any candidate can be published.

[Review and approve the workflow run](${runUrl})`;
  }

  const result = conclusion || 'unknown';
  return `${COMMENT_MARKER}
## Review candidate unavailable

The build for PR #${prNumber} head \`${shortSha}\` completed with **${result}** and did not publish a candidate bundle.

[View workflow run](${runUrl})`;
}

function artifactPrNumber(artifacts, headSha) {
  const shortSha = headSha.slice(0, 12);
  for (const artifact of artifacts) {
    const match = ARTIFACT_NAME_RE.exec(artifact.name);
    if (match && match[2] === shortSha) return Number(match[1]);
  }
  return null;
}

async function resolvePrNumber({ github, owner, repo, run, artifacts }) {
  const payloadPr = run.pull_requests?.[0]?.number;
  if (payloadPr) return payloadPr;

  const artifactPr = artifactPrNumber(artifacts, run.head_sha);
  if (artifactPr) return artifactPr;

  const headOwner = run.head_repository?.owner?.login;
  if (!headOwner || !run.head_branch) return null;

  const { data: pulls } = await github.rest.pulls.list({
    owner,
    repo,
    head: `${headOwner}:${run.head_branch}`,
    state: 'all',
    per_page: 100,
  });
  const exact = pulls.find((pull) =>
    pull.head.sha === run.head_sha && pull.base.ref === 'dev'
  );
  return exact?.number ?? null;
}

async function resolveWorkflowRun({ github, context, core }) {
  const completedRun = context.payload.workflow_run;
  if (completedRun) return completedRun;

  const requested = context.payload.inputs?.run_id;
  const runId = Number(requested);
  if (!Number.isSafeInteger(runId) || runId <= 0) {
    core.setFailed(`Invalid Build Review Bundle run ID: ${requested ?? ''}`);
    return null;
  }
  const { owner, repo } = context.repo;
  const { data: run } = await github.rest.actions.getWorkflowRun({
    owner,
    repo,
    run_id: runId,
  });
  return run;
}

async function reportReviewBundle({ github, context, core }) {
  const run = await resolveWorkflowRun({ github, context, core });
  const { owner, repo } = context.repo;
  if (!run) return;
  if (run.name !== 'Build Review Bundle' || run.event !== 'pull_request') {
    core.info('Ignoring a review-bundle run that was not triggered by a pull request.');
    return;
  }

  const artifacts = await github.paginate(
    github.rest.actions.listWorkflowRunArtifacts,
    { owner, repo, run_id: run.id, per_page: 100 },
  );
  const prNumber = await resolvePrNumber({ github, owner, repo, run, artifacts });
  if (!prNumber) {
    core.warning(`Could not resolve a pull request for review-bundle run ${run.id}.`);
    return;
  }

  const expectedName = `hermes-relay-review-pr-${prNumber}-${run.head_sha.slice(0, 12)}`;
  const artifact = artifacts.find((item) => item.name === expectedName && !item.expired);
  const body = buildReviewComment({
    conclusion: run.conclusion,
    prNumber,
    headSha: run.head_sha,
    runUrl: run.html_url,
    artifact,
  });

  const comments = await github.paginate(
    github.rest.issues.listComments,
    { owner, repo, issue_number: prNumber, per_page: 100 },
  );
  const existing = comments.find((comment) =>
    comment.user?.login === 'github-actions[bot]' &&
    comment.body?.includes(COMMENT_MARKER)
  );

  if (existing) {
    await github.rest.issues.updateComment({
      owner,
      repo,
      comment_id: existing.id,
      body,
    });
    core.info(`Updated review-candidate comment on PR #${prNumber}.`);
  } else {
    await github.rest.issues.createComment({
      owner,
      repo,
      issue_number: prNumber,
      body,
    });
    core.info(`Created review-candidate comment on PR #${prNumber}.`);
  }
}

module.exports = {
  ARTIFACT_NAME_RE,
  COMMENT_MARKER,
  artifactPrNumber,
  buildReviewComment,
  reportReviewBundle,
  resolvePrNumber,
  resolveWorkflowRun,
};
