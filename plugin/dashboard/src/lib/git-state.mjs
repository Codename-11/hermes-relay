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
