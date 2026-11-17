import assert from 'node:assert/strict'
import test from 'node:test'

import { isDaemonProcessAlive, parseWindowsProcessSnapshot, windowsProcessMatchesOwner, windowsProcessProbeScript } from '../src/lib/daemonStatus.js'

test('daemon liveness rejects a recycled pid owned by another executable', () => {
  const status = { pid: process.pid, process_name: 'hermes-relay.exe' }
  assert.equal(isDaemonProcessAlive(status, () => 'svchost.exe'), false)
})

test('daemon liveness accepts the recorded executable case-insensitively', () => {
  const status = { pid: process.pid, process_name: 'HERMES-RELAY.EXE' }
  assert.equal(isDaemonProcessAlive(status, () => 'hermes-relay.exe'), true)
})

const owner = { pid: 123, process_name: 'HERMES-RELAY.EXE', executable_path: 'C:\\Relay\\hermes-relay.exe', process_started_at: 123456 }
const alive = { state: 'alive' as const, name: 'hermes-relay', executablePath: 'c:/relay/hermes-relay.exe', startedAt: 123456 }

test('Windows liveness recognizes only exact native markers and keeps unknown results locked', () => {
  assert.deepEqual(parseWindowsProcessSnapshot('HERMES_RELAY_PID_V1:{"state":"gone"}\r\n'), { state: 'gone' })
  for (const output of ['', 'INFO: No tasks are running', 'Access denied', '{"state":"gone"}',
    'HERMES_RELAY_PID_V1:broken', 'HERMES_RELAY_PID_V1:{"state":"alive"}',
    'noise\nHERMES_RELAY_PID_V1:{"state":"gone"}']) {
    const snapshot = parseWindowsProcessSnapshot(output)
    assert.equal(snapshot.state, 'unknown')
    assert.equal(windowsProcessMatchesOwner(owner, snapshot), true)
  }
  assert.equal(windowsProcessMatchesOwner(owner, { state: 'gone' }), false)
})

test('Windows lock owner distinguishes recycled PID images, paths and process creation times', () => {
  assert.equal(windowsProcessMatchesOwner(owner, alive), true)
  assert.equal(windowsProcessMatchesOwner(owner, { ...alive, name: 'powershell' }), false)
  assert.equal(windowsProcessMatchesOwner(owner, { ...alive, executablePath: 'C:\\Other\\hermes-relay.exe' }), false)
  assert.equal(windowsProcessMatchesOwner(owner, { ...alive, startedAt: 234567 }), false)
  // An inaccessible path/start time is not evidence that the owner died.
  assert.equal(windowsProcessMatchesOwner(owner, { ...alive, executablePath: null, startedAt: null }), true)
  // Legacy locks have no path/start-time metadata; still compare their image.
  assert.equal(windowsProcessMatchesOwner({ pid: 123, process_name: 'hermes-relay.exe' }, alive), true)
})

test('Windows probe builds fixed native markers with only validated numeric PID input', () => {
  assert.match(windowsProcessProbeScript(123), /GetProcessById\(123\)/)
  assert.match(windowsProcessProbeScript(123), /catch \[ArgumentException\]/)
  assert.match(windowsProcessProbeScript(123), /state='unknown'/)
  for (const pid of [0, -1, NaN, Infinity, 1.1]) assert.throws(() => windowsProcessProbeScript(pid))
})
