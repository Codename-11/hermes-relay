import test from "node:test";
import assert from "node:assert/strict";

import {
  normalizeRepos,
  normalizeStatus,
  normalizeBranches,
  branchLabel,
  isTruncated,
} from "../src/lib/git-state.mjs";

test("normalizeRepos accepts object and bare-array shapes", () => {
  assert.deepEqual(
    normalizeRepos({ repos: [{ id: "a", name: "A" }] }),
    [{ id: "a", name: "A" }],
  );
  assert.deepEqual(normalizeRepos([{ id: "b", name: "B" }]), [
    { id: "b", name: "B" },
  ]);
  assert.deepEqual(normalizeRepos(null), []);
  assert.deepEqual(normalizeRepos({ repos: [{ id: 1 }, null] }), []);
});

test("normalizeStatus fills missing groups and preserves truncation", () => {
  const status = normalizeStatus({
    counts: { staged: 1, modified: 2, untracked: 3 },
    staged: [{ path: "a" }],
    truncated: true,
  });
  assert.equal(status.counts.staged, 1);
  assert.equal(status.counts.modified, 2);
  assert.equal(status.counts.untracked, 3);
  assert.deepEqual(status.staged, [{ path: "a" }]);
  assert.deepEqual(status.modified, []);
  assert.deepEqual(status.untracked, []);
  assert.equal(status.truncated, true);
  assert.equal(isTruncated(status), true);
  assert.equal(isTruncated(normalizeStatus({})), false);
});

test("normalizeBranches accepts object and bare-array shapes", () => {
  assert.deepEqual(normalizeBranches({ branches: [{ name: "main" }] }), [
    { name: "main" },
  ]);
  assert.deepEqual(normalizeBranches([{ name: "dev" }]), [{ name: "dev" }]);
  assert.deepEqual(normalizeBranches(null), []);
});

test("branchLabel renders upstream and ahead/behind", () => {
  assert.equal(branchLabel({ name: "main" }), "main");
  assert.equal(
    branchLabel({ name: "main", upstream: "origin/main" }),
    "main → origin/main",
  );
  assert.equal(
    branchLabel({ name: "feature", upstream: "origin/feature", ahead: 1, behind: 2 }),
    "feature → origin/feature (ahead 1, behind 2)",
  );
  assert.equal(branchLabel(null), "");
});
