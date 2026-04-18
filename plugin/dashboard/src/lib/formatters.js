// Pure formatting helpers — no React, no SDK deps.

/**
 * Relative time string (e.g. "just now", "12s ago", "3m ago", "2h ago").
 * Accepts ms epoch, seconds epoch, or ISO string. Returns "—" for falsy.
 */
export function relativeTime(ts) {
  if (ts === null || ts === undefined || ts === "") return "—";
  let ms;
  if (typeof ts === "number") {
    // Heuristic: values below ~1e12 are seconds, above are ms.
    ms = ts < 1e12 ? ts * 1000 : ts;
  } else {
    const parsed = Date.parse(ts);
    if (Number.isNaN(parsed)) return "—";
    ms = parsed;
  }
  const diff = Date.now() - ms;
  if (diff < 0) return "just now";
  const s = Math.floor(diff / 1000);
  if (s < 5) return "just now";
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}d ago`;
  const mo = Math.floor(d / 30);
  if (mo < 12) return `${mo}mo ago`;
  const y = Math.floor(d / 365);
  return `${y}y ago`;
}

/**
 * Human-readable byte size (IEC, base-1024).
 */
export function bytes(n) {
  if (n === null || n === undefined || Number.isNaN(n)) return "—";
  const num = Number(n);
  if (num < 1024) return `${num} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = num / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}

/**
 * TTL countdown from an epoch. Accepts ms or seconds; returns short
 * "Nm Ns" / "Nh Nm" / "expired" string.
 */
export function ttlCountdown(expiresAt, now = Date.now()) {
  if (expiresAt === null || expiresAt === undefined) return "—";
  let ms;
  if (typeof expiresAt === "number") {
    ms = expiresAt < 1e12 ? expiresAt * 1000 : expiresAt;
  } else {
    const parsed = Date.parse(expiresAt);
    if (Number.isNaN(parsed)) return "—";
    ms = parsed;
  }
  const diff = ms - now;
  if (diff <= 0) return "expired";
  const s = Math.floor(diff / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  if (m < 60) return rs ? `${m}m ${rs}s` : `${m}m`;
  const h = Math.floor(m / 60);
  const rm = m % 60;
  return rm ? `${h}h ${rm}m` : `${h}h`;
}

/**
 * Format uptime seconds as "1d 2h 3m" / "2h 3m" / "45m".
 */
export function uptime(seconds) {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return "—";
  const s = Math.max(0, Math.floor(Number(seconds)));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (d) return `${d}d ${h}h ${m}m`;
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m ${sec}s`;
  return `${sec}s`;
}

/**
 * Short token label — "abc123…" given a 64-hex token.
 */
export function shortToken(token, n = 6) {
  if (!token) return "—";
  return token.length <= n ? token : `${token.slice(0, n)}…`;
}
