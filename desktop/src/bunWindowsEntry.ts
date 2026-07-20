import { spawnSync } from 'node:child_process'

import { shouldRelaunchBunWithWindowsSystemCa } from './bunSystemCaBootstrap.js'

// Bun's Windows trust-store switch is startup-only. The released Windows
// executable therefore performs one early self-relaunch before importing any
// module that can create a TLS context. NODE_USE_SYSTEM_CA=1 keeps Bun's
// bundled roots and adds Windows roots for both node:tls and global WebSocket.
if (
  shouldRelaunchBunWithWindowsSystemCa({
    platform: process.platform,
    bunVersion: process.versions.bun,
    nodeUseSystemCa: process.env.NODE_USE_SYSTEM_CA
  })
) {
  const child = spawnSync(process.execPath, process.argv.slice(2), {
    env: { ...process.env, NODE_USE_SYSTEM_CA: '1' },
    stdio: 'inherit',
    windowsHide: true
  })

  if (child.error) {
    process.stderr.write(`hermes-relay: could not enable Windows system CA trust: ${child.error.message}\n`)
    process.exit(1)
  }

  process.exit(child.status ?? 1)
}

const { main } = await import('./cli.js')

main()
  .then((code) => process.exit(code ?? 0))
  .catch((error) => {
    const message = error instanceof Error ? error.message : String(error)
    process.stderr.write(`hermes-relay: ${message}\n`)
    if (process.env.HERMES_DEBUG) {
      // eslint-disable-next-line no-console
      console.error(error)
    }
    process.exit(1)
  })
