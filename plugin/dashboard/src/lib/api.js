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
    body: JSON.stringify(port !== undefined ? { port } : {}),
  });
}

export function disableTailscale(port) {
  return fetchJSON("/remote-access/tailscale/disable", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(port !== undefined ? { port } : {}),
  });
}

export function getPublicUrl() {
  return fetchJSON("/remote-access/public-url");
}

export function putPublicUrl(url) {
  return fetchJSON("/remote-access/public-url", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url: url == null ? null : String(url) }),
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
 */
export function mintPairingWithMode({ mode, publicUrl, ...rest } = {}) {
  const body = { ...rest };
  if (mode) body.mode = mode;
  if (publicUrl !== undefined) body.public_url = publicUrl;
  return fetchJSON("/pairing", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}
