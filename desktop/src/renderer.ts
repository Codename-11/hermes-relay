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
// Decorations follow the Relay cockpit palette in src/lib/theme.ts: the
// periwinkle arrow marks an in-flight tool, status green/red mark the
// verdict, warnings are amber, and everything secondary steps down through
// the muted/dim stairs. Color flows through the shared Theme so `--no-color`
// / NO_COLOR / non-TTY all degrade to the same plain text (and the test
// suite asserts on exactly that).
//
// We deliberately do NOT handle clarify / approval / sudo / secret requests
// here — those belong at the REPL layer because they need synchronous
// keyboard input. The renderer just surfaces a warning so the user knows
// the turn is blocked on input it can't provide.

import type { GatewayEvent } from './gatewayTypes.js'
import { theme, type Theme } from './lib/theme.js'

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
  private readonly t: Theme

  constructor(private opts: RendererOptions = {}) {
    this.t = theme({ noColor: opts.noColor })
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

      case 'message.interim': {
        const text = ev.payload?.text ?? ev.payload?.message ?? ev.payload?.preview ?? ev.payload?.rendered
        if (ev.payload?.already_streamed === true) {
          if (this.inAssistantMessage && !this.lastDeltaEndedWithNewline) {
            process.stdout.write('\n')
          }
          this.inAssistantMessage = false
          this.lastDeltaEndedWithNewline = true
        } else if (typeof text === 'string' && text.length > 0) {
          if (!this.inAssistantMessage) {
            process.stdout.write('\n')
          }
          process.stdout.write(text)
          if (!text.endsWith('\n')) {
            process.stdout.write('\n')
          }
          this.inAssistantMessage = false
          this.lastDeltaEndedWithNewline = true
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
            process.stderr.write(this.t.dim(text))
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
        const context = ev.payload.context ? ` ${this.t.dim(`(${ev.payload.context})`)}` : ''
        process.stdout.write(`\n${this.t.relay('→')} ${this.t.bold(name)}${context}\n`)
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
            `${this.t.err('✗')} ${this.t.bold(name)} ${this.t.dim('—')} ${this.t.err(ev.payload.error)}\n`
          )
        } else {
          const summary = ev.payload.summary
          const suffix = summary ? ` ${this.t.dim('— ' + summary.replace(/\n+/g, ' '))}` : ''
          process.stdout.write(`${this.t.ok('✓')} ${this.t.bold(name)}${suffix}\n`)
        }
        return
      }

      case 'status.update': {
        if (this.opts.quiet) {
          return
        }
        const text = ev.payload?.text
        if (typeof text === 'string' && text.length > 0) {
          process.stderr.write(this.t.muted(`[${text}]`) + '\n')
        }
        return
      }

      case 'artifact.created': {
        if (this.opts.quiet) {
          return
        }
        this.breakLine()
        const title = ev.payload.title ?? ev.payload.path ?? 'artifact'
        const target = ev.payload.url ?? ev.payload.path
        const suffix = target ? ` ${this.t.dim(`— ${target}`)}` : ''
        process.stdout.write(`${this.t.cyan('↳')} ${this.t.bold(title)}${suffix}\n`)
        return
      }

      case 'memory.updated':
      case 'skill.loaded': {
        if (this.opts.quiet) {
          return
        }
        const label = ev.type === 'memory.updated' ? 'memory updated' : 'skill loaded'
        const detail = ev.payload.detail ? `: ${ev.payload.detail}` : ''
        process.stderr.write(this.t.muted(`[${label}${detail}]`) + '\n')
        return
      }

      case 'error': {
        this.breakLine()
        const msg = ev.payload?.message ?? 'unknown error'
        process.stderr.write(this.t.err(`error: ${msg}`) + '\n')
        return
      }

      case 'approval.request': {
        this.breakLine()
        process.stderr.write(
          this.t.warn(`⚠ approval requested: ${ev.payload.command}`) + '\n'
        )
        process.stderr.write(this.t.dim(`  ${ev.payload.description}`) + '\n')
        process.stderr.write(
          this.t.dim('  (interactive approval is not wired in v0.1 — ignore or cancel)') + '\n'
        )
        return
      }

      case 'clarify.request': {
        this.breakLine()
        process.stderr.write(
          this.t.warn(`⚠ clarification requested: ${ev.payload.question}`) + '\n'
        )
        process.stderr.write(
          this.t.dim('  (interactive clarify is not wired in v0.1 — ignore or cancel)') + '\n'
        )
        return
      }

      case 'sudo.request':
      case 'secret.request': {
        this.breakLine()
        process.stderr.write(
          this.t.warn(`⚠ server requested ${ev.type} — not wired in v0.1`) + '\n'
        )
        return
      }

      case 'gateway.stderr': {
        if (this.opts.verbose) {
          process.stderr.write(this.t.dim(ev.payload.line) + '\n')
        }
        return
      }

      case 'gateway.start_timeout': {
        process.stderr.write(
          this.t.warn('warning: gateway subprocess slow to start (>15s)') + '\n'
        )
        return
      }

      case 'gateway.protocol_error': {
        process.stderr.write(
          this.t.err(`protocol error: ${ev.payload?.preview ?? '(no preview)'}`) + '\n'
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
