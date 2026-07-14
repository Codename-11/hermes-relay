// doctor — local diagnostic report. Zero network, zero deps.
//
// Designed for two audiences:
//   1. Users sanity-checking an install ("is hermes-relay actually on my
//      PATH? did my session store survive the upgrade?").
//   2. Support triage ("paste `hermes-relay doctor --json` so we can see
//      what you've got") — hence the --json mode with redacted tokens.
//
// Everything here is a local read: package.json for version, process.*
// for runtime, fs.stat for files, and a plain listSessions() for stored
// pairings. No WSS, no HTTP, no daemon probe beyond "is the service
// file on disk?" — doctor should complete in milliseconds regardless of
// network state.

import { readFileSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { delimiter, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { humanExpiry } from '../banner.js'
import type { ParsedArgs } from '../cli.js'
import { theme as makeTheme, type Theme } from '../lib/theme.js'
import { printUsage, type UsageSpec } from '../lib/usage.js'
import { listSessions } from '../remoteSessions.js'
import { VERSION } from '../version.js'
import { detectWorkspaceContext, type WorkspaceContext } from '../workspaceContext.js'

const __dirname = dirname(fileURLToPath(import.meta.url))

const DOCTOR_USAGE: UsageSpec = {
  name: 'doctor',
  summary: 'local diagnostic report: version, binary path, PATH, stored sessions, daemon, workspace',
  usage: ['doctor [--json]'],
  flags: [{ flag: '--json', desc: 'Machine-readable report (safe to paste — tokens omitted)' }],
  examples: ['hermes-relay doctor', 'hermes-relay doctor --json']
}

function readVersion(): string {
  if (VERSION) {
    return VERSION
  }
  // Same trick as cli.ts — dist/commands/doctor.js → dist → pkg root.
  try {
    const pkgPath = join(__dirname, '..', '..', 'package.json')
    const pkg = JSON.parse(readFileSync(pkgPath, 'utf8')) as { version?: string }
    return pkg.version ?? '0.0.0'
  } catch {
    return '0.0.0'
  }
}

export function resolveBinaryPathForRuntime(
  execPath: string,
  invokedPath: string | undefined,
  fallbackPath = fileURLToPath(import.meta.url)
): string {
  const executable = execPath.split(/[\\/]/).at(-1)?.toLowerCase()
  const isScriptHost = executable === 'node' || executable === 'node.exe' ||
    executable === 'bun' || executable === 'bun.exe'

  // Node/tsx/Bun script runs should identify the invoked source or dist entry.
  // Bun-compiled binaries expose a virtual argv[1] such as B:/~BUN/root/..., but
  // process.execPath remains the physical executable the user actually ran.
  if (isScriptHost) {
    return invokedPath ?? fallbackPath
  }
  return execPath || invokedPath || fallbackPath
}

function resolveBinaryPath(): string {
  return resolveBinaryPathForRuntime(process.execPath, process.argv[1])
}

function isOnPath(installDir: string): boolean {
  const path = process.env.PATH ?? ''
  if (!path) {
    return false
  }
  const entries = path.split(delimiter).map((p) => p.trim()).filter(Boolean)
  // Case-insensitive compare on Windows, exact elsewhere. We don't try to
  // canonicalize symlinks — false negatives here are acceptable ("it's
  // working but doctor says it's not" is annoying but recoverable).
  if (process.platform === 'win32') {
    const target = installDir.toLowerCase()
    return entries.some((p) => p.toLowerCase() === target)
  }
  return entries.includes(installDir)
}

interface DaemonDetection {
  detected: boolean
  note: string | null
}

function detectDaemon(): DaemonDetection {
  // Service installers aren't shipped yet — report truthfully but have the
  // detection code ready. When the installers land we just drop the note.
  const home = homedir()
  try {
    if (process.platform === 'linux') {
      const svc = join(home, '.config', 'systemd', 'user', 'hermes-relay-daemon.service')
      try {
        statSync(svc)
        return { detected: true, note: null }
      } catch {
        return { detected: false, note: 'service installers not yet shipped' }
      }
    }
    if (process.platform === 'darwin') {
      const plist = join(home, 'Library', 'LaunchAgents', 'com.hermes.relay.daemon.plist')
      try {
        statSync(plist)
        return { detected: true, note: null }
      } catch {
        return { detected: false, note: 'service installers not yet shipped' }
      }
    }
    // Windows service installer not shipped; avoid shelling out to sc.exe.
    return { detected: false, note: 'service installers not yet shipped' }
  } catch {
    return { detected: false, note: 'service installers not yet shipped' }
  }
}

function sessionsFilePath(): string {
  return join(homedir(), '.hermes', 'remote-sessions.json')
}

function humanAge(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)}h`
  return `${Math.floor(seconds / 86_400)}d`
}

function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

interface SessionJson {
  url: string
  paired_at_iso: string
  server_version: string | null
  tools_consented: boolean
  ttl_expires_at_iso: string | null
}

interface DoctorReport {
  version: string
  binary_path: string
  node_version: string
  platform: string
  arch: string
  install_dir: string
  on_path: boolean
  sessions_file: string
  sessions_file_exists: boolean
  sessions_file_size: number | null
  sessions: SessionJson[]
  sessions_count: number
  daemon_detected: boolean
  daemon_note: string | null
  /** Local workspace context — same payload the CLI advertises over WSS
   * as `desktop.workspace`. Surfacing it here lets support triage answer
   * "which repo / branch were they in?" from a single `doctor --json`. */
  workspace: WorkspaceContext
}

async function gather(): Promise<DoctorReport> {
  const binary = resolveBinaryPath()
  const installDir = dirname(binary)
  const onPath = isOnPath(installDir)

  const sessionsFile = sessionsFilePath()
  let sessionsFileExists = false
  let sessionsFileSize: number | null = null
  try {
    const st = statSync(sessionsFile)
    sessionsFileExists = true
    sessionsFileSize = st.size
  } catch {
    /* file missing or unreadable — report as does-not-exist */
  }

  const storedSessions = await listSessions()
  const sessions: SessionJson[] = Object.entries(storedSessions).map(([url, rec]) => ({
    url,
    paired_at_iso: new Date(rec.pairedAt * 1000).toISOString(),
    server_version: rec.serverVersion,
    tools_consented: rec.toolsConsented === true,
    ttl_expires_at_iso:
      rec.ttlExpiresAt === null || rec.ttlExpiresAt === undefined
        ? null
        : new Date(rec.ttlExpiresAt * 1000).toISOString()
  }))

  const daemon = detectDaemon()
  const workspace = await detectWorkspaceContext()

  return {
    version: readVersion(),
    binary_path: binary,
    node_version: process.version,
    platform: process.platform,
    arch: process.arch,
    install_dir: installDir,
    on_path: onPath,
    sessions_file: sessionsFile,
    sessions_file_exists: sessionsFileExists,
    sessions_file_size: sessionsFileSize,
    sessions,
    sessions_count: sessions.length,
    daemon_detected: daemon.detected,
    daemon_note: daemon.note,
    workspace
  }
}

function renderHuman(report: DoctorReport, t: Theme): string {
  const lines: string[] = []
  const hints: string[] = []

  lines.push(t.bold('hermes-relay doctor'))
  lines.push(`  ${t.muted('version:      ')} ${report.version}`)
  lines.push(`  ${t.muted('binary:       ')} ${report.binary_path}`)
  lines.push(`  ${t.muted('node:         ')} ${report.node_version} (${report.platform}/${report.arch})`)

  if (report.on_path) {
    lines.push(`  ${t.muted('on PATH:      ')} ${t.ok('yes')}`)
  } else {
    lines.push(`  ${t.warnLine(`on PATH: ${t.warn('no')}  (install_dir: ${report.install_dir})`)}`)
    hints.push(
      `add ${report.install_dir} to your PATH, or re-run the installer from desktop/scripts/`
    )
  }

  if (report.sessions_file_exists) {
    const sz = report.sessions_file_size !== null ? `  (${humanSize(report.sessions_file_size)})` : ''
    lines.push(`  ${t.muted('sessions file:')} ${report.sessions_file}${sz}`)
  } else {
    lines.push(`  ${t.warnLine(`sessions file: ${report.sessions_file}  (missing)`)}`)
    hints.push('run `hermes-relay pair --remote <url>` to create it')
  }

  if (report.sessions_count === 0) {
    lines.push(`  sessions:      0 stored`)
  } else {
    lines.push(`  sessions:      ${report.sessions_count} stored`)
    const now = Math.floor(Date.now() / 1000)
    for (const s of report.sessions) {
      const pairedAtSec = Math.floor(new Date(s.paired_at_iso).getTime() / 1000)
      const age = humanAge(Math.max(0, now - pairedAtSec))
      const server = s.server_version ?? 'unknown'
      const consent = s.tools_consented ? 'tools consented' : 'tools not consented'
      let ttlPart = ''
      if (s.ttl_expires_at_iso !== null) {
        const ttlSec = Math.floor(new Date(s.ttl_expires_at_iso).getTime() / 1000)
        ttlPart = `, expires ${humanExpiry(ttlSec)}`
      } else {
        ttlPart = ', never expires'
      }
      lines.push(`    - ${s.url}  (server ${server}, paired ${age} ago, ${consent}${ttlPart})`)
    }
  }

  if (report.daemon_detected) {
    lines.push(`  daemon:        installed`)
  } else if (report.daemon_note) {
    lines.push(`  daemon:        not installed  (${report.daemon_note})`)
  } else {
    lines.push(`  daemon:        not installed`)
  }

  // Workspace block — what the CLI advertises to the relay on connect.
  // Kept compact (one line per field) to match the rest of `doctor`;
  // the full payload is available via `hermes-relay doctor --json` or
  // `hermes-relay workspace`.
  const ws = report.workspace
  lines.push(`  workspace:`)
  lines.push(`    cwd:         ${ws.cwd}`)
  if (ws.git_root) {
    lines.push(`    repo:        ${ws.repo_name ?? '(unknown)'}  (${ws.git_root})`)
    if (ws.git_branch) {
      lines.push(`    branch:      ${ws.git_branch}`)
    }
    if (ws.git_status_summary) {
      const s = ws.git_status_summary
      const tracking: string[] = []
      if (s.ahead !== undefined) tracking.push(`${s.ahead} ahead`)
      if (s.behind !== undefined) tracking.push(`${s.behind} behind`)
      const base = `${s.staged} staged, ${s.modified} modified, ${s.untracked} untracked`
      lines.push(`    status:      ${tracking.length > 0 ? `${base} (${tracking.join(', ')})` : base}`)
    }
  } else {
    lines.push(`    repo:        (not a git repo)`)
  }
  lines.push(`    host:        ${ws.hostname}`)
  if (ws.active_shell) {
    lines.push(`    shell:       ${ws.active_shell}`)
  }

  // doctor is intentionally zero-network — point at the live-relay checks for
  // anything that needs to actually talk to the server.
  lines.push('')
  lines.push(t.muted('  live relay checks: hermes-relay relay info | relay context | voice'))

  if (hints.length > 0) {
    lines.push('')
    for (const hint of hints) {
      lines.push(t.warn(`hint: ${hint}`))
    }
  }

  return lines.join('\n') + '\n'
}

export async function doctorCommand(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (args.flags.help) {
    printUsage(DOCTOR_USAGE, t)
    return 0
  }
  const report = await gather()

  if (args.flags.json) {
    process.stdout.write(JSON.stringify(report, null, 2) + '\n')
    return 0
  }

  process.stdout.write(renderHuman(report, t))
  return 0
}

export default doctorCommand
