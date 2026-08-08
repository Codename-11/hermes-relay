/**
 * Return the canonical HTTP(S) base for a Dashboard location.
 *
 * Setup QR payloads deliberately carry only this public connection identity:
 * The active Relay tab route (`/relay`) is removed while a reverse-proxy base
 * path is preserved. Query, fragment, userinfo, cookie, and bearer material
 * never cross into the QR. The Android client owns probing and sign-in.
 */
export function canonicalDashboardOrigin(locationOrOrigin) {
  const isLocation = typeof locationOrOrigin === "object" && locationOrOrigin !== null;
  const raw = typeof locationOrOrigin === "string"
    ? locationOrOrigin
    : locationOrOrigin && (locationOrOrigin.href || locationOrOrigin.origin);

  if (!raw) return null;

  try {
    const url = new URL(raw);
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    let basePath = url.pathname.replace(/\/+$/, "");
    const hashRouted = isLocation && String(locationOrOrigin.hash || "").startsWith("#/");
    if (!hashRouted && (basePath === "/relay" || basePath.endsWith("/relay"))) {
      basePath = basePath.slice(0, -"/relay".length);
    }
    return `${url.origin}${basePath === "/" ? "" : basePath}`;
  } catch (_err) {
    return null;
  }
}

/** Build the tokenless Android setup payload for this Dashboard. */
export function buildDashboardSetupPayload(locationOrOrigin) {
  const origin = canonicalDashboardOrigin(locationOrOrigin);
  if (!origin) {
    throw new Error("This Dashboard does not have a valid HTTP(S) origin.");
  }
  return JSON.stringify({ dashboard_url: origin });
}
