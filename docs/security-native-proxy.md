# Native Secure Proxy Security Contract

The plugin-owned secure proxy is a pinned-TLS transport for the Relay
WebSocket. It is not a general reverse proxy and does not replace Relay
authentication, session expiry, or channel grants.

## Trust boundaries

- The operator-displayed pairing QR is the first-pair trust ceremony. It must
  contain the proxy HTTPS authority and SPKI SHA-256 pin before Android or the
  desktop CLI makes a proxy request.
- `auth.ok`, health responses, redirects, or a previously untrusted network
  connection must never introduce or replace stored proxy trust. They may only
  confirm an exact authority-and-pin match already imported from pairing.
- Certificate rotation requires an explicit re-pair or operator-approved trust
  reset. A TLS or pin failure must never silently downgrade the same route to
  HTTP/WS. Fallback is allowed only to another independently configured route;
  plain routes retain their existing explicit acknowledgement gate.

## Server invariants

- The TLS private key is created atomically with owner-only permissions. The
  certificate SAN matches the advertised hostname or IP. Changing the
  advertised authority requires deliberate certificate/pin rotation.
- The network listener exposes only `GET`/`HEAD /relay/health` and WebSocket
  `GET /relay/ws`. It must not expose API, Dashboard, desktop, bridge, session,
  media-inspection, security-management, pairing, or other loopback-trusted
  Relay HTTP routes.
- `/relay/ws` forwards only to the configured loopback Relay WebSocket. The
  Relay still requires its normal first-frame pairing/session authentication,
  enforces expiry and grants, rate-limits failures, and binds the resulting
  connection to that authenticated session.
- Client-controlled hosts, origins, absolute URLs, proxy headers, redirects,
  and path traversal can never select an upstream. `CONNECT` and arbitrary
  HTTP methods are absent.
- The secure-proxy feature failing to initialize must not silently advertise a
  candidate. It must not make the ordinary Relay unavailable unless the
  operator explicitly configured strict startup behavior.

## Client invariants

- A proxy candidate is usable only with an HTTPS URL, a valid 32-byte SPKI
  SHA-256 pin, no user info/query/fragment, and a normalized safe base path.
- The client accepts either a system-trusted chain or the paired self-signed
  leaf for trust-manager validation, but the advertised SPKI pin is required
  in both cases. Normal hostname verification remains enabled.
- The pin and any Relay credential are scoped to the exact host and port.
  Redirects or retries outside that authority fail before credentials are sent.
- A declared plugin-proxy route fails closed if its pinned client cannot be
  built; it must not fall back to a generic TLS or TOFU client.
- UI security labels derive from the validated proxy contract, not from a
  caller-controlled role name or transport hint.

## Release acceptance tests

- QR payloads from every pairing surface contain the proxy authority and pin,
  preserve other LAN/Tailscale/public candidates, and sign the final ordered
  candidate list.
- Missing, malformed, changed, and wrong-authority pins fail before WebSocket
  authentication; explicit re-pair is the only reset path.
- The certificate SAN matches the advertised DNS name, IPv4 address, or IPv6
  address; key and containing-directory permissions are restrictive.
- Every loopback-trusted or unrelated route returns 404/405 on the secure
  listener, including encoded traversal variants.
- A real secure WebSocket integration test proves invalid pairing/session auth
  is rejected, a valid session connects, expiry/grants still apply, and Relay
  responses cannot be confused across devices.
- TLS/pin failure never selects a plain route without its existing explicit
  acknowledgement; secure alternatives remain independently selectable.

