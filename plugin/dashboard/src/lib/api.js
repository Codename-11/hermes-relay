// Wrappers around SDK.fetchJSON for the dashboard plugin's backend routes.
//
// All routes are mounted at /api/plugins/hermes-relay/<path> by the gateway
// once plugin_api.py (D1) is wired. The dashboard plugin SDK injects the
// session-token automatically, so these helpers just hand back parsed JSON.

const SDK = typeof window !== "undefined" ? window.__HERMES_PLUGIN_SDK__ : null;

const BASE = "/api/plugins/hermes-relay";

function fetchJSON(path, opts) {
  if (!SDK || typeof SDK.fetchJSON !== "function") {
    return Promise.reject(new Error("Hermes plugin SDK unavailable"));
  }
  return SDK.fetchJSON(`${BASE}${path}`, opts);
}

function fetchHostJSON(path, opts) {
  if (!SDK || typeof SDK.fetchJSON !== "function") {
    return Promise.reject(new Error("Hermes plugin SDK unavailable"));
  }
  return SDK.fetchJSON(path, opts);
}

export function getOverview() {
  return fetchJSON("/overview");
}

export function getSessions() {
  return fetchJSON("/sessions");
}

export function getBridgeActivity(limit = 100) {
  const q = Number.isFinite(limit) ? `?limit=${limit}` : "";
  return fetchJSON(`/bridge-activity${q}`);
}

export function getMedia({ includeExpired = false } = {}) {
  const q = includeExpired ? "?include_expired=true" : "";
  return fetchJSON(`/media${q}`);
}

export function getPush() {
  return fetchJSON("/push");
}

export function getAgentContext() {
  return fetchJSON("/agent-context");
}

export function getPhoneConfig() {
  return fetchJSON("/phone/config");
}

export function getUpdateCheck({ refresh = false } = {}) {
  return fetchJSON(`/update-check${refresh ? "?refresh=true" : ""}`);
}

export function putEnvSetting(key, value) {
  return fetchHostJSON("/api/env", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ key, value: String(value) }),
  });
}

export function mintPairing(body = {}) {
  return fetchJSON("/pairing", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export function revokeSession(tokenPrefix) {
  return fetchJSON(`/sessions/${encodeURIComponent(tokenPrefix)}`, {
    method: "DELETE",
  });
}

// ── Remote Access tab ──────────────────────────────────────────────────────

export function getRemoteAccessStatus() {
  return fetchJSON("/remote-access/status");
}

export function enableTailscale(port) {
  return fetchJSON("/remote-access/tailscale/enable", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(port !== undefined ? { port } : { stack: true }),
  });
}

export function disableTailscale(port) {
  return fetchJSON("/remote-access/tailscale/disable", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(port !== undefined ? { port } : { stack: true }),
  });
}

export function getPublicUrl() {
  return fetchJSON("/remote-access/public-url");
}

export function putPublicUrl(url, { legacyDirectRelay = false } = {}) {
  return fetchJSON("/remote-access/public-url", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      url: url == null ? null : String(url),
      legacy_direct_relay: !!legacyDirectRelay,
    }),
  });
}

export function probeEndpoints(candidates) {
  return fetchJSON("/remote-access/probe", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ candidates: Array.isArray(candidates) ? candidates : [] }),
  });
}

/**
 * Mint a pairing QR with a specific endpoint mode (ADR 24).
 *
 * ``mode`` is one of ``auto`` / ``lan`` / ``tailscale`` / ``public``.
 * ``publicUrl`` is optional except when ``mode === 'public'``.
 * ``prefer`` (optional, open-vocab role string) promotes the named
 * role to priority 0. Empty/null/undefined → no priority override.
 */
export function mintPairingWithMode({ mode, publicUrl, prefer, ...rest } = {}) {
  const body = { ...rest };
  if (mode) body.mode = mode;
  if (publicUrl !== undefined) body.public_url = publicUrl;
  if (prefer) body.prefer = prefer;
  return fetchJSON("/pairing", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

// ── Git State tab ───────────────────────────────────────────────────────────

export function getGitRepos() {
  return fetchJSON("/git/repos");
}

export function getGitStatus(repo) {
  return fetchJSON(`/git/status?repo=${encodeURIComponent(repo)}`);
}

export function getGitBranches(repo) {
  return fetchJSON(`/git/branches?repo=${encodeURIComponent(repo)}`);
}

export function getGitDiff(repo, path, kind = "unstaged") {
  return fetchJSON(
    `/git/diff?repo=${encodeURIComponent(repo)}&path=${encodeURIComponent(path)}&kind=${encodeURIComponent(kind)}`,
  );
}

export function getGitFile(repo, path) {
  return fetchJSON(
    `/git/file?repo=${encodeURIComponent(repo)}&path=${encodeURIComponent(path)}`,
  );
}

// ── Git State write operations ────────────────────────────────────────────
// Every write POST goes through the authenticated Dashboard plugin namespace.
// Android separately enforces its local plugin.api.write preference before it
// calls this namespace. Destructive ops pass a per-use confirmation token.

function postGit(path, body) {
  return fetchJSON(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export function gitStage(repo, paths) {
  return postGit("/git/stage", { repo, paths });
}

export function gitUnstage(repo, paths) {
  return postGit("/git/unstage", { repo, paths });
}

export function gitDiscard(repo, paths, confirmation, deleteUntracked = false) {
  return postGit("/git/discard", {
    repo,
    paths,
    confirmation,
    delete_untracked: deleteUntracked,
  });
}

export function gitCommit(repo, message) {
  return postGit("/git/commit", { repo, message });
}

export function gitFetch(repo, remote = "origin") {
  return postGit("/git/fetch", { repo, remote });
}

export function gitPull(repo, remote = "origin", branch = "") {
  return postGit("/git/pull", { repo, remote, branch });
}

export function gitPush(repo, confirmation, remote = "origin", branch = "") {
  return postGit("/git/push", { repo, remote, branch, confirmation });
}

export function gitCheckout(repo, ref, opts = {}) {
  const body = {
    repo,
    ref,
    confirmation: opts.confirmation,
    new_branch: opts.newBranch || "",
    track: !!opts.track,
  };
  return postGit("/git/checkout", body);
}

// ── Git State Phase 3 extras ───────────────────────────────────────────────
// AI commit-message suggestions + auto-stashing checkout.

export function gitCommitMessage(repo) {
  return postGit("/git/commit_message", { repo });
}

export function gitCommitMessageSelected(repo, paths) {
  return postGit("/git/commit_message_selected", { repo, paths });
}

export function gitStashCheckout(repo, ref, opts = {}) {
  const body = {
    repo,
    ref,
    new_branch: opts.newBranch || "",
    track: !!opts.track,
  };
  return postGit("/git/stash_checkout", body);
}
