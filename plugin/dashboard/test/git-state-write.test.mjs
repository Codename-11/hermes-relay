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

// ── Phase 3 extras ─────────────────────────────────────────────────────────

import {
  normalizeCommitMessage,
  hasCommitSuggestion,
  normalizeStashCheckout,
} from "../src/lib/git-state.mjs";

test("normalizeCommitMessage maps message/notice safely", () => {
  const r = normalizeCommitMessage({ message: "feat: add x", notice: "" });
  assert.equal(r.message, "feat: add x");
  assert.equal(r.notice, "");
});

test("normalizeCommitMessage tolerates missing/empty fields", () => {
  assert.deepEqual(normalizeCommitMessage(null), { message: "", notice: "" });
  assert.deepEqual(normalizeCommitMessage({}), { message: "", notice: "" });
});

test("hasCommitSuggestion is false for empty/whitespace messages", () => {
  assert.equal(hasCommitSuggestion({ message: "feat: add x" }), true);
  assert.equal(hasCommitSuggestion({ message: "" }), false);
  assert.equal(hasCommitSuggestion({ message: "   " }), false);
  assert.equal(hasCommitSuggestion(null), false);
});

test("normalizeStashCheckout carries stashed + stash_message", () => {
  const r = normalizeStashCheckout({
    head: "abc",
    stashed: true,
    stash_message: "git-state: feature",
    status: { counts: { staged: 0 } },
    branches: [],
  });
  assert.equal(r.head, "abc");
  assert.equal(r.stashed, true);
  assert.equal(r.stashMessage, "git-state: feature");
  assert.equal(r.status.counts.staged, 0);
});

test("normalizeStashCheckout defaults stashed false when absent", () => {
  const r = normalizeStashCheckout({});
  assert.equal(r.stashed, false);
  assert.equal(r.stashMessage, "");
});
