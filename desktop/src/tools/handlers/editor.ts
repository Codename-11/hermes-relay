// desktop_open_in_editor — launch the user's editor at a file[:line[:col]].
//
// args: { path: string, line?: number, col?: number, wait?: boolean }
// returns: {
//   launcher: string,       // 'code' | 'cursor' | 'subl' | ... | 'generic'
//   cwd: string,
//   resolved_path: string,  // absolute
//   pid: number | null,     // null for detached-no-pid edge cases
//   waited: boolean         // true iff wait:true was honored (editor exited)
// }
//
// Consent model: this handler is only wired into the router when the
// session's `toolsConsented` flag is true (same gate as every other
// desktop_* handler). No additional prompt here — if we're running, the
// user has already agreed to let the agent touch the machine.
//
// Default is fire-and-forget: we spawn detached, unref the child, and
// return the pid. The editor process keeps running after the CLI exits.
// With `wait: true` we block until the editor exits and surface the exit
// code through the `waited` flag (errors still throw).

import { spawn } from 'node:child_process'
import { promises as fs } from 'node:fs'
import * as path from 'node:path'

import type { ToolContext, ToolHandler } from '../router.js'
import { detectEditor, type EditorLauncher } from '../editorDetect.js'

function argString(v: unknown, name: string): string {
  if (typeof v !== 'string' || v.length === 0) {
    throw new Error(`missing or invalid "${name}" argument`)
  }
  return v
}

function argOptionalPositiveInt(v: unknown, name: string): number | null {
  if (v === undefined || v === null) {
    return null
  }
  if (typeof v !== 'number' || !Number.isFinite(v) || v < 1) {
    throw new Error(`"${name}" must be a positive integer`)
  }
  return Math.floor(v)
}

function argBool(v: unknown, fallback: boolean): boolean {
  return typeof v === 'boolean' ? v : fallback
}

/** Build the final argv to spawn given a detected launcher and a target
 * file. Handles the three arg-shape families we care about:
 *   - GUI editors (code/cursor/subl): `-g <file>:<line>:<col>`
 *   - vim family: `+<line> <file>`
 *   - everything else: `<file>` (no line/col)
 * `launcher.command` already contains the preamble (e.g. `code -g`). */
function buildArgv(launcher: EditorLauncher, absPath: string, line: number | null, col: number | null): string[] {
  const argv = [...launcher.command]
  if (launcher.supportsLineCol && line !== null) {
    // code/cursor/subl: "file:line:col" — col defaults to 1 if caller
    // passed line only. `-g` was already injected by editorDetect.
    argv.push(`${absPath}:${line}:${col ?? 1}`)
    return argv
  }
  if ((launcher.name === 'vim' || launcher.name === 'nvim') && line !== null) {
    // vim: `+<line> <file>`. Column not supported; silently dropped
    // (caller can inspect launcher.supportsLineCol ahead of time if they
    // need to know).
    argv.push(`+${line}`, absPath)
    return argv
  }
  argv.push(absPath)
  return argv
}

export const openInEditorHandler: ToolHandler = async (args, ctx: ToolContext) => {
  const rawPath = argString(args.path, 'path')
  const line = argOptionalPositiveInt(args.line, 'line')
  const col = argOptionalPositiveInt(args.col, 'col')
  const wait = argBool(args.wait, false)

  // Path resolution mirrors `fs.ts`: absolute wins, relative is joined
  // onto ctx.cwd (router default = process.cwd()).
  const resolved = path.isAbsolute(rawPath) ? rawPath : path.resolve(ctx.cwd, rawPath)

  // The agent has probably been told a real file exists — if it doesn't,
  // fail loud instead of creating a blank buffer in the editor for a
  // path typo. Directories are OK (editors handle them).
  try {
    await fs.access(resolved)
  } catch {
    throw new Error(`path does not exist: ${resolved}`)
  }

  const launcher = await detectEditor()
  const [cmd, ...rest] = buildArgv(launcher, resolved, line, col)
  if (!cmd) {
    // Shouldn't happen — detectEditor always returns a non-empty command.
    throw new Error('editor detection returned an empty command')
  }

  // `detached: true` + `child.unref()` is the POSIX-correct way to let the
  // child outlive us. On Windows the same incantation works but `detached`
  // also means "create a new process group", which is what we want anyway.
  // `stdio: 'ignore'` severs our handles so the editor doesn't interleave
  // output with the CLI's.
  const child = spawn(cmd, rest, {
    cwd: ctx.cwd,
    env: process.env,
    detached: !wait,
    stdio: 'ignore',
    windowsHide: false
  })

  if (!wait) {
    child.unref()
    // Best-effort: if spawn immediately errored (e.g. ENOENT), surface it.
    // In the happy path this listener never fires after unref().
    await new Promise<void>((resolve, reject) => {
      let settled = false
      child.once('error', err => {
        if (settled) {
          return
        }
        settled = true
        reject(new Error(`editor spawn failed: ${err.message}`))
      })
      // Spawn is synchronous enough that we can settle on the next tick.
      setImmediate(() => {
        if (!settled) {
          settled = true
          resolve()
        }
      })
    })
    return {
      launcher: launcher.name,
      cwd: ctx.cwd,
      resolved_path: resolved,
      pid: typeof child.pid === 'number' ? child.pid : null,
      waited: false
    }
  }

  // wait:true — block until editor exits. Honor the router's abortSignal
  // (30s cap) by killing the child if the handler is torn down.
  const onAbort = () => {
    try {
      child.kill()
    } catch {
      /* ignore */
    }
  }
  ctx.abortSignal.addEventListener('abort', onAbort, { once: true })
  try {
    await new Promise<void>((resolve, reject) => {
      child.once('error', err => reject(new Error(`editor spawn failed: ${err.message}`)))
      child.once('close', () => resolve())
    })
  } finally {
    ctx.abortSignal.removeEventListener('abort', onAbort)
  }

  return {
    launcher: launcher.name,
    cwd: ctx.cwd,
    resolved_path: resolved,
    pid: typeof child.pid === 'number' ? child.pid : null,
    waited: true
  }
}
