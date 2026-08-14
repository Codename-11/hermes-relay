import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { access, mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  checkCuaUpdate,
  getCuaManagementStatus,
  installCuaDriver,
  isSupportedCuaVersion,
  updateCuaDriver,
  type CuaManagementFetch
} from '../src/tools/cuaManagement.js'
import type { CuaProcessResult, CuaProcessRunner } from '../src/tools/cuaDriver.js'
import { computerUseCommand } from '../src/commands/computerUse.js'

const ok = (stdout = ''): CuaProcessResult => ({ stdout, stderr: '', exitCode: 0 })

async function packageHome(version = '0.19.3'): Promise<{ root: string; binary: string }> {
  const root = await mkdtemp(join(tmpdir(), 'hermes-cua-management-'))
  const release = join(root, '.cua-driver', 'packages', 'releases', `${version}-x86_64-pc-windows-msvc`)
  const current = join(root, '.cua-driver', 'packages', 'current')
  await mkdir(release, { recursive: true })
  const binary = join(release, 'cua-driver.exe')
  await writeFile(binary, `fake-cua-${version}`)
  await symlink(release, current, 'junction')
  return { root, binary }
}

class ProbeRunner implements CuaProcessRunner {
  constructor(
    readonly version = '0.19.3',
    readonly latest = version,
    readonly onRun?: (executable: string, args: readonly string[], env?: NodeJS.ProcessEnv) => Promise<void> | void,
    readonly health: string = 'healthy'
  ) {}

  async run(executable: string, args: readonly string[], options: { env?: NodeJS.ProcessEnv } = {}): Promise<CuaProcessResult> {
    await this.onRun?.(executable, args, options.env)
    if (args[0] === '--version') return ok(`cua-driver ${this.version}`)
    if (args[0] === 'check-update') return ok(JSON.stringify({
      checked_at: '2026-08-13T00:00:00Z',
      current_version: this.version,
      latest_version: this.latest,
      update_available: this.latest !== this.version,
      error: null
    }))
    if (args[0] === 'manifest') return ok(JSON.stringify({
      schema_version: '1',
      binary_version: this.version,
      binary_path: executable
    }))
    if (args[0] === 'list-tools') return ok([
      'health_report:', 'start_session:', 'end_session:', 'list_windows:',
      'get_window_state:', 'click:', 'set_value:', 'press_key:', 'scroll:'
    ].join('\n'))
    if (args[0] === 'status') return ok('permission mode: standard')
    if (args[0] === 'call' && args[1] === 'health_report') return ok(JSON.stringify({
      schema_version: '1', driver_version: this.version, overall: this.health
    }))
    return ok()
  }
}

test('supported CUA range is bounded to the adapter contract', () => {
  assert.equal(isSupportedCuaVersion('0.19.2'), false)
  assert.equal(isSupportedCuaVersion('0.19.3'), true)
  assert.equal(isSupportedCuaVersion('0.19.99'), true)
  assert.equal(isSupportedCuaVersion('0.20.0'), false)
})

test('status prefers canonical package/current and reports a competing PATH shim', async () => {
  const home = await packageHome()
  const stale = await mkdtemp(join(tmpdir(), 'hermes-cua-stale-'))
  await writeFile(join(stale, 'cua-driver.exe'), 'stale')
  try {
    const status = await getCuaManagementStatus({
      platform: 'win32', homeDir: home.root, path: stale, runner: new ProbeRunner()
    })
    assert.equal(status.installed, true)
    assert.equal(status.current_version, '0.19.3')
    assert.equal(status.compatible, true)
    assert.equal(status.stale_path_shim, true)
    assert.equal(status.canonical_path, home.binary)
    assert.equal(status.release_source, 'trycua/cua')
    assert.equal(status.telemetry_enabled, false)
  } finally {
    await rm(home.root, { recursive: true, force: true })
    await rm(stale, { recursive: true, force: true })
  }
})

test('check-update exposes a newer incompatible release without applying it', async () => {
  const home = await packageHome()
  try {
    const status = await checkCuaUpdate({
      platform: 'win32', homeDir: home.root, path: '', runner: new ProbeRunner('0.19.3', '0.20.0')
    })
    assert.equal(status.update?.update_available, true)
    assert.equal(status.update?.latest_version, '0.20.0')
    assert.equal(status.update?.compatible, false)
  } finally {
    await rm(home.root, { recursive: true, force: true })
  }
})

test('update refuses an unsupported latest release before any download or apply', async () => {
  const home = await packageHome()
  let fetches = 0
  let powershellRuns = 0
  try {
    await assert.rejects(updateCuaDriver({
      platform: 'win32',
      homeDir: home.root,
      path: '',
      runner: new ProbeRunner('0.19.3', '0.20.0', executable => {
        if (executable.toLowerCase() === 'powershell.exe') powershellRuns += 1
      }),
      fetch: async () => {
        fetches += 1
        throw new Error('must not fetch')
      }
    }), /available but unsupported/)
    assert.equal(fetches, 0)
    assert.equal(powershellRuns, 0)
  } finally {
    await rm(home.root, { recursive: true, force: true })
  }
})

test('install verifies trusted release metadata/checksum and sanitizes installer environment', async () => {
  const root = await mkdtemp(join(tmpdir(), 'hermes-cua-install-test-'))
  const script = Buffer.from('Write-Output canonical-installer')
  const scriptSha = createHash('sha256').update(script).digest('hex')
  const fetchImpl: CuaManagementFetch = async url => ({
    ok: true,
    status: 200,
    async arrayBuffer() { return script.buffer.slice(script.byteOffset, script.byteOffset + script.byteLength) },
    async json() {
      assert.match(url, /release-manifest\.json$/)
      return {
        schemaVersion: 1,
        repository: 'trycua/cua',
        product: 'cua-driver-rs',
        version: '0.19.3',
        tag: 'cua-driver-rs-v0.19.3',
        assets: [{ name: 'install.ps1', sha256: scriptSha }]
      }
    }
  })
  let installerEnvironment: NodeJS.ProcessEnv | undefined
  let installerPath: string | undefined
  const runner = new ProbeRunner('0.19.3', '0.19.3', async (executable, args, env) => {
    if (!executable.toLowerCase().endsWith('\\windows\\system32\\windowspowershell\\v1.0\\powershell.exe')) return
    installerEnvironment = env
    installerPath = args[args.indexOf('-File') + 1]
    const release = join(root, '.cua-driver', 'packages', 'releases', '0.19.3-x86_64-pc-windows-msvc')
    await mkdir(release, { recursive: true })
    await writeFile(join(release, 'cua-driver.exe'), 'installed')
    await symlink(release, join(root, '.cua-driver', 'packages', 'current'), 'junction')
  })
  const priorSecret = process.env.OPENAI_API_KEY
  process.env.OPENAI_API_KEY = 'must-not-leak'
  try {
    const status = await installCuaDriver({
      platform: 'win32', homeDir: root, path: '', runner, fetch: fetchImpl, systemRoot: 'C:\\Windows'
    })
    assert.equal(status.operation?.release_manifest_verified, true)
    assert.equal(status.operation?.installer_checksum_verified, true)
    assert.equal(status.operation?.runtime_verified, true)
    assert.equal(installerEnvironment?.OPENAI_API_KEY, undefined)
    assert.equal(installerEnvironment?.CUA_DRIVER_RS_TELEMETRY_ENABLED, '0')
    assert.equal(installerEnvironment?.CUA_DRIVER_RS_VERSION, '0.19.3')
    assert.ok(installerPath)
    await assert.rejects(access(installerPath!))
  } finally {
    if (priorSecret === undefined) delete process.env.OPENAI_API_KEY
    else process.env.OPENAI_API_KEY = priorSecret
    await rm(root, { recursive: true, force: true })
  }
})

test('install rejects invalid release identity metadata before downloading the installer', async () => {
  const root = await mkdtemp(join(tmpdir(), 'hermes-cua-invalid-manifest-'))
  let requests = 0
  try {
    await assert.rejects(installCuaDriver({
      platform: 'win32', homeDir: root, path: '', runner: new ProbeRunner(),
      fetch: async url => {
        requests += 1
        assert.match(url, /release-manifest\.json$/)
        return {
          ok: true,
          status: 200,
          async arrayBuffer() { return new ArrayBuffer(0) },
          async json() {
            return {
              schemaVersion: 1,
              repository: 'attacker/fork',
              product: 'cua-driver-rs',
              version: '0.19.3',
              tag: 'cua-driver-rs-v0.19.3',
              assets: [{ name: 'install.ps1', sha256: 'a'.repeat(64) }]
            }
          }
        }
      }
    }), /publisher or version manifest is invalid/)
    assert.equal(requests, 1)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('install rejects an installer whose bytes do not match release metadata', async () => {
  const root = await mkdtemp(join(tmpdir(), 'hermes-cua-bad-checksum-'))
  let powershellRuns = 0
  try {
    await assert.rejects(installCuaDriver({
      platform: 'win32', homeDir: root, path: '',
      runner: new ProbeRunner('0.19.3', '0.19.3', executable => {
        if (executable.toLowerCase() === 'powershell.exe') powershellRuns += 1
      }),
      fetch: async url => ({
        ok: true,
        status: 200,
        async arrayBuffer() { return Buffer.from('tampered').buffer },
        async json() {
          assert.match(url, /release-manifest\.json$/)
          return {
            schemaVersion: 1, repository: 'trycua/cua', product: 'cua-driver-rs',
            version: '0.19.3', tag: 'cua-driver-rs-v0.19.3',
            assets: [{ name: 'install.ps1', sha256: 'a'.repeat(64) }]
          }
        }
      })
    }), /checksum verification failed/)
    assert.equal(powershellRuns, 0)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('post-install degraded health fails the canonical runtime gate', async () => {
  const root = await mkdtemp(join(tmpdir(), 'hermes-cua-degraded-'))
  const script = Buffer.from('installer')
  const checksum = createHash('sha256').update(script).digest('hex')
  const runner = new ProbeRunner('0.19.3', '0.19.3', async executable => {
    if (!executable.toLowerCase().endsWith('\\windows\\system32\\windowspowershell\\v1.0\\powershell.exe')) return
    const release = join(root, '.cua-driver', 'packages', 'releases', '0.19.3-x86_64-pc-windows-msvc')
    await mkdir(release, { recursive: true })
    await writeFile(join(release, 'cua-driver.exe'), 'installed')
    await symlink(release, join(root, '.cua-driver', 'packages', 'current'), 'junction')
  }, 'degraded')
  try {
    await assert.rejects(installCuaDriver({
      platform: 'win32', homeDir: root, path: '', runner, systemRoot: 'C:\\Windows',
      fetch: async url => ({
        ok: true,
        status: 200,
        async arrayBuffer() { return script.buffer.slice(script.byteOffset, script.byteOffset + script.byteLength) },
        async json() {
          assert.match(url, /release-manifest\.json$/)
          return {
            schemaVersion: 1, repository: 'trycua/cua', product: 'cua-driver-rs',
            version: '0.19.3', tag: 'cua-driver-rs-v0.19.3',
            assets: [{ name: 'install.ps1', sha256: checksum }]
          }
        }
      })
    }), /health is degraded/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('CLI install and update require explicit confirmation', async () => {
  let stderr = ''
  const original = process.stderr.write
  process.stderr.write = ((chunk: string | Uint8Array) => {
    stderr += chunk.toString()
    return true
  }) as typeof process.stderr.write
  try {
    assert.equal(await computerUseCommand({ command: 'computer-use', flags: {}, positional: ['cua', 'install'] }), 1)
    assert.match(stderr, /requires explicit confirmation with --yes/)
  } finally {
    process.stderr.write = original
  }
})

test('install rejects an unverified installer before process execution', async () => {
  const root = await mkdtemp(join(tmpdir(), 'hermes-cua-bad-installer-'))
  let processStarted = false
  const fetchImpl: CuaManagementFetch = async url => ({
    ok: true,
    status: 200,
    async arrayBuffer() { return Buffer.from('tampered').buffer },
    async json() {
      assert.match(url, /release-manifest\.json$/)
      return {
        schemaVersion: 1,
        repository: 'trycua/cua',
        product: 'cua-driver-rs',
        version: '0.19.3',
        tag: 'cua-driver-rs-v0.19.3',
        assets: [{ name: 'install.ps1', sha256: '0'.repeat(64) }]
      }
    }
  })
  const runner = new ProbeRunner('0.19.3', '0.19.3', () => { processStarted = true })
  try {
    await assert.rejects(
      installCuaDriver({ platform: 'win32', homeDir: root, path: '', runner, fetch: fetchImpl }),
      /checksum verification failed/
    )
    assert.equal(processStarted, false)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
