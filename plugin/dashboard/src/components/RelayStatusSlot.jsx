// Compact "Relay" status badge for a host header slot.
//
// Registered into the dashboard's `header-right` slot via
// `window.__HERMES_PLUGINS__.registerSlot("hermes-relay", "header-right", …)`
// (see ../index.jsx). The host stacks slot components in registration order
// and re-renders the badge anywhere in the shell, not just the Relay tab.
//
// State is derived from the same loopback API the Management tab uses
// (`/api/plugins/hermes-relay/overview` → relay `/relay/info`), so the badge
// reflects exactly what the tab reports:
//   - relay unreachable (fetch throws / 502)        → "Relay · offline"  (warning)
//   - reachable, zero paired sessions               → "Relay · unpaired" (secondary)
//   - reachable, ≥1 paired session                  → "Relay · connected" (success)
//
// The host Badge (Nous DS) takes a `tone` prop — NOT a shadcn `variant`. Tones
// used here map to the design-system tokens: success=green, warning=amber,
// secondary=neutral. Resilient by construction: any fetch error is caught and
// surfaced as "offline" rather than throwing inside the header.

const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback, useRef } = SDK.hooks;

import { getOverview } from "../lib/api.js";

const { Badge } = SDK.components;

const POLL_MS = 15000;

// (label, tone) for each derived state. Tone strings are the host Badge
// contract; see web/node_modules/@nous-research/ui/.../badge.tsx.
const STATES = {
  loading: { label: "Relay · …", tone: "secondary" },
  offline: { label: "Relay · offline", tone: "warning" },
  unpaired: { label: "Relay · unpaired", tone: "secondary" },
  connected: { label: "Relay · connected", tone: "success" },
};

function deriveState(overview) {
  if (!overview || typeof overview !== "object") return "offline";
  // RelayManagement reads either field; prefer the explicit paired count.
  const paired = overview.paired_device_count ?? overview.session_count ?? 0;
  return Number(paired) > 0 ? "connected" : "unpaired";
}

export default function RelayStatusSlot() {
  const [state, setState] = useState("loading");
  const mounted = useRef(true);

  const load = useCallback(async () => {
    try {
      const ov = await getOverview();
      if (mounted.current) setState(deriveState(ov));
    } catch (_err) {
      // Relay unreachable (502 / network) — show offline, never throw.
      if (mounted.current) setState("offline");
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    load();
    const id = setInterval(load, POLL_MS);
    return () => {
      mounted.current = false;
      clearInterval(id);
    };
  }, [load]);

  const { label, tone } = STATES[state] || STATES.offline;

  return (
    <Badge
      tone={tone}
      className="whitespace-nowrap text-xs"
      title="hermes-relay status"
    >
      {label}
    </Badge>
  );
}
