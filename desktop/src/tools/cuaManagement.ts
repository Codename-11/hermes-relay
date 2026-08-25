import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { access, mkdtemp, realpath, rm, writeFile } from 'node:fs/promises'
import { homedir, tmpdir } from 'node:os'
import { delimiter, join, relative, resolve, sep, win32 } from 'node:path'

import {
  CuaDriverAdapter,
  SpawnCuaProcessRunner,
  cuaDriverEnvironment,
  type CuaProcessRunner
} from './cuaDriver.js'

export const CUA_SUPPORTED_MIN_VERSION = '0.20.0'
const TRUSTED_REPOSITORY = 'trycua/cua'
const TRUSTED_PRODUCT = 'cua-driver-rs'
const RELEASE_BASE = 'https://github.com/trycua/cua/releases/download'
const MAX_INSTALLER_BYTES = 2 * 1024 * 1024

interface FetchResponse {
  ok: boolean
  status: number
  arrayBuffer(): Promise<ArrayBuffer>
  json(): Promise<unknown>
}

export type CuaManagementFetch = (url: string) => Promise<FetchResponse>

export interface CuaUpdateStatus {
  checked_at?: string
  current_version?: string
  latest_version?: string
  update_available: boolean
  compatible: boolean
  error?: string
  release_notes_url?: string
}

export interface CuaManagementStatus {
  installed: boolean
  canonical_path: string | null
  discovered_path: string | null
  stale_path_shim: boolean
  current_version: string | null
  compatible: boolean
  compatibility_reason: string | null
  binary_sha256: string | null
  release_source: typeof TRUSTED_REPOSITORY
  telemetry_enabled: false
  bundled: false
  supported_range: {
    minimum: typeof CUA_SUPPORTED_MIN_VERSION
    maximum_exclusive: null
  }
  update?: CuaUpdateStatus
  operation?: {
    kind: 'install' | 'update'
    state: 'completed'
    version: string
    release_manifest_verified: true
    installer_checksum_verified: true
    runtime_verified: true
  }
}

export interface CuaManagementOptions {
  platform?: NodeJS.Platform
  arch?: string
  homeDir?: string
  path?: string
  runner?: CuaProcessRunner
  fetch?: CuaManagementFetch
  systemRoot?: string
}

interface TrustedRelease {
  version: string
  installerUrl: string
  installerSha256: string
}

function versionTuple(value: string): [number, number, number] | null {
  const match = /(?:^|\s|v)(\d+)\.(\d+)\.(\d+)(?:\s|$)/.exec(value.trim())
  return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null
}

function compareVersion(left: string, right: string): number | null {
  const a = versionTuple(left)
  const b = versionTuple(right)
  if (!a || !b) return null
  for (let index = 0; index < 3; index += 1) {
    const delta = a[index] - b[index]
    if (delta !== 0) return delta
  }
  return 0
}

export function isSupportedCuaVersion(value: string): boolean {
  const minimum = compareVersion(value, CUA_SUPPORTED_MIN_VERSION)
  return minimum !== null && minimum >= 0
}

function canonicalPaths(homeDir: string): { executable: string; releases: string } {
  const packages = join(resolve(homeDir), '.cua-driver', 'packages')
  return {
    executable: join(packages, 'current', 'cua-driver.exe'),
    releases: join(packages, 'releases')
  }
}

async function resolveCanonical(options: CuaManagementOptions): Promise<string | null> {
  if ((options.platform ?? process.platform) !== 'win32') return null
  const paths = canonicalPaths(options.homeDir ?? homedir())
  try {
    const [binary, releases] = await Promise.all([
      realpath(paths.executable),
      realpath(paths.releases)
    ])
    await access(binary)
    const inside = relative(releases, binary)
    if (!inside || inside === '..' || inside.startsWith(`..${sep}`)) return null
    return binary
  } catch {
    return null
  }
}

async function firstPathCandidate(options: CuaManagementOptions): Promise<string | null> {
  if ((options.platform ?? process.platform) !== 'win32') return null
  for (const entry of (options.path ?? process.env.PATH ?? '').split(delimiter)) {
    const trimmed = entry.trim().replace(/^"|"$/g, '')
    if (!trimmed) continue
    const candidate = join(trimmed, 'cua-driver.exe')
    try {
      await access(candidate)
      return await realpath(candidate)
    } catch {
      // Continue to the next PATH entry.
    }
  }
  return null
}

async function sha256File(path: string): Promise<string> {
  return new Promise((resolvePromise, reject) => {
    const hash = createHash('sha256')
    const stream = createReadStream(path)
    stream.on('data', chunk => hash.update(chunk))
    stream.on('error', reject)
    stream.on('end', () => resolvePromise(hash.digest('hex')))
  })
}

function safeError(error: unknown): string {
  return (error instanceof Error ? error.message : String(error)).slice(0, 500)
}

async function binaryVersion(binary: string, runner: CuaProcessRunner): Promise<string | null> {
  const result = await runner.run(binary, ['--version'], {
    timeoutMs: 8_000,
    env: cuaDriverEnvironment()
  })
  if (result.exitCode !== 0) return null
  const tuple = versionTuple(result.stdout)
  return tuple?.join('.') ?? null
}

export async function getCuaManagementStatus(
  options: CuaManagementOptions = {}
): Promise<CuaManagementStatus> {
  const runner = options.runner ?? new SpawnCuaProcessRunner()
  const [canonical, discovered] = await Promise.all([
    resolveCanonical(options),
    firstPathCandidate(options)
  ])
  let currentVersion: string | null = null
  let binarySha256: string | null = null
  let compatibilityReason: string | null = null
  if (canonical) {
    try {
      ;[currentVersion, binarySha256] = await Promise.all([
        binaryVersion(canonical, runner),
        sha256File(canonical)
      ])
    } catch (error) {
      compatibilityReason = safeError(error)
    }
  }
  const compatible = !!currentVersion && isSupportedCuaVersion(currentVersion)
  if (canonical && !compatibilityReason && !compatible) {
    compatibilityReason = currentVersion
      ? `CUA Driver ${currentVersion} is outside the supported range`
      : 'CUA Driver version could not be verified'
  }
  return {
    installed: canonical !== null,
    canonical_path: canonical,
    discovered_path: discovered,
    stale_path_shim: !!canonical && !!discovered && resolve(canonical).toLowerCase() !== resolve(discovered).toLowerCase(),
    current_version: currentVersion,
    compatible,
    compatibility_reason: compatibilityReason,
    binary_sha256: binarySha256,
    release_source: TRUSTED_REPOSITORY,
    telemetry_enabled: false,
    bundled: false,
    supported_range: {
      minimum: CUA_SUPPORTED_MIN_VERSION,
      maximum_exclusive: null
    }
  }
}

function normalizeUpdatePayload(payload: Record<string, unknown>): CuaUpdateStatus {
  const latest = typeof payload.latest_version === 'string' ? payload.latest_version : undefined
  const error = typeof payload.error === 'string' && payload.error.trim() ? payload.error.slice(0, 500) : undefined
  return {
    checked_at: typeof payload.checked_at === 'string' ? payload.checked_at : undefined,
    current_version: typeof payload.current_version === 'string' ? payload.current_version : undefined,
    latest_version: latest,
    update_available: payload.update_available === true,
    compatible: !!latest && isSupportedCuaVersion(latest),
    error,
    release_notes_url: typeof payload.release_notes_url === 'string' ? payload.release_notes_url : undefined
  }
}

export async function checkCuaUpdate(
  options: CuaManagementOptions = {}
): Promise<CuaManagementStatus> {
  const runner = options.runner ?? new SpawnCuaProcessRunner()
  let status = await getCuaManagementStatus({ ...options, runner })
  for (let attempt = 0; !status.installed && attempt < 2; attempt += 1) {
    await new Promise(resolvePromise => setTimeout(resolvePromise, 50))
    status = await getCuaManagementStatus({ ...options, runner })
  }
  if (!status.canonical_path) {
    return { ...status, update: { update_available: false, compatible: false, error: 'CUA Driver is not installed' } }
  }
  const result = await runner.run(status.canonical_path, ['check-update', '--json', '--no-cache'], {
    timeoutMs: 30_000,
    env: cuaDriverEnvironment()
  })
  if (result.exitCode !== 0) {
    return {
      ...status,
      update: {
        update_available: false,
        compatible: false,
        error: result.stderr.trim().slice(0, 500) || `check-update exited ${result.exitCode}`
      }
    }
  }
  try {
    const payload = JSON.parse(result.stdout) as Record<string, unknown>
    return { ...status, update: normalizeUpdatePayload(payload) }
  } catch {
    return { ...status, update: { update_available: false, compatible: false, error: 'CUA Driver returned invalid update JSON' } }
  }
}

async function trustedRelease(version: string, fetchImpl: CuaManagementFetch): Promise<TrustedRelease> {
  if (!isSupportedCuaVersion(version)) {
    throw new Error(`CUA Driver ${version} is outside the Hermes-supported range`)
  }
  const tag = `cua-driver-rs-v${version}`
  const base = `${RELEASE_BASE}/${tag}`
  const manifestResponse = await fetchImpl(`${base}/release-manifest.json`)
  if (!manifestResponse.ok) throw new Error(`CUA release manifest request failed (${manifestResponse.status})`)
  const manifest = await manifestResponse.json() as Record<string, unknown>
  if (
    manifest.schemaVersion !== 1 || manifest.repository !== TRUSTED_REPOSITORY ||
    manifest.product !== TRUSTED_PRODUCT || manifest.version !== version || manifest.tag !== tag
  ) {
    throw new Error('CUA release publisher or version manifest is invalid')
  }
  const assets = Array.isArray(manifest.assets) ? manifest.assets : []
  const installer = assets.find(asset =>
    !!asset && typeof asset === 'object' && (asset as Record<string, unknown>).name === 'install.ps1'
  ) as Record<string, unknown> | undefined
  const checksum = typeof installer?.sha256 === 'string' ? installer.sha256.toLowerCase() : ''
  if (!/^[0-9a-f]{64}$/.test(checksum)) throw new Error('CUA release installer checksum is missing')
  return { version, installerUrl: `${base}/install.ps1`, installerSha256: checksum }
}

async function applyTrustedRelease(
  release: TrustedRelease,
  runner: CuaProcessRunner,
  fetchImpl: CuaManagementFetch,
  systemRootOverride?: string
): Promise<void> {
  const response = await fetchImpl(release.installerUrl)
  if (!response.ok) throw new Error(`CUA installer request failed (${response.status})`)
  const script = Buffer.from(await response.arrayBuffer())
  if (script.byteLength === 0 || script.byteLength > MAX_INSTALLER_BYTES) {
    throw new Error('CUA installer size is invalid')
  }
  const actual = createHash('sha256').update(script).digest('hex')
  if (actual !== release.installerSha256) throw new Error('CUA installer checksum verification failed')
  const directory = await mkdtemp(join(tmpdir(), 'hermes-cua-install-'))
  const path = join(directory, 'install.ps1')
  try {
    await writeFile(path, script, { mode: 0o600 })
    const configuredSystemRoot = systemRootOverride ?? process.env.SystemRoot ?? process.env.WINDIR
    if (!configuredSystemRoot || !configuredSystemRoot.match(/^[A-Za-z]:[\\/]/)) {
      throw new Error('Windows system PowerShell path is unavailable')
    }
    const powershell = win32.join(win32.resolve(configuredSystemRoot), 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
    const result = await runner.run(powershell, [
      '-NoProfile',
      '-NonInteractive',
      '-ExecutionPolicy',
      'Bypass',
      '-File',
      path
    ], {
      timeoutMs: 15 * 60_000,
      env: cuaDriverEnvironment({
        CUA_DRIVER_RS_VERSION: release.version,
        CUA_DRIVER_INSTALL_CHANNEL: 'install_script'
      })
    })
    if (result.exitCode !== 0) {
      throw new Error(result.stderr.trim().slice(0, 500) || `CUA installer exited ${result.exitCode}`)
    }
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

async function mutateCua(
  kind: 'install' | 'update',
  version: string,
  options: CuaManagementOptions
): Promise<CuaManagementStatus> {
  if ((options.platform ?? process.platform) !== 'win32') throw new Error('CUA management is currently available on Windows only')
  const runner = options.runner ?? new SpawnCuaProcessRunner()
  const fetchImpl = options.fetch ?? (globalThis.fetch as unknown as CuaManagementFetch)
  const release = await trustedRelease(version, fetchImpl)
  await applyTrustedRelease(release, runner, fetchImpl, options.systemRoot)
  const status = await getCuaManagementStatus({ ...options, runner })
  if (!status.installed || status.current_version !== version || !status.compatible) {
    throw new Error(
      `CUA canonical package did not pass post-install version verification ` +
      `(installed=${status.installed}, version=${status.current_version ?? 'unknown'}, compatible=${status.compatible})`
    )
  }
  const runtime = await CuaDriverAdapter.status({
    platform: options.platform,
    homeDir: options.homeDir,
    runner
  })
  if (
    !runtime.ready || runtime.binaryVersion !== version ||
    !runtime.binaryPath || !status.canonical_path ||
    resolve(runtime.binaryPath).toLowerCase() !== resolve(status.canonical_path).toLowerCase()
  ) {
    throw new Error(runtime.reason ?? 'CUA canonical manifest, path, or health verification failed')
  }
  return {
    ...status,
    operation: {
      kind,
      state: 'completed',
      version,
      release_manifest_verified: true,
      installer_checksum_verified: true,
      runtime_verified: true
    }
  }
}

export async function installCuaDriver(options: CuaManagementOptions = {}): Promise<CuaManagementStatus> {
  const existing = await getCuaManagementStatus(options)
  if (existing.installed) return existing
  return mutateCua('install', CUA_SUPPORTED_MIN_VERSION, options)
}

export async function updateCuaDriver(options: CuaManagementOptions = {}): Promise<CuaManagementStatus> {
  const checked = await checkCuaUpdate(options)
  const update = checked.update
  if (!update || update.error) throw new Error(update?.error ?? 'CUA update check failed')
  if (!update.update_available) return checked
  if (!update.latest_version || !update.compatible) {
    throw new Error(`CUA Driver ${update.latest_version ?? 'unknown'} is available but unsupported by this Hermes Relay build`)
  }
  return mutateCua('update', update.latest_version, options)
}
