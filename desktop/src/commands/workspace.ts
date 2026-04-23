// workspace — print the local workspace context snapshot.
//
// Pure local detection; no WSS. Mirrors the `doctor` subcommand's split
// of human-readable default vs `--json` for scripting. This is the same
// payload the CLI advertises to the relay on connect via the
// `desktop.workspace` envelope, so `workspace --json | jq` is the
// canonical way to see exactly what the agent will receive.

import type { ParsedArgs } from '../cli.js'
import { detectActiveEditor, type ActiveEditorHint } from '../activeEditor.js'
import { detectWorkspaceContext, type WorkspaceContext } from '../workspaceContext.js'

function renderStatusSummary(
  summary: WorkspaceContext['git_status_summary']
): string {
  if (!summary) return '(clean — no git status data)'
  const parts = [
    `${summary.staged} staged`,
    `${summary.modified} modified`,
    `${summary.untracked} untracked`
  ]
  const tracking: string[] = []
  if (summary.ahead !== undefined) tracking.push(`${summary.ahead} ahead`)
  if (summary.behind !== undefined) tracking.push(`${summary.behind} behind`)
  const base = parts.join(', ')
  return tracking.length > 0 ? `${base} (${tracking.join(', ')})` : base
}

function renderEditorLine(hint: ActiveEditorHint): string {
  if (hint.source === 'none') {
    return '(none detected)'
  }
  if (hint.source === 'tmux') {
    const cmd = hint.command ? ` running ${hint.command}` : ''
    const cwd = hint.cwd ? ` in ${hint.cwd}` : ''
    const editor = hint.editor ? `${hint.editor} ` : ''
    return `${editor}(tmux${cwd}${cmd})`.trim()
  }
  if (hint.source === 'vscode_env') {
    const ed = hint.editor ?? 'code'
    return `${ed} (vscode_env detected)`
  }
  return '(none detected)'
}

function renderHuman(ctx: WorkspaceContext, editor: ActiveEditorHint): string {
  const lines: string[] = []
  lines.push(`cwd:       ${ctx.cwd}`)
  lines.push(`repo:      ${ctx.repo_name ?? '(not a git repo)'}`)
  lines.push(`branch:    ${ctx.git_branch ?? '(n/a)'}`)
  lines.push(`status:    ${renderStatusSummary(ctx.git_status_summary)}`)
  lines.push(`host:      ${ctx.hostname}`)
  lines.push(`platform:  ${ctx.platform}/${ctx.arch}`)
  lines.push(`shell:     ${ctx.active_shell ?? '(unknown)'}`)
  lines.push(`editor:    ${renderEditorLine(editor)}`)
  return lines.join('\n') + '\n'
}

export async function workspaceCommand(args: ParsedArgs): Promise<number> {
  const ctx = await detectWorkspaceContext()
  const editor = await detectActiveEditor()

  if (args.flags.json) {
    const payload = {
      workspace: ctx,
      active_editor: editor
    }
    process.stdout.write(JSON.stringify(payload, null, 2) + '\n')
    return 0
  }

  process.stdout.write(renderHuman(ctx, editor))
  return 0
}

export default workspaceCommand
