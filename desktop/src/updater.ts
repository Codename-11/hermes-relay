// updater — self-update library for the desktop CLI. Pure library, no subcommand
// wrapping; `commands/update.ts` is the thin UI shell on top.
//
// Strategy:
//   1. Query the GitHub Releases API for this repo (default Codename-11/hermes-relay),
//      filter to tags starting with `desktop-v`, take the newest. Zero deps.
//   2. Pick the asset for this platform (`process.platform` + `process.arch`).
//   3. Download to `<target>.download`, verify against SHA256SUMS.txt, then
//      atomically rename — POSIX rename keeps the running process's inode
//      valid, so an already-running daemon keeps serving until its next restart.
//   4. Windows can't replace a running .exe: stage the download as
//      `<target>.new.exe` and let `finalizePendingUpdate()` (invoked at the top
//      of `main()` on every run) perform the swap on the *next* process start.
//      This mirrors vscode / code-server / most Electron auto-updaters.
//
// The semver comparator is deliberately small — parsing `MAJOR.MINOR.PATCH
// [-PRERELEASE]` numerically/lex-ly is enough for our tag shape
// (`0.3.0-alpha.5`, `0.3.0`). Pulling in `semver` would violate the zero-deps
// stance documented in CLAUDE.md.

import { createHash } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { rename, stat, unlink } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'

import { VERSION } from './version.js'

const DEFAULT_REPO = 'Codename-11/hermes-relay'
const RELEASES_API = (repo: string): string =>
  `https://api.github.com/repos/${repo}/releases`

export interface UpdateInfo {
  current: string
  latest_tag: string
  latest_version: string
  is_prerelease: boolean
  is_upgrade: boolean
  asset_url: string
  asset_name: string
  asset_size: number | null
  checksum_url: string
  published_at: string
}

export interface DownloadOptions {
  targetPath?: string
  onProgress?: (bytes: number, total: number) => void
}

export interface DownloadResult {
  installedPath: string
  needsRestart: boolean
}

// ---------------------------------------------------------------------------
// Asset selection
// ---------------------------------------------------------------------------

/** Map Node's `process.platform` + `process.arch` to the release asset name.
 * Keep in sync with `build:bin:*` scripts in package.json and install.sh/ps1. */
export function assetNameForPlatform(
  platform: NodeJS.Platform = process.platform,
  arch: string = process.arch
): string | null {
  if (platform === 'win32' && arch === 'x64') return 'hermes-relay-win-x64.exe'
  if (platform === 'linux' && arch === 'x64') return 'hermes-relay-linux-x64'
  if (platform === 'linux' && arch === 'arm64') return 'hermes-relay-linux-arm64'
  if (platform === 'darwin' && arch === 'x64') return 'hermes-relay-darwin-x64'
  if (platform === 'darwin' && arch === 'arm64') return 'hermes-relay-darwin-arm64'
  return null
}

// ---------------------------------------------------------------------------
// SemVer comparator
// ---------------------------------------------------------------------------

interface ParsedVersion {
  major: number
  minor: number
  patch: number
  prerelease: string[]   // split on `.`, numeric vs. identifier per SemVer §11
}

function parseVersion(raw: string): ParsedVersion | null {
  // Strip leading `v` / `desktop-v`.
  let v = raw.trim()
  if (v.startsWith('desktop-v')) v = v.slice('desktop-v'.length)
  else if (v.startsWith('v')) v = v.slice(1)

  const [core, pre] = v.split('-', 2)
  if (!core) return null
  const parts = core.split('.')
  if (parts.length !== 3) return null
  const major = Number(parts[0])
  const minor = Number(parts[1])
  const patch = Number(parts[2])
  if (!Number.isInteger(major) || !Number.isInteger(minor) || !Number.isInteger(patch)) {
    return null
  }
  const prerelease = pre ? pre.split('.').filter(Boolean) : []
  return { major, minor, patch, prerelease }
}

/** Returns 1 if a > b, -1 if a < b, 0 if equal. SemVer §11 precedence rules:
 *  - Compare major/minor/patch numerically.
 *  - A version without prerelease is greater than one with prerelease.
 *  - Otherwise compare prerelease identifiers left-to-right:
 *    numeric vs. alphanumeric → numeric is lower; numeric vs. numeric →
 *    compare numerically; alphanumeric vs. alphanumeric → lex compare; shorter
 *    (after all matching) is lower. */
export function compareVersions(aRaw: string, bRaw: string): number {
  const a = parseVersion(aRaw)
  const b = parseVersion(bRaw)
  if (!a || !b) {
    // Fall back to string compare if we can't parse — conservative: treat as
    // not-an-upgrade so we don't try to replace a binary based on garbage.
    if (aRaw === bRaw) return 0
    return aRaw < bRaw ? -1 : 1
  }
  if (a.major !== b.major) return a.major < b.major ? -1 : 1
  if (a.minor !== b.minor) return a.minor < b.minor ? -1 : 1
  if (a.patch !== b.patch) return a.patch < b.patch ? -1 : 1

  // Prerelease handling.
  const aPre = a.prerelease
  const bPre = b.prerelease
  if (aPre.length === 0 && bPre.length === 0) return 0
  if (aPre.length === 0) return 1     // 1.0.0 > 1.0.0-alpha
  if (bPre.length === 0) return -1
  const min = Math.min(aPre.length, bPre.length)
  for (let i = 0; i < min; i++) {
    const ai = aPre[i]!
    const bi = bPre[i]!
    const aNum = /^\d+$/.test(ai)
    const bNum = /^\d+$/.test(bi)
    if (aNum && bNum) {
      const na = Number(ai)
      const nb = Number(bi)
      if (na !== nb) return na < nb ? -1 : 1
    } else if (aNum !== bNum) {
      return aNum ? -1 : 1             // numeric < alphanumeric
    } else {
      if (ai !== bi) return ai < bi ? -1 : 1
    }
  }
  if (aPre.length !== bPre.length) return aPre.length < bPre.length ? -1 : 1
  return 0
}

// ---------------------------------------------------------------------------
// GitHub releases discovery
// ---------------------------------------------------------------------------

interface GhAsset {
  name: string
  size: number
  browser_download_url: string
}
interface GhRelease {
  tag_name: string
  prerelease: boolean
  published_at: string
  assets: GhAsset[]
}

async function fetchReleases(repo: string): Promise<GhRelease[]> {
  const url = RELEASES_API(repo)
  const res = await fetch(url, {
    headers: {
      Accept: 'application/vnd.github+json',
      'User-Agent': `hermes-relay-cli/${VERSION}`
    }
  })
  if (!res.ok) {
    throw new Error(`GitHub Releases API ${res.status} ${res.statusText}`)
  }
  const data = (await res.json()) as GhRelease[]
  if (!Array.isArray(data)) {
    throw new Error('unexpected Releases API response (not an array)')
  }
  return data
}

export async function checkForUpdate(opts: { repo?: string } = {}): Promise<UpdateInfo | null> {
  const repo = opts.repo ?? DEFAULT_REPO
  const releases = await fetchReleases(repo)

  // Filter to desktop-v* tags. They're already returned newest-first.
  const desktop = releases.filter((r) => r.tag_name.startsWith('desktop-v'))
  const pick = desktop[0]
  if (!pick) return null

  const latestVersion = pick.tag_name.slice('desktop-v'.length)
  const current = VERSION

  const wantAsset = assetNameForPlatform()
  if (!wantAsset) {
    // Unsupported platform — tell the caller the state but don't crash here.
    // The `update` subcommand will surface this to the user.
    const base = `https://github.com/${repo}/releases/download/${pick.tag_name}`
    return {
      current,
      latest_tag: pick.tag_name,
      latest_version: latestVersion,
      is_prerelease: pick.prerelease,
      is_upgrade: compareVersions(latestVersion, current) > 0,
      asset_url: '',
      asset_name: `(unsupported: ${process.platform}/${process.arch})`,
      asset_size: null,
      checksum_url: `${base}/SHA256SUMS.txt`,
      published_at: pick.published_at
    }
  }

  const asset = pick.assets.find((a) => a.name === wantAsset)
  const base = `https://github.com/${repo}/releases/download/${pick.tag_name}`
  return {
    current,
    latest_tag: pick.tag_name,
    latest_version: latestVersion,
    is_prerelease: pick.prerelease,
    is_upgrade: compareVersions(latestVersion, current) > 0,
    asset_url: asset?.browser_download_url ?? `${base}/${wantAsset}`,
    asset_name: wantAsset,
    asset_size: asset?.size ?? null,
    checksum_url: `${base}/SHA256SUMS.txt`,
    published_at: pick.published_at
  }
}

// ---------------------------------------------------------------------------
// Download + verify + install
// ---------------------------------------------------------------------------

async function downloadTo(
  url: string,
  destPath: string,
  onProgress?: (bytes: number, total: number) => void
): Promise<void> {
  const res = await fetch(url, {
    headers: { 'User-Agent': `hermes-relay-cli/${VERSION}` }
  })
  if (!res.ok || !res.body) {
    throw new Error(`download ${url} → ${res.status} ${res.statusText}`)
  }
  const totalHeader = res.headers.get('content-length')
  const total = totalHeader ? Number(totalHeader) : 0
  let seen = 0

  const body = res.body as unknown as ReadableStream<Uint8Array>
  const nodeStream = Readable.fromWeb(
    body as Parameters<typeof Readable.fromWeb>[0]
  )
  if (onProgress) {
    nodeStream.on('data', (chunk: Buffer) => {
      seen += chunk.length
      onProgress(seen, total)
    })
  }
  const out = createWriteStream(destPath)
  await pipeline(nodeStream, out)
}

async function fetchText(url: string): Promise<string> {
  const res = await fetch(url, {
    headers: { 'User-Agent': `hermes-relay-cli/${VERSION}` }
  })
  if (!res.ok) {
    throw new Error(`fetch ${url} → ${res.status} ${res.statusText}`)
  }
  return await res.text()
}

async function sha256File(path: string): Promise<string> {
  const hash = createHash('sha256')
  const { createReadStream } = await import('node:fs')
  await pipeline(createReadStream(path), hash)
  return hash.digest('hex').toLowerCase()
}

/** Parse one line of SHA256SUMS.txt matching the asset. Format:
 *   "<hex>  <filename>" (two spaces per coreutils convention). */
function extractChecksum(sums: string, assetName: string): string | null {
  for (const raw of sums.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const parts = line.split(/\s+/)
    if (parts.length < 2) continue
    const hex = parts[0]!
    const name = parts.slice(1).join(' ').replace(/^\*/, '') // strip binary-mode `*`
    if (name === assetName) return hex.toLowerCase()
  }
  return null
}

/** Windows can't rename over a running .exe — stage next to it. Other OSes
 *  can atomically overwrite (POSIX rename keeps the existing inode live). */
function stagingPathFor(targetPath: string): { downloadTmp: string; finalStaged: string; cooperative: boolean } {
  const dir = dirname(targetPath)
  const downloadTmp = join(dir, '.hermes-relay.download')
  if (process.platform === 'win32') {
    // Mirror the target's extension when present so the new file is also
    // runnable directly if the user wants to test it before the swap.
    const ext = targetPath.toLowerCase().endsWith('.exe') ? '.exe' : ''
    const base = ext ? targetPath.slice(0, -ext.length) : targetPath
    return { downloadTmp, finalStaged: `${base}.new${ext}`, cooperative: true }
  }
  return { downloadTmp, finalStaged: targetPath, cooperative: false }
}

export async function downloadAndInstall(
  info: UpdateInfo,
  opts: DownloadOptions = {}
): Promise<DownloadResult> {
  if (!info.asset_url) {
    throw new Error(`no asset available for ${process.platform}/${process.arch}`)
  }
  const targetPath = opts.targetPath ?? process.execPath
  const { downloadTmp, finalStaged, cooperative } = stagingPathFor(targetPath)

  // Best-effort cleanup of any stale temp file from a previous aborted run.
  try { await unlink(downloadTmp) } catch { /* ok */ }

  try {
    await downloadTo(info.asset_url, downloadTmp, opts.onProgress)

    // Verify SHA256.
    const sums = await fetchText(info.checksum_url)
    const expected = extractChecksum(sums, info.asset_name)
    if (!expected) {
      throw new Error(`SHA256SUMS.txt has no entry for ${info.asset_name}`)
    }
    const actual = await sha256File(downloadTmp)
    if (actual !== expected) {
      throw new Error(`checksum mismatch: expected ${expected}, got ${actual}`)
    }

    // On POSIX we can chmod +x here before the rename so the final file is
    // immediately runnable. Skip on Windows (NTFS ignores the bits).
    if (process.platform !== 'win32') {
      const { chmod } = await import('node:fs/promises')
      await chmod(downloadTmp, 0o755)
    }

    // Atomic rename → target (POSIX) or .new.exe (Windows).
    await rename(downloadTmp, finalStaged)
    return { installedPath: finalStaged, needsRestart: cooperative }
  } catch (err) {
    try { await unlink(downloadTmp) } catch { /* ok */ }
    throw err
  }
}

// ---------------------------------------------------------------------------
// Cooperative swap — Windows only
// ---------------------------------------------------------------------------

/** On Windows, if `<argv[0]>.new.exe` exists next to the running binary, swap
 *  it in before main() dispatches. This runs on EVERY CLI invocation but does
 *  nothing unless a pending update is staged — so the only cost in the hot
 *  path is one fs.stat on a missing file. Errors are swallowed: if the swap
 *  fails (AV lock, permissions), the user keeps the old binary and we surface
 *  the pending file next time. Never fatal.
 *
 *  Safe to call unconditionally on all platforms; it no-ops off Windows. */
export async function finalizePendingUpdate(): Promise<void> {
  if (process.platform !== 'win32') return
  const target = process.execPath
  if (!target || !target.toLowerCase().endsWith('.exe')) {
    // Running via `node dist/cli.js` / `tsx src/cli.ts` — not a compiled
    // binary, nothing to swap.
    return
  }
  const base = target.slice(0, -'.exe'.length)
  const newPath = `${base}.new.exe`
  const oldPath = `${base}.old.exe`

  try {
    await stat(newPath)
  } catch {
    return // no pending update
  }

  try {
    // Reap any leftover .old.exe from a previous swap. Windows lets us delete
    // it once the file handle from the prior run is released.
    try { await unlink(oldPath) } catch { /* either absent or still locked */ }

    // Move running binary → .old.exe. This is allowed on Windows even for
    // the actively-executing image (the OS keeps the handle; the file just
    // gets a new name).
    await rename(target, oldPath)
    // Move staged → target.
    await rename(newPath, target)
    // Re-attempt old cleanup; usually stays until process exits, that's OK.
    try { await unlink(oldPath) } catch { /* ok */ }
  } catch {
    // Swap failed — keep going with whatever binary is currently on disk.
    // User will see the .new.exe on the next run and can try again, or clean
    // it up manually. We deliberately don't surface an error here: finalize
    // is a best-effort preflight, not a user-visible operation.
  }
}
