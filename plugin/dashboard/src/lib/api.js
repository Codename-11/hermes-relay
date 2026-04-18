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
