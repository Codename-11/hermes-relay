// CLI event renderer — turns GatewayEvents into plain-line stdout/stderr.
//
// Design:
//   - Assistant message text (message.delta) → stdout, streamed as it arrives.
//   - Tool calls/results → stdout inline, decorated with arrow marks so a
//     piped transcript still reads naturally.
//   - Diagnostics (status, errors, timeouts) → stderr, so `> out.txt` only
//     captures the agent's reply.
//   - --json → one event per line on stdout, for scripting; everything else
//     stays quiet.
//
// We deliberately do NOT handle clarify / approval / sudo / secret requests
// here — those belong at the REPL layer because they need synchronous
// keyboard input. The renderer just surfaces a warning so the user knows
// the turn is blocked on input it can't provide.

import type { GatewayEvent } from './gatewayTypes.js'

const ANSI = {
  reset: '\x1b[0m',
  dim: '\x1b[2m',
  bold: '\x1b[1m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  cyan: '\x1b[36m',
  gray: '\x1b[90m'
} as const

function colorEnabled(): boolean {
  if (process.env.NO_COLOR) {
    return false
  }
  if (process.env.FORCE_COLOR === '0') {
    return false
  }
  if (process.env.FORCE_COLOR && process.env.FORCE_COLOR !== '0') {
    return true
  }
  return !!process.stdout.isTTY
}

export interface RendererOptions {
  /** Emit each event as a JSON line on stdout — useful for scripting. */
  json?: boolean
  /** Include thinking / reasoning deltas. */
  verbose?: boolean
  /** Suppress status lines and tool decorations. */
  quiet?: boolean
  /** Force-disable colors (overrides auto-detect). */
  noColor?: boolean
}

export class CliRenderer {
  private inAssistantMessage = false
  private lastDeltaEndedWithNewline = true
  private readonly useColor: boolean

  constructor(private opts: RendererOptions = {}) {
    this.useColor = opts.noColor ? false : colorEnabled()
  }

  private c(code: string, text: string): string {
    return this.useColor ? `${code}${text}${ANSI.reset}` : text
  }

  /** Force a newline on stdout if the last assistant delta didn't end with one.
   * Call this before printing tool decorations / prompts that should start on
   * a fresh line regardless of what the model just emitted. */
  private breakLine(): void {
    if (this.inAssistantMessage && !this.lastDeltaEndedWithNewline) {
      process.stdout.write('\n')
      this.lastDeltaEndedWithNewline = true
    }
  }

  handle(ev: GatewayEvent): void {
    if (this.opts.json) {
      process.stdout.write(JSON.stringify(ev) + '\n')
      return
    }

    switch (ev.type) {
      case 'message.start': {
        if (!this.inAssistantMessage) {
          process.stdout.write('\n')
          this.inAssistantMessage = true
          this.lastDeltaEndedWithNewline = true
        }
        return
      }

      case 'message.delta': {
        const text = ev.payload?.text
        if (typeof text === 'string' && text.length > 0) {
          if (!this.inAssistantMessage) {
            process.stdout.write('\n')
            this.inAssistantMessage = true
          }
          process.stdout.write(text)
          this.lastDeltaEndedWithNewline = text.endsWith('\n')
        }
        return
      }

      case 'message.complete': {
        if (this.inAssistantMessage && !this.lastDeltaEndedWithNewline) {
          process.stdout.write('\n')
        }
        this.inAssistantMessage = false
        this.lastDeltaEndedWithNewline = true
        return
      }

      case 'thinking.delta':
      case 'reasoning.delta': {
        if (this.opts.verbose) {
          const text = ev.payload?.text
          if (typeof text === 'string' && text.length > 0) {
            process.stderr.write(this.c(ANSI.gray, text))
          }
        }
        return
      }

      case 'tool.start': {
        if (this.opts.quiet) {
          return
        }
        this.breakLine()
        const name = ev.payload.name ?? ev.payload.tool_id
        const context = ev.payload.context ? ` ${this.c(ANSI.dim, `(${ev.payload.context})`)}` : ''
        process.stdout.write(`\n${this.c(ANSI.cyan, '→')} ${this.c(ANSI.bold, name)}${context}\n`)
        return
      }

      case 'tool.progress':
        // Suppress per-token progress — too noisy for line-mode.
        return

      case 'tool.generating':
        // Internal "the model is filling in args" — silent.
        return

      case 'tool.complete': {
        if (this.opts.quiet) {
          return
        }
        const name = ev.payload.name ?? ev.payload.tool_id
        if (ev.payload.error) {
          process.stdout.write(
            `${this.c(ANSI.red, '✗')} ${this.c(ANSI.bold, name)} — ${ev.payload.error}\n`
          )
        } else {
          const summary = ev.payload.summary
          const suffix = summary ? ` ${this.c(ANSI.dim, '— ' + summary.replace(/\n+/g, ' '))}` : ''
          process.stdout.write(`${this.c(ANSI.green, '✓')} ${this.c(ANSI.bold, name)}${suffix}\n`)
        }
        return
      }

      case 'status.update': {
        if (this.opts.quiet) {
          return
        }
        const text = ev.payload?.text
        if (typeof text === 'string' && text.length > 0) {
          process.stderr.write(this.c(ANSI.dim, `[${text}]`) + '\n')
        }
        return
      }

      case 'error': {
        this.breakLine()
        const msg = ev.payload?.message ?? 'unknown error'
        process.stderr.write(this.c(ANSI.red, `error: ${msg}`) + '\n')
        return
      }

      case 'approval.request': {
        this.breakLine()
        process.stderr.write(
          this.c(ANSI.yellow, `⚠ approval requested: ${ev.payload.command}`) + '\n'
        )
        process.stderr.write(this.c(ANSI.dim, `  ${ev.payload.description}`) + '\n')
        process.stderr.write(
          this.c(ANSI.dim, '  (interactive approval is not wired in v0.1 — ignore or cancel)') + '\n'
        )
        return
      }

      case 'clarify.request': {
        this.breakLine()
        process.stderr.write(
          this.c(ANSI.yellow, `⚠ clarification requested: ${ev.payload.question}`) + '\n'
        )
        process.stderr.write(
          this.c(ANSI.dim, '  (interactive clarify is not wired in v0.1 — ignore or cancel)') + '\n'
        )
        return
      }

      case 'sudo.request':
      case 'secret.request': {
        this.breakLine()
        process.stderr.write(
          this.c(ANSI.yellow, `⚠ server requested ${ev.type} — not wired in v0.1`) + '\n'
        )
        return
      }

      case 'gateway.stderr': {
        if (this.opts.verbose) {
          process.stderr.write(this.c(ANSI.dim, ev.payload.line) + '\n')
        }
        return
      }

      case 'gateway.start_timeout': {
        process.stderr.write(
          this.c(ANSI.yellow, 'warning: gateway subprocess slow to start (>15s)') + '\n'
        )
        return
      }

      case 'gateway.protocol_error': {
        process.stderr.write(
          this.c(ANSI.red, `protocol error: ${ev.payload?.preview ?? '(no preview)'}`) + '\n'
        )
        return
      }

      // Subagent events, skin changes, session.info, background/btw, reasoning.available —
      // silent by default in v0.1. When we wire these into the CLI they'll get their own cases.
      case 'session.info':
      case 'gateway.ready':
      case 'skin.changed':
      case 'reasoning.available':
      case 'background.complete':
      case 'btw.complete':
      case 'subagent.start':
      case 'subagent.thinking':
      case 'subagent.tool':
      case 'subagent.progress':
      case 'subagent.complete':
        return
    }
  }
}
