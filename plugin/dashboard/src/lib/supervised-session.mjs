const MAX_PROFILE_LABEL_LENGTH = 80;
const MAX_CAPABILITY_LENGTH = 32;
const MAX_CAPABILITIES = 12;
const MAX_VISIBLE_CAPABILITIES = 4;

const CAPABILITY_LABELS = {
  text_chat: "Text chat",
  attachments: "Attachments",
  voice: "Voice",
  generated_images: "Generated images",
  new_chat: "New chat",
  cancel: "Cancel",
  steer: "Steer",
  share_images: "Share images",
  copy: "Copy",
  retry: "Retry",
  quote_reply: "Quote & reply",
  timestamps: "Timestamps",
};

function boundedText(value, maxLength) {
  if (typeof value !== "string") return null;
  const normalized = value.replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim();
  if (!normalized) return null;
  return normalized.slice(0, maxLength);
}

function capabilityLabel(value) {
  const normalized = boundedText(value, MAX_CAPABILITY_LENGTH);
  if (!normalized) return null;
  const key = normalized.toLowerCase();
  return CAPABILITY_LABELS[key] || null;
}

/**
 * Normalize optional, client-reported supervised-mode metadata for display.
 * This deliberately requires active === true and never treats the report as a
 * Relay authorization policy.
 */
export function supervisedSessionDisplay(session) {
  const raw = session && session.supervised_mode;
  if (
    !raw ||
    typeof raw !== "object" ||
    Array.isArray(raw) ||
    raw.active !== true ||
    raw.enforcement_owner !== "android_client"
  ) {
    return null;
  }

  const profileLabel = boundedText(raw.profile_label, MAX_PROFILE_LABEL_LENGTH);
  const source = Array.isArray(raw.capabilities) ? raw.capabilities : [];
  const capabilities = [];
  const seen = new Set();
  for (const entry of source.slice(0, MAX_CAPABILITIES)) {
    const label = capabilityLabel(entry);
    if (!label) continue;
    const key = label.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    capabilities.push(label);
  }

  const visibleCapabilities = capabilities.slice(0, MAX_VISIBLE_CAPABILITIES);
  const remainingCapabilityCount = Math.max(0, capabilities.length - visibleCapabilities.length);

  return {
    profileLabel,
    visibleCapabilities,
    remainingCapabilityCount,
  };
}
