import assert from 'node:assert/strict'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, test } from 'node:test'

import {
  getActiveDesktopRelayUrl,
  getDesktopHostAliases,
  setActiveDesktopRelayUrl,
  setDesktopHostAlias,
  setDesktopConfigPath
} from '../src/desktopConfig.js'

const temporaryRoots: string[] = []

afterEach(async () => {
  setDesktopConfigPath(null)
  await Promise.all(temporaryRoots.splice(0).map(path => rm(path, { recursive: true, force: true })))
})

test('host aliases are local, trimmed, removable, and preserve selection', async () => {
  const path = await temporaryConfig()
  await setActiveDesktopRelayUrl('wss://home.example.test:8767')
  await setDesktopHostAlias('wss://home.example.test:8767', '  Office Hermes  ')
  assert.deepEqual(await getDesktopHostAliases(), { 'wss://home.example.test:8767': 'Office Hermes' })
  assert.equal(await getActiveDesktopRelayUrl(), 'wss://home.example.test:8767')

  await setDesktopHostAlias('wss://home.example.test:8767', null)
  assert.deepEqual(await getDesktopHostAliases(), {})
  assert.deepEqual(JSON.parse(await readFile(path, 'utf8')), { relay_url: 'wss://home.example.test:8767' })
})

async function temporaryConfig(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'hermes-desktop-config-'))
  temporaryRoots.push(root)
  const path = join(root, 'nested', 'desktop-control.json')
  await mkdir(join(root, 'nested'), { recursive: true })
  setDesktopConfigPath(path)
  return path
}

test('active host selection is persisted and can be cleared', async () => {
  const path = await temporaryConfig()

  await setActiveDesktopRelayUrl('  wss://home.example.test:8767  ')
  assert.equal(await getActiveDesktopRelayUrl(), 'wss://home.example.test:8767')

  await setActiveDesktopRelayUrl(null)
  assert.equal(await getActiveDesktopRelayUrl(), null)
  assert.deepEqual(JSON.parse(await readFile(path, 'utf8')), {})
})

test('host selection preserves unrelated desktop settings', async () => {
  const path = await temporaryConfig()
  await writeFile(path, JSON.stringify({ overlay: { visible: true }, relay_url: 'ws://old' }))

  await setActiveDesktopRelayUrl('ws://new')

  assert.deepEqual(JSON.parse(await readFile(path, 'utf8')), {
    overlay: { visible: true },
    relay_url: 'ws://new'
  })
})
