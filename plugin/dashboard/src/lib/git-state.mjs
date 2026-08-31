// Pure data-mapping helpers for the Git State dashboard tab.
// Kept free of React/DOM so they are unit-testable with node:test.

/**
 * Normalize a /git/repos response into a stable repo list.
 * Accepts {repos:[...]} or a bare array.
 */
export function normalizeRepos(data) {
  const list = Array.isArray(data) ? data : (data && data.repos) || [];
  return list.filter((r) => r && typeof r.id === "string");
}

/**
 * Normalize a /git/status response into {counts, staged, modified, untracked,
 * truncated}. Missing groups become empty arrays.
 */
export function normalizeStatus(data) {
  const src = data && typeof data === "object" ? data : {};
  return {
    counts: {
      staged: Number(src.counts && src.counts.staged) || 0,
      modified: Number(src.counts && src.counts.modified) || 0,
      untracked: Number(src.counts && src.counts.untracked) || 0,
    },
    staged: Array.isArray(src.staged) ? src.staged : [],
    modified: Array.isArray(src.modified) ? src.modified : [],
    untracked: Array.isArray(src.untracked) ? src.untracked : [],
    truncated: !!src.truncated,
  };
}

/**
 * Normalize a /git/branches response into a branch list.
 * Accepts {branches:[...]} or a bare array.
 */
export function normalizeBranches(data) {
  const list = Array.isArray(data) ? data : (data && data.branches) || [];
  return list.filter((b) => b && typeof b.name === "string");
}

/**
 * Build a human-readable branch label, e.g. "main → origin/main (ahead 1)".
 */
export function branchLabel(branch) {
  if (!branch) return "";
  const base = branch.name || "";
  if (!branch.upstream) return base;
  const track =
    branch.ahead > 0 || branch.behind > 0
      ? ` (ahead ${branch.ahead}, behind ${branch.behind})`
      : "";
  return `${base} → ${branch.upstream}${track}`;
}

/**
 * True when a status response is truncated (over the server cap).
 */
export function isTruncated(status) {
  return !!(status && status.truncated);
}

/**
 * Fixed per-use confirmation tokens for destructive git mutations. These
 * mirror the plugin's server-side constants (plugin/git_state.py) and are
 * sent in the POST body so the server can enforce the destructive gate.
 * The dashboard tab shows a human-readable description before echoing these.
 */
export const CONFIRMATIONS = {
  discard: "discard",
  push: "push",
  dirtyCheckout: "checkout-dirty",
};

/** Ops that require a per-use confirmation string before the POST is sent. */
const DESTRUCTIVE_OPS = new Set(["discard", "push", "dirty-checkout"]);

/**
 * True when the named mutation requires a per-use confirmation string.
 * The tab must not send the POST without it (matches the server gate).
 */
export function requiresConfirmation(op) {
  return DESTRUCTIVE_OPS.has(op);
}

/**
 * Return the fixed confirmation token for a destructive op, or null when the
 * op is non-destructive (no confirmation needed).
 */
export function confirmationFor(op) {
  if (!requiresConfirmation(op)) return null;
  if (op === "discard") return CONFIRMATIONS.discard;
  if (op === "push") return CONFIRMATIONS.push;
  return CONFIRMATIONS.dirtyCheckout;
}

/**
 * Normalize a mutation response ({head, status, branches}) into a stable
 * shape, filling missing groups so the tab can render without defensive
 * branching.
 */
export function normalizeMutationResult(data) {
  const src = data && typeof data === "object" ? data : {};
  return {
    head: typeof src.head === "string" ? src.head : "",
    status: normalizeStatus(src.status),
    branches: normalizeBranches(src.branches),
  };
}

/**
 * Normalize a /git/commit_message response into {message, notice, stashed}.
 * Missing fields degrade safely so the tab can render without branching.
 */
export function normalizeCommitMessage(data) {
  const src = data && typeof data === "object" ? data : {};
  return {
    message: typeof src.message === "string" ? src.message : "",
    notice: typeof src.notice === "string" ? src.notice : "",
  };
}

/**
 * True when a commit-message suggestion is usable (non-empty message).
 */
export function hasCommitSuggestion(result) {
  return !!(result && result.message && result.message.trim());
}

export function isCurrentRepoRequest(currentRepo, currentGeneration, repo, generation) {
  return currentRepo === repo && currentGeneration === generation;
}

export function shouldOfferPushAfterCommit(commitSucceeded, pushAfterCommit) {
  return commitSucceeded === true && pushAfterCommit === true;
}

/**
 * Normalize a /git/stash_checkout response: the standard mutation shape plus
 * {stashed, stash_message}.
 */
export function normalizeStashCheckout(data) {
  const src = data && typeof data === "object" ? data : {};
  return {
    ...normalizeMutationResult(data),
    stashed: !!src.stashed,
    stashMessage: typeof src.stash_message === "string" ? src.stash_message : "",
  };
}
