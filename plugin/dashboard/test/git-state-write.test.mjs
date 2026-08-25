import test from "node:test";
import assert from "node:assert/strict";

import {
  normalizeMutationResult,
  confirmationFor,
  requiresConfirmation,
  CONFIRMATIONS,
} from "../src/lib/git-state.mjs";

test("normalizeMutationResult maps head/status/branches safely", () => {
  const r = normalizeMutationResult({
    head: "abc123",
    status: { counts: { staged: 1 } },
    branches: [{ name: "main" }],
  });
  assert.equal(r.head, "abc123");
  assert.equal(r.status.counts.staged, 1);
  assert.equal(r.branches.length, 1);
});

test("normalizeMutationResult tolerates missing fields", () => {
  const r = normalizeMutationResult(null);
  assert.equal(r.head, "");
  assert.deepEqual(r.status.counts, { staged: 0, modified: 0, untracked: 0 });
  assert.deepEqual(r.branches, []);
});

test("CONFIRMATIONS holds the fixed confirmation tokens", () => {
  assert.equal(CONFIRMATIONS.discard, "discard");
  assert.equal(CONFIRMATIONS.push, "push");
  assert.equal(CONFIRMATIONS.dirtyCheckout, "checkout-dirty");
});

test("confirmationFor returns the token only for destructive ops", () => {
  assert.equal(confirmationFor("discard"), CONFIRMATIONS.discard);
  assert.equal(confirmationFor("push"), CONFIRMATIONS.push);
  assert.equal(confirmationFor("dirty-checkout"), CONFIRMATIONS.dirtyCheckout);
  assert.equal(confirmationFor("commit"), null);
  assert.equal(confirmationFor("stage"), null);
});

test("requiresConfirmation gates only destructive ops", () => {
  assert.equal(requiresConfirmation("discard"), true);
  assert.equal(requiresConfirmation("push"), true);
  assert.equal(requiresConfirmation("dirty-checkout"), true);
  assert.equal(requiresConfirmation("stage"), false);
  assert.equal(requiresConfirmation("commit"), false);
  assert.equal(requiresConfirmation("fetch"), false);
});
