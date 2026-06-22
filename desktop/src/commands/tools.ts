// tools — ask the server what tool access the agent will have on this
// connection. Calls the `tools.list` JSON-RPC (surfaced from hermes-agent
// `run_agent.py::tools.list`, see note in CLAUDE.md) and prints a summary
// so the user can see which toolsets are enabled before spending a prompt.

import type { ParsedArgs } from '../cli.js'
import { resolveCredentials } from '../credentials.js'
import { GatewayClient } from '../gatewayClient.js'
import type { GatewayEvent, ToolsListResponse } from '../gatewayTypes.js'
import { formatError } from '../lib/hints.js'
import { asRpcResult, rpcErrorMessage } from '../lib/rpc.js'
import { createSpinner } from '../lib/spinner.js'
import { SYMBOLS, theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec } from '../lib/usage.js'
import { resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { deleteSession, saveSession } from '../remoteSessions.js'
import { RelayTransport } from '../transport/RelayTransport.js'

const READY_TIMEOUT_MS = 60_000

const TOOLS_USAGE: UsageSpec = {
  name: 'tools',
  summary: 'show the tool access the agent will have on this connection',
  usage: ['tools [--verbose] [--json]'],
  flags: [
    { flag: '--verbose', desc: 'List individual tools under each toolset' },
    { flag: '--json', desc: 'Machine-readable toolset list' },
    { flag: '--remote <url>', desc: 'Relay to query (default: stored/active)' }
  ],
  examples: ['hermes-relay tools', 'hermes-relay tools --verbose']
}

function resolveRemote(args: ParsedArgs): string | null {
  const v = args.flags.remote
  return (typeof v === 'string' ? v : null) ?? process.env.HERMES_RELAY_URL ?? null
}

function waitForReady(gw: GatewayClient): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      gw.off('event', handler)
      reject(new Error(`gateway.ready timeout after ${READY_TIMEOUT_MS}ms`))
    }, READY_TIMEOUT_MS)

    const handler = (ev: GatewayEvent) => {
      if (ev.type === 'gateway.ready') {
        clearTimeout(timer)
        gw.off('event', handler)
        resolve()
      }
    }

    gw.on('event', handler)
  })
}

export async function toolsCommand(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (args.flags.help) {
    printUsage(TOOLS_USAGE, t)
    return 0
  }
  let urlFlag = resolveRemote(args)
  const argCode = typeof args.flags.code === 'string' ? args.flags.code : undefined
  const argToken = typeof args.flags.token === 'string' ? args.flags.token : undefined
  const argPairQr =
    typeof args.flags['pair-qr'] === 'string'
      ? args.flags['pair-qr']
      : process.env.HERMES_RELAY_PAIR_QR
  const nonInteractive = !!args.flags['non-interactive']

  // First-run fallback: no --remote, no env var, no QR payload. Pick
  // from stored sessions or prompt for a URL. Throws on non-interactive
  // + ambiguous.
  if (!urlFlag && !argPairQr) {
    try {
      urlFlag = await resolveFirstRunUrl({ nonInteractive })
    } catch (e) {
      process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
      return 1
    }
  }

  // Placeholder — resolveCredentials needs SOMETHING non-empty so its
  // `getSession(url)` branch has a key to look up (harmless when we end
  // up routing through --pair-qr since we override the url below).
  const probeUrl = urlFlag ?? 'ws://pair-qr-pending'

  let creds
  try {
    creds = await resolveCredentials(probeUrl, { argCode, argToken, argPairQr, nonInteractive })
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    return 1
  }

  // Multi-endpoint path won — use the winning endpoint's relay.url. Otherwise
  // fall back to the caller-supplied --remote.
  const url = (creds.resolvedEndpoint?.relay.url ?? urlFlag)!.trim()
  const endpointRole = creds.resolvedEndpoint?.role ?? null

  const relayCfg: ConstructorParameters<typeof RelayTransport>[0] = {
    url,
    deviceName: `hermes-relay-cli (${process.platform})`
  }
  if (creds.pairingCode) {
    relayCfg.pairingCode = creds.pairingCode
  }
  if (creds.sessionToken) {
    relayCfg.sessionToken = creds.sessionToken
  }

  const relay = new RelayTransport(relayCfg)
  relay.onAuthSuccess((token, ver, meta) => {
    void saveSession(url, token, ver, {
      grants: meta.grants,
      ttlExpiresAt: meta.ttlExpiresAt,
      endpointRole
    })
  })

  const spinner = createSpinner(`Connecting to ${url}…`, {
    enabled: !args.flags.json && !args.flags.quiet,
    theme: t
  })
  spinner.start()

  relay.start()
  const outcome = await relay.whenAuthResolved()

  if (!outcome.ok) {
    if (creds.sessionToken) {
      await deleteSession(url)
    }
    spinner.fail('connection failed')
    process.stderr.write(formatError(outcome.reason, { command: 'tools', url }, t) + '\n')
    try {
      relay.kill()
    } catch {
      /* ignore */
    }
    return 1
  }

  spinner.update('Loading toolsets…')
  const gw = new GatewayClient(relay)
  const ready = waitForReady(gw)
  gw.start()
  gw.drain()

  try {
    await ready
  } catch (e) {
    spinner.fail('gateway not ready')
    process.stderr.write(formatError(e, { command: 'tools', url }, t) + '\n')
    gw.kill()
    return 1
  }

  try {
    const raw = await gw.request<ToolsListResponse>('tools.list', {})
    spinner.stop()
    const result = asRpcResult<ToolsListResponse>(raw)
    const toolsets = result?.toolsets ?? []

    if (args.flags.json) {
      process.stdout.write(JSON.stringify(toolsets, null, 2) + '\n')
      gw.kill()
      return 0
    }

    if (toolsets.length === 0) {
      process.stdout.write(t.muted('(server returned no toolsets)') + '\n')
      gw.kill()
      return 0
    }

    const enabled = toolsets.filter((ts) => ts.enabled).length
    process.stdout.write(
      `${t.muted('Server:  ')} ${url}\n` +
        `${t.muted('Version: ')} ${relay.serverVersion ?? '?'}\n` +
        `${t.muted('Toolsets:')} ${toolsets.length} (${enabled} enabled)\n\n`
    )

    for (const ts of toolsets) {
      const count = typeof ts.tool_count === 'number' ? `${ts.tool_count} tools` : '?'
      process.stdout.write(`  ${t.statusDot(!!ts.enabled)} ${t.bold(ts.name)}  ${t.muted(`(${count})`)}`)
      if (ts.description) {
        process.stdout.write(`  ${t.muted('— ' + ts.description)}`)
      }
      process.stdout.write('\n')

      if (args.flags.verbose && ts.tools && ts.tools.length > 0) {
        for (const tool of ts.tools) {
          process.stdout.write(`      ${t.muted(SYMBOLS.bullet)} ${tool.name}`)
          if (tool.description) {
            process.stdout.write(`  ${t.muted(tool.description)}`)
          }
          process.stdout.write('\n')
        }
      }
    }
    process.stdout.write(
      `\n  ${t.statusDot(true)} ${t.muted('enabled for this session')}   ` +
        `${t.statusDot(false)} ${t.muted('available but off')}\n`
    )

    gw.kill()
    return 0
  } catch (e) {
    spinner.stop()
    process.stderr.write(formatError(e, { command: 'tools', url }, t) + '\n')
    gw.kill()
    return 1
  }
}
