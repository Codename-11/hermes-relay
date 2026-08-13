# Hermes Secure Link Security Contract

**Hermes Secure Link** is the product name for the Relay plugin's optional
pinned-TLS ingress. It presents one exact HTTPS origin for Android and desktop
Relay, API-server, and Dashboard traffic. Internal configuration and wire
compatibility still use `secure_proxy` and `plugin_proxy` identifiers.

Secure Link verifies continuity with the operator-paired endpoint and protects
transport after a client can reach it. It does not independently prove the
identity of the physical Hermes host, provide DNS or discovery, traverse NAT,
change firewall rules, or act as a hosted rendezvous service. LAN routing,
Tailscale or another VPN, port forwarding, or an operator-managed public route
must make the listener reachable.

Secure Link is not an arbitrary reverse proxy and does not replace any
service's authentication or authorization.

## Trust boundaries

- The operator-reviewed pairing QR is the first-pair trust ceremony. It must
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
- The listener has exactly three fixed namespaces under one pinned origin: `/relay`, `/api`, and
  `/dashboard`. `/relay` contains only health and the authenticated WebSocket;
  `/api/*` maps only to the loopback API server; `/dashboard/*` maps only to
  the loopback Dashboard. No client-controlled upstream is accepted.
- API requests retain the API server bearer and never receive Relay or
  Dashboard credentials. Dashboard requests retain their cookie/native
  bearer and never receive Relay or API credentials.
- Because every namespace shares one external origin, credential isolation is
  enforced by the proxy rather than left to caller discipline. API requests
  never forward `Cookie`; Dashboard cookies are rewritten to a
  `/dashboard`-scoped path; Relay session/internal headers are stripped from
  both HTTP namespaces. Redirects are returned to the client and never
  followed with credentials by the proxy.
- `CONNECT` and `TRACE` are rejected. HTTP body encoding is preserved without
  transparent proxy-side decompression. Same-upstream Dashboard redirects are
  rewritten beneath `/dashboard`; loopback locations are never exposed to the
  client, while an explicitly allowed HTTPS identity-provider redirect may
  leave the Secure Link origin without carrying service credentials.
- The Dashboard namespace fails closed unless `/api/health` confirms the
  upstream OAuth/password gate is active. Loopback-token mode is never exposed
  because its HTML embeds a local operator token.
- `/relay/ws` forwards only to the configured loopback Relay WebSocket. The
  Relay still requires its normal first-frame pairing/session authentication,
  enforces expiry and grants, rate-limits failures, and binds the resulting
  connection to that authenticated session.
- Client-controlled hosts, origins, absolute URLs, proxy headers, redirects,
  encoded separators, and path traversal can never select an upstream.
- The external `Host` header is validated against the configured Secure Link
  authority and is never copied into trusted forwarding metadata. Health and
  Dashboard-auth availability probes are cached and coalesced so public traffic
  cannot amplify into an unbounded number of loopback requests.
- HTTP request and response bodies are bounded, and upstream connect/read/total
  timeouts are finite. Long-lived traffic uses the separately bounded WebSocket
  path rather than an unlimited HTTP proxy request.
- Secure Link failing to initialize must not silently advertise a
  candidate. It must not make the ordinary Relay unavailable unless the
  operator explicitly configured strict startup behavior.

## Client invariants

- A proxy candidate is usable only with an HTTPS URL, a valid 32-byte SPKI
  SHA-256 pin, no user info/query/fragment, and a normalized safe base path.
- The client accepts either a system-trusted chain or the paired self-signed
  leaf for trust-manager validation, but the advertised SPKI pin is required
  in both cases. Normal hostname verification remains enabled.
- The pin and each service credential are scoped to the exact host and port.
  Redirects or retries outside that authority fail before credentials are sent.
- A declared Secure Link route fails closed if its pinned client cannot be
  built; it must not fall back to a generic TLS or TOFU client.
- UI security labels derive from the validated proxy contract, not from a
  caller-controlled role name or transport hint.
- UI labels describe the route actually selected for each service. Merely
  advertising Hermes Secure Link must not mark a LAN fallback as encrypted,
  and partial service coverage must roll up as mixed when another active
  service still uses plaintext.
- The pairing-provided pin proves continuity with the operator-reviewed
  endpoint. Product copy must not claim independent Hermes-host identity,
  public-CA identity, or successful service availability from advertisement
  alone.

## Release acceptance tests

- QR payloads from every pairing surface contain the proxy authority and pin,
  preserve other LAN/Tailscale/public candidates, and sign the final ordered
  candidate list.
- Missing, malformed, changed, and wrong-authority pins fail before WebSocket
  authentication; explicit re-pair is the only reset path.
- The certificate SAN matches the advertised DNS name, IPv4 address, or IPv6
  address; key and containing-directory permissions are restrictive.
- Every unrelated Relay/operator route returns 404/405, encoded traversal is
  rejected, API bearer forwarding is isolated, and Dashboard forwarding
  returns 503 whenever the upstream auth gate is disabled.
- API requests cannot receive Dashboard cookies; Dashboard `Set-Cookie`
  responses are scoped to `/dashboard`; credentials are never replayed after
  a cross-authority redirect.
- `CONNECT`/`TRACE` fail closed, compressed responses retain consistent
  encoding metadata, and Dashboard login/callback redirects remain inside the
  `/dashboard` namespace unless they target an HTTPS identity provider.
- A hostile `Host` value is rejected or replaced with the configured authority;
  concurrent health and Dashboard-gate requests cause at most one loopback
  availability refresh per cache interval; slow or oversized HTTP bodies fail
  within the documented limits.
- A partial candidate such as `surfaces=["relay"]` with plaintext LAN API and
  Dashboard fallbacks renders a mixed posture, not a fully encrypted one.
- A real secure WebSocket integration test proves invalid pairing/session auth
  is rejected, a valid session connects, expiry/grants still apply, and Relay
  responses cannot be confused across devices.
- TLS/pin failure never selects a plain route without its existing explicit
  acknowledgement; secure alternatives remain independently selectable.
