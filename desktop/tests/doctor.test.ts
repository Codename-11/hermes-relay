import assert from 'node:assert/strict'
import test from 'node:test'

import { resolveBinaryPathForRuntime } from '../src/commands/doctor.js'

test('compiled binaries report the physical executable instead of Bun virtual argv', () => {
  assert.equal(
    resolveBinaryPathForRuntime(
      'C:\\Users\\example\\.hermes\\bin\\hermes-relay.exe',
      'B:/~BUN/root/hermes-relay-win-x64'
    ),
    'C:\\Users\\example\\.hermes\\bin\\hermes-relay.exe'
  )
})

test('Node development runs report the invoked CLI entrypoint', () => {
  assert.equal(
    resolveBinaryPathForRuntime(
      'C:\\Program Files\\nodejs\\node.exe',
      'C:\\src\\hermes-relay\\desktop\\src\\cli.ts'
    ),
    'C:\\src\\hermes-relay\\desktop\\src\\cli.ts'
  )
})

test('Bun script development runs report the invoked CLI entrypoint', () => {
  assert.equal(
    resolveBinaryPathForRuntime(
      'C:\\Users\\example\\.bun\\bin\\bun.exe',
      'C:\\src\\hermes-relay\\desktop\\src\\cli.ts'
    ),
    'C:\\src\\hermes-relay\\desktop\\src\\cli.ts'
  )
})
