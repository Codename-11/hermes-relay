// Minimal stderr spinner for long operations — zero deps.
//
// The CLI's #1 perceived-friction is silence: pairing probes endpoints for
// 4–12s, chat/shell wait up to 60s for the gateway, update downloads a binary
// — all with no feedback, so it looks hung. This gives a braille spinner on a
// TTY and degrades to a single final result line when piped/quiet/--json.
//
// Always on stderr so it never pollutes stdout (a piped transcript or `> out`
// stays clean). When disabled, start()/update() are no-ops but succeed()/fail()
// still print one line so non-interactive runs aren't left guessing.

import { SYMBOLS, theme as makeTheme, type Theme } from './theme.js'

export interface SpinnerOptions {
  /** Animate? Default: stderr is a TTY. Pass false for --quiet / --json. */
  enabled?: boolean
  stream?: NodeJS.WriteStream
  theme?: Theme
}

export interface Spinner {
  start(text?: string): Spinner
  update(text: string): void
  succeed(text?: string): void
  fail(text?: string): void
  /** Stop and clear the line without printing a result. */
  stop(): void
}

class TtySpinner implements Spinner {
  private timer: ReturnType<typeof setInterval> | null = null
  private frame = 0
  private text: string
  private readonly stream: NodeJS.WriteStream
  private readonly t: Theme

  constructor(text: string, private readonly enabled: boolean, stream: NodeJS.WriteStream, t: Theme) {
    this.text = text
    this.stream = stream
    this.t = t
  }

  start(text?: string): Spinner {
    if (text !== undefined) {
      this.text = text
    }
    if (!this.enabled || this.timer) {
      return this
    }
    this.stream.write('\x1b[?25l') // hide cursor
    const render = () => {
      const f = this.t.cyan(SYMBOLS.spinner[this.frame % SYMBOLS.spinner.length]!)
      this.stream.write(`\r\x1b[2K${f} ${this.text}`)
      this.frame++
    }
    render()
    this.timer = setInterval(render, 80)
    return this
  }

  update(text: string): void {
    this.text = text
    if (!this.enabled) {
      return
    }
    if (!this.timer) {
      this.stream.write(`\r\x1b[2K${this.t.muted('…')} ${this.text}`)
    }
  }

  private end(symbol: string, text?: string): void {
    const line = text ?? this.text
    this.clear()
    this.stream.write(`${symbol} ${line}\n`)
  }

  succeed(text?: string): void {
    this.end(this.t.ok(SYMBOLS.ok), text)
  }

  fail(text?: string): void {
    this.end(this.t.err(SYMBOLS.err), text)
  }

  stop(): void {
    this.clear()
  }

  private clear(): void {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
    if (this.enabled) {
      this.stream.write('\r\x1b[2K\x1b[?25h') // clear line + show cursor
    }
  }
}

export function createSpinner(text: string, opts: SpinnerOptions = {}): Spinner {
  const stream = opts.stream ?? process.stderr
  const enabled = opts.enabled ?? !!stream.isTTY
  const t = opts.theme ?? makeTheme()
  return new TtySpinner(text, enabled, stream, t)
}
