import assert from 'node:assert/strict'
import { mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { hostname, tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { desktopDeviceId, desktopRelayIdentity } from '../src/deviceIdentity.js'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

test('desktop relay identity uses hostname for display and a stable per-install ownership id', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'hermes-device-identity-test-'))
  const path = join(directory, 'device-id')
  const identity = desktopRelayIdentity(path)
  assert.equal(identity.deviceName, hostname().trim() || 'Hermes-Relay desktop')
  assert.equal(identity.deviceHostname, identity.deviceName)
  assert.equal(identity.clientSurface, 'desktop')
  assert.equal(identity.deviceFormFactor, 'desktop')
  assert.ok(identity.deviceModel.length > 0)
  assert.ok(identity.devicePlatform.length > 0)
  assert.match(identity.deviceId, UUID_PATTERN)
  assert.equal(desktopDeviceId(path), identity.deviceId)
  assert.equal((await readFile(path, 'utf8')).trim(), identity.deviceId)
})

test('malformed desktop ownership ids are repaired without using hostname as identity', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'hermes-device-identity-repair-test-'))
  const path = join(directory, 'device-id')
  await writeFile(path, 'unknown\n')

  const repaired = desktopDeviceId(path)
  assert.match(repaired, UUID_PATTERN)
  assert.notEqual(repaired, 'unknown')
})
