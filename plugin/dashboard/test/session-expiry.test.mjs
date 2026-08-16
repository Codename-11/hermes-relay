import test from "node:test";
import assert from "node:assert/strict";

import { formatSessionExpiry } from "../src/lib/session-expiry.mjs";

const NOW = Date.parse("2026-08-11T12:00:00Z");
const format = (offsetMs) =>
  formatSessionExpiry(NOW + offsetMs, NOW, "en-US", "UTC");

test("formats short paired-session expiries without noisy seconds", () => {
  assert.equal(format(45_000).label, "Less than a minute");
  assert.equal(format(27 * 60_000).label, "27 minutes");
  assert.equal(format((5 * 60 + 20) * 60_000).label, "5 hours 20 minutes");
});

test("formats long paired-session expiries as days and weeks", () => {
  assert.equal(format(8 * 86_400_000).label, "8 days");
  assert.equal(format((18 * 24 + 3) * 3_600_000).label, "2 weeks 4 days");
  assert.equal(format(21 * 86_400_000).label, "3 weeks");
});

test("uses a calendar deadline for sessions more than eight weeks away", () => {
  const result = format(70 * 86_400_000);
  assert.match(result.label, /^Oct 20, 2026/);
  assert.equal(result.exact, null);
});

test("returns exact expiry context and explicit terminal states", () => {
  const active = format(18 * 86_400_000);
  assert.match(active.exact, /^Aug 29, 2026/);
  assert.deepEqual(
    formatSessionExpiry(null, NOW, "en-US", "UTC"),
    { label: "Never", exact: null, expired: false },
  );
  assert.equal(formatSessionExpiry(NOW - 1, NOW, "en-US", "UTC").expired, true);
  assert.equal(formatSessionExpiry("not-a-date", NOW).label, "Unknown");
});
