import assert from 'node:assert/strict'
import test from 'node:test'

import {
  computerGrantAllowsTarget,
  configureComputerUseRuntime,
  getComputerGrantSummary,
  initializeComputerControlSession,
  cancelAllComputerGrants,
  requestComputerGrant,
  revokeComputerControlSession
} from '../src/tools/computerGrants.js'
import {
  ComputerControlSecurityState,
  evaluateSensitiveTarget,
  hasAuthenticatedControlIdentity,
  type ComputerControlAuthority
} from '../src/tools/computerControlSecurity.js'

const authority = (id: string): ComputerControlAuthority => ({
  controlSessionId: id,
  hostUrl: 'wss://relay.example'
})

test('control request ids are accepted once per router lifecycle', () => {
  const state = new ComputerControlSecurityState(authority('router-a'))
  assert.equal(state.bindRequest('request-1')?.requestId, 'request-1')
  assert.equal(state.bindRequest('request-1'), null)
  assert.equal(state.bindRequest('   '), null)
})

test('snapshot tokens are opaque, one-use, and bound to grant and target', () => {
  const auth = authority('router-snapshot')
  const state = new ComputerControlSecurityState(auth)
  const token = state.issueSnapshotToken({
    authority: auth,
    grantId: 'grant-a',
    target: { pid: 42, windowId: 7, app: 'Notepad' },
    snapshotGeneration: 'generation-1',
    driverElementToken: 'driver-private-token'
  })
  assert.match(token, /^hermes-snapshot-/)
  assert.equal(state.consumeSnapshotToken(token, {
    authority: auth,
    grantId: 'grant-b',
    target: { pid: 42, windowId: 7 }
  }), null)
  assert.equal(state.consumeSnapshotToken(token, {
    authority: auth,
    grantId: 'grant-a',
    target: { pid: 42, windowId: 7 }
  }), null)
})

test('snapshot tokens return their backend binding only to the exact target', () => {
  const auth = authority('router-target')
  const state = new ComputerControlSecurityState(auth)
  const token = state.issueSnapshotToken({
    authority: auth,
    grantId: null,
    target: { pid: 9, windowId: 11 },
    snapshotGeneration: 'g1',
    driverElementToken: 'opaque-driver-token'
  })
  const binding = state.consumeSnapshotToken(token, {
    authority: auth,
    grantId: null,
    target: { pid: 9, windowId: 11 },
    snapshotGeneration: 'g1'
  })
  assert.equal(binding?.driverElementToken, 'opaque-driver-token')
  assert.equal(state.consumeSnapshotToken(token, {
    authority: auth,
    grantId: null,
    target: { pid: 9, windowId: 11 }
  }), null)
})

test('authority revocation removes only that concurrent session artifacts', () => {
  const first = authority('router-first')
  const second = authority('router-second')
  const state = new ComputerControlSecurityState(first)
  assert.ok(state.bindRequest('request-first', first))
  assert.ok(state.bindRequest('request-second', second))
  const firstToken = state.issueSnapshotToken({
    authority: first, grantId: null, target: { pid: 1, windowId: 1 }, snapshotGeneration: 'g1'
  })
  const secondToken = state.issueSnapshotToken({
    authority: second, grantId: null, target: { pid: 2, windowId: 2 }, snapshotGeneration: 'g2'
  })
  state.revokeAuthority(first.controlSessionId)
  assert.ok(state.bindRequest('request-first', first))
  assert.equal(state.bindRequest('request-second', second), null)
  assert.equal(state.consumeSnapshotToken(firstToken, { authority: first, grantId: null, target: { pid: 1, windowId: 1 } }), null)
  assert.ok(state.consumeSnapshotToken(secondToken, { authority: second, grantId: null, target: { pid: 2, windowId: 2 } }))
})

test('sensitive target policy fails closed without identity and blocks baseline surfaces', () => {
  assert.deepEqual(evaluateSensitiveTarget({}), { allowed: false, reason: 'missing_target_identity' })
  assert.equal(evaluateSensitiveTarget({ app: 'Bitwarden' }).allowed, false)
  assert.equal(evaluateSensitiveTarget({ title: 'Windows Security settings' }).allowed, false)
  assert.deepEqual(evaluateSensitiveTarget({ app: 'Notepad', title: 'notes.txt' }), {
    allowed: true,
    reason: 'not_sensitive'
  })
})

test('authenticated control identity requires relay-attested requester/run/target fields', () => {
  assert.equal(hasAuthenticatedControlIdentity(authority('local-only')), false)
  assert.equal(hasAuthenticatedControlIdentity({
    controlSessionId: 'control-1',
    relaySessionId: 'relay-1',
    requesterDeviceId: 'requester-1',
    runId: 'run-1',
    targetDeviceId: 'desktop-1'
  }), true)
})

test('router control sessions inherit host policy but keep grants isolated', () => {
  configureComputerUseRuntime({
    url: 'wss://relay.example',
    computerUseConsented: true,
    consentSource: 'stored',
    accessMode: 'ask',
    capabilities: {
      commands: 'ask', files: 'ask', screen_input: 'ask', usb: 'ask', microphone: 'disabled', camera: 'disabled'
    }
  })
  const first = authority('router-first')
  const second = authority('router-second')
  initializeComputerControlSession(first)
  initializeComputerControlSession(second)
  requestComputerGrant({
    mode: 'control',
    scope: { app: 'Notepad', display: 'primary', folder: 'C:\\work' },
    duration_seconds: 60,
    reason: 'edit notes'
  }, first)
  assert.equal(getComputerGrantSummary(first).active, true)
  assert.equal(getComputerGrantSummary(second).active, false)
  assert.equal(computerGrantAllowsTarget(first, {
    pid: 1,
    windowId: 2,
    app: 'Windows Notepad',
    display: 'primary',
    folder: 'C:\\work\\notes'
  }), true)
  assert.equal(computerGrantAllowsTarget(first, {
    pid: 1,
    windowId: 2,
    app: 'Browser',
    display: 'primary',
    folder: 'C:\\work\\notes'
  }), false)
  revokeComputerControlSession(first)
  revokeComputerControlSession(second)
})

test('Full Access configured before router creation is preserved without sharing grants', () => {
  configureComputerUseRuntime({
    computerUseConsented: true,
    accessMode: 'full_access'
  })
  const full = authority('router-full')
  initializeComputerControlSession(full)
  assert.equal(getComputerGrantSummary(full).mode, 'full_access')
  revokeComputerControlSession(full)
  configureComputerUseRuntime({ accessMode: 'ask' })
})

test('local emergency cancellation revokes every active control session', () => {
  configureComputerUseRuntime({
    computerUseConsented: true,
    accessMode: 'ask',
    capabilities: {
      commands: 'ask', files: 'ask', screen_input: 'ask', usb: 'ask', microphone: 'disabled', camera: 'disabled'
    }
  })
  const first = authority('emergency-first')
  const second = authority('emergency-second')
  initializeComputerControlSession(first)
  initializeComputerControlSession(second)
  requestComputerGrant({ mode: 'control', reason: 'first' }, first)
  requestComputerGrant({ mode: 'observe', reason: 'second' }, second)
  assert.equal(cancelAllComputerGrants('emergency stop'), 2)
  assert.equal(getComputerGrantSummary(first).active, false)
  assert.equal(getComputerGrantSummary(second).active, false)
  revokeComputerControlSession(first)
  revokeComputerControlSession(second)
})
