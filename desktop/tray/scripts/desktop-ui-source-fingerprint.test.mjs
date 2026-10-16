import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { computeDesktopUiSourceFingerprint, desktopUiScreenshotSourceFiles } from './desktop-ui-source-fingerprint.mjs'

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'hermes-desktop-fingerprint-'))
  t.after(async () => {
    assert.equal(dirname(resolve(root)), resolve(tmpdir()))
    assert.ok(basename(root).startsWith('hermes-desktop-fingerprint-'))
    await rm(root, { recursive: true, force: true })
  })
  for (const file of desktopUiScreenshotSourceFiles) {
    const destination = join(root, file)
    await mkdir(dirname(destination), { recursive: true })
    await writeFile(destination, 'fixture\n')
  }
  const lockfile = {
    name: '@hermes-relay/tray-ui', version: '0.4.0-beta.6', lockfileVersion: 3,
    packages: {
      '': { name: '@hermes-relay/tray-ui', version: '0.4.0-beta.6', dependencies: { react: '^19.0.0' } },
      'node_modules/react': { version: '19.0.0', integrity: 'example-integrity' },
    },
  }
  const saveLock = () => writeFile(join(root, 'desktop/tray/package-lock.json'), JSON.stringify(lockfile, null, 2))
  await saveLock()
  return { root, lockfile, saveLock, fingerprint: () => computeDesktopUiSourceFingerprint(root) }
}

test('root release versions do not invalidate fixed-version screenshots', async t => {
  const f = await fixture(t)
  const before = await f.fingerprint()
  f.lockfile.version = '0.4.0-beta.7'
  f.lockfile.packages[''].version = '0.4.0-beta.7'
  await f.saveLock()
  assert.deepEqual(await f.fingerprint(), before)
})

test('dependency versions and integrity remain fingerprinted', async t => {
  const f = await fixture(t)
  const before = (await f.fingerprint()).digest
  f.lockfile.packages['node_modules/react'].version = '19.1.0'
  await f.saveLock()
  const changedVersion = (await f.fingerprint()).digest
  assert.notEqual(changedVersion, before)
  f.lockfile.packages['node_modules/react'].integrity = 'changed-integrity'
  await f.saveLock()
  assert.notEqual((await f.fingerprint()).digest, changedVersion)
})

test('production UI changes still invalidate screenshots', async t => {
  const f = await fixture(t)
  const before = (await f.fingerprint()).digest
  await writeFile(join(f.root, 'desktop/tray/ui/App.tsx'), 'changed UI\n')
  assert.notEqual((await f.fingerprint()).digest, before)
})

test('computer activity label changes invalidate screenshots', async t => {
  const f = await fixture(t)
  const before = (await f.fingerprint()).digest
  await writeFile(join(f.root, 'desktop/tray/ui/computerActivity.ts'), 'changed backend labels\n')
  assert.notEqual((await f.fingerprint()).digest, before)
})

test('text line endings do not cause platform-only drift', async t => {
  const f = await fixture(t)
  const before = await f.fingerprint()
  for (const file of desktopUiScreenshotSourceFiles.filter(file => !file.endsWith('.png'))) {
    const path = join(f.root, file)
    await writeFile(path, (await readFile(path, 'utf8')).replace(/\n/g, '\r\n'))
  }
  assert.deepEqual(await f.fingerprint(), before)
})
