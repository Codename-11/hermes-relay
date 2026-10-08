import assert from 'node:assert/strict'
import test from 'node:test'

import { computerActivityDetail } from '../tray/ui/computerActivity.js'

test('activity list and drilldown recognize both persisted CUA backend values', () => {
  for (const backend of ['cua', 'cua_driver']) {
    assert.equal(computerActivityDetail({ backend, dispatch: 'background' }), 'CUA · background')
    assert.equal(computerActivityDetail({ backend, dispatch: 'background' }, true), 'CUA structured engine · background')
    assert.equal(computerActivityDetail({ backend }), 'CUA')
  }
})

test('system captures never claim compatibility input or inferred background dispatch', () => {
  assert.equal(computerActivityDetail({ backend: 'system_capture' }), 'System capture')
  assert.equal(computerActivityDetail({ backend: 'system_capture' }, true), 'System capture')
})

test('legacy actions retain their recorded backend and dispatch', () => {
  assert.equal(computerActivityDetail({ backend: 'legacy_compat', dispatch: 'foreground_compatibility' }), 'Compatibility · foreground compatibility')
  assert.equal(computerActivityDetail({ backend: 'legacy_compat', dispatch: 'background' }, true), 'Windows input · Compatibility · background')
})

test('status, grant, cancel, and historical records without backend metadata stay neutral', () => {
  for (const backend of [undefined, '']) {
    assert.equal(computerActivityDetail({ backend }), 'Backend not recorded')
    assert.equal(computerActivityDetail({ backend }, true), 'Backend not recorded')
  }
  assert.equal(computerActivityDetail({ backend: 'future_backend' }), 'Unknown backend')
  assert.equal(computerActivityDetail({ backend: 'toString' }), 'Unknown backend')
  assert.equal(computerActivityDetail({ dispatch: 'background' }), 'Backend not recorded · background')
})
