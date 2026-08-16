import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { updateCommand } from '../src/commands/update.js'
import { checkForUpdate, downloadAndInstall } from '../src/updater.js'
import { VERSION } from '../src/version.js'

async function captureStdout(run: () => Promise<number>): Promise<{ code: number; output: string }> {
  const originalWrite = process.stdout.write
  let output = ''
  process.stdout.write = ((chunk: string | Uint8Array) => {
    output += typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8')
    return true
  }) as typeof process.stdout.write
  try {
    return { code: await run(), output }
  } finally {
    process.stdout.write = originalWrite
  }
}

test('desktop installer selection downloads the requested asset to an exact verified path', async () => {
  const originalFetch = globalThis.fetch
  const scratch = await mkdtemp(join(tmpdir(), 'hermes-installer-update-'))
  const target = join(scratch, 'hermes-relay-setup.exe')
  const assetName = 'hermes-relay-windows-x64-setup.exe'
  const bytes = Buffer.from('verified desktop installer fixture')
  const checksum = createHash('sha256').update(bytes).digest('hex')

  globalThis.fetch = async (input) => {
    const url = String(input)
    if (url.endsWith('/releases')) {
      return new Response(JSON.stringify([{
        tag_name: 'desktop-v9.0.0',
        prerelease: false,
        published_at: '2026-08-11T00:00:00Z',
        assets: [{ name: assetName, browser_download_url: 'https://download.test/setup.exe', size: bytes.length }]
      }]), { status: 200, headers: { 'content-type': 'application/json' } })
    }
    if (url.endsWith('/SHA256SUMS.txt')) {
      return new Response(`${checksum}  ${assetName}\n`, { status: 200 })
    }
    if (url === 'https://download.test/setup.exe') {
      return new Response(bytes, { status: 200, headers: { 'content-length': String(bytes.length) } })
    }
    return new Response('not found', { status: 404 })
  }

  try {
    const info = await checkForUpdate({ repo: 'example/hermes-relay', assetName })
    assert.ok(info)
    assert.equal(info.asset_name, assetName)
    assert.equal(info.asset_url, 'https://download.test/setup.exe')

    const result = await downloadAndInstall(info, { targetPath: target, cooperative: false })
    assert.deepEqual(result, { installedPath: target, needsRestart: false })
    assert.deepEqual(await readFile(target), bytes)
  } finally {
    globalThis.fetch = originalFetch
    await rm(scratch, { recursive: true, force: true })
  }
})

test('installer check reports a newer local preview without treating it as an error', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => new Response(JSON.stringify([{
    tag_name: 'desktop-v0.4.0-alpha.2',
    prerelease: true,
    published_at: '2026-07-14T01:13:36Z',
    assets: [{
      name: 'hermes-relay-windows-x64-setup.exe',
      browser_download_url: 'https://download.test/setup.exe',
      size: 27_335_311
    }]
  }]), { status: 200, headers: { 'content-type': 'application/json' } })

  try {
    const check = await captureStdout(() => updateCommand({
      command: 'update',
      positional: [],
      flags: { installer: true, check: true, json: true, repo: 'example/hermes-relay' }
    }))
    assert.equal(check.code, 0)
    const report = JSON.parse(check.output)
    assert.equal(report.current, VERSION)
    assert.equal(report.latest_version, '0.4.0-alpha.2')
    assert.equal(report.up_to_date, true)
    assert.equal(report.ahead_of_latest, true)
    assert.equal(report.error, null)

    const install = await captureStdout(() => updateCommand({
      command: 'update',
      positional: [],
      flags: { installer: true, yes: true, json: true, repo: 'example/hermes-relay' }
    }))
    assert.equal(install.code, 2)
    assert.match(JSON.parse(install.output).error, /older than this CLI/)
  } finally {
    globalThis.fetch = originalFetch
  }
})
