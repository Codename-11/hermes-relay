import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, test } from 'node:test'

import type { ParsedArgs } from '../src/cli.js'
import { displayAccessMode, hostsCommand, parseAccessMode } from '../src/commands/hosts.js'
import { getActiveDesktopRelayUrl, getDesktopHostAliases, setDesktopConfigPath } from '../src/desktopConfig.js'
import { getHostAccessMode, getHostCapabilityPolicies } from '../src/lib/hostAccessPolicy.js'
import { getSession, saveSession, setStorePath } from '../src/remoteSessions.js'

const temporaryRoots: string[] = []
const originalPolicyPath = process.env.HERMES_RELAY_HOST_ACCESS_POLICY_PATH

afterEach(async () => {
  setStorePath(null)
  setDesktopConfigPath(null)
  if (originalPolicyPath === undefined) delete process.env.HERMES_RELAY_HOST_ACCESS_POLICY_PATH
  else process.env.HERMES_RELAY_HOST_ACCESS_POLICY_PATH = originalPolicyPath
  await Promise.all(temporaryRoots.splice(0).map(path => rm(path, { recursive: true, force: true })))
})

async function setup(): Promise<{ root: string; url: string }> {
  const root = await mkdtemp(join(tmpdir(), 'hermes-hosts-command-'))
  temporaryRoots.push(root)
  setStorePath(join(root, 'sessions.json'))
  setDesktopConfigPath(join(root, 'desktop-control.json'))
  process.env.HERMES_RELAY_HOST_ACCESS_POLICY_PATH = join(root, 'host-access.json')
  const url = 'wss://home.example.test:8767'
  await saveSession(url, 'secret-token', '1.2.3', { endpointRole: 'tailscale' })
  return { root, url }
}

function args(positional: string[], flags: Record<string, string | true> = {}): ParsedArgs {
  return { command: 'hosts', positional: [...positional], flags }
}

test('access mode parser accepts simplified names and compatibility aliases', () => {
  assert.equal(parseAccessMode('restricted'), 'ask')
  assert.equal(parseAccessMode('ask-every-time'), 'ask_every_time')
  assert.equal(parseAccessMode('prompt'), 'ask_every_time')
  assert.equal(parseAccessMode('standard'), 'structured')
  assert.equal(parseAccessMode('ask'), 'ask')
  assert.equal(parseAccessMode('trusted'), 'trusted')
  assert.equal(parseAccessMode('structured'), 'structured')
  assert.equal(parseAccessMode('full-access'), 'full_access')
  assert.equal(parseAccessMode('full_access'), 'full_access')
  assert.equal(parseAccessMode('custom'), null)
  assert.equal(parseAccessMode('always'), null)
  assert.equal(displayAccessMode('ask'), 'restricted')
  assert.equal(displayAccessMode('ask_every_time'), 'ask-every-time')
  assert.equal(displayAccessMode('structured'), 'standard')
  assert.equal(displayAccessMode('trusted'), 'custom')
  assert.equal(displayAccessMode('custom'), 'custom')
  assert.equal(displayAccessMode('full_access'), 'full-access')
})

test('a fresh paired session gets Ask Every Time while an existing policy is preserved', async () => {
  const root = await mkdtemp(join(tmpdir(), 'hermes-new-pair-policy-'))
  temporaryRoots.push(root)
  setStorePath(join(root, 'sessions.json'))
  setDesktopConfigPath(join(root, 'desktop-control.json'))
  process.env.HERMES_RELAY_HOST_ACCESS_POLICY_PATH = join(root, 'host-access.json')
  const url = 'wss://new.example.test:8767'

  await saveSession(url, 'first-token', '1.2.3', { initializeAccessPolicy: true })
  assert.equal(await getHostAccessMode(url), 'ask_every_time')

  await hostsCommand(args(['access', 'restricted'], { remote: url, 'no-color': true }))
  await saveSession(url, 'refreshed-token', '1.2.4', { initializeAccessPolicy: true })
  assert.equal(await getHostAccessMode(url), 'ask')
})

test('USB capability policy requires confirmation for allow and rejects unavailable brokers', async () => {
  const { url } = await setup()
  assert.equal(await hostsCommand(args(['capability', 'usb', 'allow'], { remote: url })), 2)
  assert.equal(await hostsCommand(args(['capability', 'usb', 'allow'], { remote: url, yes: true, 'no-color': true })), 0)
  assert.equal((await getHostCapabilityPolicies(url)).usb, 'allow')
  assert.equal(await getHostAccessMode(url), 'custom')
  assert.equal(await hostsCommand(args(['capability', 'camera', 'allow'], { remote: url, yes: true })), 2)
  assert.equal((await getHostCapabilityPolicies(url)).camera, 'disabled')
})

test('select and access commands update shared host state', async () => {
  const { root, url } = await setup()
  assert.equal(await hostsCommand(args(['select', url], { 'no-color': true })), 0)
  assert.equal(await getActiveDesktopRelayUrl(), url)
  const pairedAt = (await getSession(url))?.pairedAt

  assert.equal(
    await hostsCommand(args(['access', 'full-access'], { remote: url, yes: true, 'no-color': true })),
    0
  )
  assert.equal(await getHostAccessMode(url), 'full_access')
  assert.equal((await getSession(url))?.toolsConsented, true)
  assert.equal((await getSession(url))?.pairedAt, pairedAt)

  const stored = JSON.parse(await readFile(join(root, 'host-access.json'), 'utf8')) as {
    hosts: Record<string, { access_mode: string }>
  }
  assert.equal(stored.hosts[url]?.access_mode, 'full_access')
})

test('hosts select rejects a persisted Dashboard Relay ingress alternate', async () => {
  const { url } = await setup()
  const ingress = 'wss://home.example.test/api/plugins/hermes-relay/transport'
  await saveSession(url, 'secret-token', '1.2.3', {
    routeCandidates: [
      {
        role: 'https',
        priority: 0,
        dashboard: { url: 'https://home.example.test' },
        relay: { url: ingress },
      },
      { role: 'tailscale', priority: 1, relay: { url } },
    ],
  })

  assert.equal(await hostsCommand(args(['select', ingress], { 'no-color': true })), 1)
  assert.equal(await getActiveDesktopRelayUrl(), null)
  assert.equal(await getSession(ingress), null)
  assert.ok((await getSession(url))?.routeCandidates?.some(candidate => candidate.relay.url === ingress))
})

test('full access requires an explicit confirmation flag', async () => {
  const { url } = await setup()
  assert.equal(await hostsCommand(args(['access', 'full-access'], { remote: url })), 2)
  assert.equal(await getHostAccessMode(url), 'ask')
})

test('rename stores a local display name for a paired host', async () => {
  const { url } = await setup()
  assert.equal(await hostsCommand(args(['rename', url, 'Office', 'Hermes'], { 'no-color': true })), 0)
  assert.deepEqual(await getDesktopHostAliases(), { [url]: 'Office Hermes' })
})

test('forget requires confirmation and removes only the targeted local host state', async () => {
  const { url } = await setup()
  const second = 'wss://second.example.test:8767'
  await saveSession(second, 'second-token', '1.2.3')
  await hostsCommand(args(['rename', url, 'Office'], { 'no-color': true }))
  await hostsCommand(args(['access', 'standard'], { remote: url, 'no-color': true }))
  await hostsCommand(args(['select', url], { 'no-color': true }))

  assert.equal(await hostsCommand(args(['forget', url], { 'no-color': true })), 2)
  assert.ok(await getSession(url))
  assert.equal(await hostsCommand(args(['forget', url], { yes: true, 'no-color': true })), 0)
  assert.equal(await getSession(url), null)
  assert.equal(await getHostAccessMode(url), 'ask')
  assert.deepEqual(await getDesktopHostAliases(), {})
  assert.equal(await getActiveDesktopRelayUrl(), second)
  assert.ok(await getSession(second))
})
