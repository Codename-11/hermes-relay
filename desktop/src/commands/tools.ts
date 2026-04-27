// tools — ask the server what tool access the agent will have on this
// connection. Calls the `tools.list` JSON-RPC (surfaced from hermes-agent
// `run_agent.py::tools.list`, see note in CLAUDE.md) and prints a summary
// so the user can see which toolsets are enabled before spending a prompt.

import type { ParsedArgs } from '../cli.js'
import { resolveCredentials } from '../credentials.js'
import { GatewayClient } from '../gatewayClient.js'
import type { GatewayEvent, ToolsListResponse } from '../gatewayTypes.js'
import { asRpcResult, rpcErrorMessage } from '../lib/rpc.js'
import { resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { deleteSession, saveSession } from '../remoteSessions.js'
import { RelayTransport } from '../transport/RelayTransport.js'

const READY_TIMEOUT_MS = 60_000

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

  relay.start()
  const outcome = await relay.whenAuthResolved()

  if (!outcome.ok) {
    if (creds.sessionToken) {
      await deleteSession(url)
    }
    process.stderr.write(`error: ${outcome.reason}\n`)
    try {
      relay.kill()
    } catch {
      /* ignore */
    }
    return 1
  }

  const gw = new GatewayClient(relay)
  const ready = waitForReady(gw)
  gw.start()
  gw.drain()

  try {
    await ready
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    gw.kill()
    return 1
  }

  try {
    const raw = await gw.request<ToolsListResponse>('tools.list', {})
    const result = asRpcResult<ToolsListResponse>(raw)
    const toolsets = result?.toolsets ?? []

    if (args.flags.json) {
      process.stdout.write(JSON.stringify(toolsets, null, 2) + '\n')
      gw.kill()
      return 0
    }

    if (toolsets.length === 0) {
      process.stdout.write('(server returned no toolsets)\n')
      gw.kill()
      return 0
    }

    const enabled = toolsets.filter((t) => t.enabled).length
    process.stdout.write(
      `Server: ${url}\n` +
        `Version: ${relay.serverVersion ?? '?'}\n` +
        `Toolsets: ${toolsets.length} (${enabled} enabled)\n\n`
    )

    for (const ts of toolsets) {
      const mark = ts.enabled ? '●' : '○'
      const count = typeof ts.tool_count === 'number' ? `${ts.tool_count} tools` : '?'
      process.stdout.write(`  ${mark} ${ts.name}  (${count})`)
      if (ts.description) {
        process.stdout.write(`  — ${ts.description}`)
      }
      process.stdout.write('\n')

      if (args.flags.verbose && ts.tools && ts.tools.length > 0) {
        for (const t of ts.tools) {
          process.stdout.write(`      • ${t.name}`)
          if (t.description) {
            process.stdout.write(`  ${t.description}`)
          }
          process.stdout.write('\n')
        }
      }
    }
    process.stdout.write('\n  ● = enabled for this session  ○ = available but off\n')

    gw.kill()
    return 0
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    gw.kill()
    return 1
  }
}
