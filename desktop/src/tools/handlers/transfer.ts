// File-transfer handlers for the `desktop` relay channel.
//
//   desktop_copy_directory — recursive copy via Node's fs.cp
//   desktop_zip            — bundle a path into a .zip archive
//   desktop_unzip          — expand an archive to a directory
//   desktop_checksum       — sha256 of a file (with size verification)
//
// Bulk model/script sync was the user's #3 ask. Today, the agent has to
// repeat desktop_write_file for every file in a directory — slow, lossy
// (no permissions, no symlinks), and error-prone. These four cover ~all
// real cases without taking on a new runtime dependency.
//
// Zip implementation: we shell out to platform tools rather than vendor
// a zip library. Windows 10+ ships `tar` (which handles .zip via -a) and
// PowerShell's `Compress-Archive`/`Expand-Archive`. POSIX has `zip`/
// `unzip`/`tar`. We probe at call time and use whichever wins — always
// preferring `tar` because it's the most uniform across platforms.

import { spawn, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { promises as fsp } from 'node:fs'
import * as path from 'node:path'

import type { ToolHandler } from '../router.js'

function argString(v: unknown, name: string): string {
  if (typeof v !== 'string' || v.length === 0) {
    throw new Error(`missing or invalid "${name}" argument`)
  }
  return v
}

function argOptionalString(v: unknown): string | null {
  if (typeof v === 'string' && v.length > 0) {
    return v
  }
  return null
}

function argBool(v: unknown, fallback: boolean): boolean {
  return typeof v === 'boolean' ? v : fallback
}

function probeBinary(name: string): boolean {
  const probe = process.platform === 'win32' ? 'where' : 'which'
  try {
    const r = spawnSync(probe, [name], { stdio: 'ignore', windowsHide: true })
    return r.status === 0
  } catch {
    return false
  }
}

function runChild(
  cmd: string,
  args: string[],
  ctx: { abortSignal: AbortSignal },
  cwd?: string
): Promise<{ stdout: string; stderr: string; code: number }> {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      cwd,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true
    })
    let stdout = ''
    let stderr = ''
    child.stdout?.on('data', (c: Buffer) => {
      stdout += c.toString('utf8')
    })
    child.stderr?.on('data', (c: Buffer) => {
      stderr += c.toString('utf8')
    })
    const onAbort = () => {
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
    }
    ctx.abortSignal.addEventListener('abort', onAbort, { once: true })
    child.on('error', e => {
      ctx.abortSignal.removeEventListener('abort', onAbort)
      reject(e)
    })
    child.on('close', code => {
      ctx.abortSignal.removeEventListener('abort', onAbort)
      resolve({ stdout, stderr, code: code ?? -1 })
    })
  })
}

// ── desktop_copy_directory ──────────────────────────────────────────────────

/** desktop_copy_directory
 *   args: { source: string, dest: string, overwrite?: boolean (default false),
 *           preserve_timestamps?: boolean (default true),
 *           dereference?: boolean (default false) }
 *   returns: { ok: true, source, dest, files_copied?, dirs_copied? }
 *
 * Recursive. Uses Node's fs.cp; doesn't shell out to xcopy/cp so behavior
 * is identical across platforms. fs.cp doesn't return a count, so we walk
 * the source quickly afterward to give the agent something to verify
 * against. */
export const copyDirectoryHandler: ToolHandler = async args => {
  const source = path.resolve(argString(args.source, 'source'))
  const dest = path.resolve(argString(args.dest, 'dest'))
  const overwrite = argBool(args.overwrite, false)
  const preserveTimestamps = argBool(args.preserve_timestamps, true)
  const dereference = argBool(args.dereference, false)

  // fs.cp does the right thing for both files and dirs when recursive:true.
  // errorOnExist:true (the inverse of overwrite) makes a duplicate-path
  // collision a hard error rather than silent stomp.
  await fsp.cp(source, dest, {
    recursive: true,
    force: overwrite,
    errorOnExist: !overwrite,
    preserveTimestamps,
    dereference
  })

  // Best-effort count — bounded walk so a giant tree doesn't dominate
  // the response time. Skip if the source is a single file.
  let filesCopied = 0
  let dirsCopied = 0
  const stat = await fsp.stat(source)
  if (stat.isDirectory()) {
    const queue: string[] = [source]
    while (queue.length > 0 && filesCopied + dirsCopied < 100_000) {
      const cur = queue.shift()!
      let entries
      try {
        entries = await fsp.readdir(cur, { withFileTypes: true })
      } catch {
        continue
      }
      for (const ent of entries) {
        const full = path.join(cur, ent.name)
        if (ent.isDirectory()) {
          dirsCopied++
          queue.push(full)
        } else if (ent.isFile()) {
          filesCopied++
        }
      }
    }
  } else {
    filesCopied = 1
  }

  return { ok: true, source, dest, files_copied: filesCopied, dirs_copied: dirsCopied }
}

// ── desktop_zip ─────────────────────────────────────────────────────────────

/** Pick the best zip command for this platform. Preference order:
 *   1. `tar` — uniform on Windows 10+, macOS, Linux. -a auto-detects format.
 *   2. `zip` — POSIX classic; available everywhere except vanilla Windows.
 *   3. `pwsh -c Compress-Archive` — Windows fallback when tar is missing.
 */
type ZipImpl = 'tar' | 'zip' | 'powershell'

function pickZipImpl(): ZipImpl {
  if (probeBinary('tar')) {
    return 'tar'
  }
  if (probeBinary('zip')) {
    return 'zip'
  }
  if (process.platform === 'win32' && (probeBinary('pwsh') || probeBinary('powershell'))) {
    return 'powershell'
  }
  throw new Error('no zip implementation found (tried tar, zip, powershell)')
}

function pickUnzipImpl(): ZipImpl {
  if (probeBinary('tar')) {
    return 'tar'
  }
  if (probeBinary('unzip')) {
    return 'zip'
  }
  if (process.platform === 'win32' && (probeBinary('pwsh') || probeBinary('powershell'))) {
    return 'powershell'
  }
  throw new Error('no unzip implementation found (tried tar, unzip, powershell)')
}

/** desktop_zip
 *   args: { source: string, dest: string, format?: 'zip' (default; only zip for now) }
 *   returns: { ok, dest, size_bytes, sha256, impl }
 *
 * The dest path is the archive file. Source can be a file or a directory;
 * directories are added recursively. */
export const zipHandler: ToolHandler = async (args, ctx) => {
  const source = path.resolve(argString(args.source, 'source'))
  const dest = path.resolve(argString(args.dest, 'dest'))
  const format = argOptionalString(args.format) ?? 'zip'
  if (format !== 'zip') {
    throw new Error(`unsupported format: ${format} (only "zip" is supported)`)
  }

  // Sanity-check source exists before spawning anything.
  await fsp.stat(source)
  await fsp.mkdir(path.dirname(dest), { recursive: true })

  const impl = pickZipImpl()
  const sourceDir = path.dirname(source)
  const sourceBase = path.basename(source)

  if (impl === 'tar') {
    // -a picks format from the file extension; -c create; -f archive path.
    // -C cd into source's dirname so the archive contains relative entries
    // (i.e. just `mydir/...`, not `/abs/path/to/mydir/...`).
    const r = await runChild(
      'tar',
      ['-a', '-c', '-f', dest, '-C', sourceDir, sourceBase],
      ctx
    )
    if (r.code !== 0) {
      throw new Error(`tar failed (exit ${r.code}): ${r.stderr.trim()}`)
    }
  } else if (impl === 'zip') {
    const r = await runChild(
      'zip',
      ['-r', '-q', dest, sourceBase],
      ctx,
      sourceDir
    )
    if (r.code !== 0) {
      throw new Error(`zip failed (exit ${r.code}): ${r.stderr.trim()}`)
    }
  } else {
    // PowerShell Compress-Archive — preserves directory structure when
    // -Path points at a folder. We pipe the cmd via -Command - so quoting
    // is not at the mercy of cmd.exe's parser.
    const ps = probeBinary('pwsh') ? 'pwsh' : 'powershell'
    // -Force allows overwriting the dest.
    const psScript =
      `Compress-Archive -Path ${jsonQuote(source)} ` +
      `-DestinationPath ${jsonQuote(dest)} -Force`
    const r = await new Promise<{ code: number; stderr: string }>(resolve => {
      const child = spawn(
        ps,
        ['-NoProfile', '-NonInteractive', '-Command', '-'],
        { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true }
      )
      let stderr = ''
      child.stderr?.on('data', (c: Buffer) => {
        stderr += c.toString('utf8')
      })
      child.on('close', code => resolve({ code: code ?? -1, stderr }))
      try {
        child.stdin?.end(psScript + '\n')
      } catch {
        /* ignore */
      }
    })
    if (r.code !== 0) {
      throw new Error(`Compress-Archive failed (exit ${r.code}): ${r.stderr.trim()}`)
    }
  }

  const finalStat = await fsp.stat(dest)
  const sha = await sha256OfFile(dest)
  return { ok: true, dest, size_bytes: finalStat.size, sha256: sha, impl }
}

/** desktop_unzip
 *   args: { source: string, dest: string }
 *   returns: { ok, dest, impl }
 *
 * source is the archive file; dest is the directory to expand into.
 * Creates dest if missing. */
export const unzipHandler: ToolHandler = async (args, ctx) => {
  const source = path.resolve(argString(args.source, 'source'))
  const dest = path.resolve(argString(args.dest, 'dest'))

  await fsp.stat(source)
  await fsp.mkdir(dest, { recursive: true })

  const impl = pickUnzipImpl()

  if (impl === 'tar') {
    const r = await runChild('tar', ['-xf', source, '-C', dest], ctx)
    if (r.code !== 0) {
      throw new Error(`tar -xf failed (exit ${r.code}): ${r.stderr.trim()}`)
    }
  } else if (impl === 'zip') {
    // -o overwrite without prompt; -q quiet
    const r = await runChild('unzip', ['-o', '-q', source, '-d', dest], ctx)
    if (r.code !== 0) {
      throw new Error(`unzip failed (exit ${r.code}): ${r.stderr.trim()}`)
    }
  } else {
    const ps = probeBinary('pwsh') ? 'pwsh' : 'powershell'
    const psScript =
      `Expand-Archive -Path ${jsonQuote(source)} ` +
      `-DestinationPath ${jsonQuote(dest)} -Force`
    const r = await new Promise<{ code: number; stderr: string }>(resolve => {
      const child = spawn(
        ps,
        ['-NoProfile', '-NonInteractive', '-Command', '-'],
        { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true }
      )
      let stderr = ''
      child.stderr?.on('data', (c: Buffer) => {
        stderr += c.toString('utf8')
      })
      child.on('close', code => resolve({ code: code ?? -1, stderr }))
      try {
        child.stdin?.end(psScript + '\n')
      } catch {
        /* ignore */
      }
    })
    if (r.code !== 0) {
      throw new Error(`Expand-Archive failed (exit ${r.code}): ${r.stderr.trim()}`)
    }
  }

  return { ok: true, dest, impl }
}

// ── desktop_checksum ────────────────────────────────────────────────────────

/** desktop_checksum
 *   args: { path: string, algorithm?: 'sha256' | 'sha1' | 'md5' (default 'sha256') }
 *   returns: { path, algorithm, hash, size_bytes }
 *
 * Stream-based — handles arbitrary file sizes without buffering. Use for
 * verifying a file transfer landed intact (compare to an expected hash). */
export const checksumHandler: ToolHandler = async args => {
  const filePath = path.resolve(argString(args.path, 'path'))
  const algorithm = (argOptionalString(args.algorithm) ?? 'sha256').toLowerCase()
  if (algorithm !== 'sha256' && algorithm !== 'sha1' && algorithm !== 'md5') {
    throw new Error('"algorithm" must be sha256, sha1, or md5')
  }

  const stat = await fsp.stat(filePath)
  if (!stat.isFile()) {
    throw new Error(`not a regular file: ${filePath}`)
  }

  const hash = await new Promise<string>((resolve, reject) => {
    const h = createHash(algorithm)
    const stream = createReadStream(filePath)
    stream.on('error', reject)
    stream.on('data', (chunk: Buffer | string) => {
      h.update(chunk)
    })
    stream.on('end', () => resolve(h.digest('hex')))
  })

  return { path: filePath, algorithm, hash, size_bytes: stat.size }
}

// ── helpers ─────────────────────────────────────────────────────────────────

/** Internal: stream a file into sha256, return hex digest. Used by zip
 * to give the caller a verifiable hash of the produced archive. */
async function sha256OfFile(file: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const h = createHash('sha256')
    const stream = createReadStream(file)
    stream.on('error', reject)
    stream.on('data', (chunk: Buffer | string) => {
      h.update(chunk)
    })
    stream.on('end', () => resolve(h.digest('hex')))
  })
}

/** JSON-encode a string into a PowerShell-safe literal. PowerShell parses
 * JSON-style double-quoted strings cleanly when the script is fed via
 * stdin (-Command -), so this gives us deterministic escaping for paths
 * with spaces, single quotes, or backslashes — without touching cmd.exe. */
function jsonQuote(s: string): string {
  return JSON.stringify(s)
}
