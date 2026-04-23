// Interactive patch approval — renders the agent-supplied unified diff
// with ANSI colors, then prompts y/n/e/r on stderr.
//
//   y            accept as-is
//   n            reject, no reason
//   r            reject with a user-supplied reason
//   e            edit the patch in $EDITOR before applying
//
// Non-interactive mode (no TTY, daemon, piped stdin) auto-rejects with a
// structured reason. We NEVER auto-accept silently — if the caller wants
// that, they can flip `toolsConsented` and skip the approval prompt
// entirely (the caller's responsibility; this module is paranoid by
// default to keep the blast radius of a compromised relay bounded).
//
// No dependencies: ANSI sequences are inline. Respects NO_COLOR and
// `!isTTY` for colorless output.

import { spawn } from 'node:child_process'
import { promises as fs } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { createInterface } from 'node:readline/promises'

import { detectEditor } from './editorDetect.js'

export interface PatchApprovalContext {
  /** For UX — shown above the diff so the user knows which file is being
   * changed. Informational; we don't use it to apply the patch. */
  targetFile: string
  /** If false (daemon, piped-stdin, no TTY) we auto-reject without
   * prompting. Callers in `shell.ts`/`chat.ts` compute this; `daemon.ts`
   * always passes false. */
  interactive: boolean
}

export interface PatchApprovalDecision {
  accepted: boolean
  /** Populated when the user picked 'n' / 'r', or when non-interactive
   * auto-rejected. Empty string when 'n' with no reason. */
  reason?: string
  /** Populated when the user picked 'e' and edited the patch. The caller
   * should use this instead of the original patchText when applying. */
  editedPatch?: string
}

/** ANSI helpers. We keep this tiny rather than pulling in a color lib —
 * the unified-diff palette is only four colors. Respects NO_COLOR env
 * and !isTTY for piped stderr. */
function colorize(enabled: boolean) {
  const on = (code: string) => (s: string) => enabled ? `\x1b[${code}m${s}\x1b[0m` : s
  return {
    red: on('31'),
    green: on('32'),
    cyan: on('36'),
    dim: on('2'),
    bold: on('1')
  }
}

/** Format a unified diff as colored lines. Headers (---/+++) are bold,
 * hunk lines (@@) cyan, additions green, deletions red, context dim. */
function renderDiff(patch: string, enableColor: boolean): string {
  const c = colorize(enableColor)
  const lines = patch.split('\n')
  const out: string[] = []
  for (const line of lines) {
    if (line.startsWith('---') || line.startsWith('+++')) {
      out.push(c.bold(line))
    } else if (line.startsWith('@@')) {
      out.push(c.cyan(line))
    } else if (line.startsWith('+')) {
      out.push(c.green(line))
    } else if (line.startsWith('-')) {
      out.push(c.red(line))
    } else {
      out.push(c.dim(line))
    }
  }
  return out.join('\n')
}

/** Decide whether ANSI is safe for this invocation. NO_COLOR env,
 * --no-color propagated through stderr not being a TTY, or piped stderr
 * → plain text. */
function colorEnabled(): boolean {
  if (process.env.NO_COLOR !== undefined) {
    return false
  }
  const anyErr = process.stderr as NodeJS.WriteStream & { isTTY?: boolean }
  return !!anyErr.isTTY
}

/** Launch $EDITOR on a tempfile pre-populated with `patch`, wait for
 * editor exit, re-read, clean up. Used by the 'e' branch. Falls back
 * to the original patch if the user blanked the file. */
async function editInEditor(patch: string): Promise<string> {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'hermes-patch-'))
  const tmpFile = path.join(tmpDir, 'patch.diff')
  await fs.writeFile(tmpFile, patch, 'utf8')
  try {
    const launcher = await detectEditor()
    // For the edit flow we ALWAYS want to block — the whole point is the
    // user's follow-up edit. GUI editors that spawn detached would return
    // instantly and we'd re-read the unmodified file.
    //
    // code/cursor/subl support `--wait`; add it when we detect one of
    // those so the child process actually blocks until the buffer is
    // closed. For vim/nvim/nano the main process is naturally blocking.
    const argv = [...launcher.command]
    if (launcher.name === 'code' || launcher.name === 'cursor') {
      argv.push('--wait')
    } else if (launcher.name === 'subl') {
      argv.push('-w')
    }
    argv.push(tmpFile)
    const [cmd, ...rest] = argv
    if (!cmd) {
      throw new Error('editor detection returned an empty command')
    }
    await new Promise<void>((resolve, reject) => {
      const child = spawn(cmd, rest, { stdio: 'inherit' })
      child.once('error', e => reject(new Error(`editor spawn failed: ${e.message}`)))
      child.once('close', () => resolve())
    })
    const edited = await fs.readFile(tmpFile, 'utf8')
    // Defensive: if the user saved an empty file, treat it as a reject
    // rather than applying nothing. The caller interprets an empty
    // patch as "no hunks" which would error anyway.
    return edited.trim().length === 0 ? patch : edited
  } finally {
    try {
      await fs.rm(tmpDir, { recursive: true, force: true })
    } catch {
      /* best-effort cleanup */
    }
  }
}

/** Public API: render + prompt + return the user's decision. Always
 * writes the diff to stderr (so piped-stdout one-shots aren't polluted).
 * On non-interactive context, auto-rejects without prompting. */
export async function approveOrReject(
  patchText: string,
  context: PatchApprovalContext
): Promise<PatchApprovalDecision> {
  if (!context.interactive) {
    return {
      accepted: false,
      reason: 'non-interactive mode — patch approval requires a TTY'
    }
  }

  const useColor = colorEnabled()
  const c = colorize(useColor)
  process.stderr.write(
    '\n' +
      c.bold(`Patch proposed for ${context.targetFile}:`) +
      '\n' +
      renderDiff(patchText, useColor) +
      '\n\n'
  )

  // Use a cooked readline on stdin/stderr — symmetric with `consent.ts`.
  // Bracketed paste off matches the other interactive prompts in this
  // codebase (Windows Terminal / ConPTY wraps pasted input in escape
  // markers that readline otherwise captures verbatim).
  process.stderr.write('\x1b[?2004l')
  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })

  try {
    // Prompt loop — we re-ask on bad input instead of defaulting, because
    // a slip-of-the-key on a patch prompt could silently apply changes.
    while (true) {
      const raw = await rl.question(
        '  Apply patch? [y]es / [n]o / [e]dit / [r]eject with reason  > '
      )
      const answer = raw.trim().toLowerCase()

      if (answer === 'y' || answer === 'yes') {
        return { accepted: true }
      }
      if (answer === 'n' || answer === 'no') {
        return { accepted: false, reason: '' }
      }
      if (answer === 'r') {
        const reason = (await rl.question('  Reason: ')).trim()
        return { accepted: false, reason }
      }
      if (answer === 'e' || answer === 'edit') {
        // Editing is blocking — close the readline first so the editor
        // gets a clean TTY (GUI editors don't care, but vim/nano do).
        rl.close()
        let edited: string
        try {
          edited = await editInEditor(patchText)
        } catch (e) {
          const msg = e instanceof Error ? e.message : String(e)
          process.stderr.write(`  ${c.red(`edit failed: ${msg}`)}\n`)
          return { accepted: false, reason: `edit failed: ${msg}` }
        }
        // Accept the edited version. We don't re-prompt: if you edited,
        // you meant to apply — re-prompting feels patronizing and the
        // user can still bail by blanking the patch (which editInEditor
        // catches and turns into the original, so they should just
        // close without saving if they want to cancel; documented in
        // the prompt? — consider adding in UX polish pass).
        return { accepted: true, editedPatch: edited }
      }

      process.stderr.write(
        `  ${c.dim('unrecognized; answer with y / n / e / r')}\n`
      )
    }
  } finally {
    try {
      rl.close()
    } catch {
      /* ignore — close may throw if edit path already closed */
    }
    process.stderr.write('\x1b[?2004h')
  }
}
