// Filesystem tool handlers for the `desktop` relay channel.
//
//   desktop_read_file  — bounded file read, truncates at max_bytes
//   desktop_write_file — optionally mkdir -p the parent, writes content
//   desktop_patch      — apply a unified diff; fails loud rather than fuzz
//
// All handlers resolve paths against `ctx.cwd`. Absolute paths pass
// through unchanged. ENOENT / EACCES surface verbatim in the error
// string so the remote agent can branch on them.

import { promises as fs } from 'node:fs'
import * as path from 'node:path'

import type { ToolContext, ToolHandler } from '../router.js'

const DEFAULT_MAX_BYTES = 1_000_000

function resolvePath(ctx: ToolContext, p: unknown): string {
  if (typeof p !== 'string' || p.length === 0) {
    throw new Error('missing or invalid "path" argument')
  }
  return path.resolve(ctx.cwd, p)
}

function argNumber(v: unknown, fallback: number): number {
  if (typeof v === 'number' && Number.isFinite(v) && v >= 0) {
    return Math.floor(v)
  }
  return fallback
}

function argString(v: unknown, name: string): string {
  if (typeof v !== 'string') {
    throw new Error(`missing or invalid "${name}" argument`)
  }
  return v
}

function argBool(v: unknown, fallback: boolean): boolean {
  if (typeof v === 'boolean') {
    return v
  }
  return fallback
}

/** desktop_read_file
 *   args: { path: string, max_bytes?: number (default 1_000_000) }
 *   returns: string — file contents, possibly truncated
 * Truncation appends a marker line so the agent can detect it. */
export const readFileHandler: ToolHandler = async (args, ctx) => {
  const abs = resolvePath(ctx, args.path)
  const maxBytes = argNumber(args.max_bytes, DEFAULT_MAX_BYTES)

  // Stat first so we can detect oversize without reading the whole file
  // into memory. For normal files readFile is fine, but a multi-GB log
  // would OOM if we blindly consumed it.
  const stat = await fs.stat(abs)
  if (!stat.isFile()) {
    throw new Error(`not a regular file: ${abs}`)
  }

  if (stat.size <= maxBytes) {
    const buf = await fs.readFile(abs)
    return buf.toString('utf8')
  }

  // Oversize: read only the first maxBytes + truncation marker.
  const handle = await fs.open(abs, 'r')
  try {
    const buf = Buffer.alloc(maxBytes)
    await handle.read(buf, 0, maxBytes, 0)
    return `${buf.toString('utf8')}\n[... truncated at ${maxBytes} bytes]`
  } finally {
    await handle.close()
  }
}

/** desktop_write_file
 *   args: { path: string, content: string, create_dirs?: boolean }
 *   returns: { ok: true, bytes_written: number, path: string } */
export const writeFileHandler: ToolHandler = async (args, ctx) => {
  const abs = resolvePath(ctx, args.path)
  const content = argString(args.content, 'content')
  const createDirs = argBool(args.create_dirs, false)

  if (createDirs) {
    await fs.mkdir(path.dirname(abs), { recursive: true })
  }

  await fs.writeFile(abs, content, 'utf8')
  const bytes = Buffer.byteLength(content, 'utf8')

  return { ok: true, bytes_written: bytes, path: abs }
}

// -- Patch application --------------------------------------------------
// We implement a deliberately strict unified-diff applier. No fuzz, no
// context-shifting — if the hunk's pre-image doesn't match the file at
// the claimed line range, we refuse and the agent gets a clear error.
// Better to punt back to the model than silently corrupt a file.

interface Hunk {
  oldStart: number // 1-based; 0 if file creation
  oldLen: number
  // newStart / newLen aren't strictly needed for application — we use the
  // hunk body to derive them — but we parse them so a malformed header
  // surfaces as a parse error rather than a weird mid-apply failure.
  newStart: number
  newLen: number
  lines: string[] // each line starts with ' ', '+', or '-'
}

const HUNK_HEADER = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/

function parseUnifiedDiff(patch: string): Hunk[] {
  const lines = patch.split('\n')
  const hunks: Hunk[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]!
    const m = HUNK_HEADER.exec(line)
    if (!m) {
      i++
      continue
    }
    const oldStart = parseInt(m[1]!, 10)
    const oldLen = m[2] ? parseInt(m[2], 10) : 1
    const newStart = parseInt(m[3]!, 10)
    const newLen = m[4] ? parseInt(m[4], 10) : 1

    i++
    const body: string[] = []
    while (i < lines.length) {
      const l = lines[i]!
      if (HUNK_HEADER.test(l)) {
        break
      }
      // Unified-diff file headers (---, +++) shouldn't appear inside a
      // hunk. If we see one, assume we've fallen out of the hunk body.
      if (l.startsWith('--- ') || l.startsWith('+++ ')) {
        break
      }
      if (l.length === 0) {
        // A bare empty line inside a hunk represents a context line
        // containing nothing — some diff generators emit it without the
        // leading space. Normalize.
        body.push(' ')
        i++
        continue
      }
      const first = l[0]
      if (first !== ' ' && first !== '+' && first !== '-' && first !== '\\') {
        // Outside the recognized prefix set → not a diff line; end of hunk.
        break
      }
      if (first === '\\') {
        // "\ No newline at end of file" — ignore, we handle trailing
        // newlines at file-write time.
        i++
        continue
      }
      body.push(l)
      i++
    }

    hunks.push({ oldStart, oldLen, newStart, newLen, lines: body })
  }

  if (hunks.length === 0) {
    throw new Error('no hunks found in patch')
  }
  return hunks
}

function applyHunk(srcLines: string[], hunk: Hunk): string[] {
  // Build pre-image (context + deletions) and post-image (context + additions)
  // from the hunk body.
  const pre: string[] = []
  const post: string[] = []
  for (const l of hunk.lines) {
    const tag = l[0]
    const content = l.slice(1)
    if (tag === ' ') {
      pre.push(content)
      post.push(content)
    } else if (tag === '-') {
      pre.push(content)
    } else if (tag === '+') {
      post.push(content)
    }
  }

  // Locate pre-image in srcLines at the expected 1-based oldStart.
  const startIdx = hunk.oldStart - 1
  if (startIdx < 0 || startIdx + pre.length > srcLines.length) {
    throw new Error(
      `hunk @@ -${hunk.oldStart},${hunk.oldLen} @@ out of range (file has ${srcLines.length} lines)`
    )
  }
  for (let j = 0; j < pre.length; j++) {
    if (srcLines[startIdx + j] !== pre[j]) {
      throw new Error(
        `hunk @@ -${hunk.oldStart},${hunk.oldLen} @@ context mismatch at line ${startIdx + j + 1}`
      )
    }
  }

  return [
    ...srcLines.slice(0, startIdx),
    ...post,
    ...srcLines.slice(startIdx + pre.length)
  ]
}

/** desktop_patch
 *   args: { path: string, patch: string }
 *   returns: { ok: true, hunks_applied: number, path: string }
 * Strict: any context mismatch aborts the whole patch — no partial writes. */
export const patchHandler: ToolHandler = async (args, ctx) => {
  const abs = resolvePath(ctx, args.path)
  const patchText = argString(args.patch, 'patch')

  let hunks: Hunk[]
  try {
    hunks = parseUnifiedDiff(patchText)
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    throw new Error(`patch did not apply cleanly: ${msg}`)
  }

  const original = await fs.readFile(abs, 'utf8')
  // Preserve the original trailing-newline state so we can re-apply it
  // after splitting. split('\n') on "a\nb\n" → ["a","b",""] which is
  // fine — we'll filter the trailing '' back out on join if it was there.
  const hadTrailingNewline = original.endsWith('\n')
  const srcLines = original.split('\n')
  if (hadTrailingNewline) {
    // Drop the phantom empty string produced by the trailing '\n' split.
    srcLines.pop()
  }

  // Apply hunks bottom-up so earlier-hunk deletions don't shift later-hunk
  // indices. The diff format already orders them top-down in the file,
  // so reversing in-place is safe.
  let working = srcLines
  const ordered = [...hunks].sort((a, b) => b.oldStart - a.oldStart)
  for (const h of ordered) {
    try {
      working = applyHunk(working, h)
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      throw new Error(`patch did not apply cleanly: ${msg}`)
    }
  }

  const joined = working.join('\n') + (hadTrailingNewline ? '\n' : '')
  await fs.writeFile(abs, joined, 'utf8')

  return { ok: true, hunks_applied: hunks.length, path: abs }
}
