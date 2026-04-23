// workspaceContext — snapshot of the operator's local workspace, used as
// the keystone payload for the "seamless local" agent experience.
//
// Everything here is a *local detector*: no network, no relay. The
// returned `WorkspaceContext` is what the CLI advertises to the relay
// via a `desktop.workspace` envelope on connect, and what `hermes-relay
// workspace` prints for operator debugging. Failures are *always*
// non-fatal — if git isn't installed or `git rev-parse` fails, we just
// omit the optional fields. The agent should treat every field below
// `version`/`cwd`/`hostname`/`platform`/`arch` as best-effort.
//
// Budget: the whole detector must complete in ≤2s wall-clock.

import { exec } from 'node:child_process'
import { hostname } from 'node:os'
import { basename } from 'node:path'

export interface WorkspaceContext {
  version: 1
  cwd: string
  git_root?: string
  git_branch?: string
  git_status_summary?: {
    staged: number
    modified: number
    untracked: number
    ahead?: number
    behind?: number
  }
  /** basename of git_root — convenient for "what repo am I in?" LLM prompts
   * without the agent having to parse a full path. Absent iff git_root is. */
  repo_name?: string
  hostname: string
  platform: NodeJS.Platform
  arch: string
  /** basename of `$SHELL` on unix, basename of `%COMSPEC%` on Windows. */
  active_shell?: string
}

/** Total budget for *all combined* git shell-outs. On a slow disk or in a
 * very large repo `git status` can take a few hundred ms, so 2 s is
 * generous but still prevents a hung git hook (e.g. a GPG prompt that
 * the terminal swallowed) from stalling the CLI startup. */
const GIT_BUDGET_MS = 2000

interface ExecResult {
  stdout: string
  stderr: string
  code: number
}

/** Run a command with a shared AbortSignal so the total git budget applies
 * across all calls, not per-call. Any failure (non-zero, timeout, ENOENT)
 * returns `{code: -1}` and lets the caller decide to omit the field. */
function runOnce(cmd: string, args: string[], signal: AbortSignal): Promise<ExecResult> {
  return new Promise((resolve) => {
    // Using `exec` because we want the shell-resolved `git` binary and
    // are not passing user input — the args array is literal. We pipe
    // through a quote-safe join so the shell sees one token per arg.
    const quoted = [cmd, ...args].map((a) => {
      // Very conservative: if it has no shell-specials, leave it bare.
      if (/^[A-Za-z0-9._:/=\\@+-]+$/.test(a)) return a
      return `"${a.replace(/"/g, '\\"')}"`
    }).join(' ')

    const child = exec(quoted, { signal, windowsHide: true, timeout: GIT_BUDGET_MS }, (err, stdout, stderr) => {
      if (err) {
        resolve({ stdout: stdout?.toString() ?? '', stderr: stderr?.toString() ?? '', code: -1 })
        return
      }
      resolve({
        stdout: stdout?.toString() ?? '',
        stderr: stderr?.toString() ?? '',
        code: 0
      })
    })

    // Defensive: if the signal fires after spawn we still need the
    // child to die. `exec`'s signal handling should do this, but older
    // Node versions were flaky here, and `child.kill()` is cheap.
    signal.addEventListener('abort', () => {
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
    }, { once: true })
  })
}

function parsePorcelainBranch(output: string): {
  branch?: string
  staged: number
  modified: number
  untracked: number
  ahead?: number
  behind?: number
} {
  // `git status --porcelain=v1 --branch` emits:
  //   ## main...origin/main [ahead 1, behind 2]
  //   M  staged-file.txt
  //    M modified-file.txt
  //   ?? untracked-file.txt
  //
  // porcelain=v1 column layout: index status (col 0), worktree status
  // (col 1), space, path. We count:
  //   staged     → col 0 is not ' ' and not '?'
  //   modified   → col 1 is not ' ' and line isn't '??'
  //   untracked  → starts with '??'
  let branch: string | undefined
  let ahead: number | undefined
  let behind: number | undefined
  let staged = 0
  let modified = 0
  let untracked = 0

  const lines = output.split(/\r?\n/)
  for (const line of lines) {
    if (!line) continue

    if (line.startsWith('##')) {
      // ## branch...origin/branch [ahead N, behind M]
      const header = line.slice(2).trim()
      const bracketIdx = header.indexOf('[')
      const bare = bracketIdx >= 0 ? header.slice(0, bracketIdx).trim() : header
      const dotsIdx = bare.indexOf('...')
      branch = (dotsIdx >= 0 ? bare.slice(0, dotsIdx) : bare).trim() || undefined
      // Detached HEAD prints "HEAD (no branch)"
      if (branch === 'HEAD (no branch)') {
        branch = 'HEAD'
      }
      if (bracketIdx >= 0) {
        const bracket = header.slice(bracketIdx + 1, header.indexOf(']', bracketIdx))
        const aheadMatch = /ahead (\d+)/.exec(bracket)
        const behindMatch = /behind (\d+)/.exec(bracket)
        if (aheadMatch) ahead = Number(aheadMatch[1])
        if (behindMatch) behind = Number(behindMatch[1])
      }
      continue
    }

    if (line.startsWith('??')) {
      untracked += 1
      continue
    }

    // Regular porcelain line: first 2 chars are status codes.
    if (line.length >= 2) {
      const indexCh = line[0]
      const worktreeCh = line[1]
      if (indexCh && indexCh !== ' ') staged += 1
      if (worktreeCh && worktreeCh !== ' ') modified += 1
    }
  }

  const result: {
    branch?: string
    staged: number
    modified: number
    untracked: number
    ahead?: number
    behind?: number
  } = { staged, modified, untracked }
  if (branch !== undefined) result.branch = branch
  if (ahead !== undefined) result.ahead = ahead
  if (behind !== undefined) result.behind = behind
  return result
}

function detectActiveShell(): string | undefined {
  if (process.platform === 'win32') {
    const comspec = process.env.COMSPEC
    if (comspec) {
      return basename(comspec).toLowerCase()
    }
    // PowerShell invocations sometimes set PSModulePath but not SHELL;
    // no single reliable env var tells us "you're in pwsh". Skip.
    return undefined
  }
  const shell = process.env.SHELL
  if (shell) {
    return basename(shell)
  }
  return undefined
}

export async function detectWorkspaceContext(): Promise<WorkspaceContext> {
  const cwd = process.cwd()
  const ctx: WorkspaceContext = {
    version: 1,
    cwd,
    hostname: hostname(),
    platform: process.platform,
    arch: process.arch
  }
  const shell = detectActiveShell()
  if (shell) {
    ctx.active_shell = shell
  }

  // Shared abort controller enforces the 2s total budget across all git
  // calls. We run them sequentially because later calls (branch, status)
  // only make sense if the first (rev-parse --show-toplevel) succeeds.
  const controller = new AbortController()
  const budgetTimer = setTimeout(() => controller.abort(), GIT_BUDGET_MS)

  try {
    const rootResult = await runOnce('git', ['rev-parse', '--show-toplevel'], controller.signal)
    if (rootResult.code !== 0) {
      return ctx
    }
    const gitRoot = rootResult.stdout.trim()
    if (!gitRoot) {
      return ctx
    }
    ctx.git_root = gitRoot
    ctx.repo_name = basename(gitRoot)

    if (controller.signal.aborted) return ctx

    const branchResult = await runOnce('git', ['rev-parse', '--abbrev-ref', 'HEAD'], controller.signal)
    if (branchResult.code === 0) {
      const branch = branchResult.stdout.trim()
      if (branch) {
        ctx.git_branch = branch
      }
    }

    if (controller.signal.aborted) return ctx

    const statusResult = await runOnce(
      'git',
      ['status', '--porcelain=v1', '--branch'],
      controller.signal
    )
    if (statusResult.code === 0) {
      const parsed = parsePorcelainBranch(statusResult.stdout)
      const summary: WorkspaceContext['git_status_summary'] = {
        staged: parsed.staged,
        modified: parsed.modified,
        untracked: parsed.untracked
      }
      if (parsed.ahead !== undefined) summary.ahead = parsed.ahead
      if (parsed.behind !== undefined) summary.behind = parsed.behind
      ctx.git_status_summary = summary
      // Prefer the status-parsed branch only if we didn't already find one —
      // `abbrev-ref` is the canonical source, porcelain is a fallback for
      // detached-HEAD clarity.
      if (!ctx.git_branch && parsed.branch) {
        ctx.git_branch = parsed.branch
      }
    }
  } catch {
    // Any unexpected throw is non-fatal — the partial ctx we have is valid.
  } finally {
    clearTimeout(budgetTimer)
  }

  return ctx
}
