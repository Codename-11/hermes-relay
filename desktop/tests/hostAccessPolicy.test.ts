import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  canonicalRelayUrl,
  effectiveHostAccessMode,
  effectiveHostCapabilityPolicies,
  getHostCapabilityPolicies,
  getHostAccessMode,
  hasFullAccess,
  parseHostAccessPolicyFile,
  readHostAccessPolicies,
  removeHostAccessPolicy,
  requiresTaskGrant,
  setHostAccessMode,
  setHostCapabilityPolicy
} from '../src/lib/hostAccessPolicy.js'

test('canonical relay identity normalizes host casing, default ports, and trailing slashes', () => {
  assert.equal(canonicalRelayUrl(' WSS://Relay.Example:443/ '), 'wss://relay.example')
  assert.equal(canonicalRelayUrl('ws://Relay.Example:80/bridge///'), 'ws://relay.example/bridge')
  assert.equal(canonicalRelayUrl('https://relay.example'), null)
  assert.equal(canonicalRelayUrl('wss://user@relay.example'), null)
  assert.equal(canonicalRelayUrl('wss://relay.example/?token=secret'), null)
})

test('missing, malformed, and unknown host policy defaults to ask', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-host-access-'))
  const filePath = join(dir, 'desktop-host-access.json')
  try {
    assert.equal(await getHostAccessMode('wss://relay.example', filePath), 'ask')
    await writeFile(filePath, '{broken')
    assert.equal(await getHostAccessMode('wss://relay.example', filePath), 'ask')
    assert.equal(await getHostAccessMode('not a relay', filePath), 'ask')
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('policy changes persist atomically under canonical host keys', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-host-access-'))
  const filePath = join(dir, '.hermes', 'desktop-host-access.json')
  try {
    await setHostAccessMode('WSS://Relay.Example:443/', 'full_access', filePath)
    assert.equal(await getHostAccessMode('wss://relay.example', filePath), 'full_access')

    const onDisk = JSON.parse(await readFile(filePath, 'utf8')) as {
      version: number
      hosts: Record<string, { access_mode: string }>
    }
    assert.equal(onDisk.version, 2)
    assert.deepEqual(Object.keys(onDisk.hosts), ['wss://relay.example'])
    assert.equal(onDisk.hosts['wss://relay.example']?.access_mode, 'full_access')

    assert.equal(await removeHostAccessPolicy('wss://RELAY.example/', filePath), true)
    assert.equal(await getHostAccessMode('wss://relay.example', filePath), 'ask')
    assert.equal(await removeHostAccessPolicy('wss://relay.example', filePath), false)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('legacy maps and arrays migrate safely without privilege escalation', () => {
  const parsed = parseHostAccessPolicyFile({
    'WSS://relay.example/': 'ask',
    hosts: {
      'wss://RELAY.example:443': { mode: 'full_access', updated_at: '2026-08-11T00:00:00Z' },
      'ws://trusted.example:80/': { access_mode: 'trusted' },
      'https://wrong-scheme.example': { access_mode: 'full_access' },
      'wss://unknown.example': { access_mode: 'superuser' }
    },
    trusted_hosts: ['wss://array.example/'],
    full_access_hosts: ['WSS://ARRAY.example:443', 42]
  })

  assert.equal(parsed.hosts['wss://relay.example']?.access_mode, 'full_access')
  assert.equal(parsed.hosts['ws://trusted.example']?.access_mode, 'trusted')
  assert.equal(parsed.hosts['wss://array.example']?.access_mode, 'trusted')
  assert.equal(parsed.hosts['https://wrong-scheme.example'], undefined)
  assert.equal(parsed.hosts['wss://unknown.example'], undefined)

  const bareMap = parseHostAccessPolicyFile({
    'WSS://legacy.example:443/': 'trusted'
  })
  assert.equal(bareMap.hosts['wss://legacy.example']?.access_mode, 'trusted')
})

test('duplicate canonical entries choose the most restrictive valid policy', () => {
  const parsed = parseHostAccessPolicyFile({
    hosts: {
      'wss://relay.example': { access_mode: 'full_access' },
      'WSS://RELAY.EXAMPLE:443/': { access_mode: 'ask', capabilities: { usb: 'allow' } },
      'wss://relay.example/': { access_mode: 'trusted', capabilities: { usb: 'disabled' } }
    }
  })
  assert.equal(parsed.hosts['wss://relay.example']?.access_mode, 'ask')
  assert.equal(parsed.hosts['wss://relay.example']?.capabilities.usb, 'disabled')
})

test('routing helpers bypass task grants only for full access', () => {
  assert.equal(requiresTaskGrant('ask'), true)
  assert.equal(requiresTaskGrant('structured'), true)
  assert.equal(requiresTaskGrant('trusted'), true)
  assert.equal(requiresTaskGrant('full_access'), false)
  assert.equal(hasFullAccess('ask'), false)
  assert.equal(hasFullAccess('full_access'), true)
})

test('capability changes create Custom while presets replace the full capability set', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-host-access-'))
  const filePath = join(dir, 'desktop-host-access.json')
  try {
    assert.deepEqual(await getHostCapabilityPolicies('wss://relay.example', filePath), {
      commands: 'disabled', files: 'disabled', screen_input: 'disabled',
      usb: 'disabled', microphone: 'disabled', camera: 'disabled'
    })
    await setHostCapabilityPolicy('wss://relay.example', 'usb', 'ask', filePath)
    assert.equal(await getHostAccessMode('wss://relay.example', filePath), 'custom')
    await setHostAccessMode('wss://relay.example', 'structured', filePath)
    assert.equal(await getHostAccessMode('wss://relay.example', filePath), 'structured')
    assert.deepEqual(await getHostCapabilityPolicies('wss://relay.example', filePath), {
      commands: 'disabled', files: 'allow', screen_input: 'ask',
      usb: 'ask', microphone: 'disabled', camera: 'disabled'
    })
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('Full Access overrides legacy capability values and customization exits the preset', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-host-access-'))
  const filePath = join(dir, 'desktop-host-access.json')
  try {
    await writeFile(filePath, JSON.stringify({
      version: 1,
      hosts: { 'wss://relay.example': { access_mode: 'full_access', capabilities: { usb: 'ask' } } }
    }))
    assert.deepEqual(await getHostCapabilityPolicies('wss://relay.example', filePath), {
      commands: 'allow', files: 'allow', screen_input: 'allow',
      usb: 'allow', microphone: 'allow', camera: 'allow'
    })
    const customized = await setHostCapabilityPolicy('wss://relay.example', 'usb', 'ask', filePath)
    assert.equal(customized.access_mode, 'custom')
    assert.equal(customized.capabilities.commands, 'allow')
    assert.equal(customized.capabilities.usb, 'ask')
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('legacy tool consent migrates visibly to trusted until Ask is explicit', () => {
  assert.equal(effectiveHostAccessMode('ask', true), 'trusted')
  assert.equal(effectiveHostAccessMode('ask', false), 'ask')
  assert.equal(effectiveHostAccessMode('full_access', true), 'full_access')
  assert.deepEqual(effectiveHostCapabilityPolicies('ask', true, {
    commands: 'disabled', files: 'disabled', screen_input: 'disabled',
    usb: 'disabled', microphone: 'disabled', camera: 'disabled'
  }), {
    commands: 'allow', files: 'allow', screen_input: 'ask',
    usb: 'ask', microphone: 'disabled', camera: 'disabled'
  })
})

test('read normalizes stored keys and ignores invalid records', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-host-access-'))
  const filePath = join(dir, 'desktop-host-access.json')
  try {
    await writeFile(filePath, JSON.stringify({
      version: 99,
      hosts: {
        'WSS://Relay.Example/': { access_mode: 'trusted' },
        garbage: { access_mode: 'full_access' }
      }
    }))
    const store = await readHostAccessPolicies(filePath)
    assert.deepEqual(Object.keys(store.hosts), ['wss://relay.example'])
    assert.equal(store.hosts['wss://relay.example']?.access_mode, 'trusted')
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})
