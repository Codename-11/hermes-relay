import assert from 'node:assert/strict'
import test from 'node:test'

import { desktopDeviceId } from '../src/deviceIdentity.js'
import type { RelayTransport } from '../src/transport/RelayTransport.js'
import { configureComputerUseRuntime, getComputerGrantSummary, requestComputerGrant } from '../src/tools/computerGrants.js'
import {
  DesktopToolRouter,
  type ToolContext
} from '../src/tools/router.js'

type ChannelListener = (type: string, payload: Record<string, unknown>) => void

function harness(handler: (ctx: ToolContext) => void) {
  let listener: ChannelListener | null = null
  const sent: Array<{ channel: string; type: string; payload: Record<string, unknown> }> = []
  const relay = {
    onChannel(channel: string, next: ChannelListener | null) {
      assert.equal(channel, 'desktop')
      listener = next
    },
    sendChannel(channel: string, type: string, payload: Record<string, unknown>) {
      sent.push({ channel, type, payload })
    }
  } as unknown as RelayTransport
  const router = new DesktopToolRouter({
    consentGranted: true,
    interactive: false,
    hostUrl: 'wss://relay.example',
    handlers: {
      desktop_computer_snapshot: async (_args, ctx) => {
        handler(ctx)
        return { ok: true }
      }
    }
  })
  router.attach(relay)
  return {
    router,
    sent,
    emit(type: string, payload: Record<string, unknown>) {
      assert.ok(listener)
      listener(type, payload)
    }
  }
}

function nextTurn(): Promise<void> {
  return new Promise(resolve => setImmediate(resolve))
}

test('router passes a strictly validated server control session to handlers', async () => {
  let received: ToolContext | undefined
  const testHarness = harness(ctx => { received = ctx })
  const requestId = 'request-1'
  testHarness.emit('desktop.command', {
    request_id: requestId,
    tool: 'desktop_computer_snapshot',
    args: {},
    control_session: {
      version: 1,
      id: 'control-1',
      request_id: requestId,
      requester_device_id: 'paired-agent-1',
      target_device_id: desktopDeviceId(),
      chat_session_id: 'chat-1',
      run_id: 'run-1'
    }
  })
  await nextTurn()

  assert.equal(received?.controlSession?.controlSessionId, 'control-1')
  assert.equal(received?.controlSession?.relaySessionId, 'control-1')
  assert.equal(received?.controlSession?.requestId, requestId)
  assert.equal(received?.controlSession?.requesterDeviceId, 'paired-agent-1')
  assert.equal(received?.controlSession?.targetDeviceId, desktopDeviceId())
  assert.equal(received?.controlSession?.runId, 'run-1')
  assert.equal(received?.controlSession?.chatSessionId, 'chat-1')
  assert.ok(testHarness.sent.some(item =>
    item.type === 'desktop.response' && item.payload.request_id === requestId && item.payload.ok === true
  ))
  testHarness.router.detach()
})

test('router rejects a supplied control session whose request binding is invalid', async () => {
  let invoked = false
  const testHarness = harness(() => { invoked = true })
  testHarness.emit('desktop.command', {
    request_id: 'request-outer',
    tool: 'desktop_computer_snapshot',
    args: {},
    control_session: {
      version: 1,
      id: 'control-1',
      request_id: 'request-other',
      requester_device_id: 'paired-agent-1',
      target_device_id: desktopDeviceId(),
      run_id: 'run-1'
    }
  })
  await nextTurn()

  assert.equal(invoked, false)
  const response = testHarness.sent.find(item =>
    item.type === 'desktop.response' && item.payload.request_id === 'request-outer'
  )
  assert.equal(response?.payload.ok, false)
  assert.equal(response?.payload.error, 'invalid server control_session binding')
  testHarness.router.detach()
})

test('router preserves legacy computer commands when the server omits identity', async () => {
  let received: ToolContext | undefined
  const testHarness = harness(ctx => { received = ctx })
  testHarness.emit('desktop.command', {
    request_id: 'legacy-request',
    tool: 'desktop_computer_snapshot',
    args: {}
  })
  await nextTurn()

  assert.match(received?.controlSession?.controlSessionId ?? '', /^router-/)
  assert.equal(received?.controlSession?.relaySessionId, undefined)
  testHarness.router.detach()
})

test('relay control-session end revokes only the exact local target authority', async () => {
  let received: ToolContext | undefined
  const testHarness = harness(ctx => {
    received = ctx
    configureComputerUseRuntime({ computerUseConsented: true, accessMode: 'ask' }, ctx.controlSession)
    requestComputerGrant({ mode: 'control', duration_seconds: 60 }, ctx.controlSession)
  })
  const requestId = 'request-ending'
  testHarness.emit('desktop.command', {
    request_id: requestId,
    tool: 'desktop_computer_snapshot',
    args: {},
    control_session: {
      version: 1,
      id: 'control-ending',
      request_id: requestId,
      requester_device_id: 'paired-agent-1',
      target_device_id: desktopDeviceId(),
      run_id: 'run-ending'
    }
  })
  await nextTurn()
  assert.equal(getComputerGrantSummary(received?.controlSession).active, true)

  testHarness.emit('desktop.control_session_end', {
    version: 1,
    id: 'control-ending',
    target_device_id: 'another-desktop',
    reason: 'wrong target'
  })
  assert.equal(getComputerGrantSummary(received?.controlSession).active, true)

  testHarness.emit('desktop.control_session_end', {
    version: 1,
    id: 'control-ending',
    target_device_id: desktopDeviceId(),
    reason: 'run ended'
  })
  assert.equal(getComputerGrantSummary(received?.controlSession).active, false)
  testHarness.router.detach()
})
