import assert from 'node:assert/strict'
import test from 'node:test'

import { isDaemonProcessAlive } from '../src/lib/daemonStatus.js'

test('daemon liveness rejects a recycled pid owned by another executable', () => {
  const status = { pid: process.pid, process_name: 'hermes-relay.exe' }
  assert.equal(isDaemonProcessAlive(status, () => 'svchost.exe'), false)
})

test('daemon liveness accepts the recorded executable case-insensitively', () => {
  const status = { pid: process.pid, process_name: 'HERMES-RELAY.EXE' }
  assert.equal(isDaemonProcessAlive(status, () => 'hermes-relay.exe'), true)
})
