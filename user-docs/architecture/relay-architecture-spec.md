<script setup>
import { withBase } from 'vitepress'
</script>

# Relay Architecture Spec

This is the compact reference for the Hermes-Relay connection model, pairing/session lifecycle, transport boundaries, and operator-owned controls.

<div class="spec-image-wrap">
  <a :href="withBase('/architecture-spec.png')" target="_blank" rel="noopener">
    <img :src="withBase('/architecture-spec.png')" alt="Hermes-Relay architecture spec diagram showing connection paths, auth gates, transport modes, session lifecycle, enforced controls, operator responsibilities, and baseline configuration." />
  </a>
</div>

<p class="spec-image-link">
  <a :href="withBase('/architecture-spec.png')" target="_blank" rel="noopener">Open the full-size spec image</a>.
</p>

## Deployment boundary

Hermes-Relay is designed for operator-owned infrastructure. Remote access should use Tailscale, another VPN, or HTTPS/WSS termination controlled by the operator. Plain `ws://` is treated as a trusted LAN/VPN mode only.

## Connection paths

| Surface | Path | Auth model | Notes |
|---|---|---|---|
| Chat | Phone/desktop → Hermes API Server | Hermes API bearer / API server auth | Uses HTTP/SSE and does not require the relay path. |
| Voice | Phone/desktop → Relay voice routes | Relay session grant or Hermes API bearer | Non-loopback API-bearer voice requests require HTTPS unless the operator enables the temporary insecure dev toggle. |
| Bridge + terminal | Phone/desktop ↔ Relay Server | Relay pairing code → session token + grants | High-trust path for terminal, media, bridge, and device-control surfaces. |
| Management routes | Host-local only | Loopback caller | Pairing registration, minting, relay security toggles, and bridge status stay loopback-only. |

## Pairing / session lifecycle

1. **Mint** — the Hermes host creates a short-lived QR/code and embeds relay/API endpoint metadata.
2. **Exchange** — the client proves possession by sending the code in its first auth envelope over the relay connection.
3. **Token** — the relay consumes the code and issues an expiring bearer token with per-channel grants.
4. **Revoke** — paired devices can be inspected, extended, limited, or revoked from app/dashboard routes.

## Implemented controls

- Pairing codes are temporary and one-shot.
- Relay sessions use scoped bearer tokens with feature-specific grants and expiries.
- Terminal, bridge, voice, media, clipboard, and profile-write paths check active grants.
- Non-loopback API-bearer voice calls require HTTPS by default.
- Pairing and management routes are loopback-only.
- Bridge/device-control actions require user-visible Android toggles and OS-level permissions before acting.

## Operational constraints

- Relay tokens are bearer credentials; protect them like API keys.
- Bridge and terminal grants are high-trust capabilities.
- Plain WebSocket is for trusted LAN/VPN only.
- Public exposure should sit behind TLS and firewall or VPN controls.
- Unknown or stale paired devices should be revoked.

<style scoped>
.spec-image-wrap {
  margin: 1.5rem 0 2rem;
  border: 1px solid var(--vp-c-border);
  border-radius: 18px;
  overflow: hidden;
  background: #020617;
}

.spec-image-wrap img {
  display: block;
  width: 100%;
  height: auto;
}
</style>
