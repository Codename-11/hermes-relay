import assert from 'node:assert/strict'
import test from 'node:test'

import { parseOneTurnModelCommand } from '../src/commands/chat.js'

test('parses an upstream one-turn model command without mutating state', () => {
  assert.deepEqual(parseOneTurnModelCommand('/model sonnet --provider anthropic --once'), {
    command: '/model sonnet --provider anthropic --once',
    label: 'sonnet'
  })
})

test('does not intercept persistent or incomplete model commands', () => {
  assert.equal(parseOneTurnModelCommand('/model sonnet'), null)
  assert.equal(parseOneTurnModelCommand('/model --once'), null)
  assert.equal(parseOneTurnModelCommand('/personality concise --once'), null)
})

test('supports the upstream provider-only one-turn form', () => {
  assert.deepEqual(parseOneTurnModelCommand('/model --provider anthropic --once'), {
    command: '/model --provider anthropic --once',
    label: 'anthropic provider'
  })
})
