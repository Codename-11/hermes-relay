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

## Guided setup control surface

`GET /secure-link/preflight` is a loopback-only, read-only Relay operator route.
The authenticated Dashboard/Desktop plugin forwards it through
`GET /remote-access/secure-link/preflight`; `hermes relay secure-link` consumes
the same report from the running Relay. Public `/relay/*` ingress never exposes
this operator endpoint. Client input selects only the proposed listener address
and port; it cannot select a probe upstream or certificate/key file.

The report checks a usable bind address, port availability, existing certificate
identity, fixed loopback upstreams, Dashboard authentication, and restart impact.
It does not mutate configuration, generate/rotate secrets, or manage services.
Instructions preserve the existing service owner and require an explicit operator
restart. Re-checking an active matching origin enables the pairing handoff; a
proposed address alone never enables it. Report readiness is not client sign-in,
network reachability from another device, or Gateway Chat readiness.

Pairing previews derive declared namespaces from validated proxy advertisements
without displaying their certificate/pin or treating ordinary system-TLS probe
failure as proof that the paired route is broken. Signed QR import remains the
client trust ceremony. Unsupported/older Relay setup endpoints fail visibly and
never fall back to browser-side configuration writes.

## Trust boundaries

- The operator-reviewed pairing QR is the first-pair trust ceremony. It must
  contain the proxy HTTPS authority, public leaf certificate (`cert_der`), and
  SPKI SHA-256 pin before Android or the desktop CLI makes a proxy request.
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
- Relay-native `/voice/*` HTTP routes and management/session HTTP routes are
  not exposed beneath `/relay`. A pinned client alone does not enable them.
  Standard voice uses the independently authenticated Dashboard namespace;
  native Relay voice requires a separately supported ingress.
- Client-controlled hosts, origins, absolute URLs, proxy headers, redirects,
  encoded separators, and path traversal can never select an upstream.
- The external `Host` header is validated against the configured Secure Link
  authority and is never copied into trusted forwarding metadata. Health and
  Dashboard-auth availability probes are cached and coalesced so public traffic
  cannot amplify into an unbounded number of loopback requests.
- HTTP request and response bodies are bounded, and upstream connect/read/total
  timeouts are finite. Long-lived traffic uses the separately bounded WebSocket
  path rather than an unlimited HTTP proxy request.
- Dashboard login HTML and JSON landing paths are rewritten under `/dashboard`.
  Rewrites request identity encoding and enforce the response limit while reading,
  including chunked responses. An upstream that ignores the identity request and
  sends compressed HTML/JSON receives a 502; compressed bytes are never returned
  with their encoding header removed.
- Gateway query tickets and ticket subprotocols survive the WebSocket proxy.
  Upstream authenticates before the outer upgrade succeeds. Only the selected
  public protocol is returned; ticket-bearing protocols are never reflected.
  The upstream leg disables compression independently of the downstream leg.
- Public Relay health reads version and counters from the same server instance;
  it does not make a second loopback Relay health request.
- Secure Link failing to initialize must not silently advertise a
  candidate. It must not make the ordinary Relay unavailable unless the
  operator explicitly configured strict startup behavior.

## Client invariants

- A proxy candidate is usable only with an HTTPS URL, a valid 32-byte SPKI
  SHA-256 pin, no user info/query/fragment, and a normalized safe base path.
- The client accepts either a system-trusted chain or the exact paired
  self-signed leaf from `cert_der` for trust-manager validation, but the
  advertised SPKI pin is required in both cases. Normal hostname verification
  remains enabled; clients never disable certificate validation to learn a pin.
- The pin and each service credential are scoped to the exact host and port.
  Redirects or retries outside that authority fail before credentials are sent.
- Android enforces the paired leaf SPKI inside its trust manager even when system
  trust succeeds. HTTP and WebSocket requests use the same HTTPS authority guard;
  automatic redirects are disabled. Gateway route changes replace the ticket and
  socket transport together, discarding a ticket minted for a superseded route.
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

- QR payloads from every pairing surface contain the proxy authority, public
  leaf certificate, and pin, preserve other LAN/Tailscale/public candidates,
  and sign the final ordered candidate list.
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
