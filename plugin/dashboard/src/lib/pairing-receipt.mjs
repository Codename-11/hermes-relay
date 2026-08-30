const HTTP_SCHEMES = new Set(["http:", "https:"]);
const RELAY_SCHEMES = new Set(["ws:", "wss:"]);

function cleanUrl(raw, schemes) {
  if (typeof raw !== "string" || !raw.trim()) return null;
  try {
    const url = new URL(raw.trim());
    if (!schemes.has(url.protocol) || !url.hostname) return null;
    if (url.username || url.password || url.search || url.hash) return null;
    return url.toString().replace(/\/$/, "");
  } catch (_err) {
    return null;
  }
}

function apiUrl(api) {
  if (!api || typeof api !== "object" || !api.host) return null;
  const port = Number(api.port);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  const host = String(api.host).includes(":") && !String(api.host).startsWith("[")
    ? `[${api.host}]`
    : String(api.host);
  return cleanUrl(`${api.tls ? "https" : "http"}://${host}:${port}`, HTTP_SCHEMES);
}

function routeProtection(role, surfaces) {
  const tlsCount = surfaces.filter((surface) =>
    surface.url.startsWith("https://") || surface.url.startsWith("wss://"),
  ).length;
  if (String(role).toLowerCase() === "tailscale" && tlsCount === 0) {
    return "Tailnet encrypted · no application TLS";
  }
  if (tlsCount === surfaces.length && tlsCount > 0) return "Application TLS";
  if (tlsCount > 0) return "Mixed application TLS";
  return "No application TLS";
}

function normalizeRoute(endpoint, index, globalPayload, endpointCount) {
  const role = String(endpoint && endpoint.role ? endpoint.role : "default");
  const priority = Number.isInteger(endpoint && endpoint.priority)
    ? endpoint.priority
    : index;
  const fallbackGlobal = endpointCount <= 1;
  const dashboardRaw = endpoint && endpoint.dashboard && endpoint.dashboard.url
    ? endpoint.dashboard.url
    : fallbackGlobal ? globalPayload.dashboard_url : null;
  const relayRaw = endpoint && endpoint.relay && endpoint.relay.url
    ? endpoint.relay.url
    : fallbackGlobal && globalPayload.relay ? globalPayload.relay.url : null;
  const dashboard = cleanUrl(dashboardRaw, HTTP_SCHEMES);
  const relay = cleanUrl(relayRaw, RELAY_SCHEMES);
  const api = apiUrl(endpoint && endpoint.api ? endpoint.api : fallbackGlobal ? globalPayload : null);
  const surfaces = [
    dashboard ? { surface: "dashboard", label: "Dashboard", url: dashboard } : null,
    relay ? { surface: "relay", label: "Relay", url: relay } : null,
    api ? { surface: "api", label: "API fallback", url: api } : null,
  ].filter(Boolean);
  const issues = [];

  if (dashboardRaw && !dashboard) issues.push(`${role}: invalid Dashboard URL`);
  if (relayRaw && !relay) issues.push(`${role}: invalid Relay URL`);
  if ((endpoint && endpoint.api) && !api) issues.push(`${role}: invalid API endpoint`);

  const normalizedRole = role.toLowerCase();
  if (normalizedRole === "public") {
    if (!dashboard) issues.push("public: missing Dashboard origin");
    if (!relay) issues.push("public: missing Relay route");
    if (dashboard && !dashboard.startsWith("https://")) {
      issues.push("public: Dashboard must use HTTPS");
    }
    if (relay && !relay.startsWith("wss://")) {
      issues.push("public: Relay must use WSS");
    }
    if (api && !api.startsWith("https://")) {
      issues.push("public: API fallback must use HTTPS");
    }
  } else if (normalizedRole === "public_legacy") {
    if (!relay) issues.push("public_legacy: missing Relay route");
    if (relay && !relay.startsWith("wss://")) {
      issues.push("public_legacy: Relay must use WSS");
    }
  }

  return {
    role,
    priority,
    surfaces,
    protection: routeProtection(role, surfaces),
    issues,
  };
}

/** Parse a signed pairing payload into a secret-free endpoint receipt. */
export function pairingEndpointReceipt(qrPayload) {
  if (!qrPayload) return { routes: [], blockingIssues: [] };
  try {
    const parsed = typeof qrPayload === "string" ? JSON.parse(qrPayload) : qrPayload;
    if (!parsed || typeof parsed !== "object") {
      return { routes: [], blockingIssues: ["Pairing payload is not an object"] };
    }
    const rawEndpoints = Array.isArray(parsed.endpoints) && parsed.endpoints.length > 0
      ? parsed.endpoints
      : [{
          role: "default",
          priority: 0,
          api: parsed.host && parsed.port
            ? { host: parsed.host, port: parsed.port, tls: !!parsed.tls }
            : null,
          relay: parsed.relay,
          dashboard: parsed.dashboard_url ? { url: parsed.dashboard_url } : null,
        }];
    const routes = rawEndpoints.map((endpoint, index) =>
      normalizeRoute(endpoint, index, parsed, rawEndpoints.length),
    );
    return {
      routes,
      blockingIssues: routes.flatMap((route) => route.issues),
    };
  } catch (_err) {
    return { routes: [], blockingIssues: ["Pairing payload is not valid JSON"] };
  }
}

/** Build structured surface probes accepted by the dashboard backend. */
export function pairingSurfaceProbes(receipt) {
  return (receipt && Array.isArray(receipt.routes) ? receipt.routes : []).flatMap((route) =>
    route.surfaces.map((surface) => ({
      role: route.role,
      priority: route.priority,
      surface: surface.surface,
      url: surface.url,
    })),
  );
}

export function pairingProbeKey(entry) {
  return [entry.role, entry.priority, entry.surface, entry.url].join("|");
}

/** Classify the recommended and old Dashboard Serve listeners independently. */
export function dashboardServeState(service, recommendedListenerPort = 10443) {
  const active = !!(service && service.active === true);
  const listenPorts = Array.isArray(service && service.listen_ports)
    ? [...new Set(service.listen_ports.filter((port) => Number.isInteger(port)))].sort((a, b) => a - b)
    : [];
  const recommendedPort = Number.isInteger(recommendedListenerPort) &&
    recommendedListenerPort > 0 && recommendedListenerPort <= 65535
    ? recommendedListenerPort
    : 10443;
  return {
    active,
    listenPorts,
    recommendedPort,
    recommendedActive: active && listenPorts.includes(recommendedPort),
    migration443: active && recommendedPort !== 443 && listenPorts.includes(443),
    migration9119: active && recommendedPort !== 9119 && listenPorts.includes(9119),
  };
}

/** Classify the public route field without accepting credentials or URL fragments. */
export function classifyPublicRouteInput(raw) {
  if (typeof raw !== "string" || !raw.trim()) return { kind: "empty", url: null };
  const url = cleanUrl(raw, HTTP_SCHEMES);
  if (!url) return { kind: "invalid", url: null };
  const parsed = new URL(url);
  const path = parsed.pathname.replace(/\/+$/, "");
  const explicitRelayPath = parsed.port === "8767" ||
    /\/(?:api\/plugins\/hermes-relay\/transport|relay(?:\/ws)?|ws)$/i.test(path);
  return {
    kind: explicitRelayPath ? "legacy-relay-path" : "dashboard-origin",
    url,
  };
}
