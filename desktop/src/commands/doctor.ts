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
import { listSessions } from '../remoteSessions.js'

const __dirname = dirname(fileURLToPath(import.meta.url))

function readVersion(): string {
  // Same trick as cli.ts — dist/commands/doctor.js → dist → pkg root.
  try {
    const pkgPath = join(__dirname, '..', '..', 'package.json')
    const pkg = JSON.parse(readFileSync(pkgPath, 'utf8')) as { version?: string }
    return pkg.version ?? '0.0.0'
  } catch {
    return '0.0.0'
  }
}

function resolveBinaryPath(): string {
  // argv[1] is the invoked script (the bin shim under normal `hermes-relay`
  // usage, or dist/cli.js under `node dist/cli.js`, or the tsx entry under
  // `tsx src/cli.ts`). It's what the user actually ran, so it's the right
  // answer for "where is my binary?"
  return process.argv[1] ?? fileURLToPath(import.meta.url)
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
    daemon_note: daemon.note
  }
}

function renderHuman(report: DoctorReport): string {
  const lines: string[] = []
  const hints: string[] = []

  lines.push('hermes-relay doctor')
  lines.push(`  version:       ${report.version}`)
  lines.push(`  binary:        ${report.binary_path}`)
  lines.push(`  node:          ${report.node_version} (${report.platform}/${report.arch})`)

  if (report.on_path) {
    lines.push(`  on PATH:       yes`)
  } else {
    lines.push(`!! on PATH:       no  (install_dir: ${report.install_dir})`)
    hints.push(
      `add ${report.install_dir} to your PATH, or re-run the installer from desktop/scripts/`
    )
  }

  if (report.sessions_file_exists) {
    const sz = report.sessions_file_size !== null ? `  (${humanSize(report.sessions_file_size)})` : ''
    lines.push(`  sessions file: ${report.sessions_file}${sz}`)
  } else {
    lines.push(`!! sessions file: ${report.sessions_file}  (missing)`)
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

  if (hints.length > 0) {
    lines.push('')
    for (const hint of hints) {
      lines.push(`hint: ${hint}`)
    }
  }

  return lines.join('\n') + '\n'
}

export async function doctorCommand(args: ParsedArgs): Promise<number> {
  const report = await gather()

  if (args.flags.json) {
    process.stdout.write(JSON.stringify(report, null, 2) + '\n')
    return 0
  }

  process.stdout.write(renderHuman(report))
  return 0
}

export default doctorCommand
