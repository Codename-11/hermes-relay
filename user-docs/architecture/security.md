# Security

## API Key Storage

API keys are stored using Android's `EncryptedSharedPreferences`:
- Encryption: AES-256-GCM
- Key management: Android Keystore (hardware-backed when available)
- Keys are never included in backups or exports

## Network Security

The app enforces HTTPS for all connections except localhost development:

```xml
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain>localhost</domain>
    <domain>127.0.0.1</domain>
    <domain>10.0.2.2</domain>  <!-- Emulator -->
  </domain-config>
  <base-config cleartextTrafficPermitted="false">
    <trust-anchors>
      <certificates src="system" />
    </trust-anchors>
  </base-config>
</network-security-config>
```

## Authentication

| Surface | Method |
|---------|--------|
| API Server | Bearer token (`Authorization: Bearer <key>`) |
| Relay Server | Pairing code followed by session token |

## Relay Auth Flow

1. Operator runs `hermes pair` on the Hermes host
2. `hermes pair` probes `localhost:RELAY_PORT/health`; if the relay is up, it mints a fresh 6-char code
3. It pre-registers the code with the relay via the loopback-only `POST /pairing/register` endpoint
4. The relay URL + code are embedded in the QR payload alongside the API server credentials
5. Phone scans once, opens WSS, and sends the code in its first `system/auth` envelope
6. Relay consumes the code and returns a session token
7. App stores the token in EncryptedSharedPreferences
8. Future connections use the token directly (no re-pairing)
9. Token expires after 30 days or on manual revoke

Pairing codes use the full `A-Z / 0-9` alphabet (36 chars). The earlier "no ambiguous 0/O/1/I" restriction was dropped on 2026-04-11 when the pairing flow moved from "human retypes code" to "code flows through QR + HTTP" — the phone-side generator uses the full alphabet, and enforcing the smaller alphabet silently rejected valid codes.

`POST /pairing/register` is gated to loopback callers only (`127.0.0.1` / `::1`). Only a process running on the same host as the relay can inject pairing codes — a LAN attacker cannot. Trust anchor: the operator with host shell access.

## Rate Limiting

- Failed WebSocket authentication attempts are rate-limited per IP
- After 5 failed attempts in 60 seconds, the IP is blocked for 5 minutes
- Blocked IPs receive HTTP 429

## Data Protection

- Session tokens encrypted in EncryptedSharedPreferences
- API keys never logged or included in error messages
- Backup exports exclude tokens and API keys
- DataStore preferences are app-private (standard Android sandbox)

## Bridge Security Considerations

The bridge channel (Phase 3) will give the agent control of the phone via AccessibilityService. This is powerful and inherently sensitive — treat it with the same caution as remote desktop access.

Once paired, the agent will have access to:
- Read all screen content (any app)
- Tap, type, swipe anywhere
- Open any app
- Take screenshots

There is currently no granular permission system. Only pair with trusted Hermes instances and disconnect when not in use.

## Recommendations

1. **Use HTTPS** in production — the network security config enforces it by default
2. **Rotate API keys** periodically in your Hermes server config
3. **Disconnect when idle** — especially if bridge is enabled
4. **Avoid public WiFi** for relay connections without additional encryption
5. **Keep the app updated** — security patches ship with new releases
