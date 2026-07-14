import assert from 'node:assert/strict'
import { mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  listPendingGrantRequests,
  resolveGrantRequest,
  validGrantRequestId
} from '../src/lib/grantBridge.js'

test('grant request ids cannot escape the bridge directory', () => {
  assert.equal(validGrantRequestId('grant-abc_123'), true)
  assert.equal(validGrantRequestId('../grant-abc'), false)
  assert.equal(validGrantRequestId('grant abc'), false)
  assert.equal(validGrantRequestId(''), false)
})

test('pending grant requests are parsed and sorted by creation time', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-grants-'))
  await writeFile(
    join(dir, 'request-later.json'),
    JSON.stringify({
      id: 'later',
      mode: 'control',
      duration_seconds: 900,
      reason: 'later request',
      created_at: '2026-07-13T12:01:00Z'
    })
  )
  await writeFile(
    join(dir, 'request-earlier.json'),
    JSON.stringify({
      id: 'earlier',
      mode: 'assist',
      duration_seconds: 300,
      reason: 'earlier request',
      created_at: '2026-07-13T12:00:00Z'
    })
  )
  await writeFile(join(dir, 'request-invalid.json'), '{not-json')

  const requests = await listPendingGrantRequests(dir)

  assert.deepEqual(requests.map(request => request.id), ['earlier', 'later'])
})

test('resolving a grant writes an atomic response and consumes the request', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-grants-'))
  await writeFile(
    join(dir, 'request-grant_123.json'),
    JSON.stringify({
      id: 'grant_123',
      mode: 'assist',
      duration_seconds: 300,
      reason: 'inspect the current window',
      created_at: '2026-07-13T12:00:00Z'
    })
  )

  await resolveGrantRequest(dir, 'grant_123', false, 'Rejected from CLI')

  const response = JSON.parse(await readFile(join(dir, 'response-grant_123.json'), 'utf8')) as {
    approved: boolean
    reason: string
    resolved_at_ms: number
  }
  assert.equal(response.approved, false)
  assert.equal(response.reason, 'Rejected from CLI')
  assert.equal(typeof response.resolved_at_ms, 'number')
  assert.deepEqual(await listPendingGrantRequests(dir), [])
})
