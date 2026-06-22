// audit — show what the remote Hermes agent has run on THIS machine via the
// desktop tool router. Reads the local JSONL written by DesktopToolRouter
// (~/.hermes/desktop-audit.jsonl) — no network, no auth, works whether the
// relay is local or remote. Answers the "what did the agent just do?" question
// the audit flagged as the biggest desktop-tools transparency gap.

import type { ParsedArgs } from '../cli.js'
import { auditLogPath, readRecentAudit } from '../lib/auditLog.js'
import { renderTable } from '../lib/table.js'
import { SYMBOLS, theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec } from '../lib/usage.js'

const AUDIT_USAGE: UsageSpec = {
  name: 'audit',
  summary: 'show recent desktop-tool activity the agent ran on this machine',
  usage: ['audit [--limit <n>] [--json]'],
  flags: [
    { flag: '--limit <n>', desc: 'How many recent entries to show (default 50)' },
    { flag: '--json', desc: 'Emit raw audit entries as JSON' }
  ],
  examples: ['hermes-relay audit', 'hermes-relay audit --limit 20']
}

function humanAge(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s}s`
  if (s < 3600) return `${Math.floor(s / 60)}m`
  if (s < 86_400) return `${Math.floor(s / 3600)}h`
  return `${Math.floor(s / 86_400)}d`
}

export async function auditCommand(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (args.flags.help) {
    printUsage(AUDIT_USAGE, t)
    return 0
  }

  const rawLimit = typeof args.flags.limit === 'string' ? parseInt(args.flags.limit, 10) : 50
  const limit = Number.isFinite(rawLimit) && rawLimit > 0 ? rawLimit : 50

  const entries = await readRecentAudit(limit)

  if (args.flags.json) {
    process.stdout.write(JSON.stringify(entries, null, 2) + '\n')
    return 0
  }

  if (entries.length === 0) {
    process.stdout.write(
      t.muted('No desktop-tool activity recorded on this machine yet.') + '\n' +
        t.muted(`  (log: ${auditLogPath()} — written when the agent runs a desktop_* tool)`) + '\n'
    )
    return 0
  }

  const now = Date.now()
  const rows = entries.map((e) => {
    const status = e.ok
      ? `${t.statusDot(true)} ok`
      : e.aborted
        ? `${t.warn(SYMBOLS.warn)} aborted`
        : `${t.err(SYMBOLS.err)} error`
    const detail = e.error ?? e.summary ?? e.args_preview ?? ''
    return [`${humanAge(now - e.ts)} ago`, e.tool, status, detail]
  })

  process.stdout.write(t.bold(`Desktop-tool activity (${entries.length} most recent)`) + '\n\n')
  process.stdout.write(
    renderTable(
      [
        { header: 'WHEN', align: 'right' },
        { header: 'TOOL' },
        { header: 'STATUS' },
        { header: 'DETAIL' }
      ],
      rows,
      { theme: t }
    ) + '\n'
  )
  return 0
}

export default auditCommand
