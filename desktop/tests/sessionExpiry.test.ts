import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { getSession, saveSession, setStorePath } from '../src/remoteSessions.js'

test('an explicit never-expiring authorization replaces the old expiry while omitted updates preserve it', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'relay-expiry-'))
  setStorePath(join(dir, 'sessions.json'))
  t.after(async () => { setStorePath(null); await rm(dir, { recursive: true, force: true }) })
  const url = 'wss://relay.example.test'
  await saveSession(url, 'old-token', 'test', { ttlExpiresAt: 1234 })
  await saveSession(url, 'old-token', 'test')
  assert.equal((await getSession(url))?.ttlExpiresAt, 1234)
  await saveSession(url, 'connector-token', 'test', { ttlExpiresAt: null, grants: { desktop: null } })
  assert.equal((await getSession(url))?.ttlExpiresAt, null)
  await saveSession(url, 'connector-token', 'test', { toolsConsented: true })
  assert.equal((await getSession(url))?.ttlExpiresAt, null)
})
