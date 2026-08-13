# Hermes Reach Transport Security Contract

**Status:** Experimental implementation contract. Reach is disabled by default,
is never the recommended route, and may change incompatibly before release.

**Hermes Reach** provides reachability when neither endpoint can accept an
inbound connection. The Hermes host and client both make outbound WSS
connections to a rendezvous broker. The broker matches an explicitly selected
host and carries an **inner Hermes Secure Link TLS 1.3 byte stream**. The inner
TLS connection terminates only in the client and the host's existing Secure
Link listener. The host connector and broker do not terminate it.

This deliberately reuses the operator-reviewed Secure Link authority, X.509
certificate, SPKI pin, service namespaces, and service authentication. It does
not introduce a second application encryption protocol.

## Security claims and non-claims

The broker can observe account and host identifiers, source addresses,
connection time, stream lifetime, direction, and byte counts. It can delay,
drop, replay, reorder, throttle, or route ciphertext to the wrong host. It
cannot read or modify a successfully established inner TLS stream without the
client rejecting the QR-pinned Secure Link identity.

The design does not hide metadata, make a compromised client safe, prevent
denial of service, or make an untrusted broker honestly enforce revocation or
quotas. Local Relay/API/Dashboard authentication remains the authority for
Hermes access even if the broker is malicious.

## Trust and credential boundaries

- The operator-reviewed pairing QR is the trust bootstrap. A broker candidate
  carries the broker WSS URL, opaque `host_id`, short-lived one-time
  `bootstrap_token`, inner Secure Link authority and SPKI SHA-256 pin, and the
  exact declared service surfaces. The signed pairing payload binds these
  fields together.

The canonical v3 candidate is:

```json
{
  "role": "outbound_broker",
  "priority": 4,
  "recommended": false,
  "experimental": true,
  "security": "e2ee_pinned_tls",
  "broker": {
    "url": "wss://broker.example/v1/connect",
    "host_id": "base64url-128-bit",
    "credential_kind": "bootstrap",
    "token": "base64url-256-bit-secret",
    "expires_at": 1780000000
  },
  "proxy": {
    "url": "https://paired-host.example:9443",
    "transport_hint": "https",
    "pin_sha256": "sha256/base64-spki-digest",
    "cert_der": "base64-der-leaf-certificate",
    "surfaces": ["relay", "api", "dashboard"],
    "services": {}
  }
}
```

`host_id` and `connection_id` are canonical unpadded base64url encodings of
16 random bytes (22 characters). Route/bootstrap tokens are canonical unpadded
base64url encodings of 32 random bytes (43 characters). `expires_at` is numeric
Unix epoch seconds. Parsers reject non-canonical forms and expired bootstrap
credentials before opening the broker socket.
New producers emit only `role: "outbound_broker"`,
`security: "e2ee_pinned_tls"`, and numeric expiry. Parsers may temporarily
accept the pre-contract `role: "broker"` and RFC 3339 expiry emitted by
development builds, but normalize them internally and never re-emit them.
`plugin_proxy` / `pinned_tls` identify direct Secure Link and must not be used
for Hermes Reach because they would erase the active route's security boundary.
- `host_id` and `connection_id` are locators, never authenticators.
- `host_registration_token` authenticates only the outbound host connector.
  It never appears in a QR or client process.
- `bootstrap_token` grants only a bounded attempt to reach the named host. It
  is random, single-use, expires with the pairing invitation, and is consumed
  atomically. A durable `client_route_token` may be issued after successful
  pairing and is scoped to one host and client session/device.
- Broker credentials are never accepted by Relay, API, or Dashboard. Relay
  pairing/session credentials, API bearer keys, Dashboard cookies, commands,
  files, and media exist only inside inner TLS.
- Credentials never appear in URLs, query strings, close reasons, metrics, or
  normal logs. Stored broker tokens are hashed or protected by the platform
  secret store; comparisons are constant-time.

## Wire protocol v1

Outer transport is system-trusted `wss://` to `/v1/connect`. Registration is a
single JSON text frame within 10 seconds:

```json
{
  "type": "register",
  "protocol_version": 1,
  "role": "host|client",
  "host_id": "base64url-128-bit",
  "connection_id": "base64url-128-bit",
  "credential_kind": "host|bootstrap|route",
  "token": "opaque-role-specific-token"
}
```

The canonical endpoint is `/v1/connect`; `/v1/tunnel` is not a v1 alias. A
host must use `credential_kind: "host"`, while a client uses `bootstrap` or
`route`. Host registration succeeds with
`{"type":"registered","protocol_version":1}`.

The authenticated host control channel registers QR bootstrap credentials with
`{"type":"publish_bootstrap","pairing_id", "token_sha256", "expires_at",
"max_uses":1}`. The broker stores only the digest and acknowledges with
`{"type":"published","credential_kind":"bootstrap","pairing_id"}`. A
matched client receives `{"type":"matched","protocol_version":1,
"stream_id"}` and the host receives `{"type":"open","protocol_version":1,
"stream_id","host_connection_id"}`. `host_connection_id` must equal the
current host registration's `connection_id`, preventing delayed control from a
retired host socket epoch from opening a stream.

The broker rejects unknown fields that would change routing or security,
unsupported versions, duplicate live `connection_id` values, invalid role/token
combinations, and attempts to reuse consumed bootstrap credentials. Responses
are `{"type":"error","protocol_version":1,"code":"..."}` and use stable
codes such as `unsupported_version`, `unauthorized`, `expired`,
`replayed`, `host_offline`, `host_busy`, `quota_exceeded`, and `revoked`; they
do not disclose whether an unrelated host or token exists.

The host connection remains multiplexed so one host supports simultaneous
clients. Host-bound binary frames use a broker-visible fixed routing header:

```text
version:u8 | flags:u8 (DATA|CLOSE) | stream_id:16 bytes | opaque inner-TLS bytes
```

JSON `open`, binary `DATA`, and exactly-once `CLOSE` are scoped to the
authenticated host socket. The broker generates unpredictable stream IDs.
`DATA` before `open`,
unknown/closed IDs, duplicate `OPEN`, and cross-host IDs are rejected. A client
data socket is bound to exactly one stream and carries only the opaque bytes;
the broker adds or removes the host-side routing header. Text frames are invalid
after client matching. An implementation may instead open one separately
authenticated outbound host data socket per client, but it must not use an
unframed single host socket for multiple clients.

After matching, the client starts inner TLS 1.3 with SNI and hostname validation
for the QR authority plus the mandatory QR SPKI pin. The host connector forwards
the bytes unchanged to its loopback Secure Link listener. Only after this inner
handshake succeeds may the client send HTTP, WebSocket upgrades, or any Hermes
credential.

## Replay, downgrade, targeting, and rotation

- TLS nonces/transcript integrity make replayed or reordered encrypted records
  fail. Broker registration `connection_id` values are fresh per attempt and
  retained in a bounded replay cache for at least the credential lifetime.
- A broker match to the wrong host fails inner hostname/SPKI validation. The
  UI and RPC layer also retain the paired Hermes `device_id`; a response from a
  different device cannot satisfy a targeted request.
- Broker failure, invalid outer TLS, inner TLS/pin failure, or protocol mismatch
  never silently selects HTTP/WS. Other independently configured routes may be
  tried only under the existing route policy and plaintext acknowledgement.
- Rotating the broker host credential permits a short explicit overlap, then
  closes connections authenticated only by the retired credential. Rotating a
  client route credential invalidates the old credential. Rotating the Secure
  Link certificate remains an explicit re-pair because it changes end-to-end
  trust. The broker cannot authorize that rotation.
- Revoking a paired Hermes session immediately terminates its local service
  authorization and asks the broker to revoke its route credential and close
  associated streams. Security does not depend on a broker honoring the close.

After successful inner-TLS Relay authentication, the host creates a new random
route token and publishes only its digest using `{"type":"publish_route",
"credential_id","token_sha256","expires_at","device_id_hash"}`. The raw
token is returned to that client only inside the encrypted Relay `auth.ok` as:

```json
{
  "route_credential": {
    "kind": "broker_route",
    "broker_url": "wss://broker.example/v1/connect",
    "host_id": "base64url-128-bit",
    "credential_id": "opaque-bounded-id",
    "token": "base64url-256-bit-secret",
    "expires_at": 1780000000
  }
}
```

The client accepts this field only on the successfully pin-verified inner
connection and only when broker authority and host ID exactly match the active
QR candidate. It ignores or rejects the field on direct routes or a scope
mismatch. The token is persisted against that exact broker authority, host ID,
and Hermes session/device and is never rendered in status or diagnostics.
The Relay server emits this raw credential only when the request arrived via
its authenticated loopback Secure Link proxy hop. A successful direct LAN
`ws://` authentication must never receive it, and a network peer cannot opt in
by forging the internal proxy header.
This handoff is required for reconnect support and is covered by the broker
acceptance tests. A static or host-wide client token is not an acceptable
substitute. Even with durable credentials, Reach remains experimental and
lower priority than supported routes.

## Resource and abuse limits

- Maximum outer binary frame: 1 MiB. Maximum queued data: both 32 frames and
  8 MiB per stream, whichever is reached first.
- Default maximum: 16 client streams per host, plus broker-wide, account, host,
  token, and source-address connection/rate limits.
- Fair per-stream scheduling prevents one transfer from starving approvals or
  control traffic. Aggregate host queue limits prevent 16 individually valid
  streams from exhausting memory.
- Registration timeout is 10 seconds. No-byte stream idle timeout is 5 minutes;
  active long-running transfers are not considered idle. Ping/pong alone does
  not indefinitely preserve an unauthenticated or payload-idle stream.
- Backpressure pauses the producing socket. Overflow closes only the offending
  stream with a bounded generic error, rather than buffering without limit.

## Required release gates

1. A broker capture contains routing metadata and ciphertext but no Hermes URL
   path, headers, cookies, bearer, pairing code, command, output, or file bytes.
2. Wrong host, wrong SPKI pin, wrong hostname, changed certificate, and a
   certificate swap during connection all fail before a Hermes credential is
   sent.
3. Replayed bootstrap registration, duplicate `connection_id`, cross-host
   `stream_id`, DATA-before-OPEN, DATA-after-CLOSE, text-after-match, oversized
   frames, queue overflow, and unsupported versions fail deterministically.
4. Two clients concurrently use different streams to the same host without
   cross-delivery, head-of-line starvation, or one response satisfying the
   other's device-targeted request.
5. Revoking one client closes and rejects only that client's streams; rotating
   host credentials preserves only the documented overlap; Secure Link pin
   rotation requires re-pairing.
6. Broker outage and malicious delay/reorder/drop behavior fail boundedly and
   never downgrade transport. Direct LAN/Tailscale/Secure Link candidates remain
   independently usable according to their existing policy.
7. Logs, metrics, health responses, exceptions, and diagnostics contain no raw
   route or Hermes credential and use bounded-cardinality identifiers.
