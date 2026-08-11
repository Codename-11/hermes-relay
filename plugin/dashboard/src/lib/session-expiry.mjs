function epochMilliseconds(value) {
  if (typeof value === "number") {
    return value < 1e12 ? value * 1000 : value;
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? null : parsed;
}

function unit(value, singular, plural = `${singular}s`) {
  return `${value} ${value === 1 ? singular : plural}`;
}

/**
 * Format a paired session's expiry for people, rather than as an unbounded
 * hours-and-minutes counter. The exact local deadline is returned separately
 * so the UI can retain precision without making the primary label noisy.
 */
export function formatSessionExpiry(
  expiresAt,
  now = Date.now(),
  locale = undefined,
  timeZone = undefined,
) {
  if (expiresAt === null || expiresAt === undefined || expiresAt === "") {
    return { label: "Never", exact: null, expired: false };
  }

  const expiryMs = epochMilliseconds(expiresAt);
  if (expiryMs === null) {
    return { label: "Unknown", exact: null, expired: false };
  }

  const exact = new Intl.DateTimeFormat(locale, {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
    ...(timeZone ? { timeZone } : {}),
  }).format(expiryMs);
  const remainingMs = expiryMs - now;

  if (remainingMs <= 0) {
    return { label: "Expired", exact, expired: true };
  }

  const totalMinutes = Math.floor(remainingMs / 60_000);
  if (totalMinutes < 1) {
    return { label: "Less than a minute", exact, expired: false };
  }
  if (totalMinutes < 60) {
    return { label: unit(totalMinutes, "minute"), exact, expired: false };
  }

  const totalHours = Math.floor(totalMinutes / 60);
  if (totalHours < 48) {
    const minutes = totalMinutes % 60;
    const label = minutes
      ? `${unit(totalHours, "hour")} ${unit(minutes, "minute")}`
      : unit(totalHours, "hour");
    return { label, exact, expired: false };
  }

  const totalDays = Math.floor(totalHours / 24);
  if (totalDays < 14) {
    return { label: unit(totalDays, "day"), exact, expired: false };
  }
  if (totalDays < 56) {
    const weeks = Math.floor(totalDays / 7);
    const days = totalDays % 7;
    const label = days
      ? `${unit(weeks, "week")} ${unit(days, "day")}`
      : unit(weeks, "week");
    return { label, exact, expired: false };
  }

  return { label: exact, exact: null, expired: false };
}
