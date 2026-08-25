import test from "node:test";
import assert from "node:assert/strict";

import { supervisedSessionDisplay } from "../src/lib/supervised-session.mjs";

test("leaves legacy and inactive sessions unchanged", () => {
  assert.equal(supervisedSessionDisplay({ device_name: "Pixel" }), null);
  assert.equal(supervisedSessionDisplay({ supervised_mode: { active: false } }), null);
  assert.equal(supervisedSessionDisplay({ supervised_mode: { active: "true" } }), null);
  assert.equal(
    supervisedSessionDisplay({ supervised_mode: { active: true, enforcement_owner: "relay" } }),
    null,
  );
  assert.equal(supervisedSessionDisplay({ supervised_mode: { active: true } }), null);
  assert.equal(supervisedSessionDisplay({ supervised_mode: [] }), null);
});

test("formats active client-reported metadata", () => {
  assert.deepEqual(
    supervisedSessionDisplay({
      supervised_mode: {
        active: true,
        profile_label: "  Willow  ",
        capabilities: ["attachments", "voice", "generated_images", "new_chat"],
        enforcement_owner: "android_client",
      },
    }),
    {
      profileLabel: "Willow",
      visibleCapabilities: ["Attachments", "Voice", "Generated images", "New chat"],
      remainingCapabilityCount: 0,
    },
  );
});

test("bounds, sanitizes, and deduplicates untrusted display values", () => {
  const result = supervisedSessionDisplay({
    supervised_mode: {
      active: true,
      enforcement_owner: "android_client",
      profile_label: `Willow\u0000 ${"x".repeat(100)}`,
      capabilities: [
        "voice",
        "VOICE",
        "unknown_capability",
        "text_chat",
        "generated_images",
        "cancel",
        "steer",
        "attachments",
        "new_chat",
        "share_images",
        "copy",
        "retry",
        "quote_reply",
        "timestamps",
      ],
    },
  });

  assert.equal(result.profileLabel.length, 80);
  assert.deepEqual(
    result.visibleCapabilities,
    ["Voice", "Text chat", "Generated images", "Cancel"],
  );
  assert.equal(result.remainingCapabilityCount, 6);
});

test("tolerates malformed optional members", () => {
  assert.deepEqual(
    supervisedSessionDisplay({
      supervised_mode: {
        active: true,
        enforcement_owner: "android_client",
        profile_label: 42,
        capabilities: "voice",
      },
    }),
    { profileLabel: null, visibleCapabilities: [], remainingCapabilityCount: 0 },
  );
});
