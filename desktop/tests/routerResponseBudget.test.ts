import assert from 'node:assert/strict'
import test from 'node:test'

import {
  DESKTOP_RESPONSE_WIRE_BUDGET_BYTES,
  fitDesktopResponseToWire,
  type ToolResponsePayload
} from '../src/tools/router.js'

test('desktop response budget preserves ordinary results', () => {
  const payload: ToolResponsePayload = {
    request_id: 'request-1',
    ok: true,
    result: { stdout: 'complete', exit_code: 0 }
  }

  assert.equal(fitDesktopResponseToWire(payload), payload)
})

test('desktop response budget replaces an oversized result with a bounded error', () => {
  const bounded = fitDesktopResponseToWire({
    request_id: 'request-2',
    ok: true,
    result: { stdout: 'x'.repeat(DESKTOP_RESPONSE_WIRE_BUDGET_BYTES + 1) }
  })

  assert.deepEqual(bounded, {
    request_id: 'request-2',
    ok: false,
    error: `Desktop result exceeded the ${DESKTOP_RESPONSE_WIRE_BUDGET_BYTES}-byte relay response budget. Retry with bounded output or save the result to a file.`
  })
  assert.ok(Buffer.byteLength(JSON.stringify(bounded)) < DESKTOP_RESPONSE_WIRE_BUDGET_BYTES)
})

test('desktop response budget rejects cyclic results without throwing', () => {
  const result: Record<string, unknown> = {}
  result.self = result

  assert.deepEqual(fitDesktopResponseToWire({ request_id: 'request-3', ok: true, result }), {
    request_id: 'request-3',
    ok: false,
    error: 'Desktop result could not be serialized. Retry with bounded text or save the output to a file.'
  })
})
