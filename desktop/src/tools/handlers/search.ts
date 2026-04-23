// File/content search handler for the `desktop` relay channel.
//
//   args: { pattern: string, cwd?: string, max_results?: number,
//           content?: boolean }
//   returns: { matches: string[], truncated: boolean }
//
// content:true  — search file *contents* for `pattern`. Prefers ripgrep
//                 if it's on PATH; falls back to a pure-Node walk+read
//                 (slower, but keeps the handler dep-free).
// content:false — search file *paths* for `pattern` (glob-ish: supports
//                 `*`, `?`, `**` with forward-slash segments).
//
// Skips `.git`, `node_modules`, `dist`, `.next`, `.cache` under all
// modes so a naïve `*.ts` doesn't drown in vendored code.

import { spawn } from 'node:child_process'
import { promises as fs } from 'node:fs'
import * as path from 'node:path'

import type { ToolContext, ToolHandler } from '../router.js'

const DEFAULT_MAX = 100
const SKIP_DIRS = new Set(['.git', 'node_modules', 'dist', '.next', '.cache'])

function argString(v: unknown, name: string): string {
  if (typeof v !== 'string' || v.length === 0) {
    throw new Error(`missing or invalid "${name}" argument`)
  }
  return v
}

function argNumber(v: unknown, fallback: number): number {
  if (typeof v === 'number' && Number.isFinite(v) && v > 0) {
    return Math.floor(v)
  }
  return fallback
}

function argBool(v: unknown, fallback: boolean): boolean {
  if (typeof v === 'boolean') {
    return v
  }
  return fallback
}

// Compile a glob pattern (`*`, `?`, `**`) to a RegExp that matches full
// relative paths. Not fully POSIX — enough for the common agent cases
// (`**/*.ts`, `src/**/*.py`, `*.md`).
function globToRegExp(glob: string): RegExp {
  let out = '^'
  let i = 0
  while (i < glob.length) {
    const c = glob[i]!
    if (c === '*') {
      if (glob[i + 1] === '*') {
        // `**` — match any sequence including path separators
        out += '.*'
        i += 2
        // Consume a trailing slash after `**` so `**/foo` doesn't require a
        // leading directory: treat `**/foo` equivalent to `(?:.*\/)?foo`.
        if (glob[i] === '/') {
          i++
        }
      } else {
        // Single `*` — match anything except a path separator
        out += '[^/]*'
        i++
      }
    } else if (c === '?') {
      out += '[^/]'
      i++
    } else if ('.+^$()|{}[]\\'.indexOf(c) >= 0) {
      out += '\\' + c
      i++
    } else {
      out += c
      i++
    }
  }
  out += '$'
  return new RegExp(out)
}

async function* walk(root: string, ctx: ToolContext): AsyncGenerator<string> {
  const queue: string[] = [root]
  while (queue.length > 0) {
    if (ctx.abortSignal.aborted) {
      return
    }
    const dir = queue.shift()!
    let entries: Array<{ name: string; isDirectory(): boolean; isFile(): boolean }> = []
    try {
      entries = await fs.readdir(dir, { withFileTypes: true })
    } catch {
      continue // unreadable dir — skip silently
    }
    for (const ent of entries) {
      const full = path.join(dir, ent.name)
      if (ent.isDirectory()) {
        if (SKIP_DIRS.has(ent.name)) {
          continue
        }
        queue.push(full)
      } else if (ent.isFile()) {
        yield full
      }
    }
  }
}

async function pathSearch(
  pattern: string,
  root: string,
  max: number,
  ctx: ToolContext
): Promise<{ matches: string[]; truncated: boolean }> {
  // Normalize pattern to forward-slash for cross-platform glob matching.
  const re = globToRegExp(pattern.replace(/\\/g, '/'))
  const matches: string[] = []
  let truncated = false

  for await (const file of walk(root, ctx)) {
    const rel = path.relative(root, file).replace(/\\/g, '/')
    if (re.test(rel) || re.test(path.basename(file))) {
      matches.push(file)
      if (matches.length >= max) {
        truncated = true
        break
      }
    }
  }

  return { matches, truncated }
}

function ripgrepContentSearch(
  pattern: string,
  root: string,
  max: number,
  ctx: ToolContext
): Promise<{ matches: string[]; truncated: boolean; ok: boolean }> {
  return new Promise(resolve => {
    const rgArgs = [
      '--files-with-matches',
      '--no-messages',
      '--max-count', '1',
      ...Array.from(SKIP_DIRS).flatMap(d => ['--glob', `!${d}`]),
      '-e',
      pattern,
      root
    ]
    let child
    try {
      child = spawn('rg', rgArgs, { stdio: ['ignore', 'pipe', 'pipe'] })
    } catch {
      resolve({ matches: [], truncated: false, ok: false })
      return
    }

    let stdout = ''
    child.stdout?.on('data', (c: Buffer) => {
      stdout += c.toString('utf8')
    })
    child.on('error', () => resolve({ matches: [], truncated: false, ok: false }))

    const onAbort = () => {
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
    }
    ctx.abortSignal.addEventListener('abort', onAbort, { once: true })

    child.on('close', () => {
      ctx.abortSignal.removeEventListener('abort', onAbort)
      const lines = stdout.split('\n').filter(Boolean)
      const matches = lines.slice(0, max)
      resolve({ matches, truncated: lines.length > max, ok: true })
    })
  })
}

async function fallbackContentSearch(
  pattern: string,
  root: string,
  max: number,
  ctx: ToolContext
): Promise<{ matches: string[]; truncated: boolean }> {
  // Literal substring match — we don't try to be a regex engine. If the
  // agent wants regex, it can use ripgrep when available or ask for
  // path search + read-file combo.
  const matches: string[] = []
  let truncated = false

  for await (const file of walk(root, ctx)) {
    if (ctx.abortSignal.aborted) {
      break
    }
    try {
      const stat = await fs.stat(file)
      if (stat.size > 5_000_000) {
        continue // skip huge files in the fallback path; rg can handle them
      }
      const buf = await fs.readFile(file)
      if (buf.includes(pattern)) {
        matches.push(file)
        if (matches.length >= max) {
          truncated = true
          break
        }
      }
    } catch {
      // unreadable — skip
    }
  }

  return { matches, truncated }
}

export const searchFilesHandler: ToolHandler = async (args, ctx) => {
  const pattern = argString(args.pattern, 'pattern')
  const cwd =
    typeof args.cwd === 'string' && args.cwd.length > 0
      ? path.resolve(ctx.cwd, args.cwd)
      : ctx.cwd
  const max = argNumber(args.max_results, DEFAULT_MAX)
  const content = argBool(args.content, false)

  if (content) {
    const rg = await ripgrepContentSearch(pattern, cwd, max, ctx)
    if (rg.ok) {
      return { matches: rg.matches, truncated: rg.truncated }
    }
    return await fallbackContentSearch(pattern, cwd, max, ctx)
  }

  return await pathSearch(pattern, cwd, max, ctx)
}
