// TOFU cert pinning helpers for the desktop CLI. Mirrors the Android
// `CertPinStore` (app/.../auth/CertPinStore.kt):
//   - pin key:   lowercase `host:port`
//   - pin value: `sha256/<base64-of-SPKI-sha256>` — same shape OkHttp's
//                CertificatePinner prints, so the format is portable across
//                our Kotlin and Node clients.
//
// This module is intentionally pure helpers — reading/writing the session
// file (remoteSessions.ts) stays in RelayTransport so storage concerns don't
// leak here. Node ≥21 only: uses built-in `node:crypto` X509Certificate and
// `node:tls` for the pre-WS probe.
//
// Scope note: SPKI sha256 is always computed over the *leaf* peer certificate.
// Intermediate CAs in the chain can (and do) rotate; pinning an intermediate
// would cause false mismatches. Since the leaf is what the server actually
// presents on each TLS handshake, pinning there matches the Android behavior
// and avoids flapping on CA renewal.

import { createHash, X509Certificate } from 'node:crypto'

/** Format version: OkHttp-compatible `sha256/<base64>`. Change only if the
 * Android store format changes too. */
const PIN_PREFIX = 'sha256/'

/**
 * Compute `sha256/<base64>` over the SubjectPublicKeyInfo of a DER-encoded
 * X.509 certificate. The input is the raw `rawCert` buffer returned by
 * `tls.TLSSocket.getPeerCertificate(false)`.
 *
 * Throws if the buffer isn't a parseable cert — callers should treat that
 * as a TOFU failure and refuse to connect.
 */
export const extractSpkiSha256 = (peerCertDer: Buffer): string => {
  const cert = new X509Certificate(peerCertDer)
  // `publicKey.export({type: 'spki', format: 'der'})` returns the exact
  // SubjectPublicKeyInfo bytes — same thing OkHttp's `sha256` pin hashes.
  const spkiDer = cert.publicKey.export({ type: 'spki', format: 'der' }) as Buffer
  const digest = createHash('sha256').update(spkiDer).digest('base64')

  return `${PIN_PREFIX}${digest}`
}

/**
 * Canonical pin-store key for a URL — lowercase `host:port`. Explicit port
 * is required (no implicit 443/80) so `wss://host/` and `wss://host:443/`
 * resolve to the same key.
 */
export const pinKey = (url: string): string => {
  const u = new URL(url)
  const host = u.hostname.toLowerCase()
  const port = u.port || (u.protocol === 'wss:' || u.protocol === 'https:' ? '443' : '80')

  return `${host}:${port}`
}

/** True if the URL uses TLS (`wss:` or `https:`). Plain ws:// short-circuits
 * all TOFU logic — no cert to pin, no probe to run. */
export const isSecureUrl = (url: string): boolean => {
  try {
    const { protocol } = new URL(url)

    return protocol === 'wss:' || protocol === 'https:'
  } catch {
    return false
  }
}

/**
 * Constant-time comparison of two pins. Both sides must share the
 * `sha256/<base64>` shape; a length mismatch returns false immediately
 * but that leak is fine — the prefix is fixed and the base64 body is a
 * fixed 44 chars for sha256.
 */
export const comparePins = (expected: string, actual: string): boolean => {
  if (expected.length !== actual.length) {
    return false
  }

  let mismatch = 0
  for (let i = 0; i < expected.length; i++) {
    mismatch |= expected.charCodeAt(i) ^ actual.charCodeAt(i)
  }

  return mismatch === 0
}
