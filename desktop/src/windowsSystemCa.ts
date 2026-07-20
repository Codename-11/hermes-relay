interface NodeTlsCaApi {
  getCACertificates?: (type?: 'default' | 'system') => string[]
  setDefaultCACertificates?: (certificates: string[]) => void
}

export interface WindowsSystemCaResult {
  applied: boolean
  reason: 'applied' | 'bun' | 'empty' | 'error' | 'not-windows' | 'unsupported'
  systemCertificateCount: number
  totalCertificateCount: number
  error?: string
}

/**
 * Adds Windows-trusted roots to Node's TLS defaults before the Relay TLS
 * probe and global WebSocket create their secure contexts. Existing defaults
 * include Node's bundled roots and any NODE_EXTRA_CA_CERTS entries, so retain
 * them rather than replacing the trust set with the Windows store.
 *
 * Bun owns its TLS trust policy and currently does not expose Node's default-CA
 * setter. The packaged Windows entry enables Bun's startup-only system-CA mode,
 * so this runtime helper must remain a no-op under Bun.
 */
export const installWindowsSystemCaTrust = (
  tlsApi: NodeTlsCaApi,
  platform = process.platform,
  isBun = typeof process.versions.bun === 'string'
): WindowsSystemCaResult => {
  if (platform !== 'win32') {
    return {
      applied: false,
      reason: 'not-windows',
      systemCertificateCount: 0,
      totalCertificateCount: 0
    }
  }

  if (isBun) {
    return {
      applied: false,
      reason: 'bun',
      systemCertificateCount: 0,
      totalCertificateCount: 0
    }
  }

  if (typeof tlsApi.getCACertificates !== 'function' || typeof tlsApi.setDefaultCACertificates !== 'function') {
    return {
      applied: false,
      reason: 'unsupported',
      systemCertificateCount: 0,
      totalCertificateCount: 0
    }
  }

  try {
    const defaultCertificates = tlsApi.getCACertificates('default')
    const systemCertificates = tlsApi.getCACertificates('system')

    if (systemCertificates.length === 0) {
      return {
        applied: false,
        reason: 'empty',
        systemCertificateCount: 0,
        totalCertificateCount: defaultCertificates.length
      }
    }

    const certificates = [...defaultCertificates, ...systemCertificates]
    tlsApi.setDefaultCACertificates(certificates)

    return {
      applied: true,
      reason: 'applied',
      systemCertificateCount: systemCertificates.length,
      totalCertificateCount: certificates.length
    }
  } catch (error) {
    return {
      applied: false,
      reason: 'error',
      systemCertificateCount: 0,
      totalCertificateCount: 0,
      error: error instanceof Error ? error.message : String(error)
    }
  }
}
