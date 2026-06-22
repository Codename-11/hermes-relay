// Tiny column-aligned table renderer — zero deps.
//
// status / devices / sessions / tools all list rows of data, but each used to
// format them differently (indented key:value, sparse columns, multi-line
// blocks). This unifies them: pass columns + rows, get an aligned block that
// adapts to terminal width. ANSI-aware so colored cells still align.

import { visibleWidth, stripAnsi, type Theme } from './theme.js'

export interface TableColumn {
  header: string
  /** Right-align numeric/age columns; default left. */
  align?: 'left' | 'right'
}

export interface TableOptions {
  /** Leading spaces on every row (default 2 — matches existing list indent). */
  indent?: number
  /** Spaces between columns (default 2). */
  gap?: number
  /** Hard width cap; defaults to the terminal width (or 100 when not a TTY). */
  maxWidth?: number
  /** Show the header row (default true). */
  header?: boolean
  /** Theme for dimming the header — omit for plain output. */
  theme?: Theme
}

/** Pad `text` to `width` visible columns, respecting embedded ANSI. */
function pad(text: string, width: number, align: 'left' | 'right'): string {
  const gap = width - visibleWidth(text)
  if (gap <= 0) {
    return text
  }
  const spaces = ' '.repeat(gap)
  return align === 'right' ? spaces + text : text + spaces
}

/** Truncate to `max` visible columns with an ellipsis. Skips cells that carry
 * ANSI (truncating mid-escape would corrupt them); those are assumed short. */
function truncate(text: string, max: number): string {
  if (max <= 0) {
    return ''
  }
  if (stripAnsi(text) !== text) {
    return text
  }
  if (text.length <= max) {
    return text
  }
  return max <= 1 ? '…' : text.slice(0, max - 1) + '…'
}

/**
 * Render an aligned table. The LAST column is treated as flexible: when the
 * natural layout overflows `maxWidth`, only that column is truncated, so
 * fixed-width identifiers (token prefixes, names) stay intact and the
 * free-text tail (description / error) absorbs the squeeze.
 */
export function renderTable(
  columns: TableColumn[],
  rows: string[][],
  opts: TableOptions = {}
): string {
  const indent = opts.indent ?? 2
  const gap = opts.gap ?? 2
  const showHeader = opts.header ?? true
  const maxWidth = opts.maxWidth ?? (process.stdout.columns || 100)
  const t = opts.theme

  const n = columns.length
  const widths = columns.map((col, i) => {
    const headerW = showHeader ? visibleWidth(col.header) : 0
    const cellW = rows.reduce((m, r) => Math.max(m, visibleWidth(r[i] ?? '')), 0)
    return Math.max(headerW, cellW)
  })

  // Shrink the flexible (last) column if the row would overflow the terminal.
  const fixed = indent + gap * (n - 1) + widths.slice(0, -1).reduce((a, b) => a + b, 0)
  const lastBudget = maxWidth - fixed
  if (n > 0 && lastBudget > 0 && widths[n - 1]! > lastBudget) {
    widths[n - 1] = lastBudget
  }

  const pre = ' '.repeat(indent)
  const sep = ' '.repeat(gap)
  const lines: string[] = []

  const renderRow = (cells: string[], dimHeader = false): string => {
    const parts = columns.map((col, i) => {
      let cell = cells[i] ?? ''
      const w = widths[i]!
      if (i === n - 1) {
        cell = truncate(cell, w)
      }
      const padded = pad(cell, w, col.align ?? 'left')
      return dimHeader && t ? t.muted(padded) : padded
    })
    return pre + parts.join(sep).replace(/\s+$/, '')
  }

  if (showHeader) {
    lines.push(renderRow(columns.map((c) => c.header), true))
  }
  for (const r of rows) {
    lines.push(renderRow(r))
  }
  return lines.join('\n')
}
