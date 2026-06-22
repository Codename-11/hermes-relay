// Shared per-subcommand usage/help renderer.
//
// Before this, only the top-level `hermes-relay --help` existed; subcommands
// with sub-verbs (devices/sessions/plugins/voice) greeted a wrong verb with a
// terse "unknown sub-verb" instead of explaining themselves. Each command now
// declares a UsageSpec and renders it consistently for both `--help` and the
// unknown-sub-verb fallback.

import { theme as makeTheme, type Theme } from './theme.js'

export interface UsageSub {
  verb: string
  desc: string
}

export interface UsageFlag {
  flag: string
  desc: string
}

export interface UsageSpec {
  /** Bare command name, e.g. "devices". */
  name: string
  /** One-line summary shown under the title. */
  summary: string
  /** Usage lines (without the leading "hermes-relay"). */
  usage: string[]
  subcommands?: UsageSub[]
  flags?: UsageFlag[]
  examples?: string[]
}

/** Pad a left column to `width` for two-column lists. */
function col(left: string, right: string, width: number, t: Theme): string {
  const gap = Math.max(2, width - left.length + 2)
  return `  ${left}${' '.repeat(gap)}${t.muted(right)}`
}

export function renderUsage(spec: UsageSpec, t: Theme = makeTheme()): string {
  const lines: string[] = []
  lines.push(`${t.bold(spec.name)} — ${spec.summary}`)
  lines.push('')
  lines.push(t.bold('Usage:'))
  for (const u of spec.usage) {
    lines.push(`  ${t.muted('hermes-relay')} ${u}`)
  }

  if (spec.subcommands?.length) {
    const w = Math.max(...spec.subcommands.map((s) => s.verb.length))
    lines.push('')
    lines.push(t.bold('Sub-commands:'))
    for (const s of spec.subcommands) {
      lines.push(col(s.verb, s.desc, w, t))
    }
  }

  if (spec.flags?.length) {
    const w = Math.max(...spec.flags.map((f) => f.flag.length))
    lines.push('')
    lines.push(t.bold('Flags:'))
    for (const f of spec.flags) {
      lines.push(col(f.flag, f.desc, w, t))
    }
  }

  if (spec.examples?.length) {
    lines.push('')
    lines.push(t.bold('Examples:'))
    for (const ex of spec.examples) {
      lines.push(`  ${t.muted('$')} ${ex}`)
    }
  }

  return lines.join('\n') + '\n'
}

/** Print usage to stdout (for --help). */
export function printUsage(spec: UsageSpec, t?: Theme): void {
  process.stdout.write(renderUsage(spec, t))
}

/** Print usage to stderr with an "unknown sub-command" preamble (for the
 * fallback when a user types a verb we don't recognise). Returns exit code 2. */
export function unknownSubcommand(spec: UsageSpec, got: string, t: Theme = makeTheme()): number {
  process.stderr.write(t.err(`unknown ${spec.name} sub-command: "${got}"`) + '\n\n')
  process.stderr.write(renderUsage(spec, t))
  return 2
}
