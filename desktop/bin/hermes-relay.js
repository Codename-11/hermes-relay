#!/usr/bin/env node
// hermes-relay — CLI thin-client entry shim.
// Imports the compiled dist/cli.js and calls main(). The shim is kept tiny
// so a publish-time tarball audit shows exactly one statement running before
// user code; everything interesting lives in src/cli.ts.
import { main } from '../dist/cli.js'

main()
  .then((code) => process.exit(code ?? 0))
  .catch((err) => {
    const msg = err instanceof Error ? err.message : String(err)
    process.stderr.write(`hermes-relay: ${msg}\n`)
    if (process.env.HERMES_DEBUG) {
      // eslint-disable-next-line no-console
      console.error(err)
    }
    process.exit(1)
  })
