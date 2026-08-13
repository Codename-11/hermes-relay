export type TransportSecurityKind = 'tls' | 'overlay' | 'plain'

export interface TransportSecurity {
  kind: TransportSecurityKind
  encrypted: boolean
  label: string
  detail: string
}

function isTailscaleIpv4(hostname: string): boolean {
  const parts = hostname.split('.').map(Number)
  return parts.length === 4 && parts.every(part => Number.isInteger(part) && part >= 0 && part <= 255) &&
    parts[0] === 100 && parts[1]! >= 64 && parts[1]! <= 127
}

export function isOverlayRoute(url: string, endpointRole?: string | null): boolean {
  const role = endpointRole?.trim().toLowerCase() ?? ''
  if (role === 'tailscale' || role === 'wireguard' || role.startsWith('wireguard-')) return true
  try {
    const hostname = new URL(url).hostname.toLowerCase()
    return hostname.endsWith('.ts.net') || isTailscaleIpv4(hostname)
  } catch {
    return false
  }
}

/** Describe the protection actually carrying relay traffic. WSS is TLS;
 * ws:// over a known Tailscale/WireGuard route is still encrypted by the
 * authenticated overlay. All other ws:// routes are plain transport. */
export function describeTransportSecurity(url: string, endpointRole?: string | null): TransportSecurity {
  try {
    if (new URL(url).protocol === 'wss:') {
      return { kind: 'tls', encrypted: true, label: 'Encrypted with TLS', detail: 'Relay traffic is protected end to end with WSS.' }
    }
  } catch { /* Invalid URLs are treated as unprotected below. */ }
  if (isOverlayRoute(url, endpointRole)) {
    const name = endpointRole?.toLowerCase().includes('wireguard') ? 'WireGuard' : 'Tailscale'
    return { kind: 'overlay', encrypted: true, label: `Encrypted by ${name}`, detail: `Relay traffic travels inside the ${name} encrypted overlay.` }
  }
  return { kind: 'plain', encrypted: false, label: 'Unencrypted relay connection', detail: 'Use WSS or an authenticated Tailscale/WireGuard route for encryption.' }
}
